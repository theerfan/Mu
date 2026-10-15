// Synthesise Karplus-Strong test material with additive noise.
// usage: gen_test_wavs outdir
#include <cmath>
#include <cstdio>
#include <random>
#include <string>
#include <vector>
#include "wav.h"
using namespace gdsp;
static std::mt19937 rng(1234);

// Karplus-Strong with first-order allpass for fractional delay (accurate tuning).
static void pluck(std::vector<float>& out, int sr, double t0, int midi, float amp, double dur = 2.5) {
  double f = 440.0 * std::pow(2.0, (midi - 69) / 12.0);
  double P = sr / f - 0.5;                 // averaging filter adds 0.5 sample delay
  int N = (int)std::floor(P - 0.1); double fr = P - N;
  double C = (1 - fr) / (1 + fr);          // allpass coefficient
  std::vector<float> line(N);
  std::uniform_real_distribution<float> u(-1, 1);
  float lp = 0;
  for (auto& v : line) { lp = 0.5f * lp + 0.5f * u(rng); v = lp; }  // slightly darker pluck
  size_t start = (size_t)(t0 * sr), len = (size_t)(dur * sr);
  if (out.size() < start + len) out.resize(start + len, 0.f);
  int idx = 0; float prev = 0, apX = 0, apY = 0; const float decay = 0.996f;
  for (size_t i = 0; i < len; ++i) {
    float cur = line[idx];
    float avg = decay * 0.5f * (cur + prev); prev = cur;
    float y = (float)(C * avg + apX - C * apY); apX = avg; apY = y;
    line[idx] = y; idx = (idx + 1) % N;
    out[start + i] += amp * cur;
  }
}
static void finish(std::vector<float>& x, float snrDb) {
  double p = 0; for (float v : x) p += v * v; p /= x.size();
  float peak = 0; for (float v : x) peak = std::max(peak, std::fabs(v));
  std::normal_distribution<float> n(0, (float)std::sqrt(p / std::pow(10, snrDb / 10)));
  for (auto& v : x) v += n(rng);
  for (auto& v : x) v *= 0.7f / peak;
}
static void strum(std::vector<float>& x, int sr, double t, const std::vector<int>& notes) {
  for (size_t i = 0; i < notes.size(); ++i) pluck(x, sr, t + 0.012 * i, notes[i], 0.5f);
}
int main(int argc, char** argv) {
  std::string d = argc > 1 ? argv[1] : ".";
  const std::vector<int> Cmaj = {48, 52, 55, 60, 64};      // open C: C3 E3 G3 C4 E4
  const std::vector<int> Gmaj = {43, 47, 50, 55, 59, 67};  // open G: G2 B2 D3 G3 B3 G4
  struct S { const char* name; int midi; } singles[] = {{"E2", 40}, {"A2", 45}, {"G3", 55}, {"E4", 64}};
  for (int sr : {44100, 48000, 22050}) {
    for (auto& s : singles) {
      std::vector<float> x(sr / 2, 0.f); pluck(x, sr, 0.25, s.midi, 0.8f); finish(x, 25);
      writeWav16(d + "/single_" + s.name + "_" + std::to_string(sr) + ".wav", x, sr);
    }
  }
  int sr = 44100;
  { std::vector<float> x; double t = 0.3; for (auto& s : singles) { pluck(x, sr, t, s.midi, 0.8f, 1.2); t += 0.8; } finish(x, 25);
    writeWav16(d + "/sequence_E2_A2_G3_E4.wav", x, sr); }
  { std::vector<float> x; strum(x, sr, 0.3, Cmaj); finish(x, 25); writeWav16(d + "/chord_Cmaj.wav", x, sr); }
  { std::vector<float> x; strum(x, sr, 0.3, Gmaj); finish(x, 25); writeWav16(d + "/chord_Gmaj.wav", x, sr); }
  { std::vector<float> x; strum(x, sr, 0.3, Cmaj); strum(x, sr, 2.3, Gmaj); finish(x, 25);
    // stereo 48k-style interleave test: duplicate channel with slight gain difference
    std::vector<float> st(x.size() * 2); for (size_t i = 0; i < x.size(); ++i) { st[2 * i] = x[i]; st[2 * i + 1] = 0.8f * x[i]; }
    writeWav16(d + "/chords_C_then_G_stereo.wav", st, sr, 2); }
  std::printf("wrote test wavs to %s\n", d.c_str());
}
