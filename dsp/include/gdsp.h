// gdsp: guitar DSP core (pitch, onset, chord verification). Real-time-safe usage:
// construct objects up front; process* methods do not allocate after construction.
#pragma once
#include <complex>
#include <string>
#include <vector>
namespace gdsp {

// In-place iterative radix-2 FFT. n must be a power of two.
void fft(std::vector<std::complex<float>>& a);
// Hann-windowed magnitude spectrum of x[0..n) (n power of 2), returns n/2+1 bins.
void magSpectrum(const float* x, int n, std::vector<float>& mag);

struct PitchResult { float freqHz = 0; float confidence = 0; bool voiced = false; };
struct NoteName { int midi = -1; float cents = 0; std::string name; };
NoteName freqToNote(float hz);
std::string midiName(int midi);

// YIN (de Cheveigne & Kawahara 2002) with parabolic interpolation.
class YinPitch {
 public:
  YinPitch(int sampleRate, int windowSize, float minHz = 70.f, float maxHz = 1400.f, float threshold = 0.15f);
  // x must contain at least windowSize samples.
  PitchResult process(const float* x);
  int windowSize() const { return W_; }
 private:
  int sr_, W_, tauMin_, tauMax_; float thr_;
  std::vector<float> d_;
};

// Spectral-flux onset detector (log-compressed magnitudes, half-wave rectified flux,
// adaptive median threshold, peak picking). Streaming: feed hops of `hop` samples.
class OnsetDetector {
 public:
  OnsetDetector(int sampleRate, int frameSize = 1024, int hop = 256, float delta = 0.6f, float minGapSec = 0.06f);
  // Feeds one hop; returns true if an onset was confirmed. onsetSample receives its position
  // (in samples since start; detection latency ~ (frameSize + lookahead*hop)).
  bool processHop(const float* hopSamples, long long& onsetSample);
  int hop() const { return hop_; }
 private:
  int sr_, N_, hop_; float delta_; int minGapFrames_;
  std::vector<float> buf_, mag_, prevMag_, win_;
  std::vector<float> fluxHist_;  // ring of recent flux values
  long long frame_ = 0, lastOnset_ = -1000000;
};

// Chroma (12 pitch classes) from a magnitude spectrum, with fundamental-only weighting
// between fmin..fmax. Normalised to unit L2.
void chromaFromSpectrum(const std::vector<float>& mag, int sampleRate, int n, float fmin, float fmax, float chroma[12]);

struct ChordMatch { float score = 0; float coverage = 0; float purity = 0; float perNote[12] = {}; };
// Verify that a segment contains the expected MIDI notes. score in [0,1].
// coverage: fraction of expected pitch classes present; purity: fraction of chroma energy inside expected set.
ChordMatch verifyChord(const float* x, int len, int sampleRate, const std::vector<int>& expectedMidi);

}  // namespace gdsp
