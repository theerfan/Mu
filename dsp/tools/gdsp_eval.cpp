// gdsp_eval: batch interface used by the Python evaluation harness (eval/).
// usage: gdsp_eval file.wav [--yin-thr F] [--yin-sub F] [--yin-vconf F] [--yin-margin F] [--onset-mult F] [--onset-add F] [--min-gap S] [--rms-gate F]
//                           [--flux-lag N] [--flux-maxfilt N] [--flux-maxhz F] [--onset-offset S]
//                           [--beta F] [--penalty F] [--no-diff] [--peak-thr F] [--weight-exp F]
//                           [--cov-thr F] [--ignore-below-bass 0|1]  < commands
// (any option not given uses the library default)
// commands (one per line):
//   O                      -> "O t1 t2 ..."        onset times (s) from the streaming detector
//   P t [end]              -> "P f0 conf"          pitch after an onset at t (seconds), not reading past end
//   C t end m1 m2 ... [| n1 n2 ...] -> "C score cos cov unexpl purity general missSalDb extraSalDb snrDb match"
//                          chord verification of [t+60ms, min(end,t+0.8s)); after '|' optional neighbour notes
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <sstream>
#include <string>
#include "gdsp.h"
#include "wav.h"
using namespace gdsp;

int main(int argc, char** argv) {
  if (argc < 2) { std::fprintf(stderr, "usage: gdsp_eval file.wav [opts] < cmds\n"); return 2; }
  std::vector<std::pair<std::string, float>> ov;  // overrides, applied on top of library defaults
  bool noDiff = false;
  for (int i = 2; i < argc; ++i) {
    if (!std::strcmp(argv[i], "--no-diff")) { noDiff = true; continue; }
    if (!std::strncmp(argv[i], "--", 2) && i + 1 < argc) { ov.emplace_back(argv[i] + 2, (float)std::atof(argv[i + 1])); ++i; }
  }
  auto get = [&](const char* k, float def) { for (auto& p : ov) if (p.first == k) return p.second; return def; };
  Audio a; std::string err;
  if (!readWav(argv[1], a, &err)) { std::fprintf(stderr, "error: %s\n", err.c_str()); return 1; }
  const int sr = a.sampleRate; const long long n = (long long)a.mono.size();
  auto pow2 = [](double v) { int k = 1; while (k < v) k <<= 1; return k; };
  const int onsetN = std::max(1024, pow2(sr * 0.023)), hop = onsetN / 4, yinW = pow2(sr * 0.045);
  YinPitch yin(sr, yinW, 70.f, 1400.f, get("yin-thr", 0.2f));
  yin.subharmonicThr = get("yin-sub", yin.subharmonicThr); yin.voicedConfidence = get("yin-vconf", yin.voicedConfidence);
  yin.subharmonicMargin = get("yin-margin", yin.subharmonicMargin);
  ChordVerifier cv(sr, (int)(0.8 * sr));
  cv.preSubtract = noDiff ? 0.f : get("beta", cv.preSubtract);
  cv.unexplainedPenalty = get("penalty", cv.unexplainedPenalty); cv.usePenalty = cv.unexplainedPenalty > 0;
  cv.peakRelThr = get("peak-thr", cv.peakRelThr); cv.unexplWeightExp = get("weight-exp", cv.unexplWeightExp);
  cv.coverageRelThr = get("cov-thr", cv.coverageRelThr); cv.ignoreBelowBass = get("ignore-below-bass", cv.ignoreBelowBass) != 0;
  cv.strict = get("strict", cv.strict) != 0;
  cv.missCenterDb = get("miss-center", cv.missCenterDb); cv.missWidthDb = get("miss-width", cv.missWidthDb);
  cv.extraCenterDb = get("extra-center", cv.extraCenterDb); cv.extraWidthDb = get("extra-width", cv.extraWidthDb);
  cv.strictPreSubtract = get("strict-beta", cv.strictPreSubtract);
  cv.strictMaxHz = get("strict-maxhz", cv.strictMaxHz); cv.strictMaxMidi = (int)get("strict-maxmidi", cv.strictMaxMidi);
  cv.strictSnrAdapt = get("snr-adapt", cv.strictSnrAdapt); cv.strictSnrRefDb = get("snr-ref", cv.strictSnrRefDb); cv.strictSnrMaxDb = get("snr-max", cv.strictSnrMaxDb);
  cv.missCombine = (int)get("miss-combine", cv.missCombine); cv.extraCombine = (int)get("extra-combine", cv.extraCombine);
  cv.baseThreshold = get("thr-base", cv.baseThreshold); cv.thresholdSlope = get("thr-slope", cv.thresholdSlope);
  cv.refSnrDb = get("thr-ref", cv.refSnrDb);
  const float minGap = get("min-gap", 0.06f);
  std::string line;
  while (std::getline(std::cin, line)) {
    std::istringstream ss(line); std::string cmd; ss >> cmd;
    if (cmd == "O") {
      OnsetDetector od(sr, onsetN, hop, 0.6f, minGap);
      od.thrMult = get("onset-mult", od.thrMult); od.thrAdd = get("onset-add", od.thrAdd); od.rmsGate = get("rms-gate", od.rmsGate);
      od.fluxLag = (int)get("flux-lag", od.fluxLag); od.maxFilterBins = (int)get("flux-maxfilt", od.maxFilterBins);
      od.fluxMaxHz = get("flux-maxhz", od.fluxMaxHz); od.timeOffsetSec = get("onset-offset", od.timeOffsetSec);
      long long pos; std::printf("O");
      for (long long i = 0; i + hop <= n; i += hop) if (od.processHop(&a.mono[i], pos)) std::printf(" %.4f", pos / (double)sr);
      std::printf("\n");
    } else if (cmd == "P") {
      double t, e = 1e9; ss >> t; if (!(ss >> e)) e = 1e9;
      long long s0 = (long long)(t * sr), s1 = std::min(n, (long long)(e * sr));
      float conf = 0, f0 = (s0 >= 0 && s0 < n) ? pitchAfterOnset(yin, a.mono.data(), n, s0, s1, sr, &conf) : 0.f;
      std::printf("P %.3f %.3f\n", f0, conf);
    } else if (cmd == "C") {
      double t, e; ss >> t >> e; std::vector<int> m; std::string tok;
      while (ss >> tok) m.push_back(tok == "|" ? -1 : std::atoi(tok.c_str()));
      long long s0 = (long long)(t * sr), cs = s0 + (long long)(0.06 * sr), ce = std::min({n, (long long)(e * sr), s0 + (long long)(0.8 * sr)});
      if (ce - cs < sr / 20 || m.empty()) { std::printf("C nan nan nan nan nan nan nan nan nan 0\n"); fflush(stdout); continue; }
      long long pe = s0 - (long long)(0.02 * sr), ps = std::max(0LL, pe - (long long)(0.3 * sr));
      const float* pre = (!noDiff && pe - ps > 0) ? &a.mono[ps] : nullptr;
      // optional song-aware neighbours: "C t end m1 m2 ... | n1 n2 ..."
      { auto bar = std::find(m.begin(), m.end(), -1);
        if (bar != m.end()) { cv.setNeighbours(&*bar + 1, (int)(m.end() - bar - 1)); m.erase(bar, m.end()); }
        else cv.setNeighbours(nullptr, 0); }
      ChordMatch r = cv.verify(&a.mono[cs], (int)(ce - cs), m.data(), (int)m.size(), pre, (int)std::max(0LL, pe - ps));
      std::printf("C %.4f %.4f %.3f %.4f %.4f %.4f %.2f %.2f %.2f %d\n", r.score, r.cosine, r.coverage, r.unexplained, r.purity,
                  r.generalScore, r.missSalDb, r.extraSalDb, r.snrDb, (int)r.match);
      if (std::getenv("GDSP_DEBUG")) std::fprintf(stderr, "weakestPc=%d extraPc=%d\n", r.weakestPc, r.extraPc);
    }
    std::fflush(stdout);
  }
}
