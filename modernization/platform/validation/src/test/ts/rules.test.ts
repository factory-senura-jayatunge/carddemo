// Runs the rule-table mirror on the cases the Java tests use.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { RuleTable, parseAmount, type LookupsFile, type RulesFile } from '../../main/ts/rules.ts';

const read = (path: string): unknown => JSON.parse(readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8'));
const lookups = read('../../main/resources/org/carddemo/platform/validation/lookups.json') as LookupsFile;
const standard = read('../../main/resources/org/carddemo/platform/validation/rules.json') as RulesFile;
interface Case {
  name: string;
  form: string;
  today: string;
  input: Record<string, string>;
  changedFields?: string[];
  stored?: Record<string, string>;
  problems: string[][];
  wrongFields: string[];
}
const doc = read('../resources/org/carddemo/platform/validation/rule-vectors.json') as { rules: RulesFile; cases: Case[]; shippedCases: Case[] };
const lone = read('../resources/org/carddemo/platform/validation/lone-surrogate-vectors.json') as { cases: Case[]; shippedCases: Case[] };
const cases = [...doc.cases, ...lone.cases];
const shippedCases = [...doc.shippedCases, ...lone.shippedCases];

const differences = (table: RuleTable, cases: Case[]): string[] =>
  cases
    .map((c) => ({ c, r: table.form(c.form).validate(c.input, c.today, c.changedFields ?? null, c.stored ?? {}) }))
    .filter(({ c, r }) =>
      JSON.stringify(r.problems.map((p) => [p.field, p.code, p.message])) !== JSON.stringify(c.problems) ||
      JSON.stringify(r.wrongFields) !== JSON.stringify(c.wrongFields))
    .map(({ c, r }) => `${c.name}: ${JSON.stringify(r)}`);

test('every rule-table case gives the expected problems', () => {
  assert.ok(lone.cases.length > 0);
  assert.ok(cases.length > 40);
  assert.deepEqual(differences(new RuleTable(doc.rules, lookups), cases), []);
});

test('every case of the shipped forms gives the expected problems', () => {
  const table = new RuleTable(standard, lookups);
  assert.deepEqual(table.formIds, [
    'accountView', 'accountUpdateLookup', 'accountUpdate', 'cardSearch', 'cardLookup', 'cardUpdate',
    'userAdd', 'userUpdate', 'userAddLegacy', 'userUpdateLegacy', 'signOn', 'passwordChange', 'passwordChangeAnyMode',
  ]);
  assert.ok(lone.shippedCases.length > 0);
  assert.ok(shippedCases.length > 70);
  assert.deepEqual(differences(table, shippedCases), []);
});

test('amount values', () => {
  assert.equal(parseAmount('$1,000.50', 10, 2), '1000.50');
  assert.equal(parseAmount('1000.50CR', 10, 2), '-1000.50');
  assert.equal(parseAmount('.5', 10, 2), '0.5');
  assert.equal(parseAmount('12345678901', 10, 2), null);
});

test('the shipped table and lists load', () => {
  const table = new RuleTable(standard, lookups);
  assert.equal(table.list('usStateCodes').length, 56);
  assert.equal(table.list('usStateZipPrefixes').length, 240);
  assert.equal(table.list('nanpGeneralPurposeAreaCodes').length, 410);
  assert.throws(() => new RuleTable({ schema: 1, forms: { f: { fields: [{ name: 'a', label: 'A', checks: [{ kind: 'bogus' }] }] } } }, lookups), /unknown check kind bogus/);
  assert.throws(() => new RuleTable({ schema: 1, forms: { f: { fields: [{ kind: 'dateOrder', date: 'x', min: 'y' }] } } }, lookups), /unknown date id x/);
  assert.throws(() => new RuleTable({ schema: 1, forms: { f: { fields: [{ kind: 'notAbove', field: 'a', limit: 'b' }] } } }, lookups), /notAbove names unknown field a/);
  assert.deepEqual(table.list('userTypes'), ['A', 'U']);
});

test('the text checks and differs need their own code and message', () => {
  const form = (fields: Record<string, unknown>[]): RulesFile => ({ schema: 1, forms: { f: { fields: fields as RulesFile['forms'][string]['fields'] } } });
  const a = (check?: Record<string, unknown>) => ({ name: 'a', label: 'A', checks: check ? [check] : [] });
  const b = { name: 'b', label: 'B' };
  assert.throws(() => new RuleTable(form([a({ kind: 'codePoints', max: 8, message: 'm' })]), lookups), /codePoints needs a code and a message/);
  assert.throws(() => new RuleTable(form([a({ kind: 'noSpace', code: 'C' })]), lookups), /noSpace needs a code and a message/);
  assert.throws(() => new RuleTable(form([a(), b, { kind: 'differs', field: 'a', other: 'b', code: 'C' }]), lookups), /differs needs a code and a message/);
  assert.throws(() => new RuleTable(form([a(), { kind: 'differs', field: 'a', other: 'b', code: 'C', message: 'm' }]), lookups), /differs names unknown field b/);
  assert.throws(() => new RuleTable(form([a(), b, { kind: 'differs', field: 'a', other: 'b', code: 'C', message: 'm', fold: 'upper' }]), lookups), /differs fold must be legacyUpper/);
  assert.throws(() => new RuleTable(form([{ name: 'a', label: 'A', trim: { both: ' ' } }]), lookups), /trim takes leading and trailing strings/);
});

test('value is what the checks of the field see', () => {
  const form = new RuleTable(doc.rules, lookups).form('textRules');
  assert.equal(form.value('code', '  ab \u0000 '), 'ab');
  assert.equal(form.value('secret', ' Abcdefgh1 \u0000'), ' Abcdefgh1');
  assert.equal(form.value('secret', 'Abcdefgh1\t'), 'Abcdefgh1\t');
  assert.equal(form.value('secret', null), '');
  assert.equal(form.value('plain', ' ab\t'), 'ab');
  assert.throws(() => form.value('nope', 'x'), /no field nope/);
});
