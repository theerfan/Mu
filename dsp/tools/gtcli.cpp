// gtcli: detect onsets + pitch in a WAV; optional chord verification.
// usage: gtcli file.wav [--expect C3,E3,G3 | --expect 48,52,55] [--json]
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <sstream>
#include "gdsp.h"
#include "wav.h"
using namespace gdsp;

static int parseNote(const std::string& s) {
  if (s.empty()) return -1;
  if (std::isdigit((unsigned char)s[0])) return std::stoi(s);
  static const int base[7] = {9, 11, 0, 2, 4, 5, 7};  // A..G
  char c = (char)std::toupper((unsigned char)s[0]); if (c < 'A' || c > 'G') return -1;
  int pc = base[c - 'A']; size_t i = 1;
  if (i < s.size() && s[i] == '#') { ++pc; ++i; } else if (i < s.size() && s[i] == 'b') { --pc; ++i; }
  if (i >= s.size()) return -1;
  return (std::stoi(s.substr(i)) + 1) * 12 + pc;
}

int main(int argc, char** argv) {
  if (argc < 2) { std::fprintf(stderr, "usage: %s file.wav [--expect C3,E3,G3] [--json]\n", argv[0]); return 2; }
  std::vector<int> expect; bool json = false, noPenalty = false, noDiff = false;
  for (int i = 2; i < argc; ++i) {
    if (!std::strcmp(argv[i], "--expect") && i + 1 < argc) {
      std::stringstream ss(argv[++i]); std::string tok;
      while (std::getline(ss, tok, ',')) { int m = parseNote(tok); if (m < 0) { std::fprintf(stderr, "bad note %s\n", tok.c_str()); return 2; } expect.push_back(m); }
    } else if (!std::strcmp(argv[i], "--json")) json = true;
    else if (!std::strcmp(argv[i], "--no-penalty")) noPenalty = true;  // A/B: disable unexplained-bin penalty
    else if (!std::strcmp(argv[i], "--no-diff")) noDiff = true;        // A/B: disable pre-onset spectral subtraction
  }
  Audio a; std::string err;
  if (!readWav(argv[1], a, &err)) { std::fprintf(stderr, "error: %s\n", err.c_str()); return 1; }
  const int sr = a.sampleRate;
  // Scale frame sizes with sample rate so behaviour is rate-independent.
  auto pow2 = [](double v) { int n = 1; while (n < v) n <<= 1; return n; };
  const int onsetN = pow2(sr * 0.023), hop = onsetN / 4, yinW = pow2(sr * 0.045);
  OnsetDetector od(sr, onsetN, hop);
  YinPitch yin(sr, yinW, 70.f, 1400.f, 0.15f);
  const float kMatch = 0.6f;  // decision threshold (midpoint of synthetic right/wrong margins)
  ChordVerifier cv(sr, (int)(0.8 * sr));
  cv.usePenalty = !noPenalty;
  std::vector<long long> onsets; long long pos;
  for (size_t i = 0; i + hop <= a.mono.size(); i += hop) if (od.processHop(&a.mono[i], pos)) onsets.push_back(pos);
  if (!json) std::printf("file=%s rate=%d dur=%.2fs onsets=%zu\n", argv[1], sr, a.mono.size() / (double)sr, onsets.size());
  else std::printf("[\n");
  for (size_t k = 0; k < onsets.size(); ++k) {
    long long s0 = onsets[k], s1 = k + 1 < onsets.size() ? onsets[k + 1] : (long long)a.mono.size();
    // pitch: median of 3 YIN frames after the attack transient (~40 ms in)
    std::vector<float> f0s; float conf = 0;
    for (int j = 0; j < 3; ++j) {
      long long st = s0 + (long long)(0.04 * sr) + j * yinW / 2;
      if (st + yinW > s1 || st + yinW > (long long)a.mono.size()) break;
      PitchResult p = yin.process(&a.mono[st]);
      if (p.voiced) { f0s.push_back(p.freqHz); conf += p.confidence; }
    }
    float f0 = 0;
    if (!f0s.empty()) { std::sort(f0s.begin(), f0s.end()); f0 = f0s[f0s.size() / 2]; conf /= f0s.size(); }
    NoteName nn = freqToNote(f0);
    double t = s0 / (double)sr;
    std::string chordStr;
    if (!expect.empty()) {
      long long cs = s0 + (long long)(0.06 * sr), ce = std::min(s1, s0 + (long long)(0.8 * sr));
      if (ce > cs) {
        // pre-onset context: up to 300 ms ending 20 ms before the onset (keeps the new attack out)
        long long pe = s0 - (long long)(0.02 * sr), ps = std::max(0LL, pe - (long long)(0.3 * sr));
        const float* pre = (!noDiff && pe - ps > 0) ? &a.mono[ps] : nullptr;
        ChordMatch m = cv.verify(&a.mono[cs], (int)(ce - cs), expect.data(), (int)expect.size(), pre, (int)(pe - ps));
        if (std::getenv("GDSP_DEBUG")) { std::fprintf(stderr, "chroma:"); for (float c : m.chroma) std::fprintf(stderr, " %.2f", c); std::fprintf(stderr, "\n"); }
        char b[200]; std::snprintf(b, sizeof b, json ? ", \"chord_score\": %.3f, \"coverage\": %.2f, \"purity\": %.2f, \"unexplained\": %.2f, \"match\": %s"
                                                      : "  chord_score=%.3f coverage=%.2f purity=%.2f unexpl=%.2f %s",
                                   m.score, m.coverage, m.purity, m.unexplained, json ? (m.score >= kMatch ? "true" : "false") : (m.score >= kMatch ? "MATCH" : "NO-MATCH"));
        chordStr = b;
      }
    }
    if (json) std::printf("  {\"t\": %.3f, \"f0\": %.2f, \"note\": \"%s\", \"cents\": %.1f, \"conf\": %.2f%s}%s\n", t, f0, nn.name.c_str(), nn.cents, conf, chordStr.c_str(), k + 1 < onsets.size() ? "," : "");
    else if (f0 > 0) std::printf("onset t=%7.3fs  f0=%8.2fHz  note=%-4s cents=%+6.1f conf=%.2f%s\n", t, f0, nn.name.c_str(), nn.cents, conf, chordStr.c_str());
    else std::printf("onset t=%7.3fs  (unpitched/polyphonic)%s\n", t, chordStr.c_str());
  }
  if (json) std::printf("]\n");
  return 0;
}
