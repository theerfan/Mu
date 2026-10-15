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
dsp/test/run_tests.sh     builds, synthesises, asserts results
dsp/test/results.txt      latest test output
```

## Build & run
```
cd dsp && cmake -S . -B build && cmake --build build
./build/gtcli song.wav                         # onsets + note name + cents
./build/gtcli strum.wav --expect C3,E3,G3,C4,E4 # chord verification (names or MIDI numbers)
./build/gtcli song.wav --json                  # machine-readable output
./test/run_tests.sh                            # full test
```

## How it works
* **Onsets**: spectral flux on log-compressed (log1p(100|X|)) Hann-windowed magnitudes, ~23 ms frame, hop = frame/4,
  half-wave-rectified, threshold = 1.5×median(last 16 frames)+const, local-max peak picking, 60 ms min gap,
  RMS silence gate and 100 ms warm-up. Streaming API (`processHop`), so it's the same code as runs on device.
* **Pitch**: YIN (difference fn → cumulative-mean normalisation → absolute threshold 0.15 → parabolic interpolation),
  70–1400 Hz, ~45 ms window; the CLI takes the median of 3 frames starting 40 ms after onset (skipping the pick transient).
* **Chord verification**: FFT (≤32768 pts) of the 60–800 ms after the onset → peak-picked spectrum (70–2000 Hz) →
  12-bin chroma (sqrt magnitude, L2 norm). The expected template puts each expected note's harmonics 1–8 (minus 7th)
  on their pitch classes with weight 1/√h. `score = cosine(chroma, template) × coverage`, where coverage = fraction
  of expected pitch classes with ≥25 % of the max chroma. MATCH if score ≥ 0.75. Purity (energy share inside the
  expected pitch classes) is reported as extra diagnostics.
* Frame sizes scale with sample rate; stereo gets downmixed to mono.

## Test results (`dsp/test/run_tests.sh`)
Test audio: Karplus-Strong plucks (allpass fractional delay, so tuning is exact) + white noise at 25 dB SNR.
Singles at 44.1/48/22.05 kHz; a 4-note sequence; open-position C major (C3 E3 G3 C4 E4) and G major
(G2 B2 D3 G3 B3 G4) strummed with 12 ms between strings; a stereo file with C followed by G (C still ringing).
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
PASS  Cmaj audio vs expected Cmaj score=0.876 expected match
PASS  Cmaj audio vs expected Gmaj score=0.638 expected nomatch
PASS  Cmaj audio vs expected Am   score=0.476 expected nomatch
PASS  Gmaj audio vs expected Gmaj score=0.864 expected match
PASS  Gmaj audio vs expected Cmaj score=0.164 expected nomatch
PASS  stereo seq chord#1 vs Cmaj  score=0.902 expected match
PASS  stereo seq chord#2 vs Gmaj  score=0.795 expected match
PASS  stereo seq chord#2 vs Cmaj  score=0.564 expected nomatch
== 21 passed, 0 failed
```
Sequence output:
```
file=dsp/test/wavs/sequence_E2_A2_G3_E4.wav rate=44100 dur=3.90s onsets=4
onset t=  0.302s  f0=   82.40Hz  note=E2   cents=  -0.0 conf=0.99
onset t=  1.103s  f0=  109.99Hz  note=A2   cents=  -0.2 conf=0.81
onset t=  1.904s  f0=  196.06Hz  note=G3   cents=  +0.6 conf=0.85
onset t=  2.699s  f0=  329.65Hz  note=E4   cents=  +0.1 conf=0.92
```
Stereo chord file vs expected G major (first chord is C → rejected, second is G → accepted):
```
file=dsp/test/wavs/chords_C_then_G_stereo.wav rate=44100 dur=4.86s onsets=2
onset t=  0.302s  (unpitched/polyphonic)  chord_score=0.664 coverage=1.00 purity=0.44 NO-MATCH
onset t=  2.305s  (unpitched/polyphonic)  chord_score=0.795 coverage=1.00 purity=0.50 MATCH
```

## Known limitations
* Only tested on synthetic Karplus-Strong audio with white noise. Real guitars (inharmonicity, body resonance,
  fret buzz, room reverb, phone mic AGC) haven't been tested yet; next step is a set of real recordings.
* Onset times come out quantised to the hop (~5.8 ms) and include a fixed estimator offset (+0–6 ms in the tests).
  On device this offset gets absorbed by latency calibration.
* The chord margin is thin when chords share notes: C-audio vs G-expected gets 0.64 (G is common to both),
  against a 0.75 threshold. A whole-chord C vs G (score 0.80) right after a ringing C is also close. Should improve
  with per-note harmonic-sum salience checks, onset-aligned spectral differencing (subtracting the previous
  chord's ringing energy), and a threshold tuned per difficulty.
* Chroma can't tell octaves or voicings apart (C3 vs C4), and it can't check *which string*. That's fine for
  verification-style scoring but not for transcription.
* YIN only handles one note at a time. On a chord it returns something arbitrary, so the CLI marks it unreliable.
  No pYIN/HMM smoothing, no bend/vibrato tracking yet.
* The single-note test only checks |cents| < 5. Synthetic tones are exact, so real-world error will be bigger.
* The CLI allocates per call in `magSpectrum`/`verifyChord`. For the real-time path, preallocate (or swap in
  pffft/KissFFT) before putting it on the audio thread.
