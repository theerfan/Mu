#!/usr/bin/env bash
# Builds, synthesises Karplus-Strong test WAVs (SNR 25 dB white noise) and checks detection.
set -u
cd "$(dirname "$0")/.."
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release >/dev/null && cmake --build build -j >/dev/null || exit 1
mkdir -p test/wavs && ./build/gen_test_wavs test/wavs >/dev/null
CLI=./build/gtcli; pass=0; fail=0
check() { # desc, condition-result
  if [ "$2" = 1 ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1"; fail=$((fail+1)); fi; }
for sr in 44100 48000 22050; do for n in E2 A2 G3 E4; do
  out=$($CLI test/wavs/single_${n}_${sr}.wav)
  line=$(echo "$out" | grep '^onset' )
  cnt=$(echo "$line" | wc -l); note=$(echo "$line" | sed -n 's/.*note=\([A-G#0-9]*\).*/\1/p' | head -1)
  cents=$(echo "$line" | sed -n 's/.*cents= *\([-+0-9.]*\).*/\1/p' | head -1)
  t=$(echo "$line" | sed -n 's/onset t= *\([0-9.]*\)s.*/\1/p' | head -1)
  ok=$(awk -v c="$cents" -v t="$t" -v k="$cnt" -v a="$note" -v b="$n" 'BEGIN{print (a==b && c<5 && c>-5 && k==1 && t>0.23 && t<0.27)?1:0}')
  check "single $n @${sr}Hz -> $note ${cents}c onset ${t}s (truth 0.250s, onsets=$cnt)" $ok
done; done
seq=$($CLI test/wavs/sequence_E2_A2_G3_E4.wav | grep '^onset')
notes=$(echo "$seq" | sed -n 's/.*note=\([A-G#0-9]*\).*/\1/p' | tr '\n' ' ')
check "sequence E2 A2 G3 E4 (truth onsets .3 1.1 1.9 2.7) -> $notes" $([ "$notes" = "E2 A2 G3 E4 " ] && echo 1 || echo 0)
C=C3,E3,G3,C4,E4; G=G2,B2,D3,G3,B3,G4; Am=A2,E3,A3,C4,E4
score() { $CLI "$1" --expect "$2" | grep '^onset' | sed -n "${3:-1}p" | sed -n 's/.*chord_score=\([0-9.]*\).*/\1/p'; }
chk() { s=$(score "$1" "$2" "${5:-1}"); ok=$(awk -v s="$s" -v w="$4" 'BEGIN{print ((w=="match" && s>=0.75)||(w=="nomatch" && s<0.75))?1:0}'); check "$3 score=$s expected $4" $ok; }
chk test/wavs/chord_Cmaj.wav $C  "Cmaj audio vs expected Cmaj" match
chk test/wavs/chord_Cmaj.wav $G  "Cmaj audio vs expected Gmaj" nomatch
chk test/wavs/chord_Cmaj.wav $Am "Cmaj audio vs expected Am  " nomatch
chk test/wavs/chord_Gmaj.wav $G  "Gmaj audio vs expected Gmaj" match
chk test/wavs/chord_Gmaj.wav $C  "Gmaj audio vs expected Cmaj" nomatch
chk test/wavs/chords_C_then_G_stereo.wav $C "stereo seq chord#1 vs Cmaj " match 1
chk test/wavs/chords_C_then_G_stereo.wav $G "stereo seq chord#2 vs Gmaj " match 2
chk test/wavs/chords_C_then_G_stereo.wav $C "stereo seq chord#2 vs Cmaj " nomatch 2
echo "== $pass passed, $fail failed"; [ $fail = 0 ]
