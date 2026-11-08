// Offline backing-track synthesis and audio helpers (platform-free, allocation is fine: these run before play,
// never on the audio thread).
//   renderNotes  — plucked strings (Karplus-Strong), bass, GM drums and a soft keys voice into a mono buffer
//   resampleLinear / timeStretch — bring a decoded recording to the engine rate and the practice tempo
//                  (WSOLA: tempo changes, pitch stays)
#pragma once
#include <cstdint>
#include <vector>

namespace mu {

enum class Voice : int { Pluck = 0, Bass = 1, Drums = 2, Keys = 3 };

struct SynthNote {
  double t = 0, dur = 0.25;  // seconds, buffer sample 0 = t 0
  int midi = 60;             // drums: General MIDI percussion key (35 kick, 38 snare, 42 hat, ...)
  float vel = 0.8f;          // 0..1
  Voice voice = Voice::Pluck;
};

// Adds every note into out[0, len). Deterministic (fixed noise seed).
void renderNotes(const SynthNote* notes, int n, int sr, float* out, long long len);

// Peak-normalises to `peak` (if louder or quieter) and converts to int16.
std::vector<int16_t> toInt16(const std::vector<float>& x, float peak);

std::vector<float> resampleLinear(const float* in, long long n, double fromSr, double toSr);

// Plays `in` `speed` times as fast without changing pitch (speed 0.5 = half tempo). speed == 1 copies.
std::vector<float> timeStretch(const float* in, long long n, int sr, double speed);

}  // namespace mu
