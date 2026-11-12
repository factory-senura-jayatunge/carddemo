// Fails when the production bundle in dist/ is over the budget in package.json
// ("bundleBudget") or when the message catalogue or the lazy routes are placed wrongly.
// Sizes are gzip sizes in KiB. An optional argument names another web folder (the tests
// run it on small synthetic builds).
//
// - "Initial" is the entry chunk plus every chunk it imports statically, which is
//   what the first page load downloads before any lazy route.
// - Lazy routes: the pages src/router.tsx imports with import(). Each must have its own
//   chunk in the build (a page also imported statically has none, and fails), and that
//   chunk must stay within lazyRouteJsGzipKiB. A dynamic page chunk the router does not
//   list fails too. The chunks a route shares with others are listed and count toward
//   the total only.
// - Messages: the groups the build must hold are the src/messages/<group> files that an
//   app module imports (not the mocks or tests). Every key of those groups must be in
//   exactly one chunk, in the table shape `KEY:{code:`; a key in no chunk or in two fails.
//   A catalogue text as a `text:` value fails in a chunk that does not hold as many
//   keys with that text in the table shape, so a new table shape fails rather than
//   passes. Anywhere else a catalogue text fails unless the validation rules hold it too
//   (VALIDATION_TEXTS: the rule table's messages and the engine's default messages, which
//   repeat some catalogue texts), so a key-less table of any group still fails; every
//   group must have at least one text that is not a rule text, so there is one to watch. Only core and bill may reach an initial chunk.
//   A group file no app module imports is reported as not shipped, and fails if any of
//   its keys or texts is in the build anyway.
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { basename, dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ENTRY_GROUPS, GROUPS, entriesOfGroupFile, keysOfGroupFile } from './message-groups.mjs';

const web = resolve(process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), '..'));
const src = join(web, 'src');
const dist = join(web, 'dist');
const budget = JSON.parse(readFileSync(join(web, 'package.json'), 'utf8')).bundleBudget;
const manifest = JSON.parse(readFileSync(join(dist, '.vite/manifest.json'), 'utf8'));
const kib = (file) => gzipSync(readFileSync(join(dist, file)), { level: 9 }).length / 1024;
const fmt = (size) => size.toFixed(1).padStart(6);
const problems = [];

const keyOf = (chunk) => Object.keys(manifest).find((key) => manifest[key] === chunk);
const closure = (start) => {
  const files = new Set();
  const walk = (key) => {
    const chunk = manifest[key];
    if (!chunk || files.has(chunk.file)) return;
    files.add(chunk.file);
    (chunk.imports ?? []).forEach(walk);
  };
  walk(start);
  return files;
};
const entry = Object.values(manifest).find((chunk) => chunk.isEntry);
const initial = closure(keyOf(entry));

const assets = readdirSync(join(dist, 'assets'));
const sum = (files) => files.reduce((total, file) => total + kib(file), 0);
const js = assets.filter((f) => f.endsWith('.js')).map((f) => `assets/${f}`);
const css = assets.filter((f) => f.endsWith('.css')).map((f) => `assets/${f}`);

const rows = [
  ['initial JS', sum([...initial].filter((f) => f.endsWith('.js'))), budget.initialJsGzipKiB],
  ['total JS', sum(js), budget.totalJsGzipKiB],
  ['total CSS', sum(css), budget.totalCssGzipKiB],
];
for (const [label, size, limit] of rows) {
  const ok = size <= limit;
  if (!ok) problems.push(`${label} ${size.toFixed(1)} KiB is over ${limit} KiB`);
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${label.padEnd(10)} ${fmt(size)} KiB gzip (budget ${limit} KiB)`);
}

// The module a specifier names, without its extension, or undefined for a package.
const target = (file, specifier) => {
  const path = specifier.startsWith('@/') ? join(src, specifier.slice(2)) : specifier.startsWith('.') ? resolve(dirname(file), specifier) : undefined;
  return path?.replace(/\.(tsx?|jsx?|mts)$/, '');
};
const withoutExtension = (path) => path.replace(/\.(tsx?|jsx?|mts)$/, '');

// Lazy routes: the router's import() calls are the inventory.
const pages = join(src, 'pages');
const routerFile = join(src, 'router.tsx');
const inventory = existsSync(routerFile)
  ? [...readFileSync(routerFile, 'utf8').matchAll(/\bimport\(\s*['"]([^'"]+)['"]\s*\)/g)]
      .map((m) => target(routerFile, m[1]))
      .filter((path) => path !== undefined && dirname(path) === pages)
      .map((path) => basename(path))
  : [];
if (inventory.length === 0) problems.push('no lazy page found in src/router.tsx: the route list cannot be read');
const dynamicPages = new Map(
  Object.entries(manifest)
    .filter(([key, chunk]) => chunk.isDynamicEntry && /^src\/pages\//.test(chunk.src ?? key))
    .map(([key, chunk]) => [withoutExtension((chunk.src ?? key).replace(/^src\/pages\//, '')), { key, chunk }]),
);
const routes = [];
for (const name of new Set(inventory)) {
  const found = dynamicPages.get(name);
  if (!found) {
    problems.push(`route ${name}: the router loads it with import() but the build has no chunk of its own for it (is it also imported statically?)`);
    continue;
  }
  const own = kib(found.chunk.file);
  const firstUse = [...closure(found.key)].filter((file) => file.endsWith('.js') && !initial.has(file));
  routes.push({ name, file: found.chunk.file, own, firstUse: sum(firstUse) });
}
for (const name of dynamicPages.keys()) {
  if (!inventory.includes(name)) problems.push(`page ${name} is loaded dynamically but is not a lazy route in src/router.tsx`);
}
routes.sort((a, b) => b.own - a.own);
console.log(
  `lazy routes, ${routes.length} of ${new Set(inventory).size} in src/router.tsx (own chunk; budget ${budget.lazyRouteJsGzipKiB} KiB each; first use = own + shared chunks not yet loaded):`,
);
for (const route of routes) {
  const ok = route.own <= budget.lazyRouteJsGzipKiB;
  if (!ok) problems.push(`route ${route.name} ${route.own.toFixed(1)} KiB is over ${budget.lazyRouteJsGzipKiB} KiB`);
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${route.name.padEnd(26)} ${fmt(route.own)} KiB, first use ${fmt(route.firstUse)} KiB`);
}
const routeFiles = new Set(routes.map((route) => route.file));
const kindOf = (file) => (initial.has(file) ? 'initial' : routeFiles.has(file) ? 'route' : 'shared');
console.log('chunks:');
for (const file of js.sort((a, b) => kib(b) - kib(a))) console.log(`      ${kindOf(file).padEnd(8)} ${fmt(kib(file))} KiB  ${file}`);

// Message groups: the generated files, and the ones the app imports.
const messageDir = join(src, 'messages');
const groupFiles = existsSync(messageDir) ? readdirSync(messageDir).filter((name) => !['index.ts', 'all.ts'].includes(name)) : [];
const groups = [];
for (const name of groupFiles) {
  const group = name.replace(/\.ts$/, '');
  const text = readFileSync(join(messageDir, name), 'utf8');
  const entries = entriesOfGroupFile(text);
  if (!GROUPS.includes(group)) problems.push(`src/messages/${name} is not a group in scripts/message-groups.mjs`);
  else if (entries.length === 0 || entries.length !== keysOfGroupFile(text).length) problems.push(`src/messages/${name}: the checker cannot read its keys`);
  else groups.push({ group, entries });
}
groups.sort((a, b) => GROUPS.indexOf(a.group) - GROUPS.indexOf(b.group));
if (groups.length === 0) problems.push('no message groups in src/messages (npm run gen:messages)');

const appFiles = [];
const collect = (dir) => {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      if (![messageDir, join(src, 'mocks'), join(src, 'test')].includes(path)) collect(path);
    } else if (/\.(tsx?|jsx?|mts)$/.test(name) && !/\.test\.(tsx?|jsx?)$/.test(name)) appFiles.push(path);
  }
};
if (existsSync(src)) collect(src);
const importers = new Map();
const imported = (group, file) => importers.set(group, [...(importers.get(group) ?? []), relative(web, file)]);
for (const file of appFiles) {
  const code = readFileSync(file, 'utf8');
  const specifiers = [
    ...[...code.matchAll(/\b(?:import|export)\s+(type\s+)?([\w\s{},*$]*?)\s*from\s*['"]([^'"]+)['"]/g)]
      .filter(([, typeOnly, clause]) => !typeOnly && !/^\{(\s*type\s+[\w$]+(\s+as\s+[\w$]+)?\s*,?)*\s*\}$/.test(clause.trim()))
      .map((m) => m[3]),
    ...[...code.matchAll(/\bimport\s*\(?\s*['"]([^'"]+)['"]/g)].map((m) => m[1]),
  ];
  for (const specifier of specifiers) {
    const path = target(file, specifier);
    if (path === undefined || dirname(path) !== messageDir) continue;
    const name = basename(path);
    if (name === 'all') groups.forEach(({ group }) => imported(group, file));
    else if (name !== 'index') imported(name, file);
  }
}
for (const group of importers.keys()) {
  if (!groups.some((g) => g.group === group)) problems.push(`message group ${group} is imported by ${importers.get(group).join(', ')} but src/messages/${group}.ts is missing`);
}

const code = Object.fromEntries(js.map((file) => [file, readFileSync(join(dist, file), 'utf8')]));
const holds = (text, key) => new RegExp(`(^|[{,\\s])["']?${key}["']?:\\s*\\{\\s*["']?code["']?:`).test(text);
// Texts plain enough to be found as written in minified code, with the keys that carry each.
const plain = /^[A-Za-z0-9 ,.:;!?(){}/-]{16,}$/;
const textKeys = new Map();
for (const { entries } of groups) for (const { key, text } of entries) if (plain.test(text)) textKeys.set(text, [...(textKeys.get(text) ?? []), key]);
// The texts the validation rules carry under their own properties: `message` and `messages` in the
// rule files, and the engine's DEFAULT_MESSAGES. A catalogue text equal to one of them may appear
// outside a table; any other catalogue text may not.
const VALIDATION_TEXTS = [
  '../platform/validation/src/main/resources/org/carddemo/platform/validation/rules.json',
  'src/api/generated/validation-forms',
  '../platform/validation/src/main/ts/rules.ts',
];
const ruleTexts = new Set();
const collectRuleTexts = (node, property) => {
  if (typeof node === 'string') {
    if (property === 'message' || property === 'messages') ruleTexts.add(node);
  } else if (Array.isArray(node)) node.forEach((item) => collectRuleTexts(item, property));
  else if (node && typeof node === 'object') {
    for (const [name, value] of Object.entries(node)) collectRuleTexts(value, property === 'messages' ? 'messages' : name);
  }
};
for (const source of VALIDATION_TEXTS.map((path) => join(web, path))) {
  if (!existsSync(source)) {
    problems.push(`${relative(web, source)} is missing: the validation texts cannot be read`);
  } else if (source.endsWith('.ts')) {
    const block = /const DEFAULT_MESSAGES\b[^{]*\{([\s\S]*?)\n\};/.exec(readFileSync(source, 'utf8'))?.[1];
    if (block === undefined) problems.push(`${relative(web, source)}: DEFAULT_MESSAGES cannot be read`);
    else for (const m of block.matchAll(/:\s*'((?:[^'\\]|\\.)*)'/g)) ruleTexts.add(m[1]);
  } else {
    const files = statSync(source).isDirectory() ? readdirSync(source).filter((name) => name.endsWith('.json')).map((name) => join(source, name)) : [source];
    for (const file of files) collectRuleTexts(JSON.parse(readFileSync(file, 'utf8')));
  }
}
// Every group needs a text the check can watch, or a key-less copy of its table would pass unseen.
const watched = groups.map(({ group, entries }) => [group, entries.filter(({ text }) => plain.test(text) && !ruleTexts.has(text)).length]);
for (const [group, n] of watched) if (n === 0) problems.push(`message group ${group} has no text the checker can watch outside the tables`);
const ruleHits = new Map();
const escape = (text) => text.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');
for (const file of js) {
  for (const [text, keys] of textKeys) {
    const literals = code[file].match(new RegExp(`(["'\`])${escape(text)}\\1`, 'g'))?.length ?? 0;
    if (literals === 0) continue;
    const entries = code[file].match(new RegExp(`(^|[{,\\s])["']?text["']?:\\s*(["'\`])${escape(text)}\\2`, 'g'))?.length ?? 0;
    const read = keys.filter((key) => holds(code[file], key)).length;
    if (entries > read) problems.push(`${file} holds the text of ${keys.join('/')} ${entries} times but ${read} of its keys in a table shape the checker reads`);
    if (literals > entries && !ruleTexts.has(text)) problems.push(`${file} holds the text of ${keys.join('/')} outside a table entry, and the validation rules do not hold it`);
    else if (literals > entries) ruleHits.set(file, (ruleHits.get(file) ?? 0) + 1);
  }
}

console.log('messages:');
for (const { group, entries } of groups) {
  const shipped = importers.has(group);
  const where = new Map();
  const missing = [];
  for (const { key } of entries) {
    const files = js.filter((file) => holds(code[file], key));
    if (shipped && files.length === 0) missing.push(key);
    if (files.length > 1) problems.push(`message ${key} is in ${files.length} chunks: ${files.join(', ')}`);
    if (!shipped && files.length > 0) problems.push(`message ${key} is in ${files.join(', ')} although no app module imports its group ${group}`);
    for (const file of files) where.set(file, (where.get(file) ?? 0) + 1);
  }
  if (missing.length) {
    problems.push(`message group ${group}: ${missing.length} of ${entries.length} keys are in no chunk (${missing.slice(0, 3).join(', ')}${missing.length > 3 ? ', ...' : ''})`);
  }
  const entryGroup = ENTRY_GROUPS.includes(group);
  for (const file of where.keys()) {
    if (!entryGroup && initial.has(file)) problems.push(`message group ${group} reaches the initial chunk ${file}`);
  }
  const placed = shipped
    ? [...where].map(([file, n]) => `${file} (${kindOf(file)}, ${n})`).join(', ') || 'no chunk'
    : 'not shipped (no app module imports it)';
  console.log(`      ${group.padEnd(14)} ${String(entries.length).padStart(3)} keys${entryGroup ? ', entry' : ''}: ${placed}`);
}
const ruleHitList = [...ruleHits].map(([file, n]) => `${file} (${n})`).join(', ') || 'none';
console.log(`      catalogue texts outside the tables that the validation rules also hold (${ruleTexts.size} rule texts): ${ruleHitList}`);
console.log(`      texts watched outside the tables: ${watched.map(([group, n]) => `${group} ${n}`).join(', ')}`);

for (const problem of problems) console.log(`FAIL  ${problem}`);
console.log(problems.length ? 'budget: FAILED' : 'budget: OK');
process.exit(problems.length ? 1 : 0);
