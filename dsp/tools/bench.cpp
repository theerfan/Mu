// Benchmarks per-call cost of each analysis step and verifies the hot paths do not allocate
// (global operator new is instrumented). usage: bench [sampleRate=48000]
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <random>
#include <vector>
#include "gdsp.h"
using namespace gdsp;

static std::atomic<long> gAllocs{0};
void* operator new(std::size_t n) { ++gAllocs; if (void* p = std::malloc(n ? n : 1)) return p; throw std::bad_alloc(); }
void* operator new[](std::size_t n) { ++gAllocs; if (void* p = std::malloc(n ? n : 1)) return p; throw std::bad_alloc(); }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }
void operator delete[](void* p, std::size_t) noexcept { std::free(p); }

static volatile float gSink;
template <class F> static void bench(const char* name, int iters, F&& f, double budgetUs) {
  for (int i = 0; i < iters / 10 + 1; ++i) f();  // warm-up
  long a0 = gAllocs.load();
  std::vector<double> t; t.reserve(iters);
  long a1 = gAllocs.load();
  for (int i = 0; i < iters; ++i) {
    auto s = std::chrono::steady_clock::now(); f();
    t.push_back(std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - s).count());
  }
  long allocs = gAllocs.load() - a1; (void)a0;
  std::vector<double> s(t); std::sort(s.begin(), s.end());
  double mean = 0; for (double v : t) mean += v; mean /= t.size();
  std::printf("%-34s mean %8.2f us  p50 %8.2f  p99 %8.2f  | budget %7.0f us -> %5.2f%% | allocs/call %ld\n",
              name, mean, s[s.size() / 2], s[s.size() * 99 / 100], budgetUs, 100 * mean / budgetUs, allocs / iters);
}

int main(int argc, char** argv) {
  const int sr = argc > 1 ? std::atoi(argv[1]) : 48000;
  auto pow2 = [](double v) { int n = 1; while (n < v) n <<= 1; return n; };
  const int onsetN = std::max(1024, pow2(sr * 0.023)), hop = onsetN / 4, yinW = pow2(sr * 0.045), chordLen = (int)(0.74 * sr);
  std::vector<float> x(sr * 2);
  std::mt19937 rng(1); std::normal_distribution<float> nd(0, 0.01f);
  for (size_t i = 0; i < x.size(); ++i) x[i] = 0.3f * std::sin(2 * 3.14159f * 110.f * i / sr) + nd(rng);
  std::printf("sr=%d onsetFrame=%d hop=%d (%.2f ms) yinWindow=%d chordSegment=%d\n", sr, onsetN, hop, 1000.0 * hop / sr, yinW, chordLen);
  const double hopUs = 1e6 * hop / sr;

  std::vector<std::complex<float>> buf(onsetN);
  FFT fft(onsetN);
  bench("FFT (onset frame size)", 20000, [&] { for (int i = 0; i < onsetN; ++i) buf[i] = x[i]; fft.forward(buf.data()); gSink = buf[3].real(); }, hopUs);
  SpectrumAnalyzer sa(onsetN);
  bench("SpectrumAnalyzer (onset frame)", 20000, [&] { gSink = sa.process(x.data())[5]; }, hopUs);
  OnsetDetector od(sr, onsetN, hop); long long pos; size_t off = 0;
  bench("OnsetDetector::processHop", 20000, [&] { od.processHop(&x[off], pos); off = (off + hop) % (x.size() - hop); }, hopUs);
  YinPitch yin(sr, yinW, 70.f, 1400.f);
  bench("YinPitch::process (70 Hz min)", 2000, [&] { gSink = yin.process(x.data()).freqHz; }, hopUs);
  ChordVerifier cv(sr, (int)(0.8 * sr)); const int exp[5] = {48, 52, 55, 60, 64};
  ChordVerifier cv1(sr, (int)(0.8 * sr)); cv1.strict = false;
  bench("ChordVerifier::verify (v1: strict off)", 500, [&] { gSink = cv1.verify(&x[sr / 10], chordLen, exp, 5).score; }, hopUs);
  bench("ChordVerifier::verify", 500, [&] { gSink = cv.verify(&x[sr / 10], chordLen, exp, 5).score; }, hopUs);
  bench("ChordVerifier::verify (+pre diff)", 500, [&] { gSink = cv.verify(&x[sr / 2], chordLen, exp, 5, x.data(), (int)(0.3 * sr)).score; }, hopUs);
  std::printf("budget = one hop of real time. YIN runs ~3x per onset and verify once per expected chord,\n"
              "so they may be scheduled across hops on the analysis thread rather than per hop.\n");
}
