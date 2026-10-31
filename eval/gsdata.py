"""GuitarSet loading, event extraction and audio augmentation for the gdsp evaluation harness."""
import glob, json, os, re, subprocess, zlib
import numpy as np
import soundfile as sf
from scipy import signal

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
GS = os.path.join(ROOT, 'data', 'guitarset')
CACHE = os.path.join(ROOT, 'data', 'cache')
TOOL = os.environ.get('GDSP_EVAL_TOOL', os.path.join(ROOT, 'dsp', 'build', 'gdsp_eval'))
OPEN = [40, 45, 50, 55, 59, 64]  # standard tuning, string index 0 = low E (JAMS data_source)
PC = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B']


def file_ids():
    return sorted(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(GS, 'annotation', '*.jams')))


def player(fid):
    return int(fid[:2])


def load(fid):
    d = json.load(open(os.path.join(GS, 'annotation', fid + '.jams')))
    notes, chords = [], []
    for a in d['annotations']:
        if a['namespace'] == 'note_midi':
            s = int(a['annotation_metadata']['data_source'])
            for n in a['data']:
                notes.append(dict(string=s, t=n['time'], end=n['time'] + n['duration'], midi=n['value']))
        elif a['namespace'] == 'chord':
            chords.append([(c['time'], c['time'] + c['duration'], c['value']) for c in a['data']])
    notes.sort(key=lambda n: n['t'])
    # chords[0] = instructed (lead sheet), chords[1] = performed (transcribed)
    return dict(id=fid, notes=notes, instructed=chords[0], performed=chords[1] if len(chords) > 1 else chords[0],
                audio=os.path.join(GS, 'audio_mono-mic', fid + '_mic.wav'))


def ref_onsets(notes, merge=0.05):
    """Note onsets merged into events: an onset within `merge` s of the cluster's first onset joins it (strums)."""
    out = []
    for t in sorted(n['t'] for n in notes):
        if not out or t - out[-1] > merge:
            out.append(t)
    return out


def chord_events(notes, gap=0.06, min_notes=3):
    """Clusters of near-simultaneous note onsets (strums/plucked chords) with >=min_notes distinct strings.
    expected = MIDI notes (rounded) sounding 100 ms after the first onset of the cluster."""
    clusters, cur = [], []
    for n in notes:
        if cur and n['t'] - cur[-1]['t'] > gap:
            clusters.append(cur); cur = []
        cur.append(n)
    if cur: clusters.append(cur)
    ev = []
    for i, c in enumerate(clusters):
        if len({n['string'] for n in c}) < min_notes:
            continue
        t0 = c[0]['t']; tq = t0 + 0.1
        sounding = sorted({int(round(n['midi'])) for n in notes if n['t'] <= tq < n['end']} |
                          {int(round(n['midi'])) for n in c})
        nxt = clusters[i + 1][0]['t'] if i + 1 < len(clusters) else t0 + 0.8
        ev.append(dict(t=t0, end=min(nxt, t0 + 0.8), midis=sounding))
    return [e for e in ev if e['end'] - e['t'] >= 0.15]


def label_at(chords, t):
    for a, b, v in chords:
        if a <= t < b:
            return v
    return 'N'


QUAL = {'maj': [0, 4, 7], 'min': [0, 3, 7], '7': [0, 4, 7, 10], 'maj7': [0, 4, 7, 11], 'min7': [0, 3, 7, 10],
        'hdim7': [0, 3, 6, 10], 'dim': [0, 3, 6], 'aug': [0, 4, 8], 'sus4': [0, 5, 7], 'sus2': [0, 2, 7]}


def simplify(label):
    """'D#:sus2(7)/1' -> ('D#', 'sus2') ; unknown qualities fall back to maj/min by third."""
    if label in ('N', 'X') or ':' not in label:
        return None
    root, q = label.split(':', 1)
    q = re.split(r'[(/]', q)[0]
    return root, q if q in QUAL else ('min' if q.startswith('min') else 'maj')


def canonical_voicing(label):
    """Lead-sheet style expected notes for a chord label: root in octave 3 (MIDI 48-59) + chord tones above,
    plus root an octave up, i.e. NOT the voicing actually played."""
    s = simplify(label)
    if not s:
        return None
    root, q = s
    r = PC.index(root.replace('Db', 'C#').replace('Eb', 'D#').replace('Gb', 'F#').replace('Ab', 'G#').replace('Bb', 'A#'))
    base = 48 + r
    return sorted({base + i for i in QUAL[q]} | {base + 12})


# ---------------- augmentation ----------------
def pink(n, rng):
    w = rng.standard_normal(n)
    b = [0.049922035, -0.095993537, 0.050612699, -0.004408786]
    a = [1, -2.494956002, 2.017265875, -0.522189400]
    return signal.lfilter(b, a, w)


CONDITIONS = {
    'clean': None,
    'pink20': dict(noise='pink', snr=20),
    'pink10': dict(noise='pink', snr=10),
    'white15': dict(noise='white', snr=15),
    'phone': dict(noise='pink', snr=20, band=(150, 6000)),  # cheap phone mic: no low end, rolled-off top
}


def audio_path(fid, cond):
    src = os.path.join(GS, 'audio_mono-mic', fid + '_mic.wav')
    if cond == 'clean':
        return src
    out = os.path.join(CACHE, cond, fid + '.wav')
    if os.path.exists(out):
        return out
    os.makedirs(os.path.dirname(out), exist_ok=True)
    x, sr = sf.read(src, dtype='float64')
    if x.ndim > 1: x = x.mean(1)
    c = CONDITIONS[cond]
    rng = np.random.default_rng(zlib.crc32(f"{fid}:{cond}".encode()))
    if 'band' in c:
        sos = signal.butter(2, c['band'], btype='band', fs=sr, output='sos')
        x = signal.sosfilt(sos, x)
    nz = pink(len(x), rng) if c['noise'] == 'pink' else rng.standard_normal(len(x))
    p_sig = np.mean(x ** 2)  # whole-file power (includes pauses), i.e. a conservative SNR
    nz *= np.sqrt(p_sig / np.mean(nz ** 2) / 10 ** (c['snr'] / 10))
    y = x + nz
    y *= 0.9 / max(1e-9, np.max(np.abs(y)))
    sf.write(out, y.astype(np.float32), sr, subtype='FLOAT')
    return out


def run_tool(wav, cmds, params=()):
    p = subprocess.run([TOOL, wav, *map(str, params)], input='\n'.join(cmds) + '\n', capture_output=True, text=True, check=True)
    return p.stdout.splitlines()
