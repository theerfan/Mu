"""Authors samples/exercise2.gp5 with PyGuitarPro (original content, no copyrighted material).
Piece (90 bpm, 4/4, standard tuning):
  bar 1: quarters  E2(6/0) A2(5/0) E3(4/2) G3(3/0)
  bar 2: eighths   C4(3/5) -h-> D4(3/7), quarter D4(3/7) with full bend, half C major chord
usage: python make_gp5.py out.gp5   (also writes .gp4 and .gp3 next to it)"""
import sys
import guitarpro as gp

song = gp.Song()
song.title, song.artist, song.tempo = "Mu Exercise 2", "theerfan", 90
track = song.tracks[0]
track.name = "Guitar"
track.strings = [gp.GuitarString(i + 1, v) for i, v in enumerate([64, 59, 55, 50, 45, 40])]  # string 1 = high E
song.newMeasure()  # second bar

def beat(voice, dur, notes, stroke=None):
    b = gp.Beat(voice, duration=gp.Duration(value=dur))
    for s, f, eff in notes:
        n = gp.Note(b, value=f, string=s, type=gp.NoteType.normal)
        if eff == "h": n.effect.hammer = True
        if eff == "b":
            n.effect.bend = gp.BendEffect(type=gp.BendType.bend, value=100,
                points=[gp.BendPoint(0, 0), gp.BendPoint(6, 4), gp.BendPoint(12, 4)])  # value in quarter tones*? (see GP spec)
        b.notes.append(n)
    if stroke: b.effect.stroke = gp.BeatStroke(direction=stroke, value=gp.Duration.sixteenth)
    b.status = gp.BeatStatus.normal
    voice.beats.append(b)

v1 = track.measures[0].voices[0]
for s, f in [(6, 0), (5, 0), (4, 2), (3, 0)]: beat(v1, 4, [(s, f, None)])
v2 = track.measures[1].voices[0]
beat(v2, 8, [(3, 5, "h")]); beat(v2, 8, [(3, 7, None)]); beat(v2, 4, [(3, 7, "b")])
beat(v2, 2, [(5, 3, None), (4, 2, None), (3, 0, None), (2, 1, None), (1, 0, None)])
out = sys.argv[1] if len(sys.argv) > 1 else "exercise2.gp5"
gp.write(song, out)
for ver, ext in [((4, 0, 0), "gp4"), ((3, 0, 0), "gp3")]:  # older formats from the same model
    gp.write(song, out.rsplit(".", 1)[0] + "." + ext, version=ver)
