"""Grid search of chord-verifier parameters on the TRAIN split (players 00-03), pooled over clean + pink20.
The penalty strength k is applied offline: score = cos * cov * max(0, 1 - k * unexplained).
usage: python eval/tune_chord.py [--out eval/results/tune_chord.json]"""
import itertools, json, os, sys
from collections import defaultdict
from multiprocessing import Pool
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
import evaluate as E

GRID = dict(beta=[0.0, 0.8], peak_thr=[0.05, 0.15], weight_exp=[0.5, 1.0, 2.0], below_bass=[0, 1], cov_thr=[0.15, 0.25])
KS = [0, 0.25, 0.5, 0.75, 1, 1.5, 2, 3]


def rows_for(params, ids, conds=('clean', 'pink20')):
    items = E.chord_items(ids); byf = defaultdict(list)
    for i in items: byf[i['fid']].append(i)
    with Pool(8) as p:
        return [x for c in conds for xs in p.map(E.chord_job, [(f, c, params, its) for f, its in byf.items()]) for x in xs
                if x['score'] == x['score']]


def score(r, k): return r['cos'] * r['cov'] * max(0.0, 1 - k * r['unexpl'])


def evaluate_k(rows, k):
    pos = [score(r, k) for r in rows if r['kind'] == 'pos']
    neg = [score(r, k) for r in rows if r['kind'].startswith('neg')]
    sh = [score(r, k) for r in rows if r['kind'] == 'neg_shared']
    ls = [score(r, k) for r in rows if r['kind'] == 'leadsheet']
    return dict(k=k, all=E.roc(pos, neg), shared=E.roc(pos, sh), leadsheet=E.roc(ls, neg))


if __name__ == '__main__':
    ids = E.split_ids('train'); out = []
    for vals in itertools.product(*GRID.values()):
        g = dict(zip(GRID, vals))
        params = ['--beta', g['beta'], '--peak-thr', g['peak_thr'], '--weight-exp', g['weight_exp'], '--cov-thr', g['cov_thr'], '--penalty', 1]
        params += ['--ignore-below-bass', g['below_bass']]
        rows = rows_for(params, ids)
        for k in KS:
            res = dict(g, **evaluate_k(rows, k)); out.append(res)
        best = max((o for o in out if all(o[x] == g[x] for x in g)), key=lambda o: o['all']['auc'])
        print(g, 'best k', best['k'], best['all'], 'shared', best['shared']['auc'], 'leadsheet', best['leadsheet']['auc'], flush=True)
    out.sort(key=lambda o: -o['all']['auc'])
    print('TOP 5:'); [print(o) for o in out[:5]]
    path = sys.argv[sys.argv.index('--out') + 1] if '--out' in sys.argv else os.path.join(os.path.dirname(__file__), 'results', 'tune_chord.json')
    json.dump(out, open(path, 'w'), indent=1)
