# Mu for Android (debug MVP)

Kotlin + Jetpack Compose UI, NDK/CMake native audio on Oboe, and the shared C++ core (`../dsp` + `../core`).
minSdk 26 (Android 8.0), target/compile 35, ABIs arm64-v8a + x86_64.

## Build
```
# JDK 17+ and an Android SDK with: platforms;android-35 build-tools;35.0.0 ndk;27.2.12479018 cmake;3.22.1
ANDROID_HOME=/path/to/sdk ./build_apk.sh      # JVM unit tests + debug APK -> ../dist/mu-debug.apk
```
CI (`.github/workflows/android.yml`) runs the native tests and the same Gradle build on every push. Every push to
`main` replaces the rolling **`android`** release (tag moved to that commit, version `0.2.<run number>`), so
<https://github.com/theerfan/Mu/releases/latest/download/mu.apk> and the repo page's Releases box always point at the
newest build. All builds, CI and local, sign with the committed `app/debug.keystore`, so a new APK installs over the
old one (an APK from before this change needs one uninstall).

## Install (sideload)
* USB: enable Developer options → USB debugging, then `adb install -r dist/mu-debug.apk`.
* Without a computer: copy `mu-debug.apk` to the phone (Drive, USB, …), open it from Files, and allow
  "Install unknown apps" for that app when Android asks.
* On first launch, grant microphone access.

## First run
1. **Calibrate** (home screen tile) once per output: phone speaker, wired, USB, Bluetooth are stored separately.
   * *Loopback* (automatic): speaker on, quiet room; 12 clicks are played and detected through the mic.
   * *Tap-along* (manual): mute the strings and strum on each click (4 count-in + 16). Includes your feel and the
     detector's bias, so it's what scoring uses when present. Headphones recommended.
2. **Tuner**: play one string at a time; green = within ±5 cents.
3. Pick a song, set the tempo (50–125 %), toggle the metronome, **Start**.

## Songs
**Online search** (home screen → *Search songs and tabs online*): searches Songsterr (band songs; pick your guitar
part, and the drums, bass, vocal melody and up to two other guitars become the synth band) and Classtab (~4000 free
classical tabs in plain text). Results are converted on the phone (`online/Online.kt`, `song/AsciiTab.kt`) and saved
with your songs, so they work offline afterwards. Songsterr is used through its unofficial web API: it can change or
block clients at any time, and it's for personal practice only. You can also *Share* a Songsterr/Classtab link or any
plain-text tab to Mu.

**Versions**: every song gets *Basics* (bass note on chord changes, notes on the beat), *Simplified* (3-string shapes
from the real voicing, fast runs thinned) and *Full*, each with a 1–10 level and a "what makes it hard" breakdown
(speed, chord types, change rate, notes at once, fretboard range, techniques) — `song/Arrangement.kt`.

**Backing** (per song, on the setup screen): *Band* = the song's own other instruments when it has them, else a
generated bass + drum groove; *Recording* = an audio file you pick (decoded on the phone, slowed with the tempo
without changing pitch; set where the first tab note falls in it); *Guide* = your part played softly, either always or
only after you miss notes. Rendered by the C++ synth in `core/src/synth.cpp`. On the phone speaker the mic hears it
too, so use headphones for fair scoring.

Bundled (all self-authored): *Mu Chord Etude* (open strings, open chords incl. C/Cmaj7, mixed bars; written by
`importer/samples/make_chord_etude.py`), *Mu Exercise 1* and *2* (the importer test pieces).

Your own songs: convert Guitar Pro (.gp3–.gp7/.gpx), MusicXML or alphaTex on a computer with the importer, then
get the JSON onto the phone:
```
cd importer && npm install
node mu-import.mjs song.gp5 --list          # list tracks
node mu-import.mjs song.gp5 --track 1 -o song.json
```
On the phone either tap **Import a song** (file picker, end of the song list), or *Share* / *Open with* the JSON from any app (Files,
Drive, a chat app) and pick Mu. Imported songs live in app storage; delete them with the bin icon.

## Play screen
Play runs full screen in landscape. Tap the highway (or press Back) to **pause**: resume, change the tempo, loop a
section ("4 bars from here" or any named section), restart, or see the results so far. While paused, tap the minimap or
drag its dot to move anywhere in the song; with a loop set, drag its blue handles to resize it. The **−/+ tempo**
control in the top bar works while playing: the run picks up from the same spot at the new tempo after a short
count-in. Runs that seek or loop are scored as a practice run (only the notes you played; no best score saved).

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
* No section looping or auto slow-down yet. Text tabs carry no rhythm: timing comes from column spacing within each bar.
* With the phone speaker, the mic hears the metronome; clicks can register as extra/wrong onsets. Use headphones or
  turn the in-song metronome off. Same for tap-along calibration.
* The highway is Compose Canvas (fine at 60 fps for these songs); a GPU renderer would be needed only for very
  dense songs.
* Techniques (bends, slides, hammer-ons) are shown nowhere and scored like plain notes; legato notes without an
  attack are often missed by the onset detector.
* Chord scoring needs the exact voicing from the tab (strict mode); songs with chord names only would need
  `strict = false`.
