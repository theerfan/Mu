#!/usr/bin/env node
// mu-import: Guitar Pro (gp3/gp4/gp5/gpx/gp7-8 .gp) / MusicXML / alphaTex -> Mu song JSON (schema 1).
// Parsing is done by alphaTab (@coderline/alphatab); this file maps alphaTab's model to our schema.
// usage: node mu-import.mjs <input> [-o out.json] [--track N] [--list]
import fs from 'node:fs';
import path from 'node:path';
import * as alphaTab from '@coderline/alphatab';

const TICKS_PER_QUARTER = 960;  // alphaTab MidiUtils.QuarterTime

export function loadScore(file) {
  const settings = new alphaTab.Settings();
  const data = new Uint8Array(fs.readFileSync(file));
  if (/\.(tex|atex|alphatex)$/i.test(file)) {
    const imp = new alphaTab.importer.AlphaTexImporter();
    imp.initFromString(new TextDecoder().decode(data), settings);
    return imp.readScore();
  }
  // ScoreLoader sniffs the format (GP3-5, GPX, GP7/8, MusicXML incl. compressed .mxl, CapXML).
  return alphaTab.importer.ScoreLoader.loadScoreFromBytes(data, settings);
}

function buildTempoMap(score) {
  // [{tick, bpm}] sorted; tempo automations live on master bars.
  const map = [];
  for (const mb of score.masterBars) {
    const autos = mb.tempoAutomations ?? (mb.tempoAutomation ? [mb.tempoAutomation] : []);
    for (const a of autos) map.push({ tick: mb.start + a.ratioPosition * mb.calculateDuration(), bpm: a.value });
  }
  if (!map.length || map[0].tick > 0) map.unshift({ tick: 0, bpm: score.tempo || 120 });
  map.sort((a, b) => a.tick - b.tick);
  // collapse duplicates at the same tick (keep the last) and no-op changes
  const out = [];
  for (const m of map) {
    if (out.length && out.at(-1).tick === m.tick) out[out.length - 1] = m;
    else if (!out.length || out.at(-1).bpm !== m.bpm) out.push(m);
  }
  return out;
}
function ticksToSec(map, tick) {
  let sec = 0;
  for (let i = 0; i < map.length; i++) {
    const end = i + 1 < map.length ? Math.min(map[i + 1].tick, tick) : tick;
    if (tick <= map[i].tick) break;
    if (end <= map[i].tick) continue;
    sec += ((end - map[i].tick) / TICKS_PER_QUARTER) * (60 / map[i].bpm);
  }
  return sec;
}
const clean = (x) => (x ?? '').replace(/\u00a0/g, ' ').trim();  // alphaTab's MusicXML reader emits NBSPs
const r3 = (x) => Math.round(x * 1000) / 1000;

// alphaTab 1.8's MusicXML importer drops <hammer-on>/<pull-off> and <slide>, so for MusicXML we
// read those markings ourselves. Key: `${partIndex}:${measureIndex}:${string}:${fret}#${occurrence}`.
function musicXmlLegatoMarks(xml) {
  const marks = new Map();
  const parts = [...xml.matchAll(/<part\b[^>]*>([\s\S]*?)<\/part>/g)];
  parts.forEach((pm, pi) => {
    [...pm[1].matchAll(/<measure\b[^>]*>([\s\S]*?)<\/measure>/g)].forEach((mm, mi) => {
      const seen = new Map();
      for (const nm of mm[1].matchAll(/<note\b[^>]*>([\s\S]*?)<\/note>/g)) {
        const body = nm[1];
        const str = body.match(/<string>\s*(\d+)\s*<\/string>/), fret = body.match(/<fret>\s*(\d+)\s*<\/fret>/);
        if (!str || !fret) continue;
        const base = `${pi}:${mi}:${+str[1]}:${+fret[1]}`, occ = seen.get(base) ?? 0; seen.set(base, occ + 1);
        const m = {};
        if (/<(hammer-on|pull-off)\b[^>]*type="start"/.test(body)) m.legatoStart = true;
        if (/<slide\b[^>]*type="start"/.test(body)) m.slide = true;
        if (m.legatoStart || m.slide) marks.set(`${base}#${occ}`, m);
      }
    });
  });
  return marks;
}

function techniques(beat, note) {
  const t = [];
  if (note.isHammerPullDestination && note.hammerPullOrigin)
    t.push(note.fret >= note.hammerPullOrigin.fret ? 'hammer' : 'pull');
  if (note.slideOutType && note.slideOutType !== 0) t.push('slide');
  if (note.hasBend && note.bendPoints?.length) {
    const maxQ = Math.max(...note.bendPoints.map((p) => p.value));  // value in quarter tones
    t.push({ type: 'bend', semitones: maxQ / 2 });
  }
  if (note.vibrato && note.vibrato !== 0) t.push('vibrato');
  if (note.isPalmMute) t.push('palm_mute');
  if (note.harmonicType && note.harmonicType !== 0) t.push('harmonic');
  if (note.isDead) t.push('dead');
  return t;
}

export function scoreToSong(score, { track: trackIdx, source = 'unknown', xmlMarks = null } = {}) {
  const tracks = score.tracks;
  const isGuitar = (tr) => tr.staves.some((s) => s.tuning?.length >= 4 && !s.isPercussion);
  const tr = trackIdx != null ? tracks[trackIdx] : tracks.find(isGuitar);
  if (!tr) throw new Error('no stringed (tab) track found; use --list and --track N');
  const staff = tr.staves.find((s) => s.tuning?.length) ?? tr.staves[0];
  const nStrings = staff.tuning.length;
  const tempoMap = buildTempoMap(score);
  const events = [];
  const lastByString = new Map();  // for ties: string -> event note
  const legatoFrom = new Map();     // schema string -> origin fret (MusicXML supplement)
  const partIndex = tracks.indexOf(tr);
  for (const bar of staff.bars) {
    const occ = new Map();
    for (const voice of bar.voices) {
      for (const beat of voice.beats) {
        if (beat.isRest || !beat.notes.length) continue;
        const t0 = beat.absolutePlaybackStart, t1 = t0 + beat.playbackDuration;
        const sec0 = ticksToSec(tempoMap, t0), sec1 = ticksToSec(tempoMap, t1);
        const notes = [], beatTech = new Set();
        for (const n of beat.notes) {
          if (n.isTieDestination) {  // extend the tied note instead of re-attacking
            const prev = lastByString.get(n.string);
            if (prev) prev.ev.dur = r3(sec1 - prev.ev.t);
            continue;
          }
          if (n.string == null || n.string < 1 || n.fret < 0) continue;  // not a tab note
          const nn = { string: nStrings - n.string + 1, fret: n.fret, midi: n.realValue };
          const tech = techniques(beat, n);
          if (xmlMarks) {
            const base = `${partIndex}:${bar.index}:${nn.string}:${n.fret}`, o = occ.get(base) ?? 0; occ.set(base, o + 1);
            const mk = xmlMarks.get(`${base}#${o}`);
            if (legatoFrom.has(nn.string) && !tech.some((x) => x === 'hammer' || x === 'pull'))
              tech.unshift(n.fret >= legatoFrom.get(nn.string) ? 'hammer' : 'pull');
            legatoFrom.delete(nn.string);
            if (mk?.legatoStart) legatoFrom.set(nn.string, n.fret);
            if (mk?.slide && !tech.includes('slide')) tech.push('slide');
          }
          if (tech.length) nn.tech = tech;
          notes.push(nn);
        }
        // alphaTab BrushType: 1 BrushUp, 2 BrushDown, 3 ArpeggioUp, 4 ArpeggioDown; PickStroke: 1 Up, 2 Down.
        if (beat.brushType === 2 || beat.pickStroke === 2) beatTech.add('strum_down');
        if (beat.brushType === 1 || beat.pickStroke === 1) beatTech.add('strum_up');
        if (beat.brushType === 3) beatTech.add('arpeggio_up');
        if (beat.brushType === 4) beatTech.add('arpeggio_down');
        if (!notes.length) continue;
        notes.sort((a, b) => b.string - a.string);
        // Hoist per-note techniques into event tech when there is only one note (schema v1 style).
        const tech = [...beatTech];
        if (notes.length === 1 && notes[0].tech) { tech.push(...notes[0].tech); delete notes[0].tech; }
        const ev = { t: r3(sec0), dur: r3(sec1 - sec0) };
        if (beat.chord?.name) ev.chord = clean(beat.chord.name);
        ev.notes = notes; ev.tech = tech;
        events.push(ev);
        for (const n of beat.notes) if (!n.isTieDestination) lastByString.set(n.string, { ev });
      }
    }
  }
  events.sort((a, b) => a.t - b.t);
  const sections = [];
  score.masterBars.forEach((mb, i) => {
    if (mb.section) sections.push({ name: clean(mb.section.text) || clean(mb.section.marker) || `Section ${sections.length + 1}`, start: r3(ticksToSec(tempoMap, mb.start)) });
  });
  const endSec = r3(ticksToSec(tempoMap, score.masterBars.at(-1).start + score.masterBars.at(-1).calculateDuration()));
  sections.forEach((s, i) => (s.end = i + 1 < sections.length ? sections[i + 1].start : endSec));
  const timeSig = [];
  score.masterBars.forEach((mb, i) => {
    const prev = timeSig.at(-1);
    if (!prev || prev.num !== mb.timeSignatureNumerator || prev.den !== mb.timeSignatureDenominator)
      timeSig.push({ bar: i, num: mb.timeSignatureNumerator, den: mb.timeSignatureDenominator });
  });
  return {
    schema: 1,
    title: clean(score.title), artist: clean(score.artist), source,
    track: clean(tr.name) || `Track ${tr.index + 1}`,
    tuning: [...staff.tuning].reverse(),  // low -> high (alphaTab stores high -> low)
    capo: staff.capo || 0,
    tempoMap: tempoMap.map((x) => ({ beat: r3(x.tick / TICKS_PER_QUARTER), bpm: x.bpm })),
    timeSig, sections, events, duration: endSec,
  };
}

function sourceOf(file) {
  const e = path.extname(file).toLowerCase().slice(1);
  return { gp: 'gp7', musicxml: 'musicxml', xml: 'musicxml', mxl: 'musicxml', tex: 'alphatex' }[e] ?? e;
}

if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith('mu-import.mjs')) {
  const args = process.argv.slice(2);
  if (!args.length || args.includes('-h')) {
    console.error('usage: node mu-import.mjs <input.gp|gpx|gp5|gp4|gp3|musicxml|xml|mxl|tex> [-o out.json] [--track N] [--list]');
    process.exit(2);
  }
  const input = args[0]; let out = null, track = null;
  for (let i = 1; i < args.length; i++) {
    if (args[i] === '-o') out = args[++i];
    else if (args[i] === '--track') track = parseInt(args[++i], 10);
  }
  const score = loadScore(input);
  if (args.includes('--list')) {
    score.tracks.forEach((t, i) => console.log(`${i}: ${t.name} strings=${t.staves[0]?.tuning?.length ?? 0}`));
    process.exit(0);
  }
  const src = sourceOf(input);
  const xmlMarks = src === 'musicxml' && !/\.mxl$/i.test(input) ? musicXmlLegatoMarks(fs.readFileSync(input, 'utf8')) : null;
  const json = JSON.stringify(scoreToSong(score, { track, source: src, xmlMarks }), null, 1);
  if (out) fs.writeFileSync(out, json + '\n'); else process.stdout.write(json + '\n');
}
