"""Builds eval/results/SUMMARY.md from v0_test.json / v1_test.json / v2_test.json / tune_chord.json."""
import json, os
R = os.path.join(os.path.dirname(__file__), 'results')
b = json.load(open(os.path.join(R, 'v0_test.json')))['results']
a = json.load(open(os.path.join(R, 'v1_test.json')))['results']
v2 = json.load(open(os.path.join(R, 'v2_test.json')))['results']
conds = list(a)
L = ['# GuitarSet evaluation summary', '',
     'Held-out TEST split = players 04-05 (120 files: 60 comp, 60 solo; 6 styles), mono **mic** audio, 44.1 kHz.',
     'All tuning used only the TRAIN split (players 00-03). "before" = v0 parameters, "after" = v1 (GuitarSet-tuned, Task 3). '
     'v2 (Task 4) only changes chord verification; its pitch/onset numbers are listed to show they did not move.', '',
     'Conditions: clean; pink20 / pink10 = pink noise at 20 / 10 dB SNR; white15 = white noise 15 dB; '
     'phone = 150-6000 Hz band-pass + pink noise 20 dB. SNR is measured against whole-file signal power (pauses included), '
     'so it is conservative during notes.', '',
     '## Single-note pitch (YIN, isolated notes in solo takes, annotated onset given)', '',
     '| condition | n | acc ±50c before | after | octave err before | after | unvoiced before | after |', '|---|---|---|---|---|---|---|---|']
for c in conds:
    x, y = b[c]['pitch']['all'], a[c]['pitch']['all']
    L.append(f"| {c} | {y['n']} | {x['acc50']} | **{y['acc50']}** | {x['octave_err']} | {y['octave_err']} | {x['unvoiced']} | {y['unvoiced']} |")
L += ['', 'v2 pitch acc ±50c (identical code path): ' + ', '.join(f"{c} {v2[c]['pitch']['all']['acc50']}" for c in conds) + '.']
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
L += ['', 'v2 onset F: ' + ', '.join(f"{c} {v2[c]['onset']['all']['F']}" for c in conds) + '.']
L += ['', 'After, clean: comp F = %s, solo F = %s.' % (a['clean']['onset']['comp']['F'], a['clean']['onset']['solo']['F']), '',
      '## Chord verification, v0 → v1 (Task 3; comp takes; expected = annotated sounding notes)', '',
      'Positives: each strum/plucked chord (≥3 strings within 60 ms) vs its own annotated notes. Negatives: same audio vs the '
      'notes of a random other chord sharing ≤1 pitch class ("other") or ≥2 pitch classes with a different label ("shared"). '
      '"lead-sheet": same audio vs a canonical voicing of the instructed chord label (root in octave 3 + triad/7th + octave), '
      'i.e. what a score without exact voicing would expect.', '',
      '| condition | AUC before | AUC after | AUC vs shared after | TPR/FPR @thr before (0.6) | TPR/FPR @thr after (0.58) | FPR vs shared after | lead-sheet TPR before → after |',
      '|---|---|---|---|---|---|---|---|']
for c in conds:
    x, y = b[c]['chord'], a[c]['chord']
    L.append(f"| {c} | {x['roc_pos_vs_all_neg']['auc']} | **{y['roc_pos_vs_all_neg']['auc']}** | {y['roc_pos_vs_shared']['auc']} | "
             f"{x['rates']['tpr']} / {x['rates']['fpr_all']} | "
             f"{y['rates']['tpr']} / {y['rates']['fpr_all']} | {y['rates']['fpr_shared']} | "
             f"{x['rates']['leadsheet_tpr']} → {y['rates']['leadsheet_tpr']} |")
L += ['', '## Chord verification, v1 → v2: strict note-level test + noise adaptation (Task 4)', '',
      'v1 = general score (cosine × coverage × penalty), fixed threshold 0.58. v2 = v1 × P(all expected notes present) × '
      '(1 − P(an extra note is present)), with per-note harmonic-unique salience over the local noise floor and centres shifted '
      'by the segment SNR; threshold 0.32. Decisions use each version\'s own MATCH. One-note-apart negatives: "near" = the '
      'audio vs a real GuitarSet chord whose pitch-class set differs by 1-2 classes (e.g. maj vs maj7); "add"/"drop"/"move" = '
      'the expected notes with one pitch class added / removed / moved by a semitone or tone.', '',
      '| condition | AUC all v1 → v2 | AUC one-note v1 → v2 | TPR v1 → v2 | FPR all v1 → v2 | FPR shared v1 → v2 | FPR near v1 → v2 | FPR add / drop / move v1 → v2 | lead-sheet TPR v1 → v2 |',
      '|---|---|---|---|---|---|---|---|---|']
for c in conds:
    x, y = a[c]['chord'], v2[c]['chord']; rx, ry = x['rates'], y['rates']
    L.append(f"| {c} | {x['roc_pos_vs_all_neg']['auc']} → **{y['roc_pos_vs_all_neg']['auc']}** | {x['roc_pos_vs_one_note']['auc']} → **{y['roc_pos_vs_one_note']['auc']}** | "
             f"{rx['tpr']} → {ry['tpr']} | {rx['fpr_all']} → **{ry['fpr_all']}** | {rx['fpr_shared']} → **{ry['fpr_shared']}** | {rx['fpr_near']} → **{ry['fpr_near']}** | "
             f"{rx['fpr_add']} / {rx['fpr_drop']} / {rx['fpr_move']} → {ry['fpr_add']} / {ry['fpr_drop']} / {ry['fpr_move']} | {rx['leadsheet_tpr']} → {ry['leadsheet_tpr']} |")
L += ['', 'Named confusions (false matches / trials, shared+near negatives, all 5 conditions pooled), v1 → v2:', '',
      '| played / expected | v1 | v2 |', '|---|---|---|']
keys = sorted({k for c in conds for k in list(a[c]['chord']['named_pairs']) + list(v2[c]['chord']['named_pairs'])})
def pool(res, k):
    f = t_ = 0
    for c in conds:
        v = res[c]['chord']['named_pairs'].get(k)
        if v: f += int(v.split('/')[0]); t_ += int(v.split('/')[1])
    return f"{f}/{t_}" + (f" ({100 * f / t_:.0f}%)" if t_ else '')
for k in keys: L.append(f"| {k} | {pool(a, k)} | {pool(v2, k)} |")
L += ['', 'By chord quality (clean; shared+near negatives), false-match % v1 → v2:', '', '| played, expected | n | v1 | v2 |', '|---|---|---|---|']
qa = {r['pair']: r for r in a['clean']['chord']['quality_confusions']}
for r in v2['clean']['chord']['quality_confusions']:
    if r['pair'] in qa: L.append(f"| {r['pair']} | {r['of']} | {qa[r['pair']]['pct']} | **{r['pct']}** |")
L += ['', 'Most frequent remaining false matches (v2, clean):', '']
for r in v2['clean']['chord'].get('top_shared_confusions', []):
    L.append(f"- {r['pair']}: {r['false_match']}/{r['of']}")
L += ['', 'Score percentiles (10/25/50/75/90), v2, clean:', '']
for k, v in v2['clean']['chord']['percentiles_10_25_50_75_90'].items():
    L.append(f'- {k}: {v}')
ts = os.path.join(R, 'tune_strict.json')
if os.path.exists(ts):
    L += ['', 'Strict-mode tuning (TRAIN, clean+pink20+pink10): see eval/tune_strict.py and tune_strict.json. Song-aware neighbour '
          'restriction (only the expected/previous chords\' neighbours) and an SNR-dependent threshold slope gave no gain over the '
          'generic all-pitch-class scan with SNR-shifted salience centres.']
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
