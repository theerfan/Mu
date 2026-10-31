// gdsp: guitar DSP core (pitch, onset, chord verification).
// Real-time safety: every analyzer preallocates all buffers in its constructor. The process*/verify
// methods do not allocate, lock, or do I/O, so they can run on an audio/analysis thread.
#pragma once
#include <complex>
#include <string>
#include <vector>
namespace gdsp {

// Radix-2 complex FFT with precomputed twiddles and bit-reversal table.
class FFT {
 public:
  explicit FFT(int n);
  void forward(std::complex<float>* a) const;  // in place, length n
  int size() const { return n_; }
 private:
  int n_; std::vector<int> rev_; std::vector<std::complex<float>> tw_;
};

// Hann-windowed magnitude spectrum, fixed size n (power of two). Input shorter than n is
// windowed over its own length and zero-padded. mag() has n/2+1 bins, scaled so a window's
// sum is normalised (levels comparable across input lengths).
class SpectrumAnalyzer {
 public:
  explicit SpectrumAnalyzer(int n);
  const std::vector<float>& process(const float* x, int len);
  const std::vector<float>& process(const float* x) { return process(x, fft_.size()); }
  const std::vector<float>& mag() const { return mag_; }
  int size() const { return fft_.size(); }
 private:
  FFT fft_; std::vector<float> win_; std::vector<std::complex<float>> buf_; std::vector<float> mag_;
};

struct PitchResult { float freqHz = 0; float confidence = 0; bool voiced = false; };
struct NoteName { int midi = -1; float cents = 0; std::string name; };  // not RT (std::string)
NoteName freqToNote(float hz);
std::string midiName(int midi);
float freqToMidi(float hz);  // fractional MIDI, RT-safe

// YIN (de Cheveigne & Kawahara 2002) with parabolic interpolation.
class YinPitch {
 public:
  YinPitch(int sampleRate, int windowSize, float minHz = 70.f, float maxHz = 1400.f, float threshold = 0.2f);
  PitchResult process(const float* x);  // x has >= windowSize samples
  int windowSize() const { return W_; }
  // Sub-octave guard: after picking tau, prefer tau/2 or tau/3 if the normalised difference there is
  // below subharmonicThr (YIN's absolute threshold sometimes skips a slightly-too-high true-period dip). 0 = off.
  float subharmonicThr = 0.5f;
  float subharmonicMargin = 0.2f;  // ...and only if d'(tau/k) <= d'(tau) + margin (guards against octave-up errors)
  // A frame with no dip below the threshold is still reported voiced if 1 - d'(tau) exceeds this.
  float voicedConfidence = 0.3f;
 private:
  int sr_, W_, tauMin_, tauMax_; float thr_;
  std::vector<float> d_;
};

// Note pitch after an onset: median of up to `frames` YIN frames starting `skipSec` after s0 (skips the pick
// transient), hopping W/2, never reading past s1. Returns 0 Hz if no voiced frame.
float pitchAfterOnset(YinPitch& yin, const float* x, long long n, long long s0, long long s1, int sampleRate,
                      float* confidence = nullptr, int frames = 3, float skipSec = 0.04f);

// Streaming spectral-flux onset detector. Feed hops of `hop` samples.
class OnsetDetector {
 public:
  OnsetDetector(int sampleRate, int frameSize = 1024, int hop = 256, float delta = 0.6f, float minGapSec = 0.06f);
  bool processHop(const float* hopSamples, long long& onsetSample);
  int hop() const { return hop_; }
  // Tunables: flux peak must exceed thrMult * median(recent flux) + thrAdd; frames under rmsGate are ignored.
  // Defaults tuned on GuitarSet (eval/, README): v0 was thrMult 1.5, thrAdd 0.06, lag 1, no max filter, all bins.
  float thrMult = 1.1f, thrAdd = 0.02f, rmsGate = 0.005f;
  // SuperFlux-style options (Boeck & Widmer 2013): compare with the frame `fluxLag` hops back after a max
  // filter of +-maxFilterBins over frequency (suppresses vibrato/noise flicker); only bins <= fluxMaxHz count.
  int fluxLag = 3;          // 1..3
  int maxFilterBins = 2;    // 0..3
  float fluxMaxHz = 5000.f;
  float timeOffsetSec = 0.f;  // added to reported onset times (detector bias correction)
 private:
  int sr_, N_, hop_; float delta_; int minGapFrames_;
  SpectrumAnalyzer spec_;
  std::vector<float> buf_, prevMag_, fluxHist_, scratch_, logRing_;  // logRing_: 4 frames x bins
  long long frame_ = 0, lastOnset_ = -1000000;
};

void chromaFromSpectrum(const std::vector<float>& mag, int sampleRate, int n, float fmin, float fmax, float chroma[12]);

constexpr float kChordMatchThreshold = 0.58f;  // tuned on GuitarSet train split (balanced accuracy)

struct ChordMatch {
  float score = 0;       // final score in [0,1]
  float cosine = 0;      // similarity to harmonic template
  float coverage = 0;    // fraction of expected pitch classes present
  float purity = 0;      // chroma energy share inside the expected pitch classes
  float unexplained = 0; // chroma energy in bins neither expected nor a harmonic of an expected note
  float chroma[12] = {};
};

// Chord verification against expected notes. Construct once per stream (allocates);
// verify() is allocation-free. Segments longer than maxLen samples are truncated.
class ChordVerifier {
 public:
  ChordVerifier(int sampleRate, int maxLen);
  // x/len: audio after the onset. pre/preLen (optional): audio right before the onset; its
  // spectrum (still-ringing previous chord) is subtracted from x's before chroma extraction.
  ChordMatch verify(const float* x, int len, const int* expectedMidi, int nExpected,
                    const float* pre = nullptr, int preLen = 0);
  // Defaults tuned on GuitarSet (eval/, README). v0: preSubtract 0.8, penalty 3, peakRelThr 0.05,
  // weightExp 0.5, ignoreBelowBass false -- the strong penalty rejected ~75 % of real correct chords.
  float preSubtract = 0.f;       // beta in |X_post| - beta*|X_pre| (0 = off; pre spectrum then not computed)
  float unexplainedPenalty = 0.25f;
  bool usePenalty = true;
  // Unexplained-peak test tunables (see README "GuitarSet evaluation" for how they were chosen):
  float peakRelThr = 0.15f;      // peaks below this fraction of the max are ignored
  float unexplWeightExp = 2.f;   // peak weight = magnitude^exp (0.5 = sqrt: small peaks count a lot)
  bool ignoreBelowBass = true;   // ignore peaks below the lowest expected fundamental (body/air resonance)
  float coverageRelThr = 0.25f;  // expected pitch class counts as present if chroma >= this * max
 private:
  int sr_, maxLen_;
  SpectrumAnalyzer spec_;
  std::vector<float> post_;
};

}  // namespace gdsp
