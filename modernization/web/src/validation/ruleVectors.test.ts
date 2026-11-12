import { describe, expect, it } from 'vitest';
import vectors from '../../../platform/validation/src/test/resources/org/carddemo/platform/validation/rule-vectors.json';
import { ruleTable } from './forms';

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

const shipped = (vectors as unknown as { shippedCases: Case[] }).shippedCases;

// The same cases FormRulesVectorsTest runs on the services' Java table, here on the table the pages import.
describe('platform/validation shipped cases in the web build', () => {
  it('loads the thirteen shipped forms', () => {
    expect(ruleTable.formIds).toEqual([
      'accountView',
      'accountUpdateLookup',
      'accountUpdate',
      'cardSearch',
      'cardLookup',
      'cardUpdate',
      'userAdd',
      'userUpdate',
      'userAddLegacy',
      'userUpdateLegacy',
      'signOn',
      'passwordChange',
      'passwordChangeAnyMode',
    ]);
    expect(shipped.length).toBeGreaterThan(70);
  });

  it.each(shipped.map((c) => [c.name, c] as const))('%s', (_name, c) => {
    const result = ruleTable.form(c.form).validate(c.input, c.today, c.changedFields ?? null, c.stored ?? {});
    expect(result.problems.map((p) => [p.field, p.code, p.message])).toEqual(c.problems);
    expect(result.wrongFields).toEqual(c.wrongFields);
  });
});
