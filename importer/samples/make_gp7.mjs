// Authors samples/exercise1.gp (Guitar Pro 7 format) from samples/exercise1.tex via alphaTab's Gp7Exporter.
// usage: node samples/make_gp7.mjs
import fs from 'node:fs';
import * as alphaTab from '@coderline/alphatab';
import { loadScore } from '../mu-import.mjs';
const dir = new URL('.', import.meta.url).pathname;
const score = loadScore(dir + 'exercise1.tex');
fs.writeFileSync(dir + 'exercise1.gp', new alphaTab.exporter.Gp7Exporter().export(score, new alphaTab.Settings()));
console.log('wrote', dir + 'exercise1.gp');
