// Android audio shell: Oboe full-duplex (output callback drives the clock and pulls mic input non-blocking),
// a lock-free SPSC ring to an analysis thread, and the mucore analyzers on that thread.
// Audio callback: no allocation, locks, logging or JNI. UI <-> engine: atomics + SPSC queues.
#pragma once
#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <thread>
#include <vector>
#include "mu/practice.h"
#include "mu/spsc.h"
#include "mu/synth.h"

namespace mu {

enum class Mode : int { Idle = 0, Tuner = 1, Calibrate = 2, Play = 3 };

struct EngineStatus {
  int sampleRate = 0, outBufferFrames = 0, outBurst = 0, inputPreset = 0, sessionId = 0;
  long long xruns = 0, droppedSamples = 0, framesWritten = 0;
  bool timestampsValid = false, disconnected = false, exclusiveOut = false, exclusiveIn = false;
  double outLatencyMs = -1, inLatencyMs = -1;
};

class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
 public:
  AudioEngine();
  ~AudioEngine();
  int open(bool unprocessed);  // returns the sample rate, or a negative oboe::Result
  bool start();
  void stop();
  void close();
  EngineStatus status();

  // ---- UI-thread commands ----
  void setUserOffsetMs(float ms) { userOffsetSec_.store(ms / 1000.0); }
  void startTuner();
  bool tuner(float out[4]);  // freq, confidence, level dB, voiced
  // Schedule clicks at song times (s) with song time 0 = now + leadIn. collect = record mic onsets (calibration).
  void startClicks(const double* t, const bool* accent, int n, double leadInSec, float gain, bool collect);
  int onsets(double* out, int max);
  void loadSong(const ExpectedEvent* ev, int n);
  bool startPlay(const double* clickT, const bool* accent, int n, double leadInSec, float clickGain);
  // Backing: two prerendered mono tracks (band, guide) whose sample 0 is song time -prerollSec, mixed under the
  // clicks while a song plays. Replacing them briefly mutes the backing (never call while playing).
  void setBacking(std::vector<int16_t>&& band, std::vector<int16_t>&& guide, double prerollSec);
  void setBackingGains(float band, float guide) { bandGain_.store(band); guideGain_.store(guide); }
  int sampleRate() const { return sr_; }
  int poll(Judgement* out, int max);
  int finishPlay(Judgement* out, int max);
  void stopAll();
  double songTime();

  // ---- Oboe callbacks ----
  oboe::DataCallbackResult onAudioReady(oboe::AudioStream* s, void* data, int32_t frames) override;
  void onErrorAfterClose(oboe::AudioStream* s, oboe::Result r) override { disconnected_.store(true); }

 private:
  void analysisLoop();
  bool setModeSync(Mode m);
  void refreshTimestamps();
  double outputFrameOfInput(long long streamIdx);
  void scheduleClicks(const double* t, const bool* accent, int n, long long songStart, float gain);

  std::shared_ptr<oboe::AudioStream> out_, in_;
  int sr_ = 48000;
  // callback-owned
  std::vector<float> inBuf_;
  long long framesWritten_ = 0, inCounter_ = 0;
  int clickBank_ = -1, clickCursor_ = 0; const float* clickWave_ = nullptr; int clickPos_ = 0, clickLen_ = 0; float clickAmp_ = 0;
  // shared
  SpscQueue<float> ring_{1 << 17};
  SpscQueue<Judgement> judgements_{1024};
  std::atomic<long long> framesWrittenA_{0}, inOffset_{0}, xruns_{0};
  std::atomic<long long> songStartFrame_{0};
  std::atomic<double> userOffsetSec_{0};
  std::atomic<bool> disconnected_{false}, running_{false}, tsValid_{false};
  // click schedule, double-buffered: UI fills the inactive bank, then publishes it
  static constexpr int kMaxClicks = 4096;
  std::vector<long long> clickFrame_[2]; std::vector<uint8_t> clickAccent_[2];
  std::atomic<int> clickCount_[2]; std::atomic<int> activeBank_{-1}; std::atomic<float> clickGain_{0.5f};
  std::vector<float> clickHi_, clickLo_;
  // backing tracks (int16 to halve memory); the callback reads them only while backingReady_ && backingPlaying_
  std::vector<int16_t> band_, guide_; long long backingPreroll_ = 0;
  std::atomic<bool> backingReady_{false}, backingPlaying_{false};
  std::atomic<float> bandGain_{0.f}, guideGain_{0.f};
  float bandG_ = 0.f, guideG_ = 0.f;  // callback-owned smoothed gains
  // seqlock'd mapping data written by the analysis thread / callback
  std::atomic<unsigned> tsSeq_{0};
  std::atomic<long long> inTsPos_{0}, outTsPos_{0}, inTsNs_{0}, outTsNs_{0};
  std::atomic<unsigned> pairSeq_{0};
  std::atomic<long long> pairIn_{0}, pairOut_{0};
  int outBufferFrames_ = 0;
  // analysis
  std::thread thread_;
  std::atomic<int> requestedMode_{0}, activeMode_{0};
  std::unique_ptr<PlayAnalyzer> play_;
  std::unique_ptr<Tuner> tuner_;
  std::unique_ptr<OnsetCollector> collector_;
  std::atomic<float> tFreq_{0}, tConf_{0}, tDb_{-100}; std::atomic<bool> tVoiced_{false};
  long long analysisCounter_ = 0;
  int inputPreset_ = 0, sessionId_ = 0;
};

}  // namespace mu
