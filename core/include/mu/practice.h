// Platform-independent practice core: play-along analysis/scoring, tuner, latency calibration math.
// All classes preallocate in their constructors; process()/feed() calls do not allocate, lock or do I/O,
// so they can run on a real-time-ish analysis thread. Times are in seconds on the song timeline
// (0 = first beat of the song, already corrected for audio latency by the caller).
#pragma once
#include <cstdint>
#include <memory>
#include <vector>
#include "gdsp.h"
namespace mu {

constexpr int kMaxNotesPerEvent = 8;

struct ExpectedEvent {
  double t = 0;    // song time (s), already scaled for practice tempo
  float dur = 0;
  int n = 0;       // number of notes (1 = single note; >= 2 = chord / double stop)
  int midi[kMaxNotesPerEvent] = {};
};

enum class Kind : int32_t { Hit = 0, Partial = 1, Wrong = 2, Miss = 3, Extra = 4 };
enum class Grade : int32_t { Perfect = 0, Good = 1, Ok = 2, None = 3 };

struct Judgement {
  Kind kind = Kind::Miss;
  Grade grade = Grade::None;
  int32_t index = -1;       // expected event index (-1 for Extra)
  float dtMs = 0;           // detected - expected (negative = early)
  float songTime = 0;       // detected onset time (or expected time for Miss)
  int32_t detMidi = -1;     // single notes: detected MIDI (-1 = unvoiced)
  float cents = 0;          // single notes: deviation from detMidi
  float chordScore = -1;    // chords: verifier score (-1 = n/a)
  int32_t missingPc = -1;   // chords: expected pitch class without evidence (0 = C .. 11 = B)
  int32_t extraPc = -1;     // chords: non-expected pitch class that stands out
};

struct Windows { float perfect = 0.040f, good = 0.080f, ok = 0.130f; };

// Streams mic audio, detects onsets, and judges them against the expected events (ARCHITECTURE.md §5):
// nearest unmatched expected event within the ok window; single notes by YIN pitch (octave = partial),
// chords by gdsp::ChordVerifier (strict note-level test, with missing/extra pitch class feedback).
// Expected events that pass without a matching onset become Miss; onsets near nothing become Extra.
class PlayAnalyzer {
 public:
  PlayAnalyzer(int sampleRate, int maxEvents = 8192);
  // Not real-time: copies the expected events (sorted by t) into preallocated storage. Call while stopped.
  void setSong(const ExpectedEvent* ev, int n);
  void reset();  // restart from the beginning (keeps the song)
  // Feed n mic samples whose first sample was *played* at song time t0 (latency-corrected). Judgements are
  // written to out (up to maxOut); returns how many. Call with consecutive blocks of any size.
  int process(const float* x, int n, double t0, Judgement* out, int maxOut);
  // Song over (or stopped): judge pending onsets with what is there and mark all remaining events Miss.
  int finish(Judgement* out, int maxOut);
  Windows windows;
  float lateGraceSec = 1.0f;    // how long after an event's ok window a miss is declared (analysis delay)
  gdsp::ChordVerifier& verifier() { return cv_; }
  int sampleRate() const { return sr_; }
  int hop() const { return hop_; }
 private:
  struct Pending { long long idx; double t; };
  int judgeOnset(const Pending& p, long long end, Judgement* out, int maxOut);
  int flushMisses(double now, Judgement* out, int maxOut);
  void copyHistory(long long from, long long to, float* dst) const;
  int sr_, hop_, maxEvents_;
  gdsp::OnsetDetector od_;
  gdsp::YinPitch yin_;
  gdsp::ChordVerifier cv_;
  std::vector<ExpectedEvent> ev_; int nEv_ = 0;
  std::vector<uint8_t> matched_;
  int missCursor_ = 0;
  std::vector<float> hist_; long long histMask_;   // ring of recent input (power of two)
  std::vector<float> hopBuf_; int hopFill_ = 0;
  std::vector<float> seg_, pre_;                    // contiguous scratch for verify / YIN
  long long total_ = 0;                             // samples received so far
  long long anchorIdx_ = 0; double anchorT_ = 0;    // song time of sample anchorIdx_
  Pending pend_[32]; int nPend_ = 0;
  double timeOf(long long idx) const { return anchorT_ + (double)(idx - anchorIdx_) / sr_; }
};

// Streaming tuner: YIN over the most recent window, evaluated every hop.
class Tuner {
 public:
  explicit Tuner(int sampleRate);
  // Returns true when a new estimate is available in result.
  bool feed(const float* x, int n, gdsp::PitchResult& result, float& rmsDb);
 private:
  int W_, hop_; gdsp::YinPitch yin_;
  std::vector<float> win_; int sinceLast_ = 0; long long fed_ = 0;
};

// Collects onset times (song timeline) from a mic stream, e.g. during calibration.
class OnsetCollector {
 public:
  explicit OnsetCollector(int sampleRate, int maxOnsets = 512);
  void reset();
  void process(const float* x, int n, double t0);
  int count() const { return nOn_; }
  const double* onsets() const { return on_.data(); }
 private:
  int sr_; gdsp::OnsetDetector od_; std::vector<float> hopBuf_; int hopFill_ = 0;
  long long total_ = 0, anchorIdx_ = 0; double anchorT_ = 0;
  std::vector<double> on_; int nOn_ = 0;
};

struct CalibResult { bool ok = false; float offsetMs = 0; float spreadMs = 0; int used = 0; int total = 0; };
// For each reference time (click / beat) take the nearest detection within maxDistSec; offset = median of
// (detected - reference), after dropping errors further than max(3*MAD, 8 ms) from the first median.
// ok when at least minUsed references were matched.
CalibResult robustOffset(const double* detected, int nDet, const double* refs, int nRef, double maxDistSec,
                         int minUsed = 4);

const char* pitchClassName(int pc);

}  // namespace mu
