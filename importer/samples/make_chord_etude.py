"""Writes chord_etude.json: a self-authored practice etude (open strings, open chords incl. C/Cmaj7, mixed bars)
directly in the app's song JSON schema (ARCHITECTURE.md §6). Usage: python3 make_chord_etude.py"""
import json
TUNING = [40, 45, 50, 55, 59, 64]  # string 6 .. 1
BPM, NUM = 80, 4
beat = 60.0 / BPM
def note(s, f): return {"string": s, "fret": f, "midi": TUNING[6 - s] + f}
SHAPES = {  # string -> fret
    "C": {5: 3, 4: 2, 3: 0, 2: 1, 1: 0}, "Cmaj7": {5: 3, 4: 2, 3: 0, 2: 0, 1: 0}, "Am": {5: 0, 4: 2, 3: 2, 2: 1, 1: 0},
    "Em": {6: 0, 5: 2, 4: 2, 3: 0, 2: 0, 1: 0}, "G": {6: 3, 5: 2, 4: 0, 3: 0, 2: 0, 1: 3}, "D": {4: 0, 3: 2, 2: 3, 1: 2},
}
events, sections = [], []
def single(b, s, f, d=1): events.append({"t": round(b * beat, 4), "dur": round(d * beat, 4), "notes": [note(s, f)], "tech": []})
def chord(b, name, d=2): events.append({"t": round(b * beat, 4), "dur": round(d * beat, 4), "chord": name,
                                         "notes": [note(s, f) for s, f in sorted(SHAPES[name].items(), reverse=True)], "tech": ["strum_down"]})
b = 0
sections.append(("Open strings", b))
for s, f in [(6, 0), (5, 0), (4, 0), (3, 0), (2, 0), (1, 0), (2, 0), (3, 0), (4, 0), (5, 0), (6, 0)]: single(b, s, f); b += 1
b += 1  # rest
for s, f in [(5, 2), (5, 3), (4, 0), (4, 2), (3, 0), (3, 2), (2, 0), (2, 1)]: single(b, s, f); b += 1
sections.append(("Chords", b))
for name in ["C", "Cmaj7", "Am", "Em", "G", "D", "C", "G"]:
    chord(b, name); chord(b + 2, name); b += 4
sections.append(("Mixed", b))
for name, line in [("C", [(2, 1), (3, 0), (4, 2)]), ("Am", [(1, 0), (2, 1), (3, 2)]), ("G", [(1, 3), (2, 0), (3, 0)]), ("Cmaj7", [(2, 0), (3, 0), (4, 2)])]:
    chord(b, name, 1); b += 1
    for s, f in line: single(b, s, f); b += 1
chord(b, "C", 4); b += 4
end = round(b * beat, 4)
secs = [{"name": n, "start": round(s * beat, 4), "end": round((sections[i + 1][1] if i + 1 < len(sections) else b) * beat, 4)} for i, (n, s) in enumerate(sections)]
song = {"schema": 1, "title": "Mu Chord Etude", "artist": "theerfan", "source": "manual", "track": "Guitar", "duration": end,
        "tuning": TUNING, "capo": 0, "tempoMap": [{"beat": 0, "bpm": BPM}], "timeSig": [{"bar": 0, "num": NUM, "den": 4}],
        "sections": secs, "events": events}
json.dump(song, open("chord_etude.json", "w"), indent=1)
print(f"chord_etude.json: {len(events)} events, {end:.1f} s")
