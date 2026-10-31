"""Builds eval/results/SUMMARY.md from before_test.json / after_test.json / tune_chord.json."""
import json, os
R = os.path.join(os.path.dirname(__file__), 'results')
b = json.load(open(os.path.join(R, 'before_test.json')))['results']
a = json.load(open(os.path.join(R, 'after_test.json')))['results']
conds = list(a)
L = ['# GuitarSet evaluation summary', '',
     'Held-out TEST split = players 04-05 (120 files: 60 comp, 60 solo; 6 styles), mono **mic** audio, 44.1 kHz.',
     'All tuning used only the TRAIN split (players 00-03). "before" = v0 parameters, "after" = tuned library defaults.', '',
     'Conditions: clean; pink20 / pink10 = pink noise at 20 / 10 dB SNR; white15 = white noise 15 dB; '
     'phone = 150-6000 Hz band-pass + pink noise 20 dB. SNR is measured against whole-file signal power (pauses included), '
     'so it is conservative during notes.', '',
     '## Single-note pitch (YIN, isolated notes in solo takes, annotated onset given)', '',
     '| condition | n | acc ±50c before | after | octave err before | after | unvoiced before | after |', '|---|---|---|---|---|---|---|---|']
for c in conds:
    x, y = b[c]['pitch']['all'], a[c]['pitch']['all']
    L.append(f"| {c} | {y['n']} | {x['acc50']} | **{y['acc50']}** | {x['octave_err']} | {y['octave_err']} | {x['unvoiced']} | {y['unvoiced']} |")
L += ['', 'After, clean, by string / register (acc ±50c, %):', '',
      '| ' + ' | '.join(a['clean']['pitch']['by_string']) + ' | ' + ' | '.join(a['clean']['pitch']['by_register']) + ' |',
      '|' + '---|' * (6 + len(a['clean']['pitch']['by_register'])),
      '| ' + ' | '.join(f"{v['acc50']} (n={v['n']})" for v in a['clean']['pitch']['by_string'].values()) + ' | ' +
      ' | '.join(f"{v['acc50']} (n={v['n']})" for v in a['clean']['pitch']['by_register'].values()) + ' |', '',
      '## Onsets (±50 ms vs annotated note onsets; onsets within 50 ms merged, so a strum = one event)', '',
      '| condition | P before | R before | F before | P after | R after | F after | strum FP % before → after | mean err ms after |', '|---|---|---|---|---|---|---|---|---|']
for c in conds:
    x, y = b[c]['onset']['all'], a[c]['onset']['all']
    L.append(f"| {c} | {x['P']} | {x['R']} | {x['F']} | {y['P']} | {y['R']} | **{y['F']}** | {x['strum_fp_pct']} → {y['strum_fp_pct']} | {y['mean_err_ms']} |")
L += ['', 'After, clean: comp F = %s, solo F = %s.' % (a['clean']['onset']['comp']['F'], a['clean']['onset']['solo']['F']), '',
      '## Chord verification (comp takes; expected = annotated sounding notes)', '',
      'Positives: each strum/plucked chord (≥3 strings within 60 ms) vs its own annotated notes. Negatives: same audio vs the '
      'notes of a random other chord sharing ≤1 pitch class ("other") or ≥2 pitch classes with a different label ("shared"). '
      '"lead-sheet": same audio vs a canonical voicing of the instructed chord label (root in octave 3 + triad/7th + octave), '
      'i.e. what a score without exact voicing would expect.', '',
      '| condition | AUC before | AUC after | AUC vs shared after | TPR/FPR @thr before (0.6) | TPR/FPR @thr after (0.58) | FPR vs shared after | lead-sheet TPR before → after |',
      '|---|---|---|---|---|---|---|---|']
for c in conds:
    x, y = b[c]['chord'], a[c]['chord']
    L.append(f"| {c} | {x['roc_pos_vs_all_neg']['auc']} | **{y['roc_pos_vs_all_neg']['auc']}** | {y['roc_pos_vs_shared']['auc']} | "
             f"{x['at_threshold']['pos_vs_neg']['tpr']} / {x['at_threshold']['pos_vs_neg']['fpr']} | "
             f"{y['at_threshold']['pos_vs_neg']['tpr']} / {y['at_threshold']['pos_vs_neg']['fpr']} | {y['at_threshold']['pos_vs_shared']['fpr']} | "
             f"{x['at_threshold']['leadsheet_tpr']} → {y['at_threshold']['leadsheet_tpr']} |")
L += ['', 'Score percentiles (10/25/50/75/90), after, clean:', '']
for k, v in a['clean']['chord']['percentiles_10_25_50_75_90'].items():
    L.append(f'- {k} (n={a["clean"]["chord"]["n"][k]}): {v}')
L += ['', 'Most frequent false matches among shared-note negatives (after, clean, thr 0.58):', '']
for r in a['clean']['chord'].get('top_shared_confusions', []):
    L.append(f"- {r['pair']}: {r['false_match']}/{r['of']}")
t = json.load(open(os.path.join(R, 'tune_chord.json')))
sel = [r for r in t if (r['beta'], r['peak_thr'], r['weight_exp'], r['below_bass'], r['cov_thr']) == (0.0, 0.15, 2.0, 1, 0.25)]
v0 = [r for r in t if (r['beta'], r['peak_thr'], r['weight_exp'], r['below_bass'], r['cov_thr']) == (0.8, 0.05, 0.5, 0, 0.25)]
L += ['', '### Unexplained-peak ("voicing") penalty strength k (TRAIN, clean+pink20)', '',
      'score = cosine × coverage × max(0, 1 − k·unexplained). AUC of real positives vs all negatives, vs shared-note negatives, '
      'and of lead-sheet positives vs all negatives:', '',
      '| k | v0 peak test: AUC all / shared / lead-sheet | tuned peak test: AUC all / shared / lead-sheet |', '|---|---|---|']
for k in sorted({r['k'] for r in sel}):
    p = next(r for r in v0 if r['k'] == k); q = next(r for r in sel if r['k'] == k)
    L.append(f"| {k} | {p['all']['auc']} / {p['shared']['auc']} / {p['leadsheet']['auc']} | {q['all']['auc']} / {q['shared']['auc']} / {q['leadsheet']['auc']} |")
print('\n'.join(L))
