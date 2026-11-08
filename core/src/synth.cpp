#include "mu/synth.h"
#include <algorithm>
#include <cmath>

namespace mu {
namespace {
constexpr double kPi = 3.14159265358979323846;
double midiHz(double m) { return 440.0 * std::pow(2.0, (m - 69.0) / 12.0); }

struct Lcg {
  uint32_t s = 0x1234567u;
  float next() { s = s * 1664525u + 1013904223u; return (float)(s >> 8) / 8388608.f - 1.f; }  // [-1, 1)
};

// Karplus-Strong string: noise burst through a damped averaging delay line. `bright` 0..1 shapes the pick.
void ksString(float* out, long long len, long long start, int sr, double hz, double dur, float amp, float bright, float damp, Lcg& rng) {
  const int N = std::max(2, (int)std::lround(sr / hz));
  std::vector<float> line(N);
  float lp = 0;
  for (auto& v : line) { lp = bright * rng.next() + (1 - bright) * lp; v = lp; }
  const long long ring = (long long)((dur + 0.35) * sr);   // let it ring a little past its written length
  const long long fadeFrom = (long long)(dur * sr);
  float prev = 0; int idx = 0;
  for (long long i = 0; i < ring && start + i < len; ++i) {
    if (start + i < 0) { idx = (idx + 1) % N; continue; }
    const float cur = line[idx];
    line[idx] = damp * 0.5f * (cur + prev); prev = cur; idx = (idx + 1) % N;
    float env = i < fadeFrom ? 1.f : 1.f - (float)(i - fadeFrom) / (float)(ring - fadeFrom);
    out[start + i] += amp * env * cur;
  }
}

void keys(float* out, long long len, long long start, int sr, double hz, double dur, float amp) {
  const long long n = (long long)((dur + 0.3) * sr);
  for (long long i = 0; i < n && start + i < len; ++i) {
    if (start + i < 0) continue;
    const double t = (double)i / sr;
    const double env = std::min(1.0, t / 0.01) * std::exp(-t / std::max(0.3, dur)) * (t < dur ? 1.0 : std::max(0.0, 1.0 - (t - dur) / 0.3));
    const double ph = 2 * kPi * hz * t;
    out[start + i] += (float)(amp * env * (0.6 * std::sin(ph) + 0.25 * std::sin(2 * ph) + 0.1 * std::sin(3 * ph)));
  }
}

void drum(float* out, long long len, long long start, int sr, int key, float amp, Lcg& rng) {
  enum { Kick, Snare, HatClosed, HatOpen, Tom, Cymbal, Click } kind;
  if (key == 35 || key == 36) kind = Kick;
  else if (key == 38 || key == 40 || key == 37 || key == 39) kind = Snare;
  else if (key == 42 || key == 44) kind = HatClosed;
  else if (key == 46) kind = HatOpen;
  else if (key == 41 || key == 43 || key == 45 || key == 47 || key == 48 || key == 50) kind = Tom;
  else if (key == 49 || key == 51 || key == 52 || key == 53 || key == 55 || key == 57 || key == 59) kind = Cymbal;
  else kind = Click;
  const double len_s = kind == Kick ? 0.35 : kind == Snare ? 0.2 : kind == HatClosed ? 0.06 : kind == HatOpen ? 0.3
                     : kind == Tom ? 0.3 : kind == Cymbal ? 1.2 : 0.05;
  const long long n = (long long)(len_s * sr);
  double ph = 0; float hp = 0, prevN = 0;
  const double tomHz = 80 + (key - 41) * 12.0;
  for (long long i = 0; i < n && start + i < len; ++i) {
    const double t = (double)i / sr;
    const float nz = rng.next();
    const float hi = nz - prevN; prevN = nz; hp = 0.6f * hp + 0.4f * hi;   // crude high-pass noise for hats
    float s = 0;
    switch (kind) {
      case Kick: { const double f = 50 + 90 * std::exp(-t / 0.03); ph += 2 * kPi * f / sr; s = (float)(std::sin(ph) * std::exp(-t / 0.12)); break; }
      case Snare: { ph += 2 * kPi * 185.0 / sr; s = (float)(0.45 * std::sin(ph) * std::exp(-t / 0.05) + 0.6 * nz * std::exp(-t / 0.07)); break; }
      case HatClosed: s = (float)(0.5 * hp * std::exp(-t / 0.015)); break;
      case HatOpen: s = (float)(0.45 * hp * std::exp(-t / 0.09)); break;
      case Tom: { const double f = tomHz * (1 + 0.5 * std::exp(-t / 0.05)); ph += 2 * kPi * f / sr; s = (float)(std::sin(ph) * std::exp(-t / 0.12)); break; }
      case Cymbal: s = (float)(0.35 * hp * std::exp(-t / 0.4)); break;
      case Click: s = (float)(0.4 * nz * std::exp(-t / 0.01)); break;
    }
    if (start + i >= 0) out[start + i] += amp * s;
  }
}
}  // namespace

void renderNotes(const SynthNote* notes, int n, int sr, float* out, long long len) {
  Lcg rng;
  for (int k = 0; k < n; ++k) {
    const SynthNote& nt = notes[k];
    const long long start = (long long)std::llround(nt.t * sr);
    if (start >= len) continue;
    const double dur = std::clamp(nt.dur, 0.05, 4.0);
    const float amp = std::clamp(nt.vel, 0.f, 1.f);
    switch (nt.voice) {
      case Voice::Pluck: ksString(out, len, start, sr, midiHz(nt.midi), dur, 0.5f * amp, 0.85f, 0.996f, rng); break;
      case Voice::Bass: ksString(out, len, start, sr, midiHz(nt.midi), dur, 0.8f * amp, 0.35f, 0.998f, rng); break;
      case Voice::Drums: drum(out, len, start, sr, nt.midi, 0.7f * amp, rng); break;
      case Voice::Keys: keys(out, len, start, sr, midiHz(nt.midi), dur, 0.25f * amp); break;
    }
  }
}

std::vector<int16_t> toInt16(const std::vector<float>& x, float peak) {
  float m = 0;
  for (float v : x) m = std::max(m, std::fabs(v));
  const float g = m > 1e-6f ? peak / m : 0.f;
  std::vector<int16_t> out(x.size());
  for (size_t i = 0; i < x.size(); ++i) out[i] = (int16_t)std::lround(std::clamp(x[i] * g, -1.f, 1.f) * 32767.f);
  return out;
}

std::vector<float> resampleLinear(const float* in, long long n, double fromSr, double toSr) {
  if (n <= 0) return {};
  if (std::fabs(fromSr - toSr) < 0.5) return std::vector<float>(in, in + n);
  const double step = fromSr / toSr;
  const long long m = (long long)std::floor((n - 1) / step) + 1;
  std::vector<float> out((size_t)m);
  for (long long i = 0; i < m; ++i) {
    const double p = i * step; const long long a = (long long)p; const double f = p - a;
    out[(size_t)i] = (float)(in[a] * (1 - f) + (a + 1 < n ? in[a + 1] : in[a]) * f);
  }
  return out;
}

std::vector<float> timeStretch(const float* in, long long n, int sr, double speed) {
  if (n <= 0) return {};
  if (std::fabs(speed - 1.0) < 1e-3) return std::vector<float>(in, in + n);
  // WSOLA: Hann frames of 40 ms at 50 % overlap; each next input frame is searched (±10 ms) for the best match
  // with the natural continuation of the previous one, so periodic sound joins without phasing.
  const int N = std::max(256, sr / 25), hop = N / 2, tol = sr / 100;
  const long long outLen = (long long)(n / speed);
  std::vector<float> out((size_t)outLen + N, 0.f), win(N);
  for (int i = 0; i < N; ++i) win[i] = (float)(0.5 - 0.5 * std::cos(2 * kPi * i / N));
  long long prevIn = 0;  // where the previous frame was taken from
  for (long long k = 0;; ++k) {
    const long long outPos = k * hop;
    if (outPos >= outLen) break;
    const long long ideal = (long long)(outPos * speed);
    long long best = ideal;
    if (k > 0) {
      const long long nat = prevIn + hop;   // natural continuation of the previous frame
      double bestC = -1e30;
      for (long long d = -tol; d <= tol; d += 2) {
        const long long p = ideal + d;
        if (p < 0 || p + hop >= n || nat + hop >= n) continue;
        double c = 0;
        for (int i = 0; i < hop; i += 4) c += (double)in[p + i] * in[nat + i];
        if (c > bestC) { bestC = c; best = p; }
      }
    }
    best = std::clamp<long long>(best, 0, std::max<long long>(0, n - 1));
    for (int i = 0; i < N && outPos + i < (long long)out.size(); ++i) {
      const long long src = best + i;
      if (src >= n) break;
      out[(size_t)(outPos + i)] += in[src] * win[i];
    }
    prevIn = best;
  }
  out.resize((size_t)outLen);
  return out;
}

}  // namespace mu
