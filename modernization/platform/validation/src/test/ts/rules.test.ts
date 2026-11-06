// Runs the rule-table mirror on the cases the Java tests use.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { RuleTable, parseAmount, type LookupsFile, type RulesFile } from '../../main/ts/rules.ts';

const read = (path: string): unknown => JSON.parse(readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8'));
const lookups = read('../../main/resources/org/carddemo/platform/validation/lookups.json') as LookupsFile;
const standard = read('../../main/resources/org/carddemo/platform/validation/rules.json') as RulesFile;
const doc = read('../resources/org/carddemo/platform/validation/rule-vectors.json') as {
  rules: RulesFile;
  cases: { name: string; form: string; today: string; input: Record<string, string>; problems: string[][]; wrongFields: string[] }[];
};

test('every rule-table case gives the expected problems', () => {
  const table = new RuleTable(doc.rules, lookups);
  assert.ok(doc.cases.length > 40);
  const differences = doc.cases
    .map((c) => ({ c, r: table.form(c.form).validate(c.input, c.today) }))
    .filter(({ c, r }) =>
      JSON.stringify(r.problems.map((p) => [p.field, p.code, p.message])) !== JSON.stringify(c.problems) ||
      JSON.stringify(r.wrongFields) !== JSON.stringify(c.wrongFields))
    .map(({ c, r }) => `${c.name}: ${JSON.stringify(r)}`);
  assert.deepEqual(differences, []);
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
});
