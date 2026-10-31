#!/usr/bin/env bash
# Downloads GuitarSet (Xi et al., ISMIR 2018; Zenodo 3371780, CC BY 4.0): JAMS annotations (39 MB)
# and mono microphone audio (656 MB) into data/guitarset/. Audio is never committed (data/ is gitignored).
set -euo pipefail
cd "$(dirname "$0")/.."
D=data/guitarset; mkdir -p "$D"
for f in annotation audio_mono-mic; do
  [ -d "$D/$f" ] && { echo "have $f"; continue; }
  curl -L --fail --retry 3 -o "$D/$f.zip" "https://zenodo.org/api/records/3371780/files/$f.zip/content"
  mkdir -p "$D/$f" && unzip -q -o "$D/$f.zip" -d "$D/$f" && rm "$D/$f.zip"
done
echo "annotations: $(ls $D/annotation | wc -l)  audio: $(ls $D/audio_mono-mic | wc -l)"
