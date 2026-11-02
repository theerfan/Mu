#include "mu/practice.h"
#include <algorithm>
#include <cmath>
namespace mu {
namespace {
int pow2Ceil(double v) { int n = 1; while (n < v) n <<= 1; return n; }
int onsetFrame(int sr) { return std::max(1024, pow2Ceil(sr * 0.023)); }
}  // namespace

const char* pitchClassName(int pc) {
  static const char* names[12] = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
  return pc >= 0 && pc < 12 ? names[pc] : "?";
}

// ---------------- PlayAnalyzer ----------------
PlayAnalyzer::PlayAnalyzer(int sr, int maxEvents)
    : sr_(sr), hop_(onsetFrame(sr) / 4), maxEvents_(maxEvents),
      od_(sr, onsetFrame(sr), onsetFrame(sr) / 4),
      yin_(sr, pow2Ceil(sr * 0.045)),
      cv_(sr, (int)(0.8 * sr)) {
  ev_.resize(maxEvents); matched_.assign(maxEvents, 0);
  const int histLen = pow2Ceil(sr * 2.5);
  hist_.assign(histLen, 0.f); histMask_ = histLen - 1;
  hopBuf_.assign(hop_, 0.f);
  seg_.assign((size_t)(0.8 * sr) + 16, 0.f);  // chord window: 60 ms .. 0.8 s after the onset (as in eval/)
  pre_.assign((size_t)(0.35 * sr) + 16, 0.f);
}

void PlayAnalyzer::setSong(const ExpectedEvent* ev, int n) {
  nEv_ = std::min(n, maxEvents_);
  std::copy(ev, ev + nEv_, ev_.begin());
  std::stable_sort(ev_.begin(), ev_.begin() + nEv_, [](const ExpectedEvent& a, const ExpectedEvent& b) { return a.t < b.t; });
  reset();
}

void PlayAnalyzer::reset() {
  od_ = gdsp::OnsetDetector(sr_, onsetFrame(sr_), hop_);  // not RT (allocates); reset() is called while stopped
  std::fill(matched_.begin(), matched_.end(), 0);
  std::fill(hist_.begin(), hist_.end(), 0.f);
  missCursor_ = 0; hopFill_ = 0; total_ = 0; anchorIdx_ = 0; anchorT_ = 0; nPend_ = 0;
}

void PlayAnalyzer::copyHistory(long long from, long long to, float* dst) const {
  for (long long i = from; i < to; ++i) *dst++ = hist_[(size_t)(i & histMask_)];
}

int PlayAnalyzer::process(const float* x, int n, double t0, Judgement* out, int maxOut) {
  anchorIdx_ = total_; anchorT_ = t0;
  int nOut = 0;
  const double first = nEv_ ? ev_[0].t - windows.ok - 0.3 : 0, last = nEv_ ? ev_[nEv_ - 1].t + windows.ok + 0.3 : 0;
  for (int i = 0; i < n; ++i) {
    hist_[(size_t)(total_ & histMask_)] = x[i];
    ++total_;
    hopBuf_[hopFill_++] = x[i];
    if (hopFill_ == hop_) {
      hopFill_ = 0;
      long long on;
      if (od_.processHop(hopBuf_.data(), on)) {
        double t = timeOf(on);
        if (t >= first && t <= last && nPend_ < 32) pend_[nPend_++] = Pending{on, t};
      }
    }
  }
  // Judge onsets whose analysis window is complete. The window runs to the next onset (max 0.6 s); with no
  // next onset yet, wait for the detector's latency too so a following onset isn't missed.
  const long long maxWin = (long long)(0.8 * sr_), detLatency = 2LL * onsetFrame(sr_);
  while (nPend_ > 0) {
    const Pending p = pend_[0];
    long long end = p.idx + maxWin;
    bool ready;
    if (nPend_ > 1) { end = std::min(end, pend_[1].idx); ready = total_ >= end; }
    else ready = total_ >= end + detLatency;
    if (!ready) break;
    nOut += judgeOnset(p, end, out + nOut, maxOut - nOut);
    std::move(pend_ + 1, pend_ + nPend_, pend_); --nPend_;
  }
  nOut += flushMisses(timeOf(total_), out + nOut, maxOut - nOut);
  return nOut;
}

int PlayAnalyzer::judgeOnset(const Pending& p, long long end, Judgement* out, int maxOut) {
  if (maxOut <= 0) return 0;
  end = std::min(end, total_);
  Judgement j; j.songTime = (float)p.t;
  // nearest unmatched expected event within the ok window
  const double ok = windows.ok;
  int lo = (int)(std::lower_bound(ev_.begin(), ev_.begin() + nEv_, p.t - ok,
                                  [](const ExpectedEvent& e, double v) { return e.t < v; }) - ev_.begin());
  int best = -1; double bestD = 1e9;
  for (int k = lo; k < nEv_ && ev_[k].t <= p.t + ok; ++k)
    if (!matched_[k] && std::fabs(ev_[k].t - p.t) < bestD) { bestD = std::fabs(ev_[k].t - p.t); best = k; }
  if (best < 0) { j.kind = Kind::Extra; out[0] = j; return 1; }
  matched_[best] = 1;
  const ExpectedEvent& e = ev_[best];
  j.index = best; j.dtMs = (float)((p.t - e.t) * 1000.0);
  const float ad = std::fabs((float)(p.t - e.t));
  j.grade = ad <= windows.perfect ? Grade::Perfect : ad <= windows.good ? Grade::Good : Grade::Ok;
  if (e.n <= 1) {
    const long long len = std::min<long long>(end - p.idx, (long long)seg_.size());
    copyHistory(p.idx, p.idx + len, seg_.data());
    float conf = 0;
    float f = gdsp::pitchAfterOnset(yin_, seg_.data(), len, 0, len, sr_, &conf, 3, 0.04f);
    if (f <= 0) { j.kind = Kind::Wrong; }
    else {
      float m = gdsp::freqToMidi(f); int dm = (int)std::lround(m);
      j.detMidi = dm; j.cents = (m - dm) * 100.f;
      const int d = std::abs(dm - e.midi[0]);
      j.kind = d == 0 ? Kind::Hit : (d == 12 || d == 24) ? Kind::Partial : Kind::Wrong;
    }
  } else {
    const long long s0 = p.idx + (long long)(0.06 * sr_);
    const long long len = std::min<long long>(end - s0, (long long)seg_.size());
    if (len < (long long)(0.03 * sr_)) { j.kind = Kind::Wrong; out[0] = j; return 1; }
    copyHistory(s0, s0 + len, seg_.data());
    const long long pe = p.idx - (long long)(0.02 * sr_), ps = std::max(0LL, pe - (long long)(0.3 * sr_));
    const long long histLen = (long long)hist_.size();
    const bool havePre = pe - ps > 0 && total_ - ps <= histLen;
    if (havePre) copyHistory(ps, pe, pre_.data());
    gdsp::ChordMatch m = cv_.verify(seg_.data(), (int)len, e.midi, e.n, havePre ? pre_.data() : nullptr, havePre ? (int)(pe - ps) : 0);
    j.chordScore = m.score;
    if (m.match) j.kind = Kind::Hit;
    else {
      j.kind = Kind::Wrong;
      if (cv_.strict) {
        const float shift = cv_.strictSnrAdapt * (std::min(m.snrDb, cv_.strictSnrMaxDb) - cv_.strictSnrRefDb);
        if (m.missSalDb < 98.f && m.missSalDb < cv_.missCenterDb + shift) j.missingPc = m.weakestPc;
        const float exThr = cv_.extraCenterDb + shift;
        if (m.extraSalDb > -98.f && m.extraSalDb > exThr) {
          // Feedback names the note, not one of its overtones: among the pitch classes that stand out, prefer
          // the one whose 3rd/5th/7th harmonics (+7/+4/+10 semitones) explain the others.
          int best = m.extraPc, bestCount = -1;
          for (int pc = 0; pc < 12; ++pc) {
            if (!(m.pcSalDb[pc] > exThr)) continue;
            int c = 0;
            for (int o : {7, 4, 10}) if (m.pcSalDb[(pc + o) % 12] > exThr) ++c;
            if (c > bestCount || (c == bestCount && m.pcSalDb[pc] > m.pcSalDb[best])) { bestCount = c; best = pc; }
          }
          j.extraPc = best;
        }
      }
    }
  }
  out[0] = j; return 1;
}

int PlayAnalyzer::flushMisses(double now, Judgement* out, int maxOut) {
  int k = 0;
  while (missCursor_ < nEv_ && ev_[missCursor_].t + windows.ok + lateGraceSec < now && k < maxOut) {
    if (!matched_[missCursor_]) {
      matched_[missCursor_] = 2;
      Judgement j; j.kind = Kind::Miss; j.index = missCursor_; j.songTime = (float)ev_[missCursor_].t; out[k++] = j;
    }
    ++missCursor_;
  }
  return k;
}

int PlayAnalyzer::finish(Judgement* out, int maxOut) {
  int k = 0;
  while (nPend_ > 0 && k < maxOut) {
    long long end = nPend_ > 1 ? pend_[1].idx : total_;
    k += judgeOnset(pend_[0], end, out + k, maxOut - k);
    std::move(pend_ + 1, pend_ + nPend_, pend_); --nPend_;
  }
  k += flushMisses(1e18, out + k, maxOut - k);
  return k;
}

// ---------------- Tuner ----------------
Tuner::Tuner(int sr) : W_(pow2Ceil(sr * 0.06)), hop_(pow2Ceil(sr * 0.06) / 4), yin_(sr, pow2Ceil(sr * 0.06), 60.f, 1400.f) {
  win_.assign(W_, 0.f);
}
bool Tuner::feed(const float* x, int n, gdsp::PitchResult& res, float& rmsDb) {
  bool got = false;
  while (n > 0) {
    int k = std::min(n, hop_ - sinceLast_);
    std::move(win_.begin() + k, win_.end(), win_.begin());
    std::copy(x, x + k, win_.end() - k);
    x += k; n -= k; sinceLast_ += k; fed_ += k;
    if (sinceLast_ == hop_ && fed_ >= W_) {
      sinceLast_ = 0;
      double e = 0; for (float v : win_) e += (double)v * v;
      rmsDb = (float)(10.0 * std::log10(e / W_ + 1e-12));
      res = rmsDb > -60.f ? yin_.process(win_.data()) : gdsp::PitchResult{};
      got = true;
    } else if (sinceLast_ == hop_) sinceLast_ = 0;
  }
  return got;
}

// ---------------- OnsetCollector ----------------
OnsetCollector::OnsetCollector(int sr, int maxOnsets) : sr_(sr), od_(sr, onsetFrame(sr), onsetFrame(sr) / 4) {
  hopBuf_.assign(onsetFrame(sr) / 4, 0.f); on_.assign(maxOnsets, 0.0);
}
void OnsetCollector::reset() {
  od_ = gdsp::OnsetDetector(sr_, onsetFrame(sr_), onsetFrame(sr_) / 4);
  hopFill_ = 0; total_ = 0; nOn_ = 0; anchorIdx_ = 0; anchorT_ = 0;
}
void OnsetCollector::process(const float* x, int n, double t0) {
  anchorIdx_ = total_; anchorT_ = t0;
  const int hop = (int)hopBuf_.size();
  for (int i = 0; i < n; ++i) {
    hopBuf_[hopFill_++] = x[i]; ++total_;
    if (hopFill_ == hop) {
      hopFill_ = 0; long long on;
      if (od_.processHop(hopBuf_.data(), on) && nOn_ < (int)on_.size())
        on_[nOn_++] = anchorT_ + (double)(on - anchorIdx_) / sr_;
    }
  }
}

// ---------------- calibration math ----------------
static double median(std::vector<double> v) {
  if (v.empty()) return 0;
  std::sort(v.begin(), v.end());
  size_t n = v.size(); return n % 2 ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
}
CalibResult robustOffset(const double* det, int nDet, const double* refs, int nRef, double maxDist, int minUsed) {
  CalibResult r; r.total = nRef;
  std::vector<double> err;
  for (int i = 0; i < nRef; ++i) {
    double best = 1e9;
    for (int k = 0; k < nDet; ++k) if (std::fabs(det[k] - refs[i]) < std::fabs(best)) best = det[k] - refs[i];
    if (std::fabs(best) <= maxDist) err.push_back(best);
  }
  if ((int)err.size() < minUsed) { r.used = (int)err.size(); return r; }
  double m = median(err);
  std::vector<double> dev; for (double e : err) dev.push_back(std::fabs(e - m));
  double mad = median(dev), lim = std::max(3.0 * 1.4826 * mad, 0.008);
  std::vector<double> kept; for (double e : err) if (std::fabs(e - m) <= lim) kept.push_back(e);
  if ((int)kept.size() < minUsed) { r.used = (int)kept.size(); return r; }
  double m2 = median(kept);
  std::vector<double> d2; for (double e : kept) d2.push_back(std::fabs(e - m2));
  r.ok = true; r.used = (int)kept.size(); r.offsetMs = (float)(m2 * 1000); r.spreadMs = (float)(median(d2) * 1000);
  return r;
}

}  // namespace mu
