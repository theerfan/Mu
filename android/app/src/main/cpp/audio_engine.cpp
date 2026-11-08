#include "audio_engine.h"
#include <android/log.h>
#include <time.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "MuAudio", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "MuAudio", __VA_ARGS__)

namespace mu {
namespace {
long long nowNs() { timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return ts.tv_sec * 1000000000LL + ts.tv_nsec; }
void makeClick(std::vector<float>& w, int sr, float hz) {
  const int n = (int)(0.03 * sr); w.assign(n, 0.f); unsigned lcg = 12345;
  for (int i = 0; i < n; ++i) {
    float t = (float)i / sr, env = std::exp(-t / 0.008f);
    lcg = lcg * 1664525u + 1013904223u; float nz = ((lcg >> 9) / 4194304.f - 1.f) * std::exp(-t / 0.0015f);
    w[i] = env * (0.8f * std::sin(2.f * (float)M_PI * hz * t)) + 0.4f * nz;
  }
}
}  // namespace

AudioEngine::AudioEngine() {
  for (int b = 0; b < 2; ++b) { clickFrame_[b].assign(kMaxClicks, 0); clickAccent_[b].assign(kMaxClicks, 0); clickCount_[b].store(0); }
}
AudioEngine::~AudioEngine() { close(); }

int AudioEngine::open(bool unprocessed) {
  close();
  oboe::AudioStreamBuilder ob;
  ob.setDirection(oboe::Direction::Output)->setPerformanceMode(oboe::PerformanceMode::LowLatency)
      ->setSharingMode(oboe::SharingMode::Exclusive)->setFormat(oboe::AudioFormat::Float)
      ->setChannelCount(oboe::ChannelCount::Mono)->setUsage(oboe::Usage::Game)
      ->setContentType(oboe::ContentType::Music)->setDataCallback(this)->setErrorCallback(this);
  oboe::Result r = ob.openStream(out_);
  if (r != oboe::Result::OK) { LOGW("output open failed: %s", oboe::convertToText(r)); return (int)r; }
  sr_ = out_->getSampleRate();
  out_->setBufferSizeInFrames(out_->getFramesPerBurst() * 2);
  outBufferFrames_ = out_->getBufferSizeInFrames();
  auto openIn = [&](oboe::InputPreset preset) {
    oboe::AudioStreamBuilder ib;
    ib.setDirection(oboe::Direction::Input)->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)->setSampleRate(sr_)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setInputPreset(preset)->setSessionId(oboe::SessionId::Allocate);
    return ib.openStream(in_);
  };
  r = openIn(unprocessed ? oboe::InputPreset::Unprocessed : oboe::InputPreset::VoiceRecognition);
  if (r != oboe::Result::OK && unprocessed) r = openIn(oboe::InputPreset::VoiceRecognition);
  if (r != oboe::Result::OK) { LOGW("input open failed: %s", oboe::convertToText(r)); out_->close(); out_.reset(); return (int)r; }
  inputPreset_ = (int)in_->getInputPreset(); sessionId_ = (int)in_->getSessionId();
  inBuf_.assign(8192, 0.f);
  makeClick(clickHi_, sr_, 1760.f); makeClick(clickLo_, sr_, 1175.f);
  play_ = std::make_unique<PlayAnalyzer>(sr_);
  tuner_ = std::make_unique<Tuner>(sr_);
  collector_ = std::make_unique<OnsetCollector>(sr_);
  LOGI("opened: sr=%d outBurst=%d outBuf=%d inPreset=%d session=%d", sr_, out_->getFramesPerBurst(), outBufferFrames_, inputPreset_, sessionId_);
  return sr_;
}

bool AudioEngine::start() {
  if (!out_ || !in_) return false;
  stop();
  disconnected_.store(false); inCounter_ = 0; analysisCounter_ = 0;
  framesWritten_ = out_->getFramesWritten();  // stream counters continue across stop/start; the clock follows them
  framesWrittenA_.store(framesWritten_); inOffset_.store(0); tsValid_.store(false); activeBank_.store(-1); clickBank_ = -1;
  requestedMode_.store(0); activeMode_.store(0);
  ring_.clear();
  running_.store(true);
  thread_ = std::thread(&AudioEngine::analysisLoop, this);
  if (in_->requestStart() != oboe::Result::OK || out_->requestStart() != oboe::Result::OK) { stop(); return false; }
  return true;
}

void AudioEngine::stop() {
  if (out_) out_->requestStop();
  if (in_) in_->requestStop();
  running_.store(false);
  if (thread_.joinable()) thread_.join();
}

void AudioEngine::close() {
  stop();
  if (out_) { out_->close(); out_.reset(); }
  if (in_) { in_->close(); in_.reset(); }
}

EngineStatus AudioEngine::status() {
  EngineStatus s; s.sampleRate = sr_; s.outBufferFrames = outBufferFrames_; s.inputPreset = inputPreset_; s.sessionId = sessionId_;
  s.framesWritten = framesWrittenA_.load(); s.droppedSamples = (long long)ring_.dropped();
  s.timestampsValid = tsValid_.load(); s.disconnected = disconnected_.load();
  if (out_) {
    s.outBurst = out_->getFramesPerBurst();
    auto x = out_->getXRunCount(); if (x) s.xruns = x.value();
    auto l = out_->calculateLatencyMillis(); if (l) s.outLatencyMs = l.value();
    s.exclusiveOut = out_->getSharingMode() == oboe::SharingMode::Exclusive;
  }
  if (in_) {
    auto l = in_->calculateLatencyMillis(); if (l) s.inLatencyMs = l.value();
    s.exclusiveIn = in_->getSharingMode() == oboe::SharingMode::Exclusive;
  }
  return s;
}

// ---------------- audio callback (real-time) ----------------
oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream*, void* data, int32_t frames) {
  float* o = static_cast<float*>(data);
  if (in_) {
    auto res = in_->read(inBuf_.data(), std::min<int32_t>(frames * 2, (int32_t)inBuf_.size()), 0);
    if (res && res.value() > 0) {
      const int n = res.value();
      inCounter_ += (long long)ring_.push(inBuf_.data(), (size_t)n);
      const long long fr = in_->getFramesRead();
      inOffset_.store(fr - inCounter_, std::memory_order_relaxed);
      pairSeq_.fetch_add(1, std::memory_order_acq_rel);
      pairIn_.store(fr, std::memory_order_relaxed); pairOut_.store(framesWritten_, std::memory_order_relaxed);
      pairSeq_.fetch_add(1, std::memory_order_acq_rel);
    }
  }
  const int bank = activeBank_.load(std::memory_order_acquire);
  if (bank != clickBank_) { clickBank_ = bank; clickCursor_ = 0; }
  const int cnt = bank >= 0 ? clickCount_[bank].load(std::memory_order_acquire) : 0;
  const long long* cf = bank >= 0 ? clickFrame_[bank].data() : nullptr;
  const uint8_t* ca = bank >= 0 ? clickAccent_[bank].data() : nullptr;
  const float gain = clickGain_.load(std::memory_order_relaxed);
  const bool backing = backingReady_.load(std::memory_order_acquire) && backingPlaying_.load(std::memory_order_relaxed);
  const long long bOrigin = songStartFrame_.load(std::memory_order_relaxed) - backingPreroll_;
  const float bT = bandGain_.load(std::memory_order_relaxed), gT = guideGain_.load(std::memory_order_relaxed);
  const long long nBand = (long long)band_.size(), nGuide = (long long)guide_.size();
  for (int i = 0; i < frames; ++i) {
    const long long f = framesWritten_ + i;
    while (clickCursor_ < cnt && cf[clickCursor_] <= f) {
      if (cf[clickCursor_] >= f - 2) {
        clickWave_ = ca[clickCursor_] ? clickHi_.data() : clickLo_.data();
        clickLen_ = (int)clickHi_.size(); clickPos_ = 0; clickAmp_ = ca[clickCursor_] ? gain : 0.7f * gain;
      }
      ++clickCursor_;
    }
    float s = 0.f;
    if (clickWave_ && clickPos_ < clickLen_) s = clickWave_[clickPos_++] * clickAmp_;
    if (backing) {
      bandG_ += 0.0005f * (bT - bandG_); guideG_ += 0.0005f * (gT - guideG_);   // ~40 ms glide, no zipper noise
      const long long k = f - bOrigin;
      if (k >= 0 && k < nBand) s += band_[(size_t)k] * (bandG_ / 32768.f);
      if (k >= 0 && k < nGuide) s += guide_[(size_t)k] * (guideG_ / 32768.f);
    }
    o[i] = std::max(-1.f, std::min(1.f, s));
  }
  framesWritten_ += frames;
  framesWrittenA_.store(framesWritten_, std::memory_order_release);
  return oboe::DataCallbackResult::Continue;
}

// ---------------- clock mapping ----------------
void AudioEngine::refreshTimestamps() {
  if (!in_ || !out_) return;
  auto ri = in_->getTimestamp(CLOCK_MONOTONIC);
  auto ro = out_->getTimestamp(CLOCK_MONOTONIC);
  if (ri && ro) {
    tsSeq_.fetch_add(1, std::memory_order_acq_rel);
    inTsPos_.store(ri.value().position, std::memory_order_relaxed); inTsNs_.store(ri.value().timestamp, std::memory_order_relaxed);
    outTsPos_.store(ro.value().position, std::memory_order_relaxed); outTsNs_.store(ro.value().timestamp, std::memory_order_relaxed);
    tsSeq_.fetch_add(1, std::memory_order_acq_rel);
    tsValid_.store(true);
  } else {
    tsValid_.store(false);
  }
}

// Output frame (presentation timeline) at which input frame streamIdx was captured.
double AudioEngine::outputFrameOfInput(long long streamIdx) {
  if (tsValid_.load()) {
    long long ip, in, op, on; unsigned s0, s1;
    do {
      s0 = tsSeq_.load(std::memory_order_acquire);
      ip = inTsPos_.load(std::memory_order_relaxed); in = inTsNs_.load(std::memory_order_relaxed);
      op = outTsPos_.load(std::memory_order_relaxed); on = outTsNs_.load(std::memory_order_relaxed);
      s1 = tsSeq_.load(std::memory_order_acquire);
    } while (s0 != s1 || (s0 & 1));
    const double ns = (double)in + (double)(streamIdx - ip) * 1e9 / sr_;
    return (double)op + (ns - (double)on) * sr_ / 1e9;
  }
  // Fallback without timestamps: the input frame read in the callback that wrote output frame pairOut is
  // heard about one output buffer later. Latency calibration absorbs the remaining error.
  long long pi, po; unsigned s0, s1;
  do {
    s0 = pairSeq_.load(std::memory_order_acquire);
    pi = pairIn_.load(std::memory_order_relaxed); po = pairOut_.load(std::memory_order_relaxed);
    s1 = pairSeq_.load(std::memory_order_acquire);
  } while (s0 != s1 || (s0 & 1));
  return (double)(po + (streamIdx - pi) - outBufferFrames_);
}

double AudioEngine::songTime() {
  const long long start = songStartFrame_.load();
  double of;
  if (tsValid_.load()) {
    long long op, on; unsigned s0, s1;
    do {
      s0 = tsSeq_.load(std::memory_order_acquire);
      op = outTsPos_.load(std::memory_order_relaxed); on = outTsNs_.load(std::memory_order_relaxed);
      s1 = tsSeq_.load(std::memory_order_acquire);
    } while (s0 != s1 || (s0 & 1));
    of = (double)op + (double)(nowNs() - on) * sr_ / 1e9;
  } else {
    of = (double)(framesWrittenA_.load() - outBufferFrames_);
  }
  return (of - (double)start) / sr_;
}

// ---------------- analysis thread ----------------
void AudioEngine::analysisLoop() {
  std::vector<float> buf(1024);
  std::vector<Judgement> js(64);
  auto lastTs = std::chrono::steady_clock::now() - std::chrono::seconds(1);
  while (running_.load()) {
    const int req = requestedMode_.load(std::memory_order_acquire);
    if (req != activeMode_.load(std::memory_order_relaxed)) activeMode_.store(req, std::memory_order_release);
    auto now = std::chrono::steady_clock::now();
    if (now - lastTs > std::chrono::milliseconds(50)) { refreshTimestamps(); lastTs = now; }
    const size_t n = ring_.pop(buf.data(), buf.size());
    if (n == 0) { std::this_thread::sleep_for(std::chrono::milliseconds(2)); continue; }
    const long long streamIdx = analysisCounter_ + inOffset_.load(std::memory_order_relaxed);
    analysisCounter_ += (long long)n;
    const Mode mode = (Mode)req;
    if (mode == Mode::Idle) continue;
    if (mode == Mode::Tuner) {
      gdsp::PitchResult p; float db = -100;
      if (tuner_->feed(buf.data(), (int)n, p, db)) {
        tFreq_.store(p.freqHz); tConf_.store(p.confidence); tDb_.store(db); tVoiced_.store(p.voiced);
      }
      continue;
    }
    double t0 = (outputFrameOfInput(streamIdx) - (double)songStartFrame_.load()) / sr_;
    if (mode == Mode::Calibrate) collector_->process(buf.data(), (int)n, t0);
    else if (mode == Mode::Play) {
      t0 -= userOffsetSec_.load();
      const int k = play_->process(buf.data(), (int)n, t0, js.data(), (int)js.size());
      if (k > 0) judgements_.push(js.data(), (size_t)k);
    }
  }
  activeMode_.store(requestedMode_.load());
}

bool AudioEngine::setModeSync(Mode m) {
  requestedMode_.store((int)m, std::memory_order_release);
  if (!running_.load()) { activeMode_.store((int)m); return true; }
  for (int i = 0; i < 500; ++i) {
    if (activeMode_.load(std::memory_order_acquire) == (int)m) return true;
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
  }
  LOGW("mode switch to %d timed out", (int)m);
  return false;
}

// ---------------- UI commands ----------------
void AudioEngine::scheduleClicks(const double* t, const bool* accent, int n, long long songStart, float gain) {
  static auto lastPublish = std::chrono::steady_clock::now() - std::chrono::seconds(1);
  if (std::chrono::steady_clock::now() - lastPublish < std::chrono::milliseconds(60))
    std::this_thread::sleep_for(std::chrono::milliseconds(60));  // old bank no longer read by the callback
  const int cur = activeBank_.load(), bank = cur == 0 ? 1 : 0;
  n = std::min(n, kMaxClicks);
  for (int i = 0; i < n; ++i) {
    clickFrame_[bank][i] = songStart + (long long)std::llround(t[i] * sr_);
    clickAccent_[bank][i] = accent[i] ? 1 : 0;
  }
  clickCount_[bank].store(n, std::memory_order_release);
  clickGain_.store(gain);
  activeBank_.store(bank, std::memory_order_release);
  lastPublish = std::chrono::steady_clock::now();
}

void AudioEngine::setBacking(std::vector<int16_t>&& band, std::vector<int16_t>&& guide, double prerollSec) {
  backingReady_.store(false, std::memory_order_release);
  std::this_thread::sleep_for(std::chrono::milliseconds(60));  // the callback has stopped reading the old tracks
  band_ = std::move(band); guide_ = std::move(guide); backingPreroll_ = (long long)std::llround(prerollSec * sr_);
  bandG_ = guideG_ = 0.f;
  backingReady_.store(true, std::memory_order_release);
}

void AudioEngine::startTuner() { setModeSync(Mode::Idle); setModeSync(Mode::Tuner); }

bool AudioEngine::tuner(float out[4]) {
  out[0] = tFreq_.load(); out[1] = tConf_.load(); out[2] = tDb_.load(); out[3] = tVoiced_.load() ? 1.f : 0.f;
  return activeMode_.load() == (int)Mode::Tuner;
}

void AudioEngine::startClicks(const double* t, const bool* accent, int n, double leadInSec, float gain, bool collect) {
  setModeSync(Mode::Idle);
  const long long start = framesWrittenA_.load() + (long long)(leadInSec * sr_);
  songStartFrame_.store(start);
  backingPlaying_.store(false);
  scheduleClicks(t, accent, n, start, gain);
  if (collect) { collector_->reset(); setModeSync(Mode::Calibrate); }
}

int AudioEngine::onsets(double* out, int max) {
  setModeSync(Mode::Idle);
  const int n = std::min(max, collector_->count());
  std::copy(collector_->onsets(), collector_->onsets() + n, out);
  return n;
}

void AudioEngine::loadSong(const ExpectedEvent* ev, int n) { setModeSync(Mode::Idle); play_->setSong(ev, n); }

bool AudioEngine::startPlay(const double* clickT, const bool* accent, int n, double leadInSec, float gain) {
  if (!running_.load()) return false;
  setModeSync(Mode::Idle);
  play_->reset();
  judgements_.clear();
  const long long start = framesWrittenA_.load() + (long long)(leadInSec * sr_);
  songStartFrame_.store(start);
  scheduleClicks(clickT, accent, n, start, gain);
  backingPlaying_.store(true);
  return setModeSync(Mode::Play);
}

int AudioEngine::poll(Judgement* out, int max) { return (int)judgements_.pop(out, (size_t)max); }

int AudioEngine::finishPlay(Judgement* out, int max) {
  setModeSync(Mode::Idle);
  int k = (int)judgements_.pop(out, (size_t)max);
  return k + play_->finish(out + k, max - k);
}

void AudioEngine::stopAll() { activeBank_.store(-1, std::memory_order_release); backingPlaying_.store(false); setModeSync(Mode::Idle); }

}  // namespace mu
