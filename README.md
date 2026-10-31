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
./build/gtcli strum.wav --expect C3,E3,G3,C4,E4 # chord verification (names or MIDI numbers)
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
  * **Onset-local spectral difference** (library default now off, `preSubtract = 0`; `gtcli` keeps it at 0.8 unless
    `--no-diff`): the spectrum of 300 ms ending 20 ms before the onset (the still-ringing previous chord) is
    subtracted, ×β, from the post-onset spectrum *for the unexplained test only*. Chroma/
    coverage use the raw spectrum, so notes shared with the previous chord (which get re-plucked) still count.
  * `score = cosine × coverage × max(0, 1 − 0.25·unexplained)`; MATCH if score ≥ 0.58 (`kChordMatchThreshold`).
    v0 used penalty 3 and threshold 0.6; on real guitar audio that rejected ~75 % of correct chords (see below).
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
the first (first still ringing); a stereo C→G file.
```
PASS  single E2 @44100Hz -> E2 -0.0c onset 0.250s (truth 0.250s, onsets=1)
PASS  single A2 @44100Hz -> A2 -0.1c onset 0.250s (truth 0.250s, onsets=1)
PASS  single G3 @44100Hz -> G3 -0.0c onset 0.250s (truth 0.250s, onsets=1)
PASS  single E4 @44100Hz -> E4 -0.1c onset 0.250s (truth 0.250s, onsets=1)
PASS  single E2 @48000Hz -> E2 +0.0c onset 0.256s (truth 0.250s, onsets=1)
PASS  single A2 @48000Hz -> A2 -0.0c onset 0.256s (truth 0.250s, onsets=1)
PASS  single G3 @48000Hz -> G3 -0.0c onset 0.256s (truth 0.250s, onsets=1)
PASS  single E4 @48000Hz -> E4 -0.0c onset 0.256s (truth 0.250s, onsets=1)
PASS  single E2 @22050Hz -> E2 +0.0c onset 0.250s (truth 0.250s, onsets=1)
PASS  single A2 @22050Hz -> A2 +0.0c onset 0.250s (truth 0.250s, onsets=1)
PASS  single G3 @22050Hz -> G3 +0.0c onset 0.250s (truth 0.250s, onsets=1)
PASS  single E4 @22050Hz -> E4 -0.1c onset 0.250s (truth 0.250s, onsets=1)
PASS  sequence E2 A2 G3 E4 (truth onsets .3 1.1 1.9 2.7) -> E2 A2 G3 E4 
PASS  Cmaj audio vs expected Cmaj score=0.879 expected match
PASS  Cmaj audio vs expected Gmaj score=0.000 expected nomatch
PASS  Cmaj audio vs expected Am   score=0.101 expected nomatch
PASS  Gmaj audio vs expected Gmaj score=0.873 expected match
PASS  Gmaj audio vs expected Cmaj score=0.000 expected nomatch
PASS  stereo seq chord#1 vs Cmaj  score=0.927 expected match
PASS  stereo seq chord#2 vs Gmaj  score=0.809 expected match
PASS  stereo seq chord#2 vs Cmaj  score=0.000 expected nomatch
PASS  Cmaj audio vs expected Em (shares E,G) score=0.405 expected nomatch
PASS  Am audio vs expected Am   score=0.882 expected match
PASS  Am audio vs expected Cmaj (shares C,E) score=0.000 expected nomatch
PASS  Em audio vs expected Em   score=0.770 expected match
PASS  Em audio vs expected Gmaj (shares G,B) score=0.223 expected nomatch
PASS  Am after C (prev ringing) vs Am score=0.811 expected match
PASS  Am after C (prev ringing) vs C score=0.000 expected nomatch
PASS  C after Am (prev ringing) vs C score=0.858 expected match
PASS  C after Am (prev ringing) vs Am score=0.131 expected nomatch
PASS  Em after G (prev ringing) vs Em score=0.752 expected match
PASS  Em after G (prev ringing) vs G score=0.000 expected nomatch
PASS  G after Em (prev ringing) vs G score=0.783 expected match
PASS  G after Em (prev ringing) vs Em score=0.248 expected nomatch
PASS  G after C (prev ringing) vs G score=0.833 expected match
PASS  G after C (prev ringing) vs C score=0.000 expected nomatch
== 36 passed, 0 failed
```

Since the GuitarSet retune, 8 synthetic checks are expected failures (`XFAIL`): wrong chords that share notes
with the played chord are no longer rejected on this perfectly harmonic synthetic audio, and one 22.05 kHz note gets
a second onset in its tail. That's the trade-off the real-data tuning picked (see "GuitarSet evaluation").

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

Current defaults (GuitarSet-tuned penalty 0.25, threshold 0.58). Correct chords still score 0.81–0.88, but
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

## Benchmark (`dsp/build/bench 48000`, this box: Intel Xeon VM, -O3 default Release, single thread)
```
sr=48000 onsetFrame=2048 hop=512 (10.67 ms) yinWindow=4096 chordSegment=35520
FFT (onset frame size)             mean    19.57 us  p50    19.20  p99    31.73  | budget   10667 us ->  0.18% | allocs/call 0
SpectrumAnalyzer (onset frame)     mean    25.94 us  p50    23.09  p99    54.09  | budget   10667 us ->  0.24% | allocs/call 0
OnsetDetector::processHop          mean    30.50 us  p50    28.89  p99    51.29  | budget   10667 us ->  0.29% | allocs/call 0
YinPitch::process (70 Hz min)      mean  1232.28 us  p50  1227.26  p99  1414.92  | budget   10667 us -> 11.55% | allocs/call 0
ChordVerifier::verify              mean   619.04 us  p50   617.77  p99   739.94  | budget   10667 us ->  5.80% | allocs/call 0
ChordVerifier::verify (+pre diff)  mean   629.02 us  p50   624.00  p99   740.61  | budget   10667 us ->  5.90% | allocs/call 0
budget = one hop of real time. YIN runs ~3x per onset and verify once per expected chord,
so they may be scheduled across hops on the analysis thread rather than per hop.
```
Phone estimate (mid-range ARM, ~3–5× slower): onset hop ≈ 0.12–0.2 ms per 10.7 ms hop (1–2 %); YIN ≈ 4–7 ms
per call (×3 per onset); chord verify ≈ 2–3.5 ms (pre-diff is off by default now; with it ≈ 6–11 ms), once per
expected chord. All fit on
the analysis thread (not the audio callback). Cheap wins if needed: FFT-based YIN difference function (O(N log N),
~20× faster), smaller YIN window above E2, SIMD FFT (pffft), and caching the pre-onset spectrum continuously.

## Known limitations
* Real-audio results come from GuitarSet's mic recordings (one acoustic guitar, one room, 6 players), plus synthetic
  noise and band-pass. That's still not a phone: there's no phone AGC/noise suppression, no electric guitar, and no
  real room noise. See "GuitarSet evaluation" for the remaining failure modes.
* Onset times come out quantised to the hop (~5.8 ms) and include a fixed estimator offset (+0–6 ms in the tests).
  On device this offset gets absorbed by latency calibration.
* Chord verification can't reliably reject a wrong chord that differs by one note (maj vs maj7, C vs Am): about 20 %
  of shared-note negatives pass on clean real audio. The unexplained-peak penalty is kept weak (0.25) because
  stronger values cost far more true chords than they save, especially when the expected notes come from a lead
  sheet rather than the exact voicing.
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
PYTHON=venv/bin/python ./eval/run_eval.sh   # before/after on the TEST split, all conditions -> eval/results/
venv/bin/python eval/tune_chord.py          # grid searches on the TRAIN split (players 00-03)
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

Full tables: [`eval/results/SUMMARY.md`](eval/results/SUMMARY.md) (raw JSON alongside). Headline, TEST split:

| | clean before → after | pink10 before → after | phone before → after |
|---|---|---|---|
| Pitch acc ±50 c | 96.0 → **96.5** | 82.5 → **92.5** | 95.9 → **96.6** |
| Onset F (P/R) | 67.2 (55/86) → **72.7** (62/88) | 27.6 (96/16) → **75.1** (87/66) | 48.5 (92/33) → **80.1** (78/82) |
| Chord AUC | 0.733 → **0.946** | 0.630 → **0.883** | 0.723 → **0.944** |
| Chord TPR / FPR at threshold | 23.8 / 0.2 → **85.8 / 10.8** | 8.7 / 0.1 → **86.9 / 29.0** | 24.2 / 0.3 → **91.0 / 18.6** |

**Tuned values (TRAIN split):**
* YIN threshold 0.2, sub-octave guard 0.5 with margin 0.2, voicing bar 0.3.
* Onsets: flux lag 3, ±2-bin max filter, ≤5 kHz, 1.1×median+0.02.
* Chords: penalty k = 0.25, peak test magnitude², ≥15 % of max, ignores peaks below the bass note, no pre-diff, threshold 0.58.

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
* Chords differing by one note get confused, mostly maj ↔ maj7 (e.g. D# vs D#maj7 10/17 false matches) and
  C ↔ Cmaj7. About 20 % of shared-note negatives pass on clean audio, 48 % at 10 dB SNR. A single fixed threshold is
  also noise-dependent: FPR rises from 11 % clean to 29 % at pink 10 dB, so a per-session noise-floor estimate
  (from calibration) should adjust it.
* Onset precision on clean audio is only 62 %. There are extra detections on ringing/decaying notes, and
  legato notes (hammer-ons, slides) have no attack and get missed. Solo F is 68.5 against comp F 76.5.
* Pitch: ~3.5 % errors on clean audio, about a quarter of them octave errors. Accuracy is lowest on the high e string
  (94.6 %). I haven't inspected the rest; bends/slides right after the onset and annotation offsets are the likely
  causes.
* GuitarSet is one acoustic guitar in one room. Results on a phone mic with AGC, an electric guitar, or real room
  noise will differ. IDMT-SMT-Guitar / NSynth weren't used (GuitarSet covered the needs; they could add electric and
  isolated-note coverage).
