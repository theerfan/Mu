# Architecture — personal guitar practice app (Android first, iOS later)

## 1. Modules
```
core/ (C++17, shared, no platform deps)
  dsp/        FFT, YIN/MPM pitch, spectral-flux onsets, chroma + chord verifier   (PoC: dsp/)
  engine/     AudioEngine interface, ring buffers, clock mapping, analysis worker
  score/      expected-event timeline, matcher, tolerance windows, stats
  song/       internal song model + JSON (de)serialiser
  calib/      latency calibration (loopback + tap)
platform/
  android/    Oboe stream (AAudio, input preset VoiceRecognition→Unprocessed if supported), JNI bridge
  ios/        AVAudioEngine / RemoteIO (measurement mode), Obj-C++ bridge          (later)
app/
  android/    Kotlin + Jetpack Compose UI: song list, scrolling tab/highway, results
  import/     Guitar Pro / MusicXML → song JSON (alphaTab offline in a tool/WebView, or desktop script)
```

## 2. Data flow
```
Mic → Oboe input callback (audio thread) ──lock-free SPSC ring (float, + frame position)──► Analysis thread
                                                                                              │ OnsetDetector (hop 256@48k)
Backing track/metronome ◄── Oboe output callback (same clock) ◄── mixer                      │ YIN on onset+40ms
                                                                                              │ verifyChord on expected chords
                                                                         DetectedEvent{t_audio, midi, cents, chordScore}
                                                                                              ▼
                                         Scorer (expected timeline from song JSON, in audio-clock seconds)
                                                                                              ▼
                                         SPSC event queue → UI thread (Compose, 60/120 fps) renders hits/misses
```

## 3. Threading
* **Audio callback threads** (Oboe input + output, full-duplex): no allocation, locks, logging or JNI calls.
  They only copy samples into or out of preallocated rings and read atomics (play state, transport position).
* **Analysis thread** (normal priority, no `SCHED_FIFO` needed): pulls hops from the ring and runs the DSP.
  Budget: <2 ms per 256-sample hop. Expected cost (not yet measured on device): FFT1024 + YIN should be well under 1 ms on mid-range ARM.
* **UI thread**: polls the event queue each frame (Choreographer), interpolates the transport position from the
  last `(framePosition, systemNanos)` timestamp pair so scrolling stays smooth.
* All queues are SPSC lock-free with fixed capacity, and they drop on overflow (counted).

## 4. Timing & latency calibration
* **One clock**: everything is in *output frame position* of the playback stream. Song time t ↔ frame `t*sr`.
  Input sample index i maps to the output timeline through `AAudioStream_getTimestamp` (both streams, CLOCK_MONOTONIC):
  `t_played(i) = (inTimeNs(i) − outTimeNs(0)) / 1e9 − L_user`.
* **Round-trip latency** L_rt = output latency + input latency (from timestamps) + device-specific residual.
* **Calibration A (loopback, automatic)**: play 10 clicks at known output frames, detect them in the mic with the
  same onset detector, residual = median(detected − emitted) after timestamp correction. Takes about 5 s. Needs
  speaker output (not BT).
* **Calibration B (tap/strum, user)**: play a metronome, user plucks muted strings on 16 beats. Offset = median
  error with outliers rejected (MAD). This also absorbs the onset estimator's bias and the user's perception. It's
  the one that matters for scoring.
* Store per (device route: speaker/wired/USB/BT) offsets. Bluetooth output: warn the user and require calibration B
  (latency is 150–300 ms and varies).

## 5. Scoring algorithm
Expected events E_j = {t_j, string, fret, midi(s), dur, technique}; detected events D_k = {t_k, midi, cents, chord}.
1. **Windowing**: for each E_j, candidates are D_k with |t_k − t_j| ≤ W_late/early (defaults: perfect ±40 ms,
   good ±80 ms, ok ±130 ms; scale with difficulty and tempo).
2. **Pitch check**:
   * single note: detected midi == expected (allow ±1 octave as "octave error, partial credit"), |cents| ≤ 35 counts as
     in-tune. Re-evaluate pitch over 3 frames, since the attack can read sharp.
   * chord: `verifyChord(segment, expected midis)`, score ≥ τ (0.75 by default) counts as a hit, and scores in
     0.5–τ get "partial". This is verification against the score, not transcription.
   * Sustains: re-check pitch/chroma at 50 % of duration. Bends: track f0 over time, compare with the target interval.
3. **Assignment**: greedy in time order (or Hungarian over a small window) so a detection counts for at most one
   expected note. A detection with no expected note counts as an "extra" (feedback only, no penalty by default).
4. **Points**: hit = timing grade (100/70/40) × pitch factor (1, 0.5 partial). Streak multiplier. Per-section accuracy
   feeds the practice loop (auto slow-down / loop sections under 80 %).
5. **Pre-gating to avoid false hits**: only evaluate pitch on onsets near an expected event. When nothing is expected,
   no work is done, which saves CPU and cuts false positives from noise and backing-track bleed. Use headphones,
   or later add an echo canceller / reference subtraction for backing-track bleed into the mic.

## 6. Song JSON schema (internal, v1)
```json
{
  "schema": 1,
  "title": "Example", "artist": "...", "source": "gp5|musicxml|manual",
  "tuning": [40, 45, 50, 55, 59, 64],          // MIDI, string 6 (low E) .. string 1
  "capo": 0,
  "tempoMap": [{"beat": 0, "bpm": 92}],          // beats → seconds; events also carry absolute time
  "timeSig": [{"bar": 0, "num": 4, "den": 4}],
  "backing": {"file": "example.ogg", "offsetSec": 0.0},
  "sections": [{"name": "Intro", "start": 0.0, "end": 10.4}],
  "events": [
    {"t": 1.304, "dur": 0.326, "notes": [{"string": 6, "fret": 0, "midi": 40}], "tech": []},
    {"t": 2.609, "dur": 0.652, "chord": "C",
     "notes": [{"string":5,"fret":3,"midi":48},{"string":4,"fret":2,"midi":52},{"string":3,"fret":0,"midi":55},
               {"string":2,"fret":1,"midi":60},{"string":1,"fret":0,"midi":64}],
     "tech": ["strum_down"]},
    {"t": 3.913, "dur": 0.326, "notes": [{"string": 3, "fret": 7, "midi": 62}],
     "tech": [{"type": "bend", "semitones": 1.0}]}
  ]
}
```
`tech` values: `hammer`, `pull`, `slide`, `bend{semitones}`, `vibrato`, `palm_mute`, `harmonic`, `dead`,
`strum_down/up`. `midi` gets precomputed from tuning+capo+fret, so the scorer never re-derives it.
**Import**: run alphaTab (JS) in a Node script or headless WebView to parse .gp3–.gp7/.gpx/MusicXML →
walk tracks/bars/beats/notes → emit the JSON above with absolute seconds from the tempo map. For personal use an
offline desktop/Node converter is fine, and you'd only push JSON + audio to the phone.

## 7. Android / iOS portability
* Everything in `core/` is plain C++17 with no allocation on the audio thread and no platform headers. It's built
  as a static lib with CMake for both platforms (Android NDK via Gradle `externalNativeBuild`; iOS via CMake
  Xcode generator or an XCFramework).
* `AudioEngine` interface: `start(cfg)`, `stop()`, `framePosition()`, `timestamp()`, plus callbacks into core.
  Android = Oboe (AAudio, LowLatency, Exclusive, input preset Unprocessed if
  `AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`, else VoiceRecognition; both skip AGC/NS).
  iOS = AVAudioSession `.playAndRecord` + mode `.measurement` (turns off voice processing), RemoteIO,
  timestamps from `AudioTimeStamp.mHostTime`.
* UI is per platform (Compose / SwiftUI). If you want a shared UI later, Kotlin Multiplatform + Compose MP is an
  option. Keep the UI↔core boundary to a small C API (events in, events out) so the bridges stay thin.
* Sample rate: run at the device native rate (usually 48 k). The DSP takes sr as a parameter (already the case in
  the PoC), so it needs no resampling.

## 8. Milestones
1. **M0 – DSP PoC (done)**: YIN, spectral flux, chroma verifier, WAV CLI, synthetic tests.
2. **M1 – Real-audio validation** (1–2 wk): record 30+ real guitar clips with your phone (single notes per string,
   10 common chords, wrong-chord negatives). Tune thresholds and add MPM as an alternative for the low E and
   noisy input. Make the test-suite fixtures include the real clips.
3. **M2 – Android audio shell** (1–2 wk): Oboe full-duplex, ring buffer, live tuner screen (pitch + cents) to
   prove the latency/CPU budget on your device.
4. **M3 – Calibration + metronome** (1 wk): loopback + tap calibration, per-route storage, timing-only exercise
   ("strum on the beat") with tolerance-window scoring.
5. **M4 – Song model + importer** (1–2 wk): JSON schema, alphaTab-based GP/MusicXML converter, tab/highway renderer.
6. **M5 – Play-along scoring** (2 wk): expected-event gating, single-note + chord scoring, results screen,
   section looping, tempo slow-down (time-stretch backing via Oboe resampler / SoundTouch).
7. **M6 – Polish**: techniques (bends, slides, hammer-ons via pitch-track shape), practice stats, BT latency warnings.
8. **M7 – iOS port**: AVAudioEngine backend + SwiftUI shell around the same core.
