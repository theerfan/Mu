"""Evaluates gdsp (pitch, onsets, chord verification) on GuitarSet mono-mic audio.
usage: python eval/evaluate.py [--conds clean,pink20,...] [--split all|train|test] [--params "--yin-thr 0.15 ..."]
       [--tasks pitch,onset,chord] [--out results.json] [--files N]
Train = players 00-03, test = players 04-05 (players are disjoint; all styles in both)."""
import argparse, json, os, random, shlex, sys
from collections import Counter, defaultdict
from multiprocessing import Pool
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
import gsdata as G

TOL = 0.05


def split_ids(split, limit=None):
    ids = G.file_ids()
    if split == 'train': ids = [i for i in ids if G.player(i) <= 3]
    if split == 'test': ids = [i for i in ids if G.player(i) >= 4]
    if limit: ids = ids[::max(1, len(ids) // limit)][:limit]
    return ids


# ---------------- pitch ----------------
def isolated_notes(notes):
    out = []
    for i, n in enumerate(notes):
        if n['end'] - n['t'] < 0.1: continue
        busy = any(m is not n and m['t'] < n['t'] + 0.15 and m['end'] > n['t'] - 0.0 and not (m['end'] <= n['t'] + 0.02)
                   for m in notes[max(0, i - 8):i + 8])
        if busy: continue
        nxt = min([m['t'] for m in notes[i + 1:i + 8] if m['t'] > n['t']] + [n['end']])
        out.append(dict(n, stop=min(n['end'], nxt)))
    return out


def pitch_job(a):
    fid, cond, params = a
    d = G.load(fid)
    notes = isolated_notes(d['notes'])
    if not notes: return []
    lines = G.run_tool(G.audio_path(fid, cond), [f"P {n['t']:.4f} {n['stop']:.4f}" for n in notes], params)
    res = []
    for n, l in zip(notes, lines):
        f0 = float(l.split()[1])
        err = 1200 * np.log2(f0 / (440 * 2 ** ((n['midi'] - 69) / 12))) if f0 > 0 else None
        res.append(dict(fid=fid, string=n['string'], midi=n['midi'], cents=err))
    return res


def pitch_metrics(rows):
    def summ(rs):
        n = len(rs)
        if not n: return {}
        ok = sum(r['cents'] is not None and abs(r['cents']) <= 50 for r in rs)
        octv = sum(r['cents'] is not None and abs(abs(r['cents']) - 1200) <= 50 for r in rs)
        oct_up = sum(r['cents'] is not None and abs(r['cents'] - 1200) <= 50 for r in rs)
        unv = sum(r['cents'] is None for r in rs)
        fifth = sum(r['cents'] is not None and abs(abs(r['cents']) - 1902) <= 50 for r in rs)
        okc = [abs(r['cents']) for r in rs if r['cents'] is not None and abs(r['cents']) <= 50]
        return dict(n=n, acc50=round(100 * ok / n, 1), octave_err=round(100 * octv / n, 1), octave_up=round(100 * oct_up / n, 1),
                    twelfth_err=round(100 * fifth / n, 1), unvoiced=round(100 * unv / n, 1),
                    median_abs_cents=round(float(np.median(okc)), 1) if okc else None)
    out = dict(all=summ(rows))
    out['by_string'] = {f"{6 - s} ({'EADGBe'[s]})": summ([r for r in rows if r['string'] == s]) for s in range(6)}
    regs = [('<E3 (52)', 0, 52), ('E3-D#4', 52, 64), ('E4-D#5', 64, 76), ('>=E5', 76, 200)]
    out['by_register'] = {k: summ([r for r in rows if lo <= round(r['midi']) < hi]) for k, lo, hi in regs}
    return out


# ---------------- onsets ----------------
def match(est, ref, tol=TOL):
    est, ref = sorted(est), sorted(ref)
    used, tp, i = set(), 0, 0
    for r in ref:
        best = None
        for j, e in enumerate(est):
            if j in used: continue
            if abs(e - r) <= tol and (best is None or abs(e - r) < abs(est[best] - r)): best = j
            if e > r + tol: break
        if best is not None: used.add(best); tp += 1
    return tp


def onset_job(a):
    fid, cond, params = a
    d = G.load(fid)
    ref = G.ref_onsets(d['notes'])
    est = [float(x) for x in G.run_tool(G.audio_path(fid, cond), ['O'], params)[0].split()[1:]]
    tp = match(est, ref)
    # spurious onsets inside a strum: estimates 50-150 ms after a reference onset that are not matched
    strum_fp = sum(1 for e in est if any(0.05 < e - r < 0.15 for r in ref) and not any(abs(e - r) <= TOL for r in ref))
    errs = [min((e - r for e in est), key=abs) for r in ref if est and min(abs(e - r) for e in est) <= TOL]
    return dict(fid=fid, kind='comp' if fid.endswith('comp') else 'solo', tp=tp, ne=len(est), nr=len(ref), strum_fp=strum_fp, errs=errs)


def onset_metrics(rows):
    def summ(rs):
        tp, ne, nr = sum(r['tp'] for r in rs), sum(r['ne'] for r in rs), sum(r['nr'] for r in rs)
        P, R = tp / max(1, ne), tp / max(1, nr)
        errs = [e for r in rs for e in r['errs']]
        return dict(P=round(100 * P, 1), R=round(100 * R, 1), F=round(100 * 2 * P * R / max(1e-9, P + R), 1), n_ref=nr,
                    strum_fp_pct=round(100 * sum(r['strum_fp'] for r in rs) / max(1, ne), 1),
                    mean_err_ms=round(1000 * float(np.mean(errs)), 1) if errs else None)
    return dict(all=summ(rows), comp=summ([r for r in rows if r['kind'] == 'comp']), solo=summ([r for r in rows if r['kind'] == 'solo']))


# ---------------- chords ----------------
def pcs(m): return frozenset(x % 12 for x in m)


def chord_items(ids, seed=0):
    """Positives + negatives for every chord event in comp files of `ids`."""
    rnd = random.Random(seed)
    evs = []
    for fid in ids:
        if not fid.endswith('comp'): continue
        d = G.load(fid)
        for e in G.chord_events(d['notes']):
            lab = G.simplify(G.label_at(d['performed'], e['t'] + 0.1))
            evs.append(dict(e, fid=fid, label=':'.join(lab) if lab else 'N', canon=G.canonical_voicing(G.label_at(d['instructed'], e['t'] + 0.1))))
    items = []
    for e in evs:
        P = pcs(e['midis'])
        items.append(dict(e, kind='pos', exp=e['midis']))
        if e['canon']: items.append(dict(e, kind='leadsheet', exp=e['canon']))
        for _ in range(30):  # clearly different chord
            o = rnd.choice(evs)
            if len(P & pcs(o['midis'])) <= 1: items.append(dict(e, kind='neg_other', exp=o['midis'], neg_label=o['label'])); break
        for _ in range(60):  # shares >= 2 pitch classes but is a different chord
            o = rnd.choice(evs)
            Q = pcs(o['midis'])
            if len(P & Q) >= 2 and Q != P and o['label'] != e['label']:
                items.append(dict(e, kind='neg_shared', exp=o['midis'], neg_label=o['label'])); break
    return items


def chord_job(a):
    fid, cond, params, its = a
    lines = G.run_tool(G.audio_path(fid, cond), [f"C {i['t']:.4f} {i['end']:.4f} " + ' '.join(map(str, i['exp'])) for i in its], params)
    out = []
    for i, l in zip(its, lines):
        v = l.split()[1:]
        out.append(dict(kind=i['kind'], label=i['label'], neg_label=i.get('neg_label'), fid=fid,
                        score=float(v[0]), cos=float(v[1]), cov=float(v[2]), unexpl=float(v[3])))
    return out


def roc(pos, neg):
    pos, neg = np.asarray(pos), np.asarray(neg)
    if not len(pos) or not len(neg): return {}
    # AUC = P(score_pos > score_neg) with ties counted half
    allv = np.concatenate([pos, neg]); order = np.argsort(allv, kind='mergesort')
    ranks = np.empty(len(allv)); ranks[order] = np.arange(1, len(allv) + 1)
    for v in np.unique(allv):  # average ties
        m = allv == v; ranks[m] = ranks[m].mean()
    auc = (ranks[:len(pos)].sum() - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg))
    ths = np.unique(np.concatenate([pos, neg, [0.0, 1.0]]))
    best = max(ths, key=lambda t: (np.mean(pos >= t) + np.mean(neg < t)) / 2)
    return dict(auc=round(float(auc), 3), best_thr=round(float(best), 3),
                tpr=round(100 * float(np.mean(pos >= best)), 1), fpr=round(100 * float(np.mean(neg >= best)), 1))


def at_thr(pos, neg, t):
    pos, neg = np.asarray(pos), np.asarray(neg)
    return dict(thr=t, tpr=round(100 * float(np.mean(pos >= t)), 1), fpr=round(100 * float(np.mean(neg >= t)), 1))


def chord_metrics(rows, thr=None):
    rows = [r for r in rows if r['score'] == r['score']]  # drop NaN (segment too short)
    by = defaultdict(list)
    for r in rows: by[r['kind']].append(r['score'])
    q = lambda v: [round(float(x), 3) for x in np.percentile(v, [10, 25, 50, 75, 90])] if v else None
    out = dict(n={k: len(v) for k, v in by.items()}, percentiles_10_25_50_75_90={k: q(v) for k, v in by.items()})
    negs = by['neg_other'] + by['neg_shared']
    out['roc_pos_vs_all_neg'] = roc(by['pos'], negs)
    out['roc_pos_vs_shared'] = roc(by['pos'], by['neg_shared'])
    out['roc_leadsheet_vs_all_neg'] = roc(by['leadsheet'], negs)
    if thr is not None:
        out['at_threshold'] = dict(pos_vs_neg=at_thr(by['pos'], negs, thr), pos_vs_shared=at_thr(by['pos'], by['neg_shared'], thr),
                                   leadsheet_tpr=at_thr(by['leadsheet'], negs, thr)['tpr'])
        conf = Counter(); tot = Counter()
        for r in rows:
            if r['kind'] == 'neg_shared':
                k = f"{r['label']} played, {r['neg_label']} expected"; tot[k] += 1; conf[k] += r['score'] >= thr
        out['top_shared_confusions'] = [dict(pair=k, false_match=conf[k], of=tot[k]) for k, _ in conf.most_common(8) if conf[k]]
    return out


def run(tasks, conds, ids, params, thr=None, workers=8):
    res = {}
    with Pool(workers) as pool:
        for cond in conds:
            r = res[cond] = {}
            if 'pitch' in tasks:
                rows = [x for xs in pool.map(pitch_job, [(f, cond, params) for f in ids if f.endswith('solo')]) for x in xs]
                r['pitch'] = pitch_metrics(rows)
            if 'onset' in tasks:
                r['onset'] = onset_metrics(pool.map(onset_job, [(f, cond, params) for f in ids]))
            if 'chord' in tasks:
                items = chord_items(ids)
                byf = defaultdict(list)
                for i in items: byf[i['fid']].append(i)
                rows = [x for xs in pool.map(chord_job, [(f, cond, params, its) for f, its in byf.items()]) for x in xs]
                r['chord'] = chord_metrics(rows, thr)
            print(cond, json.dumps({k: (v.get('all') if k != 'chord' else v['roc_pos_vs_all_neg']) for k, v in r.items()}), file=sys.stderr)
    return res


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--conds', default='clean'); ap.add_argument('--split', default='all')
    ap.add_argument('--params', default=''); ap.add_argument('--tasks', default='pitch,onset,chord')
    ap.add_argument('--thr', type=float, default=None); ap.add_argument('--files', type=int, default=None)
    ap.add_argument('--out', default=None)
    a = ap.parse_args()
    res = run(a.tasks.split(','), a.conds.split(','), split_ids(a.split, a.files), shlex.split(a.params), a.thr)
    js = json.dumps(dict(split=a.split, params=a.params, thr=a.thr, results=res), indent=1)
    if a.out: open(a.out, 'w').write(js + '\n')
    else: print(js)
