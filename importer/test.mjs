// Importer tests: node test.mjs  (or npm test)
import assert from 'node:assert/strict';
import { loadScore, scoreToSong } from './mu-import.mjs';
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
const dir = new URL('./samples/', import.meta.url).pathname;
const run = (f) => JSON.parse(execFileSync('node', [new URL('./mu-import.mjs', import.meta.url).pathname, dir + f]));
let n = 0; const ok = (name, fn) => { fn(); n++; console.log('PASS ', name); };

// Exercise 2: same piece authored as MusicXML (by hand) and GP3/4/5 (PyGuitarPro).
const ref = run('exercise2.musicxml');
ok('exercise2 musicxml: header', () => {
  assert.equal(ref.title, 'Mu Exercise 2'); assert.deepEqual(ref.tuning, [40, 45, 50, 55, 59, 64]);
  assert.deepEqual(ref.tempoMap, [{ beat: 0, bpm: 90 }]); assert.equal(ref.events.length, 8);
});
ok('exercise2 musicxml: timing/notes', () => {
  assert.deepEqual(ref.events.map((e) => e.t), [0, 0.667, 1.333, 2, 2.667, 3, 3.333, 4]);
  assert.deepEqual(ref.events[0].notes, [{ string: 6, fret: 0, midi: 40 }]);
  assert.deepEqual(ref.events[7].notes.map((x) => x.midi), [48, 52, 55, 60, 64]);
  assert.equal(ref.events[7].dur, 1.333);
});
ok('exercise2 musicxml: hammer + bend', () => {
  assert.deepEqual(ref.events[5].tech, ['hammer']);
  assert.deepEqual(ref.events[6].tech, [{ type: 'bend', semitones: 2 }]);
});
for (const f of ['exercise2.gp5', 'exercise2.gp4', 'exercise2.gp3'])
  ok(`${f} == musicxml events`, () => assert.deepEqual(run(f).events, ref.events));

// Exercise 1: alphaTex source and its Guitar Pro 7 export; tempo change, sections, ties, chords, strums.
const tex = run('exercise1.tex'), gp7 = run('exercise1.gp');
ok('exercise1 gp7 == alphaTex (all fields but source)', () => {
  assert.deepEqual({ ...gp7, source: 0 }, { ...tex, source: 0 }); assert.equal(gp7.source, 'gp7');
});
ok('exercise1 tempo change + sections', () => {
  assert.deepEqual(tex.tempoMap, [{ beat: 0, bpm: 90 }, { beat: 8, bpm: 120 }]);
  assert.deepEqual(tex.sections, [{ name: 'Intro', start: 0, end: 5.333 }, { name: 'Verse', start: 5.333, end: 9.333 }]);
});
ok('exercise1 chords + strum direction', () => {
  const ch = tex.events.filter((e) => e.chord);
  assert.deepEqual(ch.map((e) => [e.chord, e.t, e.tech[0]]), [['C', 4, 'strum_down'], ['G', 5.333, 'strum_down'], ['Em', 6.333, 'strum_up']]);
});
ok('exercise1 palm mute + tie extends duration', () => {
  const last = tex.events.slice(-3);
  assert.deepEqual(last.map((e) => e.tech), [['palm_mute'], ['palm_mute'], []]);
  assert.equal(last[2].t, 8.333); assert.equal(last[2].dur, 1);  // quarter tied to quarter at 120 bpm
});
console.log(`== ${n} passed`);
