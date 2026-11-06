// Fails when the production bundle in dist/ is over the budget in package.json
// ("bundleBudget"). Sizes are gzip sizes in KiB. "Initial" is the entry chunk
// plus every chunk it imports statically, which is what the first page load
// downloads before any lazy route.
import { readFileSync, readdirSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(web, 'dist');
const budget = JSON.parse(readFileSync(join(web, 'package.json'), 'utf8')).bundleBudget;
const manifest = JSON.parse(readFileSync(join(dist, '.vite/manifest.json'), 'utf8'));
const kib = (file) => gzipSync(readFileSync(join(dist, file)), { level: 9 }).length / 1024;

const entry = Object.values(manifest).find((chunk) => chunk.isEntry);
const initial = new Set();
const walk = (key) => {
  const chunk = manifest[key];
  if (!chunk || initial.has(chunk.file)) return;
  initial.add(chunk.file);
  (chunk.imports ?? []).forEach(walk);
};
walk(Object.keys(manifest).find((key) => manifest[key] === entry));

const assets = readdirSync(join(dist, 'assets'));
const sum = (files) => files.reduce((total, file) => total + kib(file), 0);
const js = assets.filter((f) => f.endsWith('.js')).map((f) => `assets/${f}`);
const css = assets.filter((f) => f.endsWith('.css')).map((f) => `assets/${f}`);
const rows = [
  ['initial JS', sum([...initial].filter((f) => f.endsWith('.js'))), budget.initialJsGzipKiB],
  ['total JS', sum(js), budget.totalJsGzipKiB],
  ['total CSS', sum(css), budget.totalCssGzipKiB],
];
let failed = false;
for (const [label, size, limit] of rows) {
  const ok = size <= limit;
  failed ||= !ok;
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${label.padEnd(10)} ${size.toFixed(1).padStart(6)} KiB gzip (budget ${limit} KiB)`);
}
console.log(failed ? 'budget: FAILED' : 'budget: OK');
process.exit(failed ? 1 : 0);
