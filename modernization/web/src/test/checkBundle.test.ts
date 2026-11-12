import { spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';

const web = resolve(import.meta.dirname, '../..');
const temp: string[] = [];

type Entries = [key: string, text: string][];
const SHARED = 'That record was not found anywhere.';
const RULE = 'Account number must be supplied here.';
const DEFAULT = 'The transaction field is too long here.';
const CORE: Entries = [
  ['SIGNED_OFF', 'You have been signed off from CardDemo.'],
  ['NOT_FOUND', SHARED],
];
const TRANSACTION: Entries = [
  ['TRNA_TOO_LONG', DEFAULT],
  ['TRAN_NOT_FOUND', SHARED],
  ['TRNA_ACCT_REQUIRED', RULE],
];
const REPORT: Entries = [['REPORT_NOT_READY', 'The report is not ready to download yet.']];

/** A generated group file, as scripts/gen-messages.mjs writes it. */
const groupFile = (group: string, entries: Entries) =>
  `// Generated\nexport const ${group}Messages = {\n${entries
    .map(([key, text]) => `  ${key}: { code: '${key}', text: '${text}', severity: 'error', params: [] },`)
    .join('\n')}\n} as const;\n`;
/** A minified table in the shape the build has today. */
const table = (entries: Entries) => `const t={${entries.map(([key, text]) => `${key}:{code:"${key}",text:"${text}",severity:"error",params:[]}`).join(',')}};`;
/** What the rule table and the engine put in a chunk: catalogue texts under their own properties. */
const ruleCode = `const r={code:"ACCT_ID_REQUIRED",message:"${RULE}"},d={TOO_LONG:"${DEFAULT}"};`;

const manifest = (pages: string[]) =>
  JSON.stringify({
    'index.html': { file: 'assets/index.js', isEntry: true, dynamicImports: pages.map((p) => `src/pages/${p}.tsx`) },
    ...Object.fromEntries(pages.map((p) => [`src/pages/${p}.tsx`, { file: `assets/${p}.js`, src: `src/pages/${p}.tsx`, isDynamicEntry: true }])),
  });

const RULES_JSON = '../platform/validation/src/main/resources/org/carddemo/platform/validation/rules.json';
const RULES_TS = '../platform/validation/src/main/ts/rules.ts';
const FORMS_JSON = 'src/api/generated/validation-forms/auth.json';

/**
 * A small web folder whose build passes: core and the rule texts in the entry, transaction in
 * route A, report imported by nothing. Paths are relative to the web folder.
 */
const base = (): Record<string, string | undefined> => ({
  'package.json': JSON.stringify({ bundleBudget: { initialJsGzipKiB: 190, totalJsGzipKiB: 280, lazyRouteJsGzipKiB: 8, totalCssGzipKiB: 40 } }),
  'src/router.tsx': "const A = later(() => import('@/pages/APage'), 'APage');\nconst B = later(() => import('./pages/BPage'), 'BPage');\n",
  'src/lib/messages.ts': "import { coreMessages } from '@/messages/core';\nimport type { EntryKey } from '@/messages';\n",
  'src/pages/APage.tsx': "import { transactionMessages } from '@/messages/transaction';\n",
  'src/pages/BPage.tsx': "import type { ReportKey } from '@/messages';\nimport { type ReportKey as R } from '@/messages/report';\n",
  'src/mocks/messages.ts': "import { messages } from '@/messages/all';\n",
  'src/pages/APage.test.tsx': "import { reportMessages } from '@/messages/report';\n",
  'src/messages/index.ts': '// types\n',
  'src/messages/all.ts': '// merged\n',
  'src/messages/core.ts': groupFile('core', CORE),
  'src/messages/transaction.ts': groupFile('transaction', TRANSACTION),
  'src/messages/report.ts': groupFile('report', REPORT),
  [RULES_JSON]: JSON.stringify({ forms: { a: { fields: [{ checks: [{ code: 'ACCT_ID_REQUIRED', message: RULE }] }] } } }),
  [FORMS_JSON]: JSON.stringify({ forms: { signOn: { fields: [{ messages: { NO_INPUT: 'Please enter the user ID now.' } }] } } }),
  [RULES_TS]: `const DEFAULT_MESSAGES: Record<string, string> = {\n  TOO_LONG: '${DEFAULT}',\n};\n`,
  'dist/.vite/manifest.json': manifest(['APage', 'BPage']),
  'dist/assets/index.js': table(CORE) + ruleCode,
  'dist/assets/APage.js': table(TRANSACTION),
  'dist/assets/BPage.js': 'export const B=1;',
});

/** check-bundle.mjs on a web folder made of the given files (undefined leaves a file out). */
function check(files: Record<string, string | undefined>, copySrc = false) {
  const root = join(mkdtempSync(join(tmpdir(), 'bundle-')), 'web');
  temp.push(dirname(root));
  if (copySrc) cpSync(join(web, 'src'), join(root, 'src'), { recursive: true });
  for (const [path, text] of Object.entries(files)) {
    if (text === undefined) continue;
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), text);
  }
  const run = spawnSync(process.execPath, ['scripts/check-bundle.mjs', root], { cwd: web, encoding: 'utf8' });
  return { status: run.status, out: run.stdout + run.stderr };
}

afterEach(() => {
  for (const folder of temp.splice(0)) rmSync(folder, { recursive: true, force: true });
});

describe('check-bundle', () => {
  it('passes a correct build, lists both routes, and reports a group no app module imports as not shipped', () => {
    const { status, out } = check(base());
    expect(out).toContain('budget: OK');
    expect(status).toBe(0);
    expect(out).toContain('lazy routes, 2 of 2 in src/router.tsx');
    expect(out).toMatch(/core\s+2 keys, entry: assets\/index\.js \(initial, 2\)/);
    expect(out).toMatch(/transaction\s+3 keys: assets\/APage\.js \(route, 3\)/);
    expect(out).toMatch(/report\s+1 keys: not shipped/);
  });

  it('passes catalogue texts that the rule table and the engine defaults also hold, and counts them', () => {
    const { status, out } = check(base());
    expect(status).toBe(0);
    expect(out).toMatch(/validation rules also hold \(3 rule texts\): assets\/index\.js \(2\)/);
  });

  it('fails a catalogue text outside a table when the validation rules do not hold it', () => {
    const { status, out } = check({ ...base(), 'dist/assets/BPage.js': `const r={code:"X",message:"${SHARED}"};` });
    expect(status).toBe(1);
    expect(out).toContain('assets/BPage.js holds the text of NOT_FOUND/TRAN_NOT_FOUND outside a table entry, and the validation rules do not hold it');
  });

  it('fails a group whose every text is also a rule text, since a key-less copy of it could not be seen', () => {
    const rules = JSON.stringify({ forms: { a: { fields: [{ checks: [{ message: RULE }, { message: REPORT[0]![1] }] }] } } });
    const { status, out } = check({ ...base(), [RULES_JSON]: rules });
    expect(status).toBe(1);
    expect(out).toContain('message group report has no text the checker can watch outside the tables');
    expect(out).toContain('texts watched outside the tables: core 2, transaction 1, report 0');
  });

  it('fails when the validation texts cannot be read', () => {
    for (const path of [RULES_JSON, RULES_TS, FORMS_JSON]) {
      const { status, out } = check({ ...base(), [path]: undefined });
      expect(status).toBe(1);
      expect(out).toMatch(/is missing: the validation texts cannot be read/);
    }
    expect(check({ ...base(), [RULES_TS]: 'export const x = 1;\n' }).out).toContain('DEFAULT_MESSAGES cannot be read');
  });

  it('fails when an imported group is in a table shape it cannot read, with or without a text property', () => {
    const tuples = `const t=Object.fromEntries([${TRANSACTION.map(([key, text]) => `["${key}",{code:"${key}",text:"${text}"}]`).join(',')}]);`;
    const shaped = check({ ...base(), 'dist/assets/APage.js': tuples });
    expect(shaped.status).toBe(1);
    expect(shaped.out).toContain('message group transaction: 3 of 3 keys are in no chunk');
    expect(shaped.out).toContain('assets/APage.js holds the text of TRNA_TOO_LONG 1 times but 0 of its keys in a table shape the checker reads');
    const bare = check({ ...base(), 'dist/assets/APage.js': `const t=[${TRANSACTION.map(([key, text]) => `["${key}","${text}"]`).join(',')}];` });
    expect(bare.status).toBe(1);
    expect(bare.out).toContain('message group transaction: 3 of 3 keys are in no chunk');
  });

  it('fails a text property in an unread shape even when the validation rules hold the same text', () => {
    const { status, out } = check({ ...base(), 'dist/assets/BPage.js': `const m=new Map([["TRNA_ACCT_REQUIRED",{text:"${RULE}"}]]);` });
    expect(status).toBe(1);
    expect(out).toContain('assets/BPage.js holds the text of TRNA_ACCT_REQUIRED 1 times but 0 of its keys in a table shape the checker reads');
  });

  it('does not let a key of another group with the same text cover an unread copy', () => {
    const copy = `const m=new Map([["TRAN_NOT_FOUND",{text:"${SHARED}"}]]);`;
    const { status, out } = check({ ...base(), 'dist/assets/index.js': table(CORE) + ruleCode + copy });
    expect(status).toBe(1);
    expect(out).toContain('assets/index.js holds the text of NOT_FOUND/TRAN_NOT_FOUND 2 times but 1 of its keys in a table shape the checker reads');
  });

  it('fails when a group no app module imports is in the build, in the table shape, as key-less tuples or with text properties', () => {
    const shape = check({ ...base(), 'dist/assets/BPage.js': table(REPORT) });
    expect(shape.status).toBe(1);
    expect(shape.out).toContain('message REPORT_NOT_READY is in assets/BPage.js although no app module imports its group report');
    const tuples = check({ ...base(), 'dist/assets/BPage.js': `const t=[${REPORT.map(([key, text]) => `["${key}","${key}","${text}"]`).join(',')}];` });
    expect(tuples.status).toBe(1);
    expect(tuples.out).toContain('assets/BPage.js holds the text of REPORT_NOT_READY outside a table entry, and the validation rules do not hold it');
    const texts = check({ ...base(), 'dist/assets/BPage.js': `const t=Object.fromEntries([${REPORT.map(([key, text]) => `["${key}",{text:"${text}"}]`).join(',')}]);` });
    expect(texts.status).toBe(1);
    expect(texts.out).toContain('assets/BPage.js holds the text of REPORT_NOT_READY 1 times but 0 of its keys');
  });

  it('fails when an imported group is in no chunk at all', () => {
    const { status, out } = check({ ...base(), 'dist/assets/APage.js': 'export const A=1;' });
    expect(status).toBe(1);
    expect(out).toMatch(/transaction\s+3 keys: no chunk/);
    expect(out).toContain('message group transaction: 3 of 3 keys are in no chunk');
  });

  it('fails when one key of an imported group is missing', () => {
    const { status, out } = check({ ...base(), 'dist/assets/APage.js': table(TRANSACTION.slice(0, 2)) });
    expect(status).toBe(1);
    expect(out).toContain('message group transaction: 1 of 3 keys are in no chunk (TRNA_ACCT_REQUIRED)');
  });

  it('fails when a key is in two chunks', () => {
    const { status, out } = check({ ...base(), 'dist/assets/BPage.js': table(TRANSACTION.slice(1, 2)) });
    expect(status).toBe(1);
    expect(out).toContain('message TRAN_NOT_FOUND is in 2 chunks');
  });

  it('fails when a feature group reaches the initial chunk', () => {
    const { status, out } = check({ ...base(), 'dist/assets/index.js': table([...CORE, ...TRANSACTION]) + ruleCode, 'dist/assets/APage.js': 'export const A=1;' });
    expect(status).toBe(1);
    expect(out).toContain('message group transaction reaches the initial chunk assets/index.js');
  });

  it('counts a relative import and an import of the merged table as shipping, and a type-only import as not', () => {
    const relative = check({ ...base(), 'src/pages/BPage.tsx': "import { reportMessages } from '../messages/report';\n" });
    expect(relative.status).toBe(1);
    expect(relative.out).toContain('message group report: 1 of 1 keys are in no chunk');
    const merged = check({ ...base(), 'src/pages/BPage.tsx': "import { messages } from '@/messages/all';\n" });
    expect(merged.out).toContain('message group report: 1 of 1 keys are in no chunk');
  });

  it('fails when a group file cannot be read or is not a group, and when an imported group has no file', () => {
    expect(check({ ...base(), 'src/messages/report.ts': 'export const reportMessages = new Map();\n' }).out).toContain(
      'src/messages/report.ts: the checker cannot read its keys',
    );
    expect(check({ ...base(), 'src/messages/extra.ts': groupFile('extra', REPORT) }).out).toContain('src/messages/extra.ts is not a group');
    const gone = check({ ...base(), 'src/messages/transaction.ts': undefined, 'dist/assets/APage.js': 'export const A=1;' });
    expect(gone.status).toBe(1);
    expect(gone.out).toContain('message group transaction is imported by src/pages/APage.tsx but src/messages/transaction.ts is missing');
  });

  it('fails when a lazy route has no chunk of its own, as when it is also imported statically', () => {
    const { status, out } = check({ ...base(), 'dist/.vite/manifest.json': manifest(['APage']) });
    expect(status).toBe(1);
    expect(out).toContain('route BPage: the router loads it with import() but the build has no chunk of its own');
    expect(out).toContain('lazy routes, 1 of 2 in src/router.tsx');
  });

  it('fails on a dynamic page the router does not list, and when the router lists no lazy page', () => {
    const extra = check({ ...base(), 'dist/.vite/manifest.json': manifest(['APage', 'BPage', 'CPage']), 'dist/assets/CPage.js': 'export const C=1;' });
    expect(extra.status).toBe(1);
    expect(extra.out).toContain('page CPage is loaded dynamically but is not a lazy route in src/router.tsx');
    const none = check({ ...base(), 'src/router.tsx': "import { APage } from '@/pages/APage';\n" });
    expect(none.status).toBe(1);
    expect(none.out).toContain('no lazy page found in src/router.tsx');
  });

  it('reads the real router, message imports and validation texts', () => {
    const real = (path: string) => readFileSync(join(web, path), 'utf8');
    const groups = readdirSync(join(web, 'src/messages')).filter((name) => !['index.ts', 'all.ts'].includes(name));
    const pages = [...real('src/router.tsx').matchAll(/import\('@\/pages\/(\w+)'\)/g)].map((m) => m[1]!);
    const tableOf = (name: string) =>
      [...real(`src/messages/${name}`).matchAll(/^ {2}([A-Z][A-Z0-9_]*): \{ code: /gm)].map((m) => `${m[1]}:{code:"x"}`).join(',');
    const { status, out } = check(
      {
        'package.json': base()['package.json'],
        [RULES_JSON]: real(RULES_JSON),
        [RULES_TS]: real(RULES_TS),
        'dist/.vite/manifest.json': manifest(pages),
        'dist/assets/index.js': `const t={${tableOf('core.ts')},${tableOf('bill.ts')}};`,
        ...Object.fromEntries(pages.map((page) => [`dist/assets/${page}.js`, 'export const p=1;'])),
        ...Object.fromEntries(
          groups.filter((name) => !['core.ts', 'bill.ts'].includes(name)).map((name) => [`dist/assets/${name.replace('.ts', '')}.js`, `const t={${tableOf(name)}};`]),
        ),
      },
      true,
    );
    expect(pages).toHaveLength(15);
    expect(out).toContain(`lazy routes, ${pages.length} of ${pages.length} in src/router.tsx`);
    expect(out).not.toContain('not shipped');
    expect(out).toMatch(/validation rules also hold \([1-9][0-9]* rule texts\): none/);
    expect(out).toContain('budget: OK');
    expect(status).toBe(0);
  });
});
