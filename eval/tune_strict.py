"""Strict-mode + noise-adaptive threshold tuning on the TRAIN split (players 00-03).
Stage 1 (collect): run the verifier once per (strict-beta, neighbour mode) on train audio for clean/pink20/pink10
and cache raw per-item components (general score, missing-note salience, extra-note salience, segment SNR).
Stage 2 (offline): grid over the logistic centres/widths and the threshold law
    score = general * sig((miss - mc)/mw) * (1 - sig((extra - ec)/ew)),  thr = base + slope * clamp(ref - snr, 0, 40)
minimising FPR(all) + FPR(one-note-apart) with the true-match rate on real chords >= 85 % in every condition.
Stage 3 (refine): around the fit result, adds an upper clamp on the SNR used for the centre shift
(shift = a * (min(snr, smax) - 23)) and a fine threshold grid; the library defaults come from this stage.
usage: python eval/tune_strict.py [collect|fit|refine]"""
import itertools, json, os, pickle, sys
from collections import defaultdict
from multiprocessing import Pool
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
import evaluate as E
import gsdata as G

CACHE = os.path.join(G.ROOT, 'data', 'evalcache'); os.makedirs(CACHE, exist_ok=True)
TRAIN_CONDS = ('clean', 'pink20', 'pink10')
VARIANTS = {'b0_generic': (0.0, False), 'b08_generic': (0.8, False), 'b0_song': (0.0, True), 'b08_song': (0.8, True)}


def collect(split='train', conds=TRAIN_CONDS, variants=VARIANTS, tag='train'):
    ids = E.split_ids(split); items = E.chord_items(ids); byf = defaultdict(list)
    for i in items: byf[i['fid']].append(i)
    for name, (beta, song) in variants.items():
        path = os.path.join(CACHE, f'{tag}_{name}.pkl')
        if os.path.exists(path): continue
        params = ['--strict-beta', beta]
        with Pool(8) as p:
            rows = [x for c in conds for xs in p.map(E.chord_job, [(f, c, params, its, song) for f, its in byf.items()]) for x in xs]
        rows = [r for r in rows if r['score'] == r['score']]
        pickle.dump(rows, open(path, 'wb')); print('collected', name, len(rows), flush=True)


def sig(z): return 1 / (1 + np.exp(-z))


def arrays(rows):
    k = np.array([r['kind'] for r in rows]); c = np.array([r['cond'] for r in rows])
    g = np.array([r['general'] for r in rows]); mi = np.array([r['miss'] for r in rows]); ex = np.array([r['extra'] for r in rows])
    sn = np.array([r['snr'] for r in rows])
    return k, c, g, mi, ex, sn


def strict_score(g, mi, ex, mc, mw, ec, ew):
    pa = np.where(mi >= 98, 1.0, sig((mi - mc) / mw)); pe = np.where(ex <= -98, 0.0, sig((ex - ec) / ew))
    return g * pa * (1 - pe)


MASKS = {}


def masks(kind, cond):
    key = id(kind)
    if key not in MASKS:
        negm = np.array([x.startswith('neg') for x in kind]); nearm = (kind == 'neg_near') | (kind == 'neg_edit')
        MASKS[key] = [(c, (kind == 'pos') & (cond == c), negm & (cond == c), nearm & (cond == c)) for c in np.unique(cond)]
    return MASKS[key]


def metrics(kind, cond, score, thr):
    hit = score >= thr
    return {c: dict(tpr=float(hit[p].mean()), fpr=float(hit[n].mean()), fpr_near=float(hit[nr].mean()))
            for c, p, n, nr in masks(kind, cond)}


SNR_REF = 23.0  # median segment SNR of clean train chords


def strict_score_adapt(g, mi, ex, sn, mc, mw, ec, ew, a):
    """Centres move with the segment SNR: a noisier segment needs less salience to count a note as present."""
    d = a * (sn - SNR_REF)
    return strict_score(g, mi, ex, mc + d, mw, ec + d, ew)


def fit():
    res = []
    for name in os.environ.get('FIT_VARIANTS', ','.join(VARIANTS)).split(','):
        rows = pickle.load(open(os.path.join(CACHE, f'train_{name}.pkl'), 'rb'))
        k, c, g, mi, ex, sn = arrays(rows)
        pos = k == 'pos'; negs = np.array([x.startswith('neg') for x in k]); near = (k == 'neg_near') | (k == 'neg_edit')
        for mc, mw, ec, ew, a in itertools.product([-99, 3, 6, 9, 12], [2, 4], [18, 21, 24, 27, 30, 99], [2, 4], [0, 0.5, 1.0]):
            if (mc == -99 and mw == 4) or (ec == 99 and ew == 4): continue
            if mc == -99 and ec == 99 and a > 0: continue
            s = strict_score_adapt(g, mi, ex, sn, mc, mw, ec, ew, a)
            auc = E.roc(s[pos], s[negs]).get('auc'); auc_near = E.roc(s[pos], s[near]).get('auc')
            for base, slope, ref in itertools.product(np.arange(0.18, 0.63, 0.04), [0, 0.01, 0.02, 0.04], [25, 30]):
                if slope == 0 and ref != 30: continue
                thr = base + slope * np.clip(ref - sn, 0, 40)
                m = metrics(k, c, s, thr)
                tpr = np.mean([v['tpr'] for v in m.values()]); fpr = np.mean([v['fpr'] for v in m.values()])
                fnear = np.mean([v['fpr_near'] for v in m.values()]); tmin = min(v['tpr'] for v in m.values())
                res.append(dict(variant=name, mc=mc, mw=mw, ec=ec, ew=ew, adapt=a, base=round(float(base), 2), slope=slope, ref=ref,
                                auc=auc, auc_near=auc_near, tpr=round(100 * tpr, 1), tpr_min=round(100 * tmin, 1),
                                fpr=round(100 * fpr, 1), fpr_near=round(100 * fnear, 1),
                                by_cond={cc: {kk: round(100 * vv, 1) for kk, vv in v.items()} for cc, v in m.items()}))
        print('fitted', name, flush=True)
    # objective: fewest false matches (one-note-apart + all negatives) while the WORST condition's true-match rate
    # stays >= 85 % (v1 was ~86 % at its threshold)
    ok = [r for r in res if r['tpr_min'] >= 85.0]
    ok.sort(key=lambda r: r['fpr_near'] + r['fpr'])
    base = [r for r in res if r['mc'] == -99 and r['ec'] == 99 and r['slope'] == 0]  # v1 scoring, fixed threshold
    base_ok = sorted([r for r in base if r['tpr_min'] >= 85.0], key=lambda r: r['fpr_near'] + r['fpr'])
    json.dump(dict(top=ok[:40], v1_scoring_best=base_ok[:5]), open(os.path.join(os.path.dirname(__file__), 'results', 'tune_strict.json'), 'w'), indent=1)
    print('v1 scoring, best fixed threshold with tpr_min>=85:', {k: v for k, v in base_ok[0].items() if k != 'by_cond'} if base_ok else None)
    for r in ok[:12]: print({k: v for k, v in r.items() if k != 'by_cond'})


def refine(variant='b08_generic'):
    rows = [r for r in pickle.load(open(os.path.join(CACHE, f'train_{variant}.pkl'), 'rb')) if r['score'] == r['score']]
    g, mi, ex, sn = (np.array([r[k] for r in rows], float) for k in ('general', 'miss', 'extra', 'snr'))
    kind = np.array([r['kind'] for r in rows]); cond = np.array([r['cond'] for r in rows])
    pos = kind == 'pos'; neg = np.isin(kind, ['neg_other', 'neg_shared']); near = np.isin(kind, ['neg_near', 'neg_edit'])
    res = []
    for smax, mc, ec in itertools.product([20, 25, 30, 99], [3, 6, 9], [21, 24, 27]):
        sh = 0.5 * (np.minimum(sn, smax) - 23)
        s_ = g * np.where(mi >= 98, 1, sig((mi - (mc + sh)) / 4)) * (1 - np.where(ex <= -98, 0, sig((ex - (ec + sh)) / 4)))
        for b in np.round(np.arange(0.26, 0.40, 0.01), 2):
            m = s_ >= b; tprs = [100 * m[pos & (cond == c)].mean() for c in TRAIN_CONDS]
            res.append(dict(snr_max=smax, mc=mc, ec=ec, base=float(b), tpr_by_cond=[round(t, 1) for t in tprs],
                            fpr=round(100 * m[neg].mean(), 1), fpr_one_note=round(100 * m[near].mean(), 1)))
    ok = sorted([r for r in res if min(r['tpr_by_cond']) >= 85], key=lambda r: r['fpr'] + r['fpr_one_note'])
    json.dump(ok[:40], open(os.path.join(os.path.dirname(__file__), 'results', 'tune_strict_refine.json'), 'w'), indent=1)
    for r in ok[:10]: print(r)


if __name__ == '__main__':
    what = sys.argv[1] if len(sys.argv) > 1 else 'collect'
    {'collect': collect, 'fit': fit, 'refine': refine}[what]()
