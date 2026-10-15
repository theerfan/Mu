#include "gdsp.h"
#include <algorithm>
#include <cmath>
namespace gdsp {
static const float kPi = 3.14159265358979f;

void fft(std::vector<std::complex<float>>& a) {
  const size_t n = a.size();
  for (size_t i = 1, j = 0; i < n; ++i) {
    size_t bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) std::swap(a[i], a[j]);
  }
  for (size_t len = 2; len <= n; len <<= 1) {
    float ang = -2 * kPi / (float)len;
    std::complex<float> wl(std::cos(ang), std::sin(ang));
    for (size_t i = 0; i < n; i += len) {
      std::complex<float> w(1, 0);
      for (size_t k = 0; k < len / 2; ++k) {
        auto u = a[i + k], v = a[i + k + len / 2] * w;
        a[i + k] = u + v; a[i + k + len / 2] = u - v; w *= wl;
      }
    }
  }
}

void magSpectrum(const float* x, int n, std::vector<float>& mag) {
  std::vector<std::complex<float>> a(n);
  for (int i = 0; i < n; ++i) a[i] = x[i] * (0.5f - 0.5f * std::cos(2 * kPi * i / (n - 1)));
  fft(a);
  mag.resize(n / 2 + 1);
  for (int i = 0; i <= n / 2; ++i) mag[i] = std::abs(a[i]);
}

std::string midiName(int m) {
  static const char* nm[12] = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
  if (m < 0) return "-";
  return std::string(nm[m % 12]) + std::to_string(m / 12 - 1);
}
NoteName freqToNote(float hz) {
  NoteName r; if (hz <= 0) return r;
  float m = 69.f + 12.f * std::log2(hz / 440.f);
  r.midi = (int)std::lround(m); r.cents = (m - r.midi) * 100.f; r.name = midiName(r.midi);
  return r;
}

// ---------------- YIN ----------------
YinPitch::YinPitch(int sr, int W, float minHz, float maxHz, float thr)
    : sr_(sr), W_(W), thr_(thr) {
  tauMin_ = std::max(2, (int)(sr / maxHz));
  tauMax_ = std::min(W / 2, (int)(sr / minHz) + 1);
  d_.assign(tauMax_ + 1, 0.f);
}
PitchResult YinPitch::process(const float* x) {
  PitchResult r;
  const int I = W_ - tauMax_;  // integration length
  double energy = 0; for (int j = 0; j < W_; ++j) energy += x[j] * x[j];
  if (energy / W_ < 1e-7) return r;
  d_[0] = 1.f; double run = 0;
  for (int tau = 1; tau <= tauMax_; ++tau) {
    double s = 0;
    for (int j = 0; j < I; ++j) { float df = x[j] - x[j + tau]; s += df * df; }
    run += s;
    d_[tau] = run > 0 ? (float)(s * tau / run) : 1.f;  // cumulative mean normalised difference
  }
  int best = -1;
  for (int tau = tauMin_; tau < tauMax_; ++tau) {
    if (d_[tau] < thr_) { while (tau + 1 < tauMax_ && d_[tau + 1] < d_[tau]) ++tau; best = tau; break; }
  }
  bool voiced = best > 0;
  if (!voiced) { best = tauMin_; for (int t = tauMin_; t < tauMax_; ++t) if (d_[t] < d_[best]) best = t; }
  float t = (float)best;
  if (best > 1 && best < tauMax_) {  // parabolic interpolation
    float a = d_[best - 1], b = d_[best], c = d_[best + 1], den = a - 2 * b + c;
    if (std::fabs(den) > 1e-9f) t += 0.5f * (a - c) / den;
  }
  r.freqHz = sr_ / t; r.confidence = std::max(0.f, 1.f - d_[best]); r.voiced = voiced || r.confidence > 0.7f;
  return r;
}

// ---------------- Onsets ----------------
OnsetDetector::OnsetDetector(int sr, int N, int hop, float delta, float minGap)
    : sr_(sr), N_(N), hop_(hop), delta_(delta) {
  minGapFrames_ = std::max(1, (int)(minGap * sr / hop));
  buf_.assign(N, 0.f); prevMag_.assign(N / 2 + 1, 0.f); fluxHist_.assign(16, 0.f);
}
bool OnsetDetector::processHop(const float* in, long long& onsetSample) {
  std::move(buf_.begin() + hop_, buf_.end(), buf_.begin());
  std::copy(in, in + hop_, buf_.end() - hop_);
  magSpectrum(buf_.data(), N_, mag_);
  double flux = 0;
  for (size_t k = 1; k < mag_.size(); ++k) {
    float v = std::log1p(100.f * mag_[k]);
    float d = v - prevMag_[k]; if (d > 0) flux += d;
    prevMag_[k] = v;
  }
  flux /= mag_.size();
  // history ring: newest at index frame_%H
  const int H = (int)fluxHist_.size();
  fluxHist_[frame_ % H] = (float)flux;
  bool onset = false;
  double e = 0; for (int i = N_ - hop_; i < N_; ++i) e += buf_[i] * buf_[i];
  float rms = (float)std::sqrt(e / hop_);
  if (frame_ >= std::max(N_ / hop_ + 2, (int)(0.1 * sr_ / hop_)) && rms > 0.005f) {  // warm-up + silence gate
    float cur = fluxHist_[frame_ % H], c = fluxHist_[(frame_ - 1) % H], p = fluxHist_[(frame_ - 2) % H];
    std::vector<float> w(fluxHist_); std::nth_element(w.begin(), w.begin() + H / 2, w.end());
    float thr = w[H / 2] * 1.5f + delta_ * 0.1f;
    if (c > p && c >= cur && c > thr && (frame_ - 1) - lastOnset_ >= minGapFrames_) {
      onset = true; lastOnset_ = frame_ - 1;
      // frame f ends at sample (f+1)*hop; attack typically lies ~N/4 before the end of the
      // first frame showing the flux peak.
      onsetSample = std::max(0LL, (long long)(frame_) * hop_ - N_ / 4);
    }
  }
  ++frame_;
  return onset;
}

// ---------------- Chroma / chord verification ----------------
void chromaFromSpectrum(const std::vector<float>& mag, int sr, int n, float fmin, float fmax, float ch[12]) {
  std::fill(ch, ch + 12, 0.f);
  float mx = *std::max_element(mag.begin(), mag.end());
  for (size_t k = 2; k + 1 < mag.size(); ++k) {
    float f = (float)k * sr / n;
    if (f < fmin || f > fmax) continue;
    // keep only spectral peaks well above the noise floor
    if (mag[k] < mag[k - 1] || mag[k] < mag[k + 1] || mag[k] < 0.02f * mx) continue;
    float a = mag[k - 1], b = mag[k], c = mag[k + 1], den = a - 2 * b + c;
    float dk = std::fabs(den) > 1e-12f ? 0.5f * (a - c) / den : 0.f;
    float fp = (k + dk) * sr / n;
    float m = 69.f + 12.f * std::log2(fp / 440.f);
    int pc = ((int)std::lround(m) % 12 + 12) % 12;
    ch[pc] += std::sqrt(b);
  }
  float norm = 0; for (int i = 0; i < 12; ++i) norm += ch[i] * ch[i];
  norm = std::sqrt(norm); if (norm > 0) for (int i = 0; i < 12; ++i) ch[i] /= norm;
}

ChordMatch verifyChord(const float* x, int len, int sr, const std::vector<int>& exp) {
  ChordMatch r; if (exp.empty() || len <= 0) return r;
  int n = 1; while (n * 2 <= len && n < 32768) n *= 2;
  std::vector<float> mag; magSpectrum(x, n, mag);
  float ch[12]; chromaFromSpectrum(mag, sr, n, 70.f, 2000.f, ch);
  // Expected template includes the first harmonics of every expected note (weighted 1/h),
  // since real strings put energy at 2f (octave), 3f (fifth), 4f, 5f (major third).
  float tpl[12] = {}; bool inSet[12] = {};
  for (int m : exp) {
    inSet[m % 12] = true;
    for (int h = 1; h <= 8; ++h) {
      int semis = (int)std::lround(12.0 * std::log2((double)h));
      if (h == 7) continue;  // 7th harmonic is ~31 cents flat; skip
      tpl[(m + semis) % 12] += 1.f / std::sqrt((float)h);
    }
  }
  float tn = 0; for (float v : tpl) tn += v * v; tn = std::sqrt(tn);
  float cos = 0, inE = 0, totE = 0, mx = *std::max_element(ch, ch + 12);
  for (int i = 0; i < 12; ++i) {
    cos += ch[i] * tpl[i] / tn; totE += ch[i] * ch[i]; if (inSet[i]) inE += ch[i] * ch[i];
    r.perNote[i] = ch[i];
  }
  int pcs = 0, present = 0;
  for (int i = 0; i < 12; ++i) if (inSet[i]) { ++pcs; if (mx > 0 && ch[i] >= 0.25f * mx) ++present; }
  r.coverage = pcs ? (float)present / pcs : 0; r.purity = totE > 0 ? inE / totE : 0;
  r.score = std::max(0.f, cos) * r.coverage;
  return r;
}
}  // namespace gdsp
