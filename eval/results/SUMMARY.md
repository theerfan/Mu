# GuitarSet evaluation summary

Held-out TEST split = players 04-05 (120 files: 60 comp, 60 solo; 6 styles), mono **mic** audio, 44.1 kHz.
All tuning used only the TRAIN split (players 00-03). "before" = v0 parameters, "after" = v1 (GuitarSet-tuned, Task 3). v2 (Task 4) only changes chord verification; its pitch/onset numbers are listed to show they did not move.

Conditions: clean; pink20 / pink10 = pink noise at 20 / 10 dB SNR; white15 = white noise 15 dB; phone = 150-6000 Hz band-pass + pink noise 20 dB. SNR is measured against whole-file signal power (pauses included), so it is conservative during notes.

## Single-note pitch (YIN, isolated notes in solo takes, annotated onset given)

| condition | n | acc ±50c before | after | octave err before | after | unvoiced before | after |
|---|---|---|---|---|---|---|---|
| clean | 3475 | 96.0 | **96.5** | 0.9 | 0.8 | 0.3 | 0.0 |
| pink20 | 3475 | 94.7 | **95.9** | 0.8 | 1.2 | 1.5 | 0.2 |
| pink10 | 3475 | 82.5 | **92.5** | 3.3 | 2.2 | 9.6 | 1.5 |
| white15 | 3475 | 89.9 | **94.9** | 2.0 | 1.5 | 3.8 | 0.5 |
| phone | 3475 | 95.9 | **96.6** | 0.7 | 1.1 | 1.2 | 0.2 |

v2 pitch acc ±50c (identical code path): clean 96.5, pink20 95.9, pink10 92.5, white15 94.9, phone 96.6.

After, clean, by string / register (acc ±50c, %):

| 6 (E) | 5 (A) | 4 (D) | 3 (G) | 2 (B) | 1 (e) | <E3 (52) | E3-D#4 | E4-D#5 | >=E5 |
|---|---|---|---|---|---|---|---|---|---|
| 100.0 (n=11) | 96.8 (n=156) | 98.0 (n=613) | 97.3 (n=1016) | 96.0 (n=976) | 94.6 (n=703) | 97.6 (n=82) | 97.5 (n=1560) | 95.6 (n=1790) | 97.7 (n=43) |

## Onsets (±50 ms vs annotated note onsets; onsets within 50 ms merged, so a strum = one event)

| condition | P before | R before | F before | P after | R after | F after | strum FP % before → after | mean err ms after |
|---|---|---|---|---|---|---|---|---|
| clean | 55.1 | 85.9 | 67.2 | 62.1 | 87.7 | **72.7** | 15.8 → 8.6 | -5.0 |
| pink20 | 87.0 | 46.9 | 60.9 | 79.4 | 81.3 | **80.3** | 4.8 → 5.5 | 1.8 |
| pink10 | 95.6 | 16.1 | 27.6 | 86.6 | 66.4 | **75.1** | 1.1 → 3.5 | 5.0 |
| white15 | 95.3 | 21.2 | 34.7 | 82.7 | 77.5 | **80.0** | 1.1 → 4.1 | 3.2 |
| phone | 92.3 | 32.9 | 48.5 | 78.4 | 82.0 | **80.1** | 2.0 → 5.9 | 1.6 |

v2 onset F: clean 72.7, pink20 80.3, pink10 75.1, white15 80.0, phone 80.1.

After, clean: comp F = 76.5, solo F = 68.5.

## Chord verification, v0 → v1 (Task 3; comp takes; expected = annotated sounding notes)

Positives: each strum/plucked chord (≥3 strings within 60 ms) vs its own annotated notes. Negatives: same audio vs the notes of a random other chord sharing ≤1 pitch class ("other") or ≥2 pitch classes with a different label ("shared"). "lead-sheet": same audio vs a canonical voicing of the instructed chord label (root in octave 3 + triad/7th + octave), i.e. what a score without exact voicing would expect.

| condition | AUC before | AUC after | AUC vs shared after | TPR/FPR @thr before (0.6) | TPR/FPR @thr after (0.58) | FPR vs shared after | lead-sheet TPR before → after |
|---|---|---|---|---|---|---|---|
| clean | 0.733 | **0.946** | 0.912 | 23.8 / 0.2 | 85.8 / 10.8 | 19.7 | 5.9 → 58.1 |
| pink20 | 0.703 | **0.939** | 0.901 | 21.1 / 0.3 | 89.3 / 16.5 | 28.6 | 4.9 → 61.2 |
| pink10 | 0.63 | **0.883** | 0.81 | 8.7 / 0.1 | 86.9 / 29.0 | 47.9 | 1.7 → 59.2 |
| white15 | 0.724 | **0.939** | 0.903 | 23.1 / 0.2 | 88.5 / 15.3 | 26.2 | 5.5 → 59.6 |
| phone | 0.723 | **0.944** | 0.907 | 24.2 / 0.3 | 91.0 / 18.6 | 32.3 | 6.3 → 61.1 |

## Chord verification, v1 → v2: strict note-level test + noise adaptation (Task 4)

v1 = general score (cosine × coverage × penalty), fixed threshold 0.58. v2 = v1 × P(all expected notes present) × (1 − P(an extra note is present)), with per-note harmonic-unique salience over the local noise floor and centres shifted by the segment SNR; threshold 0.32. Decisions use each version's own MATCH. One-note-apart negatives: "near" = the audio vs a real GuitarSet chord whose pitch-class set differs by 1-2 classes (e.g. maj vs maj7); "add"/"drop"/"move" = the expected notes with one pitch class added / removed / moved by a semitone or tone.

| condition | AUC all v1 → v2 | AUC one-note v1 → v2 | TPR v1 → v2 | FPR all v1 → v2 | FPR shared v1 → v2 | FPR near v1 → v2 | FPR add / drop / move v1 → v2 | lead-sheet TPR v1 → v2 |
|---|---|---|---|---|---|---|---|---|
| clean | 0.946 → **0.963** | 0.798 → **0.886** | 85.8 → 83.8 | 10.8 → **5.4** | 19.7 → **8.8** | 41.4 → **19.7** | 76.6 / 70.7 / 32.0 → 28.3 / 49.3 / 12.2 | 58.1 → 38.4 |
| pink20 | 0.939 → **0.953** | 0.78 → **0.872** | 89.3 → 87.9 | 16.5 → **10.7** | 28.6 → **16.6** | 48.4 → **30.3** | 84.6 / 72.6 / 43.4 → 36.5 / 62.9 / 22.3 | 61.2 → 51.7 |
| pink10 | 0.883 → **0.927** | 0.668 → **0.823** | 86.9 → 89.4 | 29.0 → **22.0** | 47.9 → **30.4** | 58.5 → **42.0** | 92.6 / 68.0 / 62.1 → 50.2 / 74.9 / 36.5 | 59.2 → 58.4 |
| white15 | 0.939 → **0.955** | 0.782 → **0.874** | 88.5 → 86.9 | 15.3 → **10.3** | 26.2 → **15.9** | 45.9 → **27.9** | 84.2 / 72.5 / 41.4 → 34.2 / 62.3 / 20.8 | 59.6 → 50.7 |
| phone | 0.944 → **0.953** | 0.786 → **0.875** | 91.0 → 86.9 | 18.6 → **10.9** | 32.3 → **16.8** | 52.2 → **28.0** | 88.6 / 74.7 / 45.4 → 33.6 / 61.5 / 19.4 | 61.1 → 51.0 |

Named confusions (false matches / trials, shared+near negatives, all 5 conditions pooled), v1 → v2:

| played / expected | v1 | v2 |
|---|---|---|
| A:maj played, A:min expected | 24/60 (40%) | 14/60 (23%) |
| A:min played, A:maj expected | 9/20 (45%) | 1/20 (5%) |
| C:maj played, C:maj7 expected | 79/95 (83%) | 59/95 (62%) |
| C:maj7 played, C:maj expected | 70/130 (54%) | 39/130 (30%) |
| D#:maj played, D#:maj7 expected | 134/195 (69%) | 24/195 (12%) |
| D#:maj7 played, D#:maj expected | 123/175 (70%) | 93/175 (53%) |
| F:maj played, F:min expected | 23/25 (92%) | 6/25 (24%) |
| F:min played, F:maj expected | 6/15 (40%) | 9/15 (60%) |

By chord quality (clean; shared+near negatives), false-match % v1 → v2:

| played, expected | n | v1 | v2 |
|---|---|---|---|
| maj played, maj expected | 357 | 24.6 | **11.8** |
| maj played, min expected | 285 | 17.9 | **8.4** |
| min played, maj expected | 236 | 16.1 | **5.1** |
| maj played, maj7 expected | 228 | 14.9 | **7.0** |
| maj played, 7 expected | 187 | 21.9 | **5.9** |
| maj played, min7 expected | 180 | 28.9 | **9.4** |
| maj played, maj7 expected (same root) | 180 | 58.9 | **21.7** |
| maj7 played, maj expected | 167 | 20.4 | **11.4** |
| maj7 played, maj expected (same root) | 162 | 53.7 | **29.0** |
| maj played, maj expected (same root) | 153 | 73.2 | **37.3** |
| 7 played, maj expected | 138 | 17.4 | **10.9** |
| min7 played, maj expected | 136 | 36.0 | **25.0** |

Most frequent remaining false matches (v2, clean):

- F:maj played, F:maj expected: 15/38
- D#:maj7 played, D#:maj expected: 14/35
- C:maj played, C:maj7 expected: 10/19
- C#:maj played, C#:maj expected: 9/26
- D:maj played, C:maj expected: 9/20
- G#:maj played, G#:maj7 expected: 8/20
- D#:maj7 played, G:min expected: 7/18
- F#:min7 played, A:maj expected: 7/7
- D#:maj played, D#:maj7 expected: 7/39
- C#:maj7 played, C#:maj expected: 6/22

Score percentiles (10/25/50/75/90), v2, clean:

- pos: [0.232, 0.408, 0.663, 0.869, 0.932]
- leadsheet: [0.006, 0.06, 0.221, 0.461, 0.71]
- neg_other: [0.0, 0.0, 0.005, 0.046, 0.148]
- neg_shared: [0.001, 0.008, 0.05, 0.163, 0.299]
- neg_near: [0.003, 0.023, 0.109, 0.272, 0.448]
- neg_edit: [0.009, 0.048, 0.154, 0.326, 0.493]

Strict-mode tuning (TRAIN, clean+pink20+pink10): see eval/tune_strict.py and tune_strict.json. Song-aware neighbour restriction (only the expected/previous chords' neighbours) and an SNR-dependent threshold slope gave no gain over the generic all-pitch-class scan with SNR-shifted salience centres.

### Unexplained-peak ("voicing") penalty strength k (TRAIN, clean+pink20)

score = cosine × coverage × max(0, 1 − k·unexplained). AUC of real positives vs all negatives, vs shared-note negatives, and of lead-sheet positives vs all negatives:

| k | v0 peak test: AUC all / shared / lead-sheet | tuned peak test: AUC all / shared / lead-sheet |
|---|---|---|
| 0 | 0.942 / 0.903 / 0.849 | 0.942 / 0.903 / 0.849 |
| 0.25 | 0.942 / 0.9 / 0.847 | 0.949 / 0.91 / 0.865 |
| 0.5 | 0.934 / 0.885 / 0.84 | 0.946 / 0.903 / 0.871 |
| 0.75 | 0.917 / 0.856 / 0.823 | 0.939 / 0.891 / 0.87 |
| 1 | 0.889 / 0.819 / 0.793 | 0.926 / 0.878 / 0.862 |
| 1.5 | 0.825 / 0.765 / 0.708 | 0.904 / 0.862 / 0.84 |
| 2 | 0.792 / 0.744 / 0.664 | 0.891 / 0.854 / 0.827 |
| 3 | 0.736 / 0.707 / 0.611 | 0.876 / 0.845 / 0.813 |
