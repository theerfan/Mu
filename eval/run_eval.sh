#!/usr/bin/env bash
# Full GuitarSet evaluation on the held-out TEST split (players 04-05), all noise conditions,
# v0 parameters ("before") vs the GuitarSet-tuned library defaults ("after"). ~5 min on 8 cores.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d data/guitarset/audio_mono-mic ] || ./eval/download_guitarset.sh
cmake -S dsp -B dsp/build -DCMAKE_BUILD_TYPE=Release >/dev/null && cmake --build dsp/build -j >/dev/null
PY=${PYTHON:-python3}
CONDS=clean,pink20,pink10,white15,phone
BEFORE="--yin-thr 0.15 --yin-sub 0 --yin-vconf 0.7 --yin-margin 1 --onset-mult 1.5 --onset-add 0.06 --flux-lag 1 --flux-maxfilt 0
 --flux-maxhz 22050 --beta 0.8 --penalty 3 --peak-thr 0.05 --weight-exp 0.5 --cov-thr 0.25 --ignore-below-bass 0"
$PY eval/evaluate.py --split test --conds $CONDS --params "$BEFORE" --thr 0.6 --out eval/results/before_test.json
$PY eval/evaluate.py --split test --conds $CONDS --thr 0.58 --out eval/results/after_test.json
$PY eval/summarize.py > eval/results/SUMMARY.md && cat eval/results/SUMMARY.md
