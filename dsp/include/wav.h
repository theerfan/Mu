// Minimal single-header WAV reader/writer (PCM 8/16/24/32-bit int, 32-bit float; any channels/rate).
#pragma once
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
namespace gdsp {
struct Audio { int sampleRate = 0; std::vector<float> mono; };
inline bool readWav(const std::string& path, Audio& out, std::string* err = nullptr) {
  FILE* f = std::fopen(path.c_str(), "rb");
  auto fail = [&](const char* m) { if (err) *err = m; if (f) std::fclose(f); return false; };
  if (!f) return fail("cannot open file");
  char hdr[12];
  if (std::fread(hdr, 1, 12, f) != 12 || std::memcmp(hdr, "RIFF", 4) || std::memcmp(hdr + 8, "WAVE", 4)) return fail("not a RIFF/WAVE file");
  uint16_t fmtTag = 0, ch = 0, bits = 0; uint32_t rate = 0; bool haveFmt = false;
  while (true) {
    char id[4]; uint32_t sz;
    if (std::fread(id, 1, 4, f) != 4 || std::fread(&sz, 4, 1, f) != 1) return fail("no data chunk");
    if (!std::memcmp(id, "fmt ", 4)) {
      std::vector<uint8_t> b(sz); if (std::fread(b.data(), 1, sz, f) != sz) return fail("bad fmt");
      std::memcpy(&fmtTag, &b[0], 2); std::memcpy(&ch, &b[2], 2); std::memcpy(&rate, &b[4], 4); std::memcpy(&bits, &b[14], 2);
      if (fmtTag == 0xFFFE && sz >= 26) std::memcpy(&fmtTag, &b[24], 2);  // WAVE_FORMAT_EXTENSIBLE subformat
      haveFmt = true;
    } else if (!std::memcmp(id, "data", 4)) {
      if (!haveFmt || ch == 0) return fail("data before fmt");
      std::vector<uint8_t> d(sz); size_t got = std::fread(d.data(), 1, sz, f); d.resize(got);
      int bps = bits / 8; size_t frames = got / (bps * ch);
      out.sampleRate = (int)rate; out.mono.assign(frames, 0.f);
      for (size_t i = 0; i < frames; ++i) {
        float acc = 0;
        for (int c = 0; c < ch; ++c) {
          const uint8_t* p = &d[(i * ch + c) * bps]; float v = 0;
          if (fmtTag == 3 && bits == 32) { std::memcpy(&v, p, 4); }
          else if (bits == 8) v = (p[0] - 128) / 128.f;
          else if (bits == 16) { int16_t s; std::memcpy(&s, p, 2); v = s / 32768.f; }
          else if (bits == 24) { int32_t s = (p[0] | (p[1] << 8) | (p[2] << 16)); if (s & 0x800000) s |= ~0xFFFFFF; v = s / 8388608.f; }
          else if (bits == 32) { int32_t s; std::memcpy(&s, p, 4); v = s / 2147483648.f; }
          else return fail("unsupported bit depth");
          acc += v;
        }
        out.mono[i] = acc / ch;
      }
      std::fclose(f); return true;
    } else { std::fseek(f, sz + (sz & 1), SEEK_CUR); }
  }
}
inline bool writeWav16(const std::string& path, const std::vector<float>& x, int rate, int ch = 1) {
  FILE* f = std::fopen(path.c_str(), "wb"); if (!f) return false;
  uint32_t dataSz = (uint32_t)(x.size() * 2), riff = 36 + dataSz, fsz = 16, br = rate * ch * 2;
  uint16_t pcm = 1, c = ch, ba = ch * 2, bits = 16; uint32_t r = rate;
  std::fwrite("RIFF", 1, 4, f); std::fwrite(&riff, 4, 1, f); std::fwrite("WAVEfmt ", 1, 8, f);
  std::fwrite(&fsz, 4, 1, f); std::fwrite(&pcm, 2, 1, f); std::fwrite(&c, 2, 1, f); std::fwrite(&r, 4, 1, f);
  std::fwrite(&br, 4, 1, f); std::fwrite(&ba, 2, 1, f); std::fwrite(&bits, 2, 1, f);
  std::fwrite("data", 1, 4, f); std::fwrite(&dataSz, 4, 1, f);
  for (float v : x) { float cl = v > 1 ? 1 : (v < -1 ? -1 : v); int16_t s = (int16_t)(cl * 32767); std::fwrite(&s, 2, 1, f); }
  std::fclose(f); return true;
}
}  // namespace gdsp
