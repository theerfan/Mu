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
* **Onsets**: spectral flux on log-compressed (log1p(100|X|)) Hann-windowed magnitudes, ~23 ms frame, hop = frame/4,
  half-wave-rectified, threshold = 1.5×median(last 16 frames)+const, local-max peak picking, 60 ms min gap,
  RMS silence gate and 100 ms warm-up. Streaming API (`processHop`), so it's the same code as runs on device.
* **Pitch**: YIN (difference fn → cumulative-mean normalisation → absolute threshold 0.15 → parabolic interpolation),
  70–1400 Hz, ~45 ms window; the CLI takes the median of 3 frames starting 40 ms after onset (skipping the pick transient).
* **Chord verification** (`ChordVerifier`): FFT (≤32768 pts, preallocated) of the 60–800 ms after the onset →
  peak-picked spectrum (70–2000 Hz) → 12-bin chroma (sqrt magnitude, L2 norm). The expected template puts each
  expected note's harmonics 1–8 (minus 7th) on their pitch classes with weight 1/√h. Then:
  * `cosine` = cos(chroma, template); `coverage` = fraction of expected pitch classes with ≥25 % of max chroma.
  * **Unexplained-peak penalty**: because the score says exactly which notes (MIDI, not just pitch class) should
    sound, every clear spectral peak ≤1.2 kHz (≥5 % of max) should be within (30 + 3h) cents of harmonic h ≤ 12 of
    some expected note. `unexplained` = sqrt-magnitude share of peaks that fit none.
  * **Onset-local spectral difference**: the spectrum of 300 ms ending 20 ms before the onset (the still-ringing
    previous chord) is subtracted, ×0.8, from the post-onset spectrum *for the unexplained test only*. Chroma/
    coverage use the raw spectrum, so notes shared with the previous chord (which get re-plucked) still count.
  * `score = cosine × coverage × max(0, 1 − 3·unexplained)`; MATCH if score ≥ 0.6.
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
(previous chord's ringing notes are counted as unexplained), so both parts are needed.

## Benchmark (`dsp/build/bench 48000`, this box: Intel Xeon VM, -O3 default Release, single thread)
```
sr=48000 onsetFrame=2048 hop=512 (10.67 ms) yinWindow=4096 chordSegment=35520
FFT (onset frame size)             mean    20.47 us  p50    19.76  p99    38.05  | budget   10667 us ->  0.19% | allocs/call 0
SpectrumAnalyzer (onset frame)     mean    26.09 us  p50    24.41  p99    46.52  | budget   10667 us ->  0.24% | allocs/call 0
OnsetDetector::processHop          mean    38.93 us  p50    38.30  p99    48.14  | budget   10667 us ->  0.36% | allocs/call 0
YinPitch::process (70 Hz min)      mean  1369.98 us  p50  1317.63  p99  1821.38  | budget   10667 us -> 12.84% | allocs/call 0
ChordVerifier::verify              mean   673.61 us  p50   668.59  p99   868.46  | budget   10667 us ->  6.32% | allocs/call 0
ChordVerifier::verify (+pre diff)  mean  2124.88 us  p50  1937.09  p99  3616.24  | budget   10667 us -> 19.92% | allocs/call 0
budget = one hop of real time. YIN runs ~3x per onset and verify once per expected chord,
so they may be scheduled across hops on the analysis thread rather than per hop.
```
Phone estimate (mid-range ARM, ~3–5× slower): onset hop ≈ 0.12–0.2 ms per 10.7 ms hop (1–2 %); YIN ≈ 4–7 ms
per call (×3 per onset); chord verify ≈ 2–3.5 ms, with pre-diff ≈ 6–11 ms, once per expected chord. All fit on
the analysis thread (not the audio callback). Cheap wins if needed: FFT-based YIN difference function (O(N log N),
~20× faster), smaller YIN window above E2, SIMD FFT (pffft), and caching the pre-onset spectrum continuously.

## Known limitations
* Only tested on synthetic Karplus-Strong audio with white noise. Real guitars (inharmonicity, body resonance,
  fret buzz, room reverb, phone mic AGC) haven't been tested yet; next step is a set of real recordings.
* Onset times come out quantised to the hop (~5.8 ms) and include a fixed estimator offset (+0–6 ms in the tests).
  On device this offset gets absorbed by latency calibration.
* Chord margins are now ~0.35 on synthetic audio (lowest correct 0.752, highest wrong 0.405), but the
  unexplained-peak test assumes the player uses the *voicing in the score*: an alternative voicing of the
  right chord (e.g. barre C instead of open C) adds "unexplained" peaks and will be penalised. Real-string
  inharmonicity is only covered by the widening tolerance; thresholds must be re-tuned on real recordings.
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
