# Mu for Android (debug MVP)

Kotlin + Jetpack Compose UI, NDK/CMake native audio on Oboe, and the shared C++ core (`../dsp` + `../core`).
minSdk 26 (Android 8.0), target/compile 35, ABIs arm64-v8a + x86_64.

## Build
```
# JDK 17+ and an Android SDK with: platforms;android-35 build-tools;35.0.0 ndk;27.2.12479018 cmake;3.22.1
ANDROID_HOME=/path/to/sdk ./build_apk.sh      # JVM unit tests + debug APK -> ../dist/mu-debug.apk
```
CI (`ci/android.yml`, to be moved to `.github/workflows/` — the box's GitHub token lacks the `workflow` scope) runs the native tests and the same Gradle build on every push, and uploads
the APK as the `mu-debug-apk` artifact. Each machine signs with its own debug key, so uninstall first when switching
between a CI APK and a locally built one.

## Install (sideload)
* USB: enable Developer options → USB debugging, then `adb install -r dist/mu-debug.apk`.
* Without a computer: copy `mu-debug.apk` to the phone (Drive, USB, …), open it from Files, and allow
  "Install unknown apps" for that app when Android asks.
* On first launch, grant microphone access.

## First run
1. **Calibrate** (top bar) once per output: phone speaker, wired, USB, Bluetooth are stored separately.
   * *Loopback* (automatic): speaker on, quiet room; 12 clicks are played and detected through the mic.
   * *Tap-along* (manual): mute the strings and strum on each click (4 count-in + 16). Includes your feel and the
     detector's bias, so it's what scoring uses when present. Headphones recommended.
2. **Tuner**: play one string at a time; green = within ±5 cents.
3. Pick a song, set the tempo (50–125 %), toggle the metronome, **Start**.

## Songs
Bundled (all self-authored): *Mu Chord Etude* (open strings, open chords incl. C/Cmaj7, mixed bars; written by
`importer/samples/make_chord_etude.py`), *Mu Exercise 1* and *2* (the importer test pieces).

Your own songs: convert Guitar Pro (.gp3–.gp7/.gpx), MusicXML or alphaTex on a computer with the importer, then
get the JSON onto the phone:
```
cd importer && npm install
node mu-import.mjs song.gp5 --list          # list tracks
node mu-import.mjs song.gp5 --track 1 -o song.json
```
On the phone either tap **Import song** (file picker), or *Share* / *Open with* the JSON from any app (Files,
Drive, a chat app) and pick Mu. Imported songs live in app storage; delete them with the bin icon.

## Play screen
A tab-style highway: one lane per string (high e on top), fret numbers on the notes, chord names above, beat/bar
lines, and the timing line at the left. Notes turn green (perfect ±40 ms), light green (good ±80 ms), yellow
(ok ±130 ms), orange (octave off), red (wrong) or grey (missed). The big line above the highway gives the latest
result: "Late 52 ms", "Wrong note: D3 (want A2)", "Wrong chord: missing B, extra C#". Results at the end: score,
accuracy, grade counts, best streak, chord hits, and your average timing offset.

## How it works
```
Oboe output callback (audio thread, drives the clock)
  ├─ reads the mic stream non-blocking (same callback, full duplex) → SPSC float ring (128 k samples)
  └─ synthesises metronome/count-in clicks from a double-buffered schedule (output frame numbers)
Analysis thread (std::thread)
  ├─ maps input frames → output-timeline frames (AAudio timestamps of both streams; fallback: callback pairing)
  ├─ song time = (output frame − song start frame)/sr − calibrated offset
  └─ mode: Tuner (YIN) | Calibrate (onset collector) | Play (mucore::PlayAnalyzer) → SPSC judgement queue
UI (Compose, every frame): song time from the output timestamp, polls judgements over JNI
```
* Audio callback: no allocation, locks, logging or JNI; only ring writes, atomics and click synthesis.
  All analyzers preallocate; `core/test/test_core.cpp` checks `PlayAnalyzer::process` makes 0 allocations.
* Input: `UNPROCESSED` preset if the device reports support, else `VOICE_RECOGNITION`; any AGC / noise suppressor /
  echo canceller on the input session is disabled. Output: low-latency, exclusive, mono, float.
* Scoring (`core/src/practice.cpp`): onset → nearest unmatched expected event within ±130 ms. Single notes: YIN
  (median of 3 frames, 40 ms after the attack): exact = hit, ±12/24 = octave (half points), else wrong. Chords:
  `gdsp::ChordVerifier` strict mode on 60 ms–0.8 s after the onset with the pre-onset spectrum subtracted; its
  weakest expected pitch class and strongest extra pitch class become "missing"/"extra" feedback. Events with no
  onset become misses ~1 s later; onsets near no event count as extras (no penalty).
* The C++ core (`core/`, `dsp/`) has no Android dependencies, so an iOS shell only needs its own audio I/O and UI.

## Tests
* `core/`: `cmake -S core -B core/build && cmake --build core/build && core/build/mucore_tests` — SPSC queue
  across threads, calibration math, tuner, and an end-to-end play-along on synthetic plucks/strums at 44.1 and
  48 kHz (hit, late, early, wrong note, miss, octave, C vs Cmaj7 both ways with missing/extra B), 0 allocations.
* `android/`: `./gradlew testDebugUnitTest` — song parsing/validation of all bundled songs, tempo map → beats,
  clicks and tempo scaling, judgement unpacking, summary/streak/score maths, feedback texts, tuner maths.
* `dsp/test/run_tests.sh` — DSP synthetic tests.

## Known gaps
* Not yet run on a real phone or emulator (the box's emulator didn't boot), so the Oboe/timestamp path, effect
  disabling, and the UI are only compile-checked. Expect device-specific fixes (input preset fallback, buffer sizes).
* No backing-track playback yet (output is clicks only); no section looping, auto slow-down or saved history.
* With the phone speaker, the mic hears the metronome; clicks can register as extra/wrong onsets. Use headphones or
  turn the in-song metronome off. Same for tap-along calibration.
* The highway is Compose Canvas (fine at 60 fps for these songs); a GPU renderer would be needed only for very
  dense songs.
* Techniques (bends, slides, hammer-ons) are shown nowhere and scored like plain notes; legato notes without an
  attack are often missed by the onset detector.
* Chord scoring needs the exact voicing from the tab (strict mode); songs with chord names only would need
  `strict = false`.
