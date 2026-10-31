# GuitarSet evaluation summary

Held-out TEST split = players 04-05 (120 files: 60 comp, 60 solo; 6 styles), mono **mic** audio, 44.1 kHz.
All tuning used only the TRAIN split (players 00-03). "before" = v0 parameters, "after" = tuned library defaults.

Conditions: clean; pink20 / pink10 = pink noise at 20 / 10 dB SNR; white15 = white noise 15 dB; phone = 150-6000 Hz band-pass + pink noise 20 dB. SNR is measured against whole-file signal power (pauses included), so it is conservative during notes.

## Single-note pitch (YIN, isolated notes in solo takes, annotated onset given)

| condition | n | acc ±50c before | after | octave err before | after | unvoiced before | after |
|---|---|---|---|---|---|---|---|
| clean | 3475 | 96.0 | **96.5** | 0.9 | 0.8 | 0.3 | 0.0 |
| pink20 | 3475 | 94.7 | **95.9** | 0.8 | 1.2 | 1.5 | 0.2 |
| pink10 | 3475 | 82.5 | **92.5** | 3.3 | 2.2 | 9.6 | 1.5 |
| white15 | 3475 | 89.9 | **94.9** | 2.0 | 1.5 | 3.8 | 0.5 |
| phone | 3475 | 95.9 | **96.6** | 0.7 | 1.1 | 1.2 | 0.2 |

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

After, clean: comp F = 76.5, solo F = 68.5.

## Chord verification (comp takes; expected = annotated sounding notes)

Positives: each strum/plucked chord (≥3 strings within 60 ms) vs its own annotated notes. Negatives: same audio vs the notes of a random other chord sharing ≤1 pitch class ("other") or ≥2 pitch classes with a different label ("shared"). "lead-sheet": same audio vs a canonical voicing of the instructed chord label (root in octave 3 + triad/7th + octave), i.e. what a score without exact voicing would expect.

| condition | AUC before | AUC after | AUC vs shared after | TPR/FPR @thr before (0.6) | TPR/FPR @thr after (0.58) | FPR vs shared after | lead-sheet TPR before → after |
|---|---|---|---|---|---|---|---|
| clean | 0.733 | **0.946** | 0.912 | 23.8 / 0.2 | 85.8 / 10.8 | 19.7 | 5.9 → 58.1 |
| pink20 | 0.703 | **0.939** | 0.901 | 21.1 / 0.3 | 89.3 / 16.5 | 28.6 | 4.9 → 61.2 |
| pink10 | 0.63 | **0.883** | 0.81 | 8.7 / 0.1 | 86.9 / 29.0 | 47.9 | 1.7 → 59.2 |
| white15 | 0.724 | **0.939** | 0.903 | 23.1 / 0.2 | 88.5 / 15.3 | 26.2 | 5.5 → 59.6 |
| phone | 0.723 | **0.944** | 0.907 | 24.2 / 0.3 | 91.0 / 18.6 | 32.3 | 6.3 → 61.2 |

Score percentiles (10/25/50/75/90), after, clean:

- pos (n=2458): [0.527, 0.662, 0.871, 0.937, 0.962]
- leadsheet (n=2458): [0.265, 0.445, 0.621, 0.868, 0.947]
- neg_other (n=2458): [0.0, 0.064, 0.135, 0.278, 0.43]
- neg_shared (n=2452): [0.225, 0.299, 0.409, 0.545, 0.655]

Most frequent false matches among shared-note negatives (after, clean, thr 0.58):

- D#:maj played, D#:maj7 expected: 10/17
- D#:maj7 played, D#:maj expected: 6/12
- C:maj played, C:maj7 expected: 6/6
- D:maj played, C:maj expected: 6/9
- C#:maj7 played, C#:maj expected: 5/8
- F#:maj played, F#:maj7 expected: 5/8
- D#:maj7 played, G:min expected: 4/7
- G#:maj played, G#:maj7 expected: 4/4

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
