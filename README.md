# Guitar practice app — DSP proof of concept

Architecture: see [ARCHITECTURE.md](ARCHITECTURE.md). This README covers the C++17 DSP PoC in `dsp/`.

## Layout
```
dsp/CMakeLists.txt
dsp/include/gdsp.h        public API: fft, YinPitch, OnsetDetector, chromaFromSpectrum, verifyChord
dsp/include/wav.h         single-header WAV reader/writer (8/16/24/32-bit PCM, float32, any channels/rate)
dsp/src/gdsp.cpp          implementation (self-written radix-2 FFT, no dependencies)
dsp/tools/gtcli.cpp       CLI: onsets + pitch, optional --expect chord verification
dsp/tools/gen_test_wavs.cpp  Karplus-Strong synth for test material (+ white noise, 25 dB SNR)
dsp/tools/bench.cpp       per-call benchmark + allocation counter for every analysis step
dsp/test/run_tests.sh     builds, synthesises, asserts results
dsp/test/chord_table.sh   chord score matrix (audio x expected) as markdown
dsp/test/results.txt      latest test output;  dsp/test/bench.txt  latest benchmark
importer/                 Guitar Pro / MusicXML -> song JSON (Node + alphaTab), see "Song importer"
```

## Build & run
```
cd dsp && cmake -S . -B build && cmake --build build
./build/gtcli song.wav                         # onsets + note name + cents
./build/gtcli strum.wav --expect C3,E3,G3,C4,E4 # chord verification (names or MIDI numbers); --no-strict = general score only
./build/gtcli song.wav --json                  # machine-readable output
./build/gtcli strum.wav --expect ... --no-diff --no-penalty   # A/B: disable the margin improvements
./build/bench [48000]                          # per-call cost + allocations/call
./test/run_tests.sh                            # full test
```

## How it works
* **Onsets**: SuperFlux-style spectral flux on log-compressed (log1p(100|X|)) Hann-windowed magnitudes, ≥1024-pt
  (~23 ms) frame, hop = frame/4: compare with the frame 3 hops back after a ±2-bin max filter over frequency, bins
  ≤5 kHz only, half-wave-rectified; threshold = 1.1×median(last 16 frames)+0.02, local-max peak picking, 60 ms min
  gap, RMS silence gate and 100 ms warm-up (v0: lag 1, no max filter, all bins, 1.5×median+0.06 — see GuitarSet). Streaming API (`processHop`), so it's the same code as runs on device.
* **Pitch**: YIN (difference fn → cumulative-mean normalisation → absolute threshold 0.2 → parabolic interpolation),
  70–1400 Hz, ~45 ms window, plus a sub-octave guard (switch to τ/2 or τ/3 if d′ there is < 0.5 and ≤ d′(τ)+0.2) and
  a lower voicing bar (1−d′ > 0.3) so noisy notes still get a pitch (v0: threshold 0.15, no guard, 0.7); the CLI takes the median of 3 frames starting 40 ms after onset (skipping the pick transient).
* **Chord verification** (`ChordVerifier`): FFT (≤32768 pts, preallocated) of the 60–800 ms after the onset →
  peak-picked spectrum (70–2000 Hz) → 12-bin chroma (sqrt magnitude, L2 norm). The expected template puts each
  expected note's harmonics 1–8 (minus 7th) on their pitch classes with weight 1/√h. Then:
  * `cosine` = cos(chroma, template); `coverage` = fraction of expected pitch classes with ≥25 % of max chroma.
  * **Unexplained-peak penalty**: because the score says exactly which notes (MIDI, not just pitch class) should
    sound, every clear spectral peak ≤1.2 kHz (≥15 % of max, at or above the lowest expected note) should be within
    (30 + 3h) cents of harmonic h ≤ 12 of some expected note. `unexplained` = magnitude²-weighted share of peaks that
    fit none (v0: ≥5 % of max, sqrt weighting, all peaks — real guitars' ~100 Hz body resonance alone tripped it).
  * **General score** = `cosine × coverage × max(0, 1 − 0.25·unexplained)` (v1, Task 3). On its own (threshold 0.58) it
    can't separate chords one note apart (maj vs maj7): their chroma differs by one weak bin.
  * **Strict note-level test** (`strict = true`, default since Task 4). Per note, a *salience* in dB: the mean level of
    its partials h = 1..4 (≤ 5 kHz) that are *harmonic-unique* (not within 50 cents of any harmonic ≤ 5.2 kHz of the
    other notes being compared), measured against a local noise floor (median magnitude in each third-octave band,
    60 Hz–6 kHz). Partials shared with other notes carry no evidence and are skipped.
    * *missing note*: `miss` = salience of the weakest expected pitch class (best octave of it in the expected notes);
    * *extra note*: `extra` = highest salience of any non-expected pitch class from just above the bass note up to E6,
      computed on the onset-local difference spectrum (pre-onset spectrum ×0.8 subtracted, so the previous chord's
      ringing notes don't count as extras).
    * `score = general × σ((miss − 6 dB − s)/4) × (1 − σ((extra − 24 dB − s)/4))`, σ = logistic.
  * **Noise adaptation**: a per-segment SNR (strongest 70–2000 Hz peak over the local floor) shifts both salience centres
    by `s = 0.5 × (min(SNR, 30) − 23)` dB: in noise a real note sticks out less, and noise peaks look less like an extra
    note. `ChordMatch.match = score ≥ thresholdFor(SNR)`; the tuned threshold is a flat 0.32 (a SNR-dependent threshold
    slope is implemented, `thresholdSlope`, but gave no gain once the centres adapt).
  * **Song context** (optional): `setNeighbours(midis)` limits the extra-note scan to given pitch classes (e.g. those
    of the previous/next chords in the song). Implemented and evaluated; it didn't beat scanning all 12 on GuitarSet.
  * `ChordMatch` reports `generalScore`, `missSalDb`/`weakestPc`, `extraSalDb`/`extraPc`, `snrDb`, `threshold`,
    `match`, so the UI can say *which* note was missing or extra. `gtcli` prints them.
  * v0 used penalty 3 and threshold 0.6; on real guitar audio that rejected ~75 % of correct chords (see below).
  Purity (energy share in the expected pitch classes) is still reported for diagnostics.
* **Real-time safety**: `FFT`, `SpectrumAnalyzer`, `YinPitch`, `OnsetDetector`, `ChordVerifier` allocate everything
  in their constructors; their process/verify calls do no heap allocation (checked by `bench`'s instrumented
  `operator new`: 0 allocs/call), no locks, no I/O. `freqToNote`/`midiName` return `std::string` and are for
  UI/CLI only; on the audio side use `freqToMidi`.
* Frame sizes scale with sample rate; stereo gets downmixed to mono.

## Test results (`dsp/test/run_tests.sh`)
Test audio: Karplus-Strong plucks (allpass fractional delay, so tuning is exact) + white noise at 25 dB SNR.
Singles at 44.1/48/22.05 kHz; a 4-note sequence; open C (C3 E3 G3 C4 E4), G (G2 B2 D3 G3 B3 G4), Am (A2 E3 A3 C4 E4),
Em (E2 B2 E3 G3 B3 E4), strummed 12 ms between strings; transitions where the second chord is strummed 1.0 s after
the first (first still ringing); a stereo C→G file; one-note-apart pairs Cmaj/Cmaj7 (C3 E3 G3 B3 E4) and
D#maj/D#maj7 (barre at fret 6: D#3 A#3 D#4 G4 A#4 / D4). Decisions use the verifier's own MATCH.
```
PASS  single E2 @44100Hz -> E2 -0.0c onset 0.261s (truth 0.250s, onsets=1)
PASS  single A2 @44100Hz -> A2 -0.1c onset 0.261s (truth 0.250s, onsets=1)
PASS  single G3 @44100Hz -> G3 -0.1c onset 0.255s (truth 0.250s, onsets=1)
PASS  single E4 @44100Hz -> E4 -0.1c onset 0.255s (truth 0.250s, onsets=1)
PASS  single E2 @48000Hz -> E2 +0.0c onset 0.267s (truth 0.250s, onsets=1)
PASS  single A2 @48000Hz -> A2 -0.0c onset 0.267s (truth 0.250s, onsets=1)
PASS  single G3 @48000Hz -> G3 -0.0c onset 0.267s (truth 0.250s, onsets=1)
PASS  single E4 @48000Hz -> E4 -0.0c onset 0.267s (truth 0.250s, onsets=1)
PASS  single E2 @22050Hz -> E2 +0.0c onset 0.267s (truth 0.250s, onsets=1)
PASS  single A2 @22050Hz -> A2 +0.0c onset 0.267s (truth 0.250s, onsets=1)
XFAIL single G3 @22050Hz -> G3 +0.0c onset 0.267s (truth 0.250s, onsets=2)
PASS  single E4 @22050Hz -> E4 -0.0c onset 0.267s (truth 0.250s, onsets=1)
PASS  sequence E2 A2 G3 E4 (truth onsets .3 1.1 1.9 2.7) -> E2 A2 G3 E4 
PASS  Cmaj audio vs expected Cmaj score=0.878 expected match
PASS  Cmaj audio vs expected Gmaj score=0.000 expected nomatch
PASS  Cmaj audio vs expected Am   score=0.000 expected nomatch
PASS  Gmaj audio vs expected Gmaj score=0.872 expected match
PASS  Gmaj audio vs expected Cmaj score=0.000 expected nomatch
PASS  stereo seq chord#1 vs Cmaj  score=0.923 expected match
PASS  stereo seq chord#2 vs Gmaj  score=0.804 expected match
PASS  stereo seq chord#2 vs Cmaj  score=0.011 expected nomatch
PASS  Cmaj audio vs expected Em (shares E,G) score=0.010 expected nomatch
PASS  Am audio vs expected Am   score=0.880 expected match
PASS  Am audio vs expected Cmaj (shares C,E) score=0.000 expected nomatch
PASS  Em audio vs expected Em   score=0.824 expected match
PASS  Em audio vs expected Gmaj (shares G,B) score=0.000 expected nomatch
PASS  Cmaj7 audio vs expected Cmaj7 score=0.920 expected match
PASS  Cmaj7 audio vs expected Cmaj (extra B) score=0.001 expected nomatch
PASS  Cmaj audio vs expected Cmaj7 (missing B) score=0.233 expected nomatch
PASS  D#maj audio vs expected D#maj score=0.961 expected match
PASS  D#maj audio vs expected D#maj7 (missing D) score=0.157 expected nomatch
PASS  D#maj7 audio vs expected D#maj7 score=0.937 expected match
PASS  D#maj7 audio vs expected D#maj (extra D) score=0.002 expected nomatch
PASS  Am after C (prev ringing) vs Am score=0.791 expected match
PASS  Am after C (prev ringing) vs C score=0.001 expected nomatch
PASS  C after Am (prev ringing) vs C score=0.835 expected match
PASS  C after Am (prev ringing) vs Am score=0.008 expected nomatch
PASS  Em after G (prev ringing) vs Em score=0.798 expected match
PASS  Em after G (prev ringing) vs G score=0.001 expected nomatch
PASS  G after Em (prev ringing) vs G score=0.800 expected match
PASS  G after Em (prev ringing) vs Em score=0.002 expected nomatch
PASS  G after C (prev ringing) vs G score=0.829 expected match
PASS  G after C (prev ringing) vs C score=0.001 expected nomatch
== 42 passed, 0 failed, 1 known failures (XFAIL)
```

After the Task 3 retune, 7 shared-note chord checks were expected failures (XFAIL). With the strict note-level test
they all pass again, plus the 7 new maj/maj7 checks. The one remaining XFAIL is a 22.05 kHz synthetic note that gets a
second onset in its tail (the GuitarSet-tuned onset detector is more sensitive; 44.1/48 kHz are the target rates).

### Chord score matrix (`dsp/test/chord_table.sh`; bold = correct expectation)
Before (v0: cosine × coverage, threshold 0.75). Note the false matches, e.g. C audio vs expected Em = 0.826,
Em vs G = 0.760, and correct "Am after C" (0.762) scoring below wrong C (0.777):
| audio (onset) | exp C | exp G | exp Am | exp Em |
|---|---|---|---|---|
| C | **0.876** | 0.638 | 0.476 | 0.826 |
| G | 0.164 | **0.864** | 0.150 | 0.433 |
| Am | 0.733 | 0.331 | **0.880** | 0.617 |
| Em | 0.424 | 0.760 | 0.399 | **0.836** |
| Am after C | 0.777 | 0.440 | **0.762** | 0.657 |
| C after Am | **0.856** | 0.643 | 0.807 | 0.792 |
| Em after G | 0.431 | 0.753 | 0.427 | **0.821** |
| G after Em | 0.386 | **0.837** | 0.345 | 0.690 |
| G after C | 0.685 | **0.822** | 0.586 | 0.744 |

After (penalty + onset-local difference, threshold 0.6). Lowest correct = 0.752, highest wrong = 0.405:
| audio (onset) | exp C | exp G | exp Am | exp Em |
|---|---|---|---|---|
| C | **0.879** | 0.000 | 0.101 | 0.405 |
| G | 0.000 | **0.873** | 0.000 | 0.171 |
| Am | 0.000 | 0.000 | **0.882** | 0.000 |
| Em | 0.000 | 0.223 | 0.000 | **0.770** |
| Am after C | 0.000 | 0.000 | **0.811** | 0.000 |
| C after Am | **0.858** | 0.000 | 0.131 | 0.261 |
| Em after G | 0.014 | 0.000 | 0.000 | **0.752** |
| G after Em | 0.000 | **0.783** | 0.000 | 0.248 |
| G after C | 0.000 | **0.833** | 0.000 | 0.199 |

Ablation: without the pre-onset subtraction (`--no-diff`) the transition rows' correct scores fall to 0.38–0.54
(previous chord's ringing notes are counted as unexplained), so both parts are needed *with that strong penalty*.

v1 (Task 3: GuitarSet-tuned penalty 0.25, threshold 0.58, no strict test). Correct chords still score 0.81–0.88, but
shared-note wrong chords now reach 0.81 (C audio vs Em), so this synthetic matrix no longer separates them; on real
audio the tuned setting is clearly better (AUC 0.73 → 0.95):
| audio (onset) | exp C | exp G | exp Am | exp Em |
|---|---|---|---|---|
| C | **0.879** | 0.530 | 0.459 | 0.813 |
| G | 0.145 | **0.873** | 0.125 | 0.412 |
| Am | 0.655 | 0.277 | **0.882** | 0.559 |
| Em | 0.385 | 0.723 | 0.365 | **0.825** |
| Am after C | 0.658 | 0.372 | **0.811** | 0.585 |
| C after Am | **0.858** | 0.540 | 0.787 | 0.739 |
| Em after G | 0.412 | 0.662 | 0.416 | **0.810** |
| G after Em | 0.360 | **0.846** | 0.295 | 0.684 |
| G after C | 0.566 | **0.833** | 0.475 | 0.696 |

Current defaults (Task 4: strict note-level test, threshold 0.32). Lowest correct = 0.791, highest wrong = 0.014:
| audio (onset) | exp C | exp G | exp Am | exp Em |
|---|---|---|---|---|
| C | **0.878** | 0.000 | 0.000 | 0.010 |
| G | 0.000 | **0.872** | 0.000 | 0.000 |
| Am | 0.000 | 0.000 | **0.880** | 0.000 |
| Em | 0.000 | 0.000 | 0.000 | **0.824** |
| Am after C | 0.001 | 0.000 | **0.791** | 0.002 |
| C after Am | **0.835** | 0.000 | 0.008 | 0.014 |
| Em after G | 0.001 | 0.001 | 0.001 | **0.798** |
| G after Em | 0.001 | **0.800** | 0.000 | 0.002 |
| G after C | 0.001 | **0.829** | 0.001 | 0.002 |

## Benchmark (`dsp/build/bench 48000`, this box: Intel Xeon VM, -O3 default Release, single thread)
```
sr=48000 onsetFrame=2048 hop=512 (10.67 ms) yinWindow=4096 chordSegment=35520
FFT (onset frame size)             mean    18.44 us  p50    17.79  p99    23.73  | budget   10667 us ->  0.17% | allocs/call 0
SpectrumAnalyzer (onset frame)     mean    22.53 us  p50    21.43  p99    27.92  | budget   10667 us ->  0.21% | allocs/call 0
OnsetDetector::processHop          mean    28.81 us  p50    27.55  p99    34.45  | budget   10667 us ->  0.27% | allocs/call 0
YinPitch::process (70 Hz min)      mean  1212.03 us  p50  1173.56  p99  1711.39  | budget   10667 us -> 11.36% | allocs/call 0
ChordVerifier::verify (v1: strict off) mean   603.43 us  p50   589.79  p99   709.72  | budget   10667 us ->  5.66% | allocs/call 0
ChordVerifier::verify              mean   618.37 us  p50   603.41  p99   736.39  | budget   10667 us ->  5.80% | allocs/call 0
ChordVerifier::verify (+pre diff)  mean  1179.30 us  p50  1159.86  p99  1298.13  | budget   10667 us -> 11.06% | allocs/call 0
budget = one hop of real time. YIN runs ~3x per onset and verify once per expected chord,
so they may be scheduled across hops on the analysis thread rather than per hop.
```
Phone estimate (mid-range ARM, ~3–5× slower): onset hop ≈ 0.12–0.2 ms per 10.7 ms hop (1–2 %); YIN ≈ 4–7 ms
per call (×3 per onset); chord verify ≈ 2–3 ms without / 3.5–6 ms with the pre-onset context, once per
expected chord. The strict test itself adds ~15–40 µs here (+3–6 %, ≈0.1–0.2 ms on a phone); the pre-onset
context costs one extra FFT (~0.55 ms here), which could be removed by caching the spectrum continuously. All fit on
the analysis thread (not the audio callback). Cheap wins if needed: FFT-based YIN difference function (O(N log N),
~20× faster), smaller YIN window above E2, SIMD FFT (pffft), and caching the pre-onset spectrum continuously.

## Known limitations
* Real-audio results come from GuitarSet's mic recordings (one acoustic guitar, one room, 6 players), plus synthetic
  noise and band-pass. That's still not a phone: there's no phone AGC/noise suppression, no electric guitar, and no
  real room noise. See "GuitarSet evaluation" for the remaining failure modes.
* Onset times come out quantised to the hop (~5.8 ms) and include a fixed estimator offset (+0–6 ms in the tests).
  On device this offset gets absorbed by latency calibration.
* Chords one note apart are still confused about 20 % of the time on clean real audio (42 % before strict mode),
  30–40 % in heavy noise. The strict test assumes the expected notes are the voicing actually played; with
  lead-sheet notes it rejects more correct chords (see "GuitarSet evaluation").
* Chroma can't tell octaves or voicings apart (C3 vs C4), and it can't check *which string*. That's fine for
  verification-style scoring but not for transcription.
* YIN only handles one note at a time. On a chord it returns something arbitrary, so the CLI marks it unreliable.
  No pYIN/HMM smoothing, no bend/vibrato tracking yet.
* The single-note test only checks |cents| < 5. Synthetic tones are exact, so real-world error will be bigger.
* YIN is the O(W·τ) time-domain version (1.4 ms here); fine for now, FFT-based version listed above if needed.

## Song importer (`importer/`)
Converts tabs into the song JSON schema (ARCHITECTURE.md §6). Parsing is done by
[alphaTab](https://alphatab.net) (`@coderline/alphatab` 1.8, MPL-2.0) running in Node; `mu-import.mjs` maps its
model to our schema (tempo map → absolute seconds, string numbering 1 = high E, techniques, ties, sections).
```
cd importer && npm install
node mu-import.mjs song.gp5 -o song.json        # .gp (GP7/8), .gpx (GP6), .gp5/.gp4/.gp3, .musicxml/.xml/.mxl, .tex
node mu-import.mjs song.gp --list               # list tracks
node mu-import.mjs song.gp --track 1 -o song.json
npm test                                         # 10 assertions over the sample files
```
| Format | Reader | Tested with |
|---|---|---|
| Guitar Pro 7/8 `.gp` | alphaTab | `samples/exercise1.gp` (exported from alphaTex by `samples/make_gp7.mjs`) |
| Guitar Pro 6 `.gpx` | alphaTab | not tested (no way to author one here) |
| Guitar Pro 3/4/5 | alphaTab | `samples/exercise2.gp3/.gp4/.gp5` (written with PyGuitarPro by `samples/make_gp5.py`) |
| MusicXML (with `<technical><string>/<fret>`) | alphaTab + own hammer-on/pull-off/slide pass | `samples/exercise2.musicxml` (hand-written) |
| alphaTex `.tex` | alphaTab | `samples/exercise1.tex` |

All samples are original exercises written for the tests (no copyrighted material). Exercise 2 is the same piece in
MusicXML, GP3, GP4 and GP5, and the tests assert identical event lists; Exercise 1 covers a tempo change, sections,
named chords, strum up/down, hammer-on, bend, palm mute and a tie (the `.gp` round-trip is asserted equal to the
`.tex` source). Expected output: `samples/exercise1.json`, `samples/exercise2.json`.

Importer limitations: repeats/voltas/D.S. are **not unrolled** (events follow written order); one track per run
(first stringed track by default); grace notes are imported as normal notes at alphaTab's computed position; tied
notes extend the previous event instead of creating one; MusicXML `.mxl` (compressed) misses the hammer/pull/slide
pass; slides are recorded as a flag only (no target fret); bends record the max bend only; GPX untested; tempo
automations inside a bar are honoured but tempo ramps (gradual) are treated as steps. Regenerate samples with
`/path/to/venv/bin/python samples/make_gp5.py samples/exercise2.gp5` (needs `pip install pyguitarpro`) and
`npm run make-samples`.

## GuitarSet evaluation (`eval/`)
Evaluates pitch, onsets and chord verification on **real** guitar recordings. Data: [GuitarSet](https://zenodo.org/records/3371780)
(Xi et al., ISMIR 2018, CC BY 4.0). 360 thirty-second excerpts, 6 players × 5 styles × 3 progressions × comp/solo,
acoustic guitar, recorded with a mic (`audio_mono-mic`, 656 MB zip). JAMS annotations (39 MB) have per-string
note_midi and chord labels. 1.5 GB on disk; it lives in `data/` (gitignored), and only scripts are committed.
```
./eval/download_guitarset.sh                # -> data/guitarset/{annotation,audio_mono-mic}
python3 -m venv venv && venv/bin/pip install -r eval/requirements.txt
PYTHON=venv/bin/python ./eval/run_eval.sh   # v0 / v1 / v2 on the TEST split, all conditions -> eval/results/
venv/bin/python eval/tune_chord.py          # grid searches on the TRAIN split (players 00-03)
venv/bin/python eval/tune_strict.py collect && venv/bin/python eval/tune_strict.py fit   # strict-mode fit (TRAIN)
venv/bin/python eval/tune_pitch_onset.py pitch|onset
```
* `dsp/tools/gdsp_eval.cpp` is a batch CLI over the same library code (`O` onsets, `P t` pitch, `C t end midis…`
  chord). Any parameter can be overridden from the command line; anything not given uses the library default.
* **Split**: tuning used only players 00–03 (240 files). All numbers below are on the held-out players 04–05
  (120 files). Train noise was clean+pink20 (chords) or clean+pink10 (pitch/onsets).
* **Conditions**: clean; pink noise at 20/10 dB SNR; white noise at 15 dB; "phone" = 150–6000 Hz band-pass + pink noise at 20 dB.
* **Pitch**: isolated notes in solo takes (≥100 ms, no other note within 150 ms), YIN at the *annotated* onset
  (so pitch errors aren't mixed up with onset errors), correct if within ±50 cents of the annotated MIDI.
* **Onsets**: annotated note onsets with notes <50 ms apart merged into one event (a strum = one event), matched ±50 ms.
* **Chords**: comp takes. Each group of ≥3 strings starting within 60 ms is one chord. The expected notes are the annotated
  sounding notes, and the audio window is 60 ms–0.8 s after the chord starts, cut at the next note onset. Negatives: the same audio vs
  another chord's notes sharing ≤1 pitch class, or ≥2 pitch classes with a different chord label. "Lead-sheet":
  expected = canonical voicing of the instructed chord label (not what was played).
  One-note-apart negatives (Task 4): "near" = the audio vs a real chord from GuitarSet whose pitch-class set differs by
  1–2 classes (maj vs maj7, maj vs min, …); "add"/"drop"/"move" = the expected notes with one pitch class added,
  removed, or moved by a semitone/tone.

Full tables: [`eval/results/SUMMARY.md`](eval/results/SUMMARY.md) (raw JSON alongside). Headline, TEST split:

| | clean before → after | pink10 before → after | phone before → after |
|---|---|---|---|
| Pitch acc ±50 c | 96.0 → **96.5** | 82.5 → **92.5** | 95.9 → **96.6** |
| Onset F (P/R) | 67.2 (55/86) → **72.7** (62/88) | 27.6 (96/16) → **75.1** (87/66) | 48.5 (92/33) → **80.1** (78/82) |
| Chord AUC | 0.733 → **0.946** | 0.630 → **0.883** | 0.723 → **0.944** |
| Chord TPR / FPR at threshold | 23.8 / 0.2 → **85.8 / 10.8** | 8.7 / 0.1 → **86.9 / 29.0** | 24.2 / 0.3 → **91.0 / 18.6** |

### Task 4: chords one note apart, noise adaptation (v1 → v2, TEST split)

| condition | AUC all | AUC one-note | TPR | FPR all | FPR shared | FPR near (1–2 pcs apart) | FPR add / drop / move |
|---|---|---|---|---|---|---|---|
| clean | 0.946 → **0.963** | 0.798 → **0.886** | 85.8 → 83.8 | 10.8 → **5.4** | 19.7 → **8.8** | 41.4 → **19.7** | 77 / 71 / 32 → 28 / 49 / 12 |
| pink20 | 0.939 → **0.953** | 0.780 → **0.872** | 89.3 → 87.9 | 16.5 → **10.7** | 28.6 → **16.6** | 48.4 → **30.3** | 85 / 73 / 43 → 37 / 63 / 22 |
| pink10 | 0.883 → **0.927** | 0.668 → **0.823** | 86.9 → 89.4 | 29.0 → **22.0** | 47.9 → **30.4** | 58.5 → **42.0** | 93 / 68 / 62 → 50 / 75 / 37 |
| white15 | 0.939 → **0.955** | 0.782 → **0.874** | 88.5 → 86.9 | 15.3 → **10.3** | 26.2 → **15.9** | 45.9 → **27.9** | 84 / 73 / 41 → 34 / 62 / 21 |
| phone | 0.944 → **0.953** | 0.786 → **0.875** | 91.0 → 86.9 | 18.6 → **10.9** | 32.3 → **16.8** | 52.2 → **28.0** | 89 / 75 / 45 → 34 / 62 / 19 |

Named confusions, false matches / trials, all 5 conditions pooled (v1 → v2): D#maj played / D#maj7 expected
134/195 → **24/195**; D#maj7 / D#maj 123/175 → 93/175; C / Cmaj7 79/95 → 59/95; Cmaj7 / C 70/130 → **39/130**;
F / Fm 23/25 → **6/25**; Am / A 9/20 → **1/20**. Pitch and onset numbers are unchanged (that code didn't change).
Lead-sheet TPR drops (clean 58 → 38 %): with canonical voicings the strict test flags the voicing's notes the player
didn't play. Full tables: `eval/results/SUMMARY.md`.

Strict-mode tuning (TRAIN players 00–03, clean+pink20+pink10, `eval/tune_strict.py`): grid over the salience centres
and widths, SNR adaptation, threshold base/slope, extra-note spectrum (raw vs pre-onset difference) and generic vs
song-aware neighbours; objective = min (FPR + FPR one-note) with every condition's TPR ≥ 85 %. Findings: averaging
over unique partials beats taking the max (single partials are often noise: extra-note AUC 0.66 → 0.78); shifting the
centres with SNR helps; clamping the SNR at 30 dB keeps very clean recordings from demanding too much salience;
neither a SNR-dependent threshold slope nor song-aware neighbour lists added anything. The chosen point (clamp 30 dB, centres 6/24 dB,
threshold 0.32; TRAIN FPR 11.3 %, one-note FPR 29.4 %, TPR ≥ 85.4 % in each condition) is from the flat top of
`tune_strict.py refine` (best 10.1 / 28.3 %); it uses the mildest clamp, which keeps every synthetic check passing.

**Tuned values (TRAIN split):**
* YIN threshold 0.2, sub-octave guard 0.5 with margin 0.2, voicing bar 0.3.
* Onsets: flux lag 3, ±2-bin max filter, ≤5 kHz, 1.1×median+0.02.
* Chords: penalty k = 0.25, peak test magnitude², ≥15 % of max, ignores peaks below the bass note, no pre-diff for the general score.
* Strict (Task 4): mean salience over harmonic-unique partials; miss centre 6 dB, extra centre 24 dB, widths 4 dB, SNR
  shift 0.5 × (min(SNR, 30) − 23), extra test on the pre-onset difference (β 0.8), threshold 0.32.

**Fixed failure modes:**
* The voicing penalty was rejecting real correct chords. A ~106 Hz body/room resonance and other small peaks made
  `unexplained` ≈ 0.3 median on correct chords, and penalty 3 scored them near 0.
* YIN went unvoiced in noise (9.6 % at 10 dB → 1.5 %).
* Sub-octave errors. The guard alone caused octave-*up* errors on clean audio (2 %), fixed with the margin test.
* Onset recall collapsed in noise. Full-band flux was dominated by noise flicker; a band limit plus SuperFlux lag and max filter fixed it.
* Spurious onsets inside strums (15.8 % → 8.6 % of detections on clean).

**About the voicing penalty**: when the expected notes come from annotations of real playing, AUC peaks at a weak
penalty (k = 0.25: 0.949 vs 0.942 without it) and falls steadily for stronger ones (k = 3: 0.876 even with the
improved peak test, 0.736 with v0's). With lead-sheet expected notes, at the operating threshold the v0 setting
accepted only 5.9 % of correct chords, against 58 % now. Exact-voicing checks only make sense when the song file gives
the exact fingering *and* the player follows it, and even then real-guitar resonances call for a weak penalty.

**Remaining failure modes:**
* Chords one note apart, after strict mode: ~20 % of "near" wrong chords still match on clean audio, 28–30 % at pink 20
  / phone, 42 % at pink 10 dB. Worst cases: an *added 7th that is played but not expected* (D#maj7 played vs D#maj
  expected 53 %, C vs Cmaj7 62 %): the major 7th sits a semitone under the root and its partials collide with the
  root's and fifth's harmonics, so few harmonic-unique partials remain. *Dropped* notes are the weakest kind
  (49 % of "expected note missing" negatives still match on clean): one string out of 5–6 whose pitch class is
  doubled elsewhere is a few dB of evidence. Some "near" pairs have the same label (F played, F expected) but a
  different annotated voicing, i.e. partial strums; these are genuinely hard and arguably not wrong chords.
* TPR costs a little: clean 85.8 → 83.8 %, phone 91.0 → 86.9 % (pink 10 rises 86.9 → 89.4 %). The threshold
  (`baseThreshold`) trades these directly; 0.32 was picked on TRAIN for TPR ≥ 85 % in every training condition.
* Noise: the SNR estimate is per segment (a dense chord raises its own "floor"), not a calibrated session noise
  floor. A calibration-window estimate could replace it, but the per-segment one already removes most of the need
  for a noise-dependent threshold (FPR all: 5 % clean → 22 % pink 10 dB, against 11 → 29 % before).
* Strict mode needs the voicing actually played. For songs that only give chord names, use the general score
  (`strict = false`) or a lenient missing-note test; lead-sheet TPR falls from 58 to 38 % on clean with strict on.
* Onset precision on clean audio is only 62 %. There are extra detections on ringing/decaying notes, and
  legato notes (hammer-ons, slides) have no attack and get missed. Solo F is 68.5 against comp F 76.5.
* Pitch: ~3.5 % errors on clean audio, about a quarter of them octave errors. Accuracy is lowest on the high e string
  (94.6 %). I haven't inspected the rest; bends/slides right after the onset and annotation offsets are the likely
  causes.
* GuitarSet is one acoustic guitar in one room. Results on a phone mic with AGC, an electric guitar, or real room
  noise will differ. IDMT-SMT-Guitar / NSynth weren't used (GuitarSet covered the needs; they could add electric and
  isolated-note coverage).
