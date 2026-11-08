// Host tests for mucore: SPSC queue, calibration math, tuner, and an end-to-end play-along run on
// synthetic Karplus-Strong audio (hit / late / wrong note / miss / chord one note off / extra), plus a
// check that the streaming calls do not allocate.
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <random>
#include <thread>
#include <vector>
#include "mu/practice.h"
#include "mu/spsc.h"
#include "mu/synth.h"

static std::atomic<long> gAllocs{0};
void* operator new(size_t n) { gAllocs.fetch_add(1, std::memory_order_relaxed); if (void* p = std::malloc(n ? n : 1)) return p; throw std::bad_alloc(); }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete(void* p, size_t) noexcept { std::free(p); }

static int pass = 0, fail = 0;
#define CHECK(cond, ...) do { if (cond) { ++pass; std::printf("PASS  "); } else { ++fail; std::printf("FAIL  "); } std::printf(__VA_ARGS__); std::printf("\n"); } while (0)

static std::mt19937 rng(7);
static void pluck(std::vector<float>& out, int sr, double t0, int midi, float amp, double dur = 2.0) {
  double f = 440.0 * std::pow(2.0, (midi - 69) / 12.0), P = sr / f - 0.5;
  int N = (int)std::floor(P - 0.1); double fr = P - N, C = (1 - fr) / (1 + fr);
  std::vector<float> line(N); std::uniform_real_distribution<float> u(-1, 1); float lp = 0;
  for (auto& v : line) { lp = 0.5f * lp + 0.5f * u(rng); v = lp; }
  size_t start = (size_t)(t0 * sr), len = (size_t)(dur * sr);
  if (out.size() < start + len) out.resize(start + len, 0.f);
  int idx = 0; float prev = 0, apX = 0, apY = 0;
  for (size_t i = 0; i < len; ++i) {
    float cur = line[idx], avg = 0.996f * 0.5f * (cur + prev); prev = cur;
    float y = (float)(C * avg + apX - C * apY); apX = avg; apY = y; line[idx] = y; idx = (idx + 1) % N;
    out[start + i] += amp * cur;
  }
}
static void strum(std::vector<float>& x, int sr, double t, std::initializer_list<int> notes) {
  int i = 0; for (int m : notes) pluck(x, sr, t + 0.012 * i++, m, 0.5f);
}
static void damp(std::vector<float>& x, int sr, double t) {  // player mutes the strings at t (20 ms fade)
  size_t s = (size_t)(t * sr);
  for (size_t i = s; i < x.size(); ++i) x[i] *= i - s < (size_t)(0.02 * sr) ? 1.f - (float)(i - s) / (0.02f * sr) : 0.f;
}
static void noise(std::vector<float>& x, float db) {
  std::normal_distribution<float> n(0, std::pow(10.f, db / 20.f)); for (auto& v : x) v += n(rng);
}

static void testSpsc() {
  mu::SpscQueue<int> q(1024); const int N = 1000000; std::atomic<bool> ok{true};
  std::thread prod([&] { int i = 0; while (i < N) { if (q.push(i)) ++i; } });
  int next = 0, buf[64];
  while (next < N) { size_t k = q.pop(buf, 64); for (size_t j = 0; j < k; ++j) if (buf[j] != next++) ok = false; }
  prod.join();
  CHECK(ok && next == N, "SPSC queue: 1e6 items across threads in order (full-queue retries counted as drops: %llu)", (unsigned long long)q.dropped());
}

static void testCalib() {
  std::vector<double> refs, det; std::normal_distribution<double> n(0, 0.002);
  for (int i = 0; i < 16; ++i) { refs.push_back(1.0 + 0.5 * i); det.push_back(refs.back() + 0.023 + n(rng)); }
  det[3] += 0.09; det.erase(det.begin() + 7); det.push_back(5.31);  // outlier, a missed click, a stray onset
  mu::CalibResult r = mu::robustOffset(det.data(), (int)det.size(), refs.data(), (int)refs.size(), 0.2);
  CHECK(r.ok && std::fabs(r.offsetMs - 23.f) < 2.f && r.used >= 13, "calibration offset %.1f ms (truth 23), used %d/%d", r.offsetMs, r.used, r.total);
  mu::CalibResult r2 = mu::robustOffset(det.data(), 2, refs.data(), (int)refs.size(), 0.2);
  CHECK(!r2.ok, "calibration with too few detections is rejected (used %d)", r2.used);
}

static void testTuner() {
  const int sr = 48000; std::vector<float> x; pluck(x, sr, 0.0, 45, 0.6, 1.0); noise(x, -50);
  mu::Tuner t(sr); gdsp::PitchResult p, last; float db = 0; int n = 0;
  for (size_t i = 0; i + 480 <= x.size(); i += 480) if (t.feed(&x[i], 480, p, db) && i > 0.3 * sr && i < 0.6 * sr && p.voiced) { last = p; ++n; }
  CHECK(n > 5 && std::fabs(last.freqHz - 110.f) < 0.5f, "tuner A2: %.2f Hz (truth 110.00), %d estimates", last.freqHz, n);
}

static mu::ExpectedEvent ev(double t, std::initializer_list<int> m) {
  mu::ExpectedEvent e; e.t = t; e.dur = 0.5f; for (int v : m) e.midi[e.n++] = v; return e;
}

static void testPlay(int sr) {
  // expected song
  std::vector<mu::ExpectedEvent> song = {
      ev(1.0, {40}),                    // 0: played 20 ms late          -> Hit, Perfect
      ev(2.0, {45}),                    // 1: D3 played instead          -> Wrong
      ev(3.0, {55}),                    // 2: nothing played             -> Miss
      ev(4.0, {48, 52, 55, 60, 64}),    // 3: C, 60 ms late              -> Hit, Good
      ev(5.5, {48, 52, 55, 59, 64}),    // 4: Cmaj7 expected, C played   -> Wrong, missing B
      ev(7.0, {48, 52, 55, 60, 64}),    // 5: C expected, Cmaj7 played   -> Wrong, extra B
      ev(8.5, {57}),                    // 6: A3 played 100 ms early     -> Hit, Ok
      ev(9.5, {57}),                    // 7: A2 played (octave down)    -> Partial
  };
  const double lead = 0.5;  // audio starts 0.5 s before song time 0
  std::vector<float> x((size_t)(12.5 * sr), 0.f);
  auto at = [&](double t) { return t + lead; };
  pluck(x, sr, at(1.02), 40, 0.6); damp(x, sr, at(1.9));
  pluck(x, sr, at(2.0), 50, 0.6); damp(x, sr, at(3.6));
  strum(x, sr, at(4.06), {48, 52, 55, 60, 64}); damp(x, sr, at(5.4));
  strum(x, sr, at(5.5), {48, 52, 55, 60, 64}); damp(x, sr, at(6.9));
  strum(x, sr, at(7.0), {48, 52, 55, 59, 64}); damp(x, sr, at(8.0));
  pluck(x, sr, at(8.4), 57, 0.6); damp(x, sr, at(9.3));
  pluck(x, sr, at(9.5), 45, 0.6); damp(x, sr, at(10.3));
  pluck(x, sr, at(11.0), 64, 0.6);  // extra note, nothing expected
  noise(x, -55);

  mu::PlayAnalyzer pa(sr); pa.setSong(song.data(), (int)song.size());
  pa.windows.ok = 0.13f;
  std::vector<mu::Judgement> got(64); int n = 0;
  const int block = sr / 100;
  long allocs = 0;
  for (size_t i = 0; i + block <= x.size(); i += block) {
    long a0 = gAllocs.load();
    n += pa.process(&x[i], block, (double)i / sr - lead, got.data() + n, 64 - n);
    allocs += gAllocs.load() - a0;
  }
  n += pa.finish(got.data() + n, 64 - n);
  CHECK(allocs == 0, "@%d Hz: PlayAnalyzer::process made %ld allocations over %zu blocks", sr, allocs, x.size() / block);
  const char* kinds[] = {"Hit", "Partial", "Wrong", "Miss", "Extra"};
  const char* grades[] = {"Perfect", "Good", "Ok", "-"};
  mu::Judgement byIdx[8]; bool seen[8] = {}; int extras = 0;
  for (int i = 0; i < n; ++i) {
    const auto& j = got[i];
    std::printf("      t=%6.3f idx=%d %-7s %-7s dt=%+6.1fms midi=%d cents=%+5.1f chord=%.2f missing=%s extra=%s\n", j.songTime, j.index,
                kinds[(int)j.kind], grades[(int)j.grade], j.dtMs, j.detMidi, j.cents, j.chordScore,
                j.missingPc >= 0 ? mu::pitchClassName(j.missingPc) : "-", j.extraPc >= 0 ? mu::pitchClassName(j.extraPc) : "-");
    if (j.kind == mu::Kind::Extra) ++extras;
    else if (j.index >= 0 && j.index < 8) { byIdx[j.index] = j; seen[j.index] = true; }
  }
  auto is = [&](int i, mu::Kind k) { return seen[i] && byIdx[i].kind == k; };
  CHECK(is(0, mu::Kind::Hit) && byIdx[0].grade == mu::Grade::Perfect && std::fabs(byIdx[0].dtMs - 20) < 25, "@%d: E2 20 ms late -> Hit/Perfect (dt %.1f; detector bias + hop quantisation, removed by calibration)", sr, byIdx[0].dtMs);
  CHECK(is(1, mu::Kind::Wrong) && byIdx[1].detMidi == 50, "@%d: D3 instead of A2 -> Wrong, detected %d", sr, byIdx[1].detMidi);
  CHECK(is(2, mu::Kind::Miss), "@%d: nothing played -> Miss", sr);
  CHECK(is(3, mu::Kind::Hit) && byIdx[3].grade == mu::Grade::Good, "@%d: C chord 60 ms late -> Hit/Good (dt %.1f, score %.2f)", sr, byIdx[3].dtMs, byIdx[3].chordScore);
  CHECK(is(4, mu::Kind::Wrong) && byIdx[4].missingPc == 11, "@%d: C played for Cmaj7 -> Wrong, missing %s", sr, mu::pitchClassName(byIdx[4].missingPc));
  CHECK(is(5, mu::Kind::Wrong) && byIdx[5].extraPc == 11, "@%d: Cmaj7 played for C -> Wrong, extra %s", sr, mu::pitchClassName(byIdx[5].extraPc));
  CHECK(is(6, mu::Kind::Hit) && byIdx[6].grade == mu::Grade::Ok && byIdx[6].dtMs < -80, "@%d: A3 100 ms early -> Hit/Ok (dt %.1f)", sr, byIdx[6].dtMs);
  CHECK(is(7, mu::Kind::Partial), "@%d: A2 for A3 -> Partial (octave)", sr);
  CHECK(extras == 0, "@%d: no Extra inside the song (the E4 after the last event is outside the song range); extras=%d", sr, extras);
}

static float tunerHz(const std::vector<float>& x, int sr, size_t from, size_t to) {
  mu::Tuner t(sr); gdsp::PitchResult p, last; float db; int n = 0;
  for (size_t i = from; i + 256 <= to; i += 256) if (t.feed(&x[i], 256, p, db) && p.voiced) { last = p; ++n; }
  return n ? last.freqHz : 0.f;
}

static void testSynth() {
  const int sr = 48000;
  std::vector<mu::SynthNote> notes(3);
  notes[0].t = 0.1; notes[0].dur = 1.0; notes[0].midi = 45; notes[0].voice = mu::Voice::Pluck;   // A2
  notes[1].t = 1.5; notes[1].dur = 1.0; notes[1].midi = 33; notes[1].voice = mu::Voice::Bass;    // A1 (55 Hz, below the tuner range)
  notes[2].t = 1.5; notes[2].midi = 36; notes[2].voice = mu::Voice::Drums;
  std::vector<float> x(3 * sr, 0.f);
  mu::renderNotes(notes.data(), (int)notes.size(), sr, x.data(), (long long)x.size());
  float pk = 0; for (float v : x) pk = std::max(pk, std::fabs(v));
  const float hz = tunerHz(x, sr, (size_t)(0.15 * sr), (size_t)(0.9 * sr));
  CHECK(pk > 0.1f && pk < 4.f && std::fabs(hz - 110.f) < 1.5f, "synth: plucked A2 reads %.2f Hz on the tuner (peak %.2f)", hz, pk);
  float pre = 0; for (int i = 0; i < (int)(0.09 * sr); ++i) pre = std::max(pre, std::fabs(x[i]));
  CHECK(pre == 0.f, "synth: silent before the first note");
  auto q = mu::toInt16(x, 0.9f); int16_t qm = 0; for (auto v : q) qm = std::max<int16_t>(qm, (int16_t)std::abs(v));
  CHECK(std::abs(qm - 29490) < 40, "synth: int16 peak-normalised to 0.9 (%d)", qm);
}

static void testStretch() {
  const int sr = 44100;
  std::vector<float> s(sr);
  for (int i = 0; i < sr; ++i) s[i] = 0.5f * (float)std::sin(2 * M_PI * 220.0 * i / sr);
  auto slow = mu::timeStretch(s.data(), (long long)s.size(), sr, 0.5);
  CHECK(std::abs((long)slow.size() - 2 * sr) <= 1, "stretch x0.5: %zu samples (want %d)", slow.size(), 2 * sr);
  const float hz = tunerHz(slow, sr, sr / 2, (size_t)(1.5 * sr));
  CHECK(std::fabs(hz - 220.f) < 1.5f, "stretch x0.5 keeps pitch: %.2f Hz (want 220)", hz);
  auto up = mu::resampleLinear(s.data(), (long long)s.size(), 44100, 48000);
  CHECK(std::abs((long)up.size() - 48000) <= 2 && std::fabs(tunerHz(up, 48000, 4800, 43200) - 220.f) < 1.5f, "resample 44.1k -> 48k: %zu samples, pitch kept", up.size());
}

int main() {
  testSpsc(); testCalib(); testTuner(); testSynth(); testStretch(); testPlay(48000); testPlay(44100);
  std::printf("== %d passed, %d failed\n", pass, fail);
  return fail ? 1 : 0;
}
