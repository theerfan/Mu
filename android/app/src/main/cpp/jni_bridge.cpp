// JNI surface for com.theerfan.mu.audio.NativeAudio. Called from UI/coroutine threads only (never from the
// audio callback). One engine per process.
#include <jni.h>
#include <memory>
#include <mutex>
#include <vector>
#include "audio_engine.h"

using mu::AudioEngine;
namespace {
std::unique_ptr<AudioEngine> gEngine;
std::mutex gMutex;  // serialises UI commands (not used by the audio or analysis threads)
AudioEngine& eng() { if (!gEngine) gEngine = std::make_unique<AudioEngine>(); return *gEngine; }
constexpr int kJudgementFloats = 10;
void packJudgements(JNIEnv* env, jfloatArray out, const mu::Judgement* j, int n) {
  std::vector<float> f((size_t)n * kJudgementFloats);
  for (int i = 0; i < n; ++i) {
    float* p = &f[(size_t)i * kJudgementFloats];
    p[0] = (float)j[i].kind; p[1] = (float)j[i].grade; p[2] = (float)j[i].index; p[3] = j[i].dtMs; p[4] = j[i].songTime;
    p[5] = (float)j[i].detMidi; p[6] = j[i].cents; p[7] = j[i].chordScore; p[8] = (float)j[i].missingPc; p[9] = (float)j[i].extraPc;
  }
  env->SetFloatArrayRegion(out, 0, (jsize)f.size(), f.data());
}
}  // namespace

#define FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_theerfan_mu_audio_NativeAudio_##name

FN(jint, open)(JNIEnv*, jobject, jboolean unprocessed) { std::lock_guard<std::mutex> l(gMutex); return eng().open(unprocessed); }
FN(jboolean, start)(JNIEnv*, jobject) { std::lock_guard<std::mutex> l(gMutex); return eng().start(); }
FN(void, stop)(JNIEnv*, jobject) { std::lock_guard<std::mutex> l(gMutex); eng().stop(); }
FN(void, close)(JNIEnv*, jobject) { std::lock_guard<std::mutex> l(gMutex); eng().close(); }

FN(void, status)(JNIEnv* env, jobject, jdoubleArray out) {
  std::lock_guard<std::mutex> l(gMutex);
  mu::EngineStatus s = eng().status();
  double v[14] = {(double)s.sampleRate, (double)s.outBufferFrames, (double)s.outBurst, (double)s.inputPreset, (double)s.sessionId,
                  (double)s.xruns, (double)s.droppedSamples, (double)s.framesWritten, s.timestampsValid ? 1.0 : 0.0,
                  s.disconnected ? 1.0 : 0.0, s.exclusiveOut ? 1.0 : 0.0, s.exclusiveIn ? 1.0 : 0.0, s.outLatencyMs, s.inLatencyMs};
  env->SetDoubleArrayRegion(out, 0, std::min<jsize>(14, env->GetArrayLength(out)), v);
}

FN(void, setUserOffsetMs)(JNIEnv*, jobject, jfloat ms) { eng().setUserOffsetMs(ms); }
FN(void, startTuner)(JNIEnv*, jobject) { std::lock_guard<std::mutex> l(gMutex); eng().startTuner(); }
FN(jboolean, tuner)(JNIEnv* env, jobject, jfloatArray out) {
  float v[4]; bool ok = eng().tuner(v); env->SetFloatArrayRegion(out, 0, 4, v); return ok;
}

FN(void, startClicks)(JNIEnv* env, jobject, jdoubleArray times, jbooleanArray accents, jdouble leadIn, jfloat gain, jboolean collect) {
  std::lock_guard<std::mutex> l(gMutex);
  const jsize n = env->GetArrayLength(times);
  std::vector<double> t(n); std::vector<jboolean> a(n); std::unique_ptr<bool[]> ab(new bool[n > 0 ? n : 1]);
  env->GetDoubleArrayRegion(times, 0, n, t.data()); env->GetBooleanArrayRegion(accents, 0, n, a.data());
  for (jsize i = 0; i < n; ++i) ab[i] = a[i];
  eng().startClicks(t.data(), ab.get(), n, leadIn, gain, collect);
}
FN(jint, onsets)(JNIEnv* env, jobject, jdoubleArray out) {
  std::lock_guard<std::mutex> l(gMutex);
  const jsize max = env->GetArrayLength(out); std::vector<double> v(max);
  int n = eng().onsets(v.data(), max); env->SetDoubleArrayRegion(out, 0, n, v.data()); return n;
}
FN(jfloatArray, robustOffset)(JNIEnv* env, jobject, jdoubleArray det, jdoubleArray refs, jdouble maxDist) {
  const jsize nd = env->GetArrayLength(det), nr = env->GetArrayLength(refs);
  std::vector<double> d(nd), r(nr);
  env->GetDoubleArrayRegion(det, 0, nd, d.data()); env->GetDoubleArrayRegion(refs, 0, nr, r.data());
  mu::CalibResult c = mu::robustOffset(d.data(), nd, r.data(), nr, maxDist);
  float v[5] = {c.ok ? 1.f : 0.f, c.offsetMs, c.spreadMs, (float)c.used, (float)c.total};
  jfloatArray out = env->NewFloatArray(5); env->SetFloatArrayRegion(out, 0, 5, v); return out;
}

FN(void, loadSong)(JNIEnv* env, jobject, jdoubleArray times, jintArray counts, jintArray midis) {
  std::lock_guard<std::mutex> l(gMutex);
  const jsize n = env->GetArrayLength(times), nm = env->GetArrayLength(midis);
  std::vector<double> t(n); std::vector<jint> c(n), m(nm);
  env->GetDoubleArrayRegion(times, 0, n, t.data()); env->GetIntArrayRegion(counts, 0, n, c.data());
  env->GetIntArrayRegion(midis, 0, nm, m.data());
  std::vector<mu::ExpectedEvent> ev(n); size_t k = 0;
  for (jsize i = 0; i < n; ++i) {
    ev[i].t = t[i];
    for (int j = 0; j < c[i]; ++j, ++k) if (ev[i].n < mu::kMaxNotesPerEvent && k < (size_t)nm) ev[i].midi[ev[i].n++] = m[k];
  }
  eng().loadSong(ev.data(), n);
}
FN(jboolean, startPlay)(JNIEnv* env, jobject, jdoubleArray times, jbooleanArray accents, jdouble leadIn, jfloat gain) {
  std::lock_guard<std::mutex> l(gMutex);
  const jsize n = env->GetArrayLength(times);
  std::vector<double> t(n); std::vector<jboolean> a(n); std::unique_ptr<bool[]> ab(new bool[n > 0 ? n : 1]);
  env->GetDoubleArrayRegion(times, 0, n, t.data()); env->GetBooleanArrayRegion(accents, 0, n, a.data());
  for (jsize i = 0; i < n; ++i) ab[i] = a[i];
  return eng().startPlay(t.data(), ab.get(), n, leadIn, gain);
}
FN(jint, poll)(JNIEnv* env, jobject, jfloatArray out) {
  const int max = env->GetArrayLength(out) / kJudgementFloats; mu::Judgement j[64];
  int n = eng().poll(j, std::min(max, 64)); packJudgements(env, out, j, n); return n;
}
FN(jint, finishPlay)(JNIEnv* env, jobject, jfloatArray out) {
  std::lock_guard<std::mutex> l(gMutex);
  const int max = env->GetArrayLength(out) / kJudgementFloats; std::vector<mu::Judgement> j(max);
  int n = eng().finishPlay(j.data(), max); packJudgements(env, out, j.data(), n); return n;
}
// Renders the band (synth notes, or a decoded recording brought to the engine rate and practice tempo) and the
// guide (your part on a plucked string) and hands both to the engine. Runs off the UI thread; can take a moment.
FN(void, renderBacking)(JNIEnv* env, jobject, jdoubleArray bT, jdoubleArray bDur, jintArray bMidi, jfloatArray bVel, jintArray bVoice,
                        jdoubleArray gT, jdoubleArray gDur, jintArray gMidi, jfloatArray gVel, jdouble lengthSec, jdouble prerollSec,
                        jshortArray pcm, jint pcmRate, jdouble speed, jdouble recStartSec) {
  std::lock_guard<std::mutex> l(gMutex);
  const int sr = eng().sampleRate();
  const long long len = (long long)((lengthSec + prerollSec + 2.0) * sr);
  auto notes = [&](jdoubleArray t, jdoubleArray d, jintArray m, jfloatArray v, jintArray voice, mu::Voice def) {
    const jsize n = env->GetArrayLength(t);
    std::vector<double> tt(n), dd(n); std::vector<jint> mm(n), vv(n, (jint)def); std::vector<float> ve(n);
    env->GetDoubleArrayRegion(t, 0, n, tt.data()); env->GetDoubleArrayRegion(d, 0, n, dd.data());
    env->GetIntArrayRegion(m, 0, n, mm.data()); env->GetFloatArrayRegion(v, 0, n, ve.data());
    if (voice) env->GetIntArrayRegion(voice, 0, n, vv.data());
    std::vector<mu::SynthNote> out(n);
    for (jsize i = 0; i < n; ++i) out[i] = {tt[i] + prerollSec, dd[i], (int)mm[i], ve[i], (mu::Voice)vv[i]};
    return out;
  };
  std::vector<int16_t> band, guide;
  if (pcm) {
    const jsize n = env->GetArrayLength(pcm);
    std::vector<jshort> s(n); env->GetShortArrayRegion(pcm, 0, n, s.data());
    std::vector<float> f(n); for (jsize i = 0; i < n; ++i) f[i] = s[i] / 32768.f;
    auto rs = mu::resampleLinear(f.data(), n, pcmRate, sr);
    auto st = mu::timeStretch(rs.data(), (long long)rs.size(), sr, speed);
    // band sample j is song time j/sr - preroll; the recording reaches song time 0 at recStartSec (100 % tempo)
    const long long shift = (long long)std::llround((recStartSec / speed - prerollSec) * sr);
    std::vector<float> b((size_t)len, 0.f);
    for (long long j = 0; j < len; ++j) { const long long k = j + shift; if (k >= 0 && k < (long long)st.size()) b[(size_t)j] = st[(size_t)k]; }
    band = mu::toInt16(b, 0.9f);
  } else {
    auto bn = notes(bT, bDur, bMidi, bVel, bVoice, mu::Voice::Pluck);
    std::vector<float> b((size_t)len, 0.f);
    mu::renderNotes(bn.data(), (int)bn.size(), sr, b.data(), len);
    band = mu::toInt16(b, 0.8f);
  }
  auto gn = notes(gT, gDur, gMidi, gVel, nullptr, mu::Voice::Pluck);
  std::vector<float> g((size_t)len, 0.f);
  mu::renderNotes(gn.data(), (int)gn.size(), sr, g.data(), len);
  guide = mu::toInt16(g, 0.8f);
  eng().setBacking(std::move(band), std::move(guide), prerollSec);
}
FN(void, setBackingGains)(JNIEnv*, jobject, jfloat band, jfloat guide) { eng().setBackingGains(band, guide); }

FN(void, stopAll)(JNIEnv*, jobject) { std::lock_guard<std::mutex> l(gMutex); eng().stopAll(); }
FN(jdouble, songTime)(JNIEnv*, jobject) { return eng().songTime(); }
