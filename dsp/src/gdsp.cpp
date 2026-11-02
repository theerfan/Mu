#include "gdsp.h"
#include <algorithm>
#include <cmath>
namespace gdsp {
static const float kPi = 3.14159265358979f;

FFT::FFT(int n) : n_(n), rev_(n), tw_(n / 2) {
  int bits = 0; while ((1 << bits) < n) ++bits;
  for (int i = 0; i < n; ++i) { int r = 0; for (int b = 0; b < bits; ++b) if (i & (1 << b)) r |= 1 << (bits - 1 - b); rev_[i] = r; }
  for (int k = 0; k < n / 2; ++k) tw_[k] = std::polar(1.f, -2 * kPi * k / n);
}
void FFT::forward(std::complex<float>* a) const {
  for (int i = 0; i < n_; ++i) if (i < rev_[i]) std::swap(a[i], a[rev_[i]]);
  for (int len = 2; len <= n_; len <<= 1) {
    int half = len / 2, step = n_ / len;
    for (int i = 0; i < n_; i += len)
      for (int k = 0; k < half; ++k) {
        auto u = a[i + k], v = a[i + k + half] * tw_[k * step];
        a[i + k] = u + v; a[i + k + half] = u - v;
      }
  }
}

SpectrumAnalyzer::SpectrumAnalyzer(int n) : fft_(n), win_(n), buf_(n), mag_(n / 2 + 1) {
  for (int i = 0; i < n; ++i) win_[i] = 0.5f - 0.5f * std::cos(2 * kPi * i / (n - 1));
}
const std::vector<float>& SpectrumAnalyzer::process(const float* x, int len) {
  const int n = fft_.size(); len = std::min(len, n);
  float wsum = 0;
  if (len == n) { for (int i = 0; i < n; ++i) { buf_[i] = x[i] * win_[i]; wsum += win_[i]; } }
  else {
    for (int i = 0; i < len; ++i) { float w = 0.5f - 0.5f * std::cos(2 * kPi * i / std::max(1, len - 1)); buf_[i] = x[i] * w; wsum += w; }
    for (int i = len; i < n; ++i) buf_[i] = 0;
  }
  fft_.forward(buf_.data());
  float g = wsum > 0 ? 2.f / wsum : 0.f;
  for (int i = 0; i <= n / 2; ++i) mag_[i] = std::abs(buf_[i]) * g;
  return mag_;
}

std::string midiName(int m) {
  static const char* nm[12] = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
  if (m < 0) return "-";
  return std::string(nm[m % 12]) + std::to_string(m / 12 - 1);
}
float freqToMidi(float hz) { return hz > 0 ? 69.f + 12.f * std::log2(hz / 440.f) : -1.f; }
NoteName freqToNote(float hz) {
  NoteName r; if (hz <= 0) return r;
  float m = freqToMidi(hz);
  r.midi = (int)std::lround(m); r.cents = (m - r.midi) * 100.f; r.name = midiName(r.midi);
  return r;
}

// ---------------- YIN ----------------
YinPitch::YinPitch(int sr, int W, float minHz, float maxHz, float thr) : sr_(sr), W_(W), thr_(thr) {
  tauMin_ = std::max(2, (int)(sr / maxHz));
  tauMax_ = std::min(W / 2, (int)(sr / minHz) + 1);
  d_.assign(tauMax_ + 1, 0.f);
}
PitchResult YinPitch::process(const float* x) {
  PitchResult r;
  const int I = W_ - tauMax_;
  double energy = 0; for (int j = 0; j < W_; ++j) energy += x[j] * x[j];
  if (energy / W_ < 1e-7) return r;
  d_[0] = 1.f; double run = 0;
  for (int tau = 1; tau <= tauMax_; ++tau) {
    float s = 0;  // float accumulation vectorises well; I <= a few thousand
    for (int j = 0; j < I; ++j) { float df = x[j] - x[j + tau]; s += df * df; }
    run += s;
    d_[tau] = run > 0 ? (float)(s * tau / run) : 1.f;
  }
  int best = -1;
  for (int tau = tauMin_; tau < tauMax_; ++tau)
    if (d_[tau] < thr_) { while (tau + 1 < tauMax_ && d_[tau + 1] < d_[tau]) ++tau; best = tau; break; }
  bool voiced = best > 0;
  if (!voiced) { best = tauMin_; for (int t = tauMin_; t < tauMax_; ++t) if (d_[t] < d_[best]) best = t; }
  if (subharmonicThr > 0) {
    for (int k = 3; k >= 2; --k) {  // try the shortest period (highest pitch) first
      int c = (int)std::lround((double)best / k);
      if (c - 2 < tauMin_) continue;
      int m = c; for (int t = c - 2; t <= c + 2 && t < tauMax_; ++t) if (d_[t] < d_[m]) m = t;
      if (d_[m] < subharmonicThr && d_[m] <= d_[best] + subharmonicMargin && d_[m] <= d_[m - 1] && d_[m] <= d_[m + 1]) { best = m; voiced = true; break; }
    }
  }
  float t = (float)best;
  if (best > 1 && best < tauMax_) {
    float a = d_[best - 1], b = d_[best], c = d_[best + 1], den = a - 2 * b + c;
    if (std::fabs(den) > 1e-9f) t += 0.5f * (a - c) / den;
  }
  r.freqHz = sr_ / t; r.confidence = std::max(0.f, 1.f - d_[best]); r.voiced = voiced || r.confidence > voicedConfidence;
  return r;
}

float pitchAfterOnset(YinPitch& yin, const float* x, long long n, long long s0, long long s1, int sr,
                      float* confOut, int frames, float skipSec) {
  float f0s[16]; int nf = 0; float conf = 0; const int W = yin.windowSize();
  frames = std::min(frames, 16);
  for (int j = 0; j < frames; ++j) {
    long long st = s0 + (long long)(skipSec * sr) + (long long)j * W / 2;
    if (st + W > s1 || st + W > n) break;
    PitchResult p = yin.process(x + st);
    if (p.voiced) { f0s[nf++] = p.freqHz; conf += p.confidence; }
  }
  if (confOut) *confOut = nf ? conf / nf : 0.f;
  if (!nf) return 0.f;
  std::sort(f0s, f0s + nf);
  return f0s[nf / 2];
}

// ---------------- Onsets ----------------
OnsetDetector::OnsetDetector(int sr, int N, int hop, float delta, float minGap)
    : sr_(sr), N_(N), hop_(hop), delta_(delta), spec_(N) {
  minGapFrames_ = std::max(1, (int)(minGap * sr / hop));
  buf_.assign(N, 0.f); prevMag_.assign(N / 2 + 1, 0.f); logRing_.assign(4 * (N / 2 + 1), 0.f); fluxHist_.assign(16, 0.f); scratch_.assign(16, 0.f);
}
bool OnsetDetector::processHop(const float* in, long long& onsetSample) {
  std::move(buf_.begin() + hop_, buf_.end(), buf_.begin());
  std::copy(in, in + hop_, buf_.end() - hop_);
  // Raw (un-normalised) magnitudes keep the original log1p(100|X|) compression scale.
  const std::vector<float>& mag = spec_.process(buf_.data());
  const float g = N_ * 0.25f;  // undo 2/sum(hann) normalisation (sum(hann) ~= N/2)
  const int B = (int)mag.size(), lag = std::min(3, std::max(1, fluxLag)), w = std::min(3, std::max(0, maxFilterBins));
  float* cur = &logRing_[(frame_ % 4) * B];
  const float* ref = &logRing_[((frame_ + 4 - lag) % 4) * B];
  const int kMax = std::min(B - 1, (int)(fluxMaxHz * N_ / sr_));
  for (int k = 0; k < B; ++k) cur[k] = std::log1p(100.f * mag[k] * g);
  double flux = 0;
  for (int k = 1; k <= kMax; ++k) {
    float r = ref[k];
    for (int j = std::max(0, k - w); j <= std::min(B - 1, k + w); ++j) r = std::max(r, ref[j]);
    float d = cur[k] - r; if (d > 0) flux += d;
  }
  flux /= B;
  const int H = (int)fluxHist_.size();
  fluxHist_[frame_ % H] = (float)flux;
  double e = 0; for (int i = N_ - hop_; i < N_; ++i) e += buf_[i] * buf_[i];
  float rms = (float)std::sqrt(e / hop_);
  bool onset = false;
  if (frame_ >= std::max(N_ / hop_ + 2, (int)(0.1 * sr_ / hop_)) && rms > rmsGate) {  // warm-up + silence gate
    float cur = fluxHist_[frame_ % H], c = fluxHist_[(frame_ - 1) % H], p = fluxHist_[(frame_ - 2) % H];
    std::copy(fluxHist_.begin(), fluxHist_.end(), scratch_.begin());
    std::nth_element(scratch_.begin(), scratch_.begin() + H / 2, scratch_.end());
    float thr = scratch_[H / 2] * thrMult + thrAdd; (void)delta_;
    if (c > p && c >= cur && c > thr && (frame_ - 1) - lastOnset_ >= minGapFrames_) {
      onset = true; lastOnset_ = frame_ - 1;
      onsetSample = std::max(0LL, (long long)(frame_) * hop_ - N_ / 4 + (long long)(timeOffsetSec * sr_));
    }
  }
  ++frame_;
  return onset;
}

// ---------------- Chroma / chord verification ----------------
void chromaFromSpectrum(const std::vector<float>& mag, int sr, int n, float fmin, float fmax, float ch[12]) {
  std::fill(ch, ch + 12, 0.f);
  float mx = 0; for (float v : mag) mx = std::max(mx, v);
  if (mx <= 0) return;
  for (size_t k = 2; k + 1 < mag.size(); ++k) {
    float f = (float)k * sr / n;
    if (f < fmin || f > fmax) continue;
    if (mag[k] < mag[k - 1] || mag[k] < mag[k + 1] || mag[k] < 0.02f * mx) continue;
    float a = mag[k - 1], b = mag[k], c = mag[k + 1], den = a - 2 * b + c;
    float dk = std::fabs(den) > 1e-12f ? 0.5f * (a - c) / den : 0.f;
    float m = freqToMidi((k + dk) * sr / n);
    int pc = ((int)std::lround(m) % 12 + 12) % 12;
    ch[pc] += std::sqrt(b);
  }
  float norm = 0; for (int i = 0; i < 12; ++i) norm += ch[i] * ch[i];
  norm = std::sqrt(norm); if (norm > 0) for (int i = 0; i < 12; ++i) ch[i] /= norm;
}

static int pow2Ceil(int v) { int n = 1; while (n < v) n <<= 1; return n; }
ChordVerifier::ChordVerifier(int sr, int maxLen)
    : sr_(sr), maxLen_(std::min(maxLen, 32768)), spec_(pow2Ceil(std::min(maxLen, 32768))),
      post_(spec_.size() / 2 + 1), raw_(spec_.size() / 2 + 1), pre_(spec_.size() / 2 + 1), diff_(spec_.size() / 2 + 1),
      scratch_(spec_.size() / 2 + 1) {
  for (int g = 1; g <= 64; ++g) semis_[g] = 12.f * std::log2((float)g);
  // third-octave bands from 60 Hz up to 6 kHz for the noise floor
  float lo = 60.f; nBands_ = 0;
  while (nBands_ < kBands && lo < 6000.f) { bandLo_[nBands_++] = lo; lo *= 1.2599f; }
}

void ChordVerifier::setNeighbours(const int* midi, int n) {
  std::fill(neighbourPc_, neighbourPc_ + 12, false); haveNeighbours_ = n > 0;
  for (int i = 0; i < n && i < 64; ++i) neighbourPc_[((midi[i] % 12) + 12) % 12] = true;
}

float ChordVerifier::thresholdFor(float snrDb) const {
  float d = std::min(40.f, std::max(0.f, refSnrDb - snrDb));
  return baseThreshold + thresholdSlope * d;
}

void ChordVerifier::computeFloor(const std::vector<float>& mag) {
  const int n = spec_.size();
  for (int b = 0; b < nBands_; ++b) {
    int k0 = std::max(1, (int)(bandLo_[b] * n / sr_)), k1 = std::min((int)mag.size() - 1, (int)(bandLo_[b] * 1.2599f * n / sr_));
    int cnt = 0; for (int k = k0; k <= k1; ++k) scratch_[cnt++] = mag[k];
    if (cnt == 0) { bandFloor_[b] = b ? bandFloor_[b - 1] : 1e-9f; continue; }
    // median of the band = noise floor (partials occupy few bins); robust to a couple of peaks
    std::nth_element(scratch_.begin(), scratch_.begin() + cnt / 2, scratch_.begin() + cnt);
    bandFloor_[b] = std::max(1e-12f, scratch_[cnt / 2]);
  }
}
float ChordVerifier::floorAt(float hz) const {
  int b = 0; while (b + 1 < nBands_ && bandLo_[b + 1] <= hz) ++b;
  return bandFloor_[b];
}
// Salience of `midi`: max over partials h=1..4 that are not within 50 cents of any harmonic (1..10) of the
// `others` notes, of 20*log10(peak / local floor). Returns NAN if no partial is harmonic-unique.
float ChordVerifier::salience(const std::vector<float>& mag, int midi, const int* others, int nOthers, int combine) const {
  // Partials h=1..4 (<= 5 kHz) of `midi` that are not within 50 cents of ANY harmonic (<= 5.2 kHz) of the
  // `others` notes; salience = max of 20*log10(peak / local floor). NAN if no partial is harmonic-unique.
  const float* semis = semis_;
  const int n = spec_.size(); float best = NAN, second = NAN, sum = 0; int cnt = 0;
  const float f0 = 440.f * std::pow(2.f, (midi - 69) / 12.f);
  for (int h = 1; h <= 4; ++h) {
    float f = f0 * h; if (f > strictMaxHz) break;
    float mh = midi + semis[h]; bool unique = true;
    for (int i = 0; i < nOthers && unique; ++i) {
      float fr = 440.f * std::pow(2.f, (others[i] - 69) / 12.f);
      int gmax = std::min(64, (int)(5200.f / fr));
      for (int g = 1; g <= gmax && unique; ++g)
        if (std::fabs(mh - (others[i] + semis[g])) < 0.5f) unique = false;
    }
    if (!unique) continue;
    int kc = (int)std::lround(f * n / sr_), dk = std::max(1, (int)std::ceil(f * 0.0145f * n / sr_));  // +-25 cents
    float pk = 0; for (int k = std::max(1, kc - dk); k <= std::min((int)mag.size() - 1, kc + dk); ++k) pk = std::max(pk, mag[k]);
    float s = 20.f * std::log10(std::max(pk, 1e-12f) / floorAt(f));
    sum += s; ++cnt;
    if (!(best >= s)) { second = best; best = s; } else if (!(second >= s)) second = s;
  }
  if (combine == 1) return cnt ? sum / cnt : NAN;
  if (combine == 2) return cnt >= 2 ? second : (cnt == 1 ? best - 6.f : NAN);  // single partial: discounted
  return best;
}

ChordMatch ChordVerifier::verify(const float* x, int len, const int* exp, int nExp, const float* pre, int preLen) {
  ChordMatch r; if (nExp <= 0 || len <= 0) return r;
  len = std::min(len, maxLen_);
  const bool havePre = pre && preLen > len / 8 && (preSubtract > 0 || (strict && strictPreSubtract > 0));
  if (havePre) {  // pre-onset context first, so spec_.mag() ends up holding the post-onset spectrum (2 FFTs, not 3)
    const std::vector<float>& pm = spec_.process(pre, std::min(preLen, maxLen_));
    std::copy(pm.begin(), pm.end(), pre_.begin());
  }
  const std::vector<float>& m = spec_.process(x, len);
  std::copy(m.begin(), m.end(), raw_.begin());
  std::copy(m.begin(), m.end(), post_.begin());
  std::copy(m.begin(), m.end(), diff_.begin());
  if (havePre) {  // onset-local spectral difference: remove still-ringing energy
    if (preSubtract > 0) for (size_t k = 0; k < post_.size(); ++k) post_[k] = std::max(0.f, raw_[k] - preSubtract * pre_[k]);
    if (strict && strictPreSubtract > 0) for (size_t k = 0; k < diff_.size(); ++k) diff_[k] = std::max(0.f, raw_[k] - strictPreSubtract * pre_[k]);
  }
  // Chroma (cosine/coverage) uses the raw spectrum so notes shared with the previous chord,
  // which are re-plucked, still count. The ringing-suppressed spectrum post_ is used for the
  // unexplained-peak test, where leftover notes of the previous chord would cause false penalties.
  float* ch = r.chroma;
  chromaFromSpectrum(spec_.mag(), sr_, spec_.size(), 70.f, 2000.f, ch);
  float tpl[12] = {}; bool inSet[12] = {};
  for (int i = 0; i < nExp; ++i) {
    int mi = exp[i]; inSet[mi % 12] = true;
    for (int h = 1; h <= 8; ++h) {
      if (h == 7) continue;  // 7th harmonic ~31 cents flat
      int semis = (int)std::lround(12.0 * std::log2((double)h));
      tpl[(mi + semis) % 12] += 1.f / std::sqrt((float)h);
    }
  }
  float tn = 0; for (float v : tpl) tn += v * v; tn = std::sqrt(tn);
  float cos = 0, inE = 0, totE = 0, mx = 0;
  for (int i = 0; i < 12; ++i) mx = std::max(mx, ch[i]);
  for (int i = 0; i < 12; ++i) { cos += ch[i] * tpl[i] / tn; totE += ch[i] * ch[i]; if (inSet[i]) inE += ch[i] * ch[i]; }
  // Frequency-specific explanation test: since the score tells us exactly which notes should
  // sound, every clear spectral peak should sit on a harmonic (1..10) of some expected note.
  // (1..12, peaks <= 1.2 kHz) Tolerance widens with h to allow string inharmonicity. Peaks that fit no expected harmonic
  // are evidence of wrong notes (or a still-ringing previous chord, if pre-subtraction is off).
  {
    const int n = spec_.size(); float pmax = 0;
    for (size_t k = 1; k < post_.size(); ++k) pmax = std::max(pmax, post_[k]);
    double tot = 0, bad = 0;
    const float unexplainedMaxHz = 1200.f, tolPerH = 3.f; const int maxH = 12;
    float lowest = 1e9f; for (int i = 0; i < nExp; ++i) lowest = std::min(lowest, (float)exp[i]);
    for (size_t k = 2; k + 1 < post_.size() && pmax > 0; ++k) {
      float f = (float)k * sr_ / n;
      if (f < 70.f || f > unexplainedMaxHz) continue;
      float b = post_[k];
      if (b < post_[k - 1] || b < post_[k + 1] || b < peakRelThr * pmax) continue;
      float a = post_[k - 1], c = post_[k + 1], den = a - 2 * b + c;
      float fp = (k + (std::fabs(den) > 1e-12f ? 0.5f * (a - c) / den : 0.f)) * sr_ / n;
      float mp = freqToMidi(fp); bool ok = false;
      if (ignoreBelowBass && mp < lowest - 0.5f) continue;
      for (int i = 0; i < nExp && !ok; ++i)
        for (int h = 1; h <= maxH && !ok; ++h) {
          float mh = exp[i] + 12.f * std::log2((float)h);
          if (std::fabs(mp - mh) * 100.f <= 30.f + tolPerH * h) ok = true;
        }
      float w = std::pow(b, unexplWeightExp); tot += w; if (!ok) bad += w;
    }
    r.unexplained = tot > 0 ? (float)(bad / tot) : 0.f;
  }
  int pcs = 0, present = 0;
  for (int i = 0; i < 12; ++i) if (inSet[i]) { ++pcs; if (mx > 0 && ch[i] >= coverageRelThr * mx) ++present; }
  r.cosine = std::max(0.f, cos);
  r.coverage = pcs ? (float)present / pcs : 0; r.purity = totE > 0 ? inE / totE : 0;
  r.score = r.cosine * r.coverage;
  if (usePenalty) r.score *= std::max(0.f, 1.f - unexplainedPenalty * r.unexplained);
  r.generalScore = r.score;

  // ---- noise floor + segment SNR ----
  computeFloor(raw_);
  {
    const int n = spec_.size(); float pk = 0, fpk = 100.f;
    for (int k = (int)(70.f * n / sr_); k <= (int)(2000.f * n / sr_) && k < (int)raw_.size(); ++k) if (raw_[k] > pk) { pk = raw_[k]; fpk = (float)k * sr_ / n; }
    r.snrDb = 20.f * std::log10(std::max(pk, 1e-12f) / floorAt(fpk));
  }
  // ---- strict note-level evidence ----
  if (strict) {
    int lowest = 1000; for (int i = 0; i < nExp; ++i) lowest = std::min(lowest, exp[i]);
    // expected pitch classes: present if any expected note of that class has a strong unique partial
    int others[64]; float miss = 99.f; int weakest = -1;
    for (int pc = 0; pc < 12; ++pc) {
      if (!inSet[pc]) continue;
      int no = 0; for (int i = 0; i < nExp && no < 64; ++i) if (exp[i] % 12 != pc) others[no++] = exp[i];
      float best = NAN;
      for (int i = 0; i < nExp; ++i) if (exp[i] % 12 == pc) { float s = salience(raw_, exp[i], others, no, missCombine); if (!(best >= s)) best = s; }
      if (best == best && best < miss) { miss = best; weakest = pc; }
    }
    // non-expected pitch classes (neighbour candidates) between the bass note and E6
    float extra = -99.f; int extraPc = -1;
    for (int pc = 0; pc < 12; ++pc) r.pcSalDb[pc] = -99.f;
    for (int pc = 0; pc < 12; ++pc) {
      if (inSet[pc] || (haveNeighbours_ && !neighbourPc_[pc])) continue;
      for (int m = lowest + 1; m <= strictMaxMidi; ++m) {
        if (m % 12 != pc) continue;
        float s = salience(diff_, m, exp, nExp, extraCombine);
        if (s == s && s > r.pcSalDb[pc]) r.pcSalDb[pc] = s;
        if (s == s && s > extra) { extra = s; extraPc = pc; }
      }
    }
    r.missSalDb = miss; r.weakestPc = weakest; r.extraSalDb = extra; r.extraPc = extraPc;
    auto logistic = [](float z) { return 1.f / (1.f + std::exp(-z)); };
    const float shift = strictSnrAdapt * (std::min(r.snrDb, strictSnrMaxDb) - strictSnrRefDb);
    float pAll = miss >= 98.f ? 1.f : logistic((miss - (missCenterDb + shift)) / missWidthDb);
    float pExtra = extra <= -98.f ? 0.f : logistic((extra - (extraCenterDb + shift)) / extraWidthDb);
    r.score *= pAll * (1.f - pExtra);
  }
  r.threshold = strict ? thresholdFor(r.snrDb) : generalThreshold;
  r.match = r.score >= r.threshold;
  return r;
}
}  // namespace gdsp
