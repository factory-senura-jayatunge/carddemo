// Writes what the web app needs from platform/validation that is not imported as source:
//
// - The declarations of the TypeScript rule engine into src/api/generated/validation/, for tsc.
//   Vite and Vitest run the engine's own source through the @carddemo/validation alias; only the
//   type check reads these declarations, because the engine is written for the platform's compiler
//   options and the web app's stricter ones (noUncheckedIndexedAccess) would report the platform's
//   code as the web app's errors.
// - The forms of the pages in the first page load (sign-on and password change) into
//   src/api/generated/validation-forms/auth.json: those forms of rules.json, unchanged, with the
//   lookup lists they name. The pages run them on the same engine; the whole table (13 forms) stays
//   out of the entry chunk and is loaded with the pages that need it.
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = resolve(web, '../platform/validation/src/main/ts');
const data = resolve(web, '../platform/validation/src/main/resources/org/carddemo/platform/validation');
const outDir = join(web, 'src/api/generated/validation');
const formsDir = join(web, 'src/api/generated/validation-forms');

/** The forms the eagerly loaded pages validate: SignOnPage and ChangePasswordPage. */
const AUTH_FORMS = ['signOn', 'passwordChange', 'passwordChangeAnyMode'];

rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });
const program = ts.createProgram([join(source, 'rules.ts')], {
  declaration: true,
  emitDeclarationOnly: true,
  outDir,
  rootDir: source,
  target: ts.ScriptTarget.ES2023,
  module: ts.ModuleKind.ESNext,
  moduleResolution: ts.ModuleResolutionKind.Bundler,
  allowImportingTsExtensions: true,
  strict: true,
  skipLibCheck: true,
  types: [],
});
const result = program.emit();
const errors = ts
  .getPreEmitDiagnostics(program)
  .concat(result.diagnostics)
  .filter((d) => d.category === ts.DiagnosticCategory.Error);
if (result.emitSkipped || errors.length > 0) {
  console.error(ts.formatDiagnostics(errors, { getCanonicalFileName: (f) => f, getCurrentDirectory: () => web, getNewLine: () => '\n' }));
  process.exit(1);
}

const rules = JSON.parse(readFileSync(join(data, 'rules.json'), 'utf8'));
const lookups = JSON.parse(readFileSync(join(data, 'lookups.json'), 'utf8'));
const forms = {};
const listNames = new Set();
for (const id of AUTH_FORMS) {
  const form = rules.forms[id];
  if (!form) {
    console.error(`gen:validation: rules.json has no form ${id}`);
    process.exit(1);
  }
  forms[id] = form;
  // The lists the engine looks up for these forms (FormRules' constructor names the same ones).
  for (const entry of form.fields) {
    const kind = entry.kind ?? 'field';
    for (const check of entry.checks ?? []) if (typeof check.list === 'string') listNames.add(check.list);
    if (kind === 'phone') listNames.add(String(entry.areaList ?? 'nanpGeneralPurposeAreaCodes'));
    if (kind === 'stateZip') listNames.add(String(entry.list ?? 'usStateZipPrefixes'));
  }
}
const lists = {};
for (const name of [...listNames].sort()) {
  if (!lookups.lists[name]) {
    console.error(`gen:validation: lookups.json has no list ${name}`);
    process.exit(1);
  }
  lists[name] = lookups.lists[name];
}
rmSync(formsDir, { recursive: true, force: true });
mkdirSync(formsDir, { recursive: true });
writeFileSync(join(formsDir, 'auth.json'), `${JSON.stringify({ schema: rules.schema, forms, lists }, null, 2)}\n`);
console.log(`gen:validation: rules, auth forms ${AUTH_FORMS.join(', ')}`);
