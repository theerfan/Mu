#!/usr/bin/env bash
# Prints chord-verification scores (audio x expected) as a markdown table. usage: chord_table.sh [gtcli] ["--no-diff --no-penalty"]
BIN=${1:-./build/gtcli}; EXTRA=${2:-}; cd "$(dirname "$0")/.."; W=test/wavs
declare -A N=([C]=C3,E3,G3,C4,E4 [G]=G2,B2,D3,G3,B3,G4 [Am]=A2,E3,A3,C4,E4 [Em]=E2,B2,E3,G3,B3,E4)
cell() { $BIN "$1" --expect "${N[$2]}" $EXTRA | grep '^onset' | sed -n "${3}p" | sed -n 's/.*chord_score=\([0-9.]*\).*/\1/p'; }
echo "| audio (onset) | exp C | exp G | exp Am | exp Em |"; echo "|---|---|---|---|---|"
row() { printf "| %s |" "$1"; for e in C G Am Em; do s=$(cell "$2" $e $3); [ "$e" = "$4" ] && s="**$s**"; printf " %s |" "$s"; done; echo; }
row "C"  $W/chord_Cmaj.wav 1 C;  row "G" $W/chord_Gmaj.wav 1 G
row "Am" $W/chord_Am.wav 1 Am;   row "Em" $W/chord_Em.wav 1 Em
row "Am after C"  $W/trans_C_Am.wav 2 Am; row "C after Am" $W/trans_Am_C.wav 2 C
row "Em after G"  $W/trans_G_Em.wav 2 Em; row "G after Em" $W/trans_Em_G.wav 2 G
row "G after C"   $W/trans_C_G.wav 2 G
