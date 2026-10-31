"""Grid search for YIN (threshold, sub-octave guard) and onset (threshold mult/add, min gap) on the TRAIN split,
pooled over clean + pink10. usage: python eval/tune_pitch_onset.py pitch|onset"""
import itertools, json, os, sys
import numpy as np
from multiprocessing import Pool
sys.path.insert(0, os.path.dirname(__file__))
import evaluate as E

CONDS = ('clean', 'pink10')
if __name__ == '__main__':
    what = sys.argv[1]; ids = E.split_ids('train'); out = []
    with Pool(8) as p:
        if what == 'pitch':
            # round 1 (thr 0.1-0.3, sub 0-0.5, vconf 0.3-0.7, no margin) chose thr 0.2, sub 0.4, vconf 0.3 but
            # added octave-up errors on clean audio; round 2 adds the margin test.
            for thr, sub, vc, mg in itertools.product([0.2], [0.4, 0.5], [0.3], [0.05, 0.1, 0.2, 1.0]):
                params = ['--yin-thr', thr, '--yin-sub', sub, '--yin-vconf', vc, '--yin-margin', mg]
                rows = [x for c in CONDS for xs in p.map(E.pitch_job, [(f, c, params) for f in ids if f.endswith('solo')]) for x in xs]
                m = E.pitch_metrics(rows)['all']; out.append(dict(yin_thr=thr, yin_sub=sub, yin_vconf=vc, yin_margin=mg, **m)); print(out[-1], flush=True)
            out.sort(key=lambda o: -o['acc50'])
        else:
            for lag, mf, fmax, mult, add in itertools.product([2, 3], [2], [5000], [1.1, 1.3, 1.5], [0.01, 0.02, 0.03]):  # round 2 (round 1: lag 1-2, maxfilt 0-2, maxhz 5k/22k, mult 1.5-2.5, add 0.03-0.15 -> tune_onset_round1.json)
                params = ['--onset-mult', mult, '--onset-add', add, '--min-gap', 0.06, '--flux-lag', lag, '--flux-maxfilt', mf, '--flux-maxhz', fmax]
                per = {c: p.map(E.onset_job, [(f, c, params) for f in ids]) for c in CONDS}
                m = E.onset_metrics([r for c in CONDS for r in per[c]])['all']
                out.append(dict(lag=lag, maxfilt=mf, maxhz=fmax, mult=mult, add=add, **m,
                                F_by_cond={c: E.onset_metrics(per[c])['all']['F'] for c in CONDS})); print(out[-1], flush=True)
            out.sort(key=lambda o: -o['F'])
    print('BEST', out[:3])
    json.dump(out, open(os.path.join(os.path.dirname(__file__), 'results', f'tune_{what}.json'), 'w'), indent=1)
