import { describe, expect, it } from 'vitest';
import authForms from '@/api/generated/validation-forms/auth.json';
import lookups from '@carddemo/validation-data/lookups.json';
import rules from '@carddemo/validation-data/rules.json';
import vectors from '../../../platform/validation/src/test/resources/org/carddemo/platform/validation/rule-vectors.json';
import { authRuleTable, passwordChangeProblem, signOnProblem } from './authRules';

const EMOJI_33 = '\u{1F600}'.repeat(33);

describe('sign-on, form signOn', () => {
  it('@VAL-SGN-001 a blank user ID comes first: only spaces and NULs are blank', () => {
    expect(signOnProblem('', '')).toMatchObject({ code: 'USER_ID_REQUIRED', field: 'userId', message: { text: 'Please enter User ID ...' } });
    expect(signOnProblem(' \0 ', 'PASSWORD')?.code).toBe('USER_ID_REQUIRED');
    // A tab or U+3000 is a typed character, left to the service.
    expect(signOnProblem('\t', 'PASSWORD')).toBeNull();
    expect(signOnProblem('\u3000', 'PASSWORD')).toBeNull();
  });

  it('@VAL-SGN-002 then a blank password, then its 64 code points after the pad', () => {
    expect(signOnProblem('ADMIN001', ' \0')).toMatchObject({ code: 'PASSWORD_REQUIRED', field: 'password', message: { text: 'Please enter Password ...' } });
    expect(signOnProblem('ADMIN001', 'PASSWORD')).toBeNull();
    expect(signOnProblem('ADMIN001', `${'x'.repeat(64)}  `)).toBeNull();
    expect(signOnProblem('ADMIN001', '\u{1F600}'.repeat(64))).toBeNull();
    expect(signOnProblem('ADMIN001', 'x'.repeat(65))).toMatchObject({ code: 'INVALID_INPUT', field: 'password', message: { text: 'Please enter Password ...' } });
  });

  it('@VAL-SGN-001 the page never limits the user ID: a padded, long or leading-space ID goes to the service', () => {
    for (const id of ['USER0001  ', 'USER00012', ' USER0001', 'x'.repeat(40)]) expect(signOnProblem(id, 'PASSWORD')).toBeNull();
    expect(signOnProblem('USER00012', '')?.code).toBe('PASSWORD_REQUIRED');
  });
});

describe('password change (D-SEC-07)', () => {
  const fixed = (newPassword: string, confirmPassword = newPassword, currentPassword = 'PASSWORD') =>
    passwordChangeProblem(true, { userId: 'USER0001', currentPassword, newPassword, confirmPassword });

  it('@D-SEC-07 fixed mode checks in the contract order, with the table texts', () => {
    expect(passwordChangeProblem(true, { userId: ' ', currentPassword: '', newPassword: '', confirmPassword: '' })?.code).toBe('USER_ID_REQUIRED');
    expect(fixed('Abcdefgh', 'Abcdefgh', '')?.code).toBe('PASSWORD_REQUIRED');
    expect(fixed('  \0')).toMatchObject({ code: 'NEW_PASSWORD_REQUIRED', field: 'newPassword' });
    expect(fixed('Short1')).toMatchObject({ code: 'NEW_PASSWORD_LENGTH', message: { text: 'New Password must be 8 to 64 characters ...' } });
    expect(fixed('short12 ')?.code).toBe('NEW_PASSWORD_LENGTH');
    expect(fixed('x'.repeat(65))?.code).toBe('NEW_PASSWORD_LENGTH');
    expect(fixed('tab\there12')?.code).toBe('NEW_PASSWORD_LENGTH');
    expect(fixed('PASSWORD  ')?.code).toBe('NEW_PASSWORD_SAME');
    expect(fixed('user0001')).toMatchObject({ code: 'NEW_PASSWORD_IS_USER_ID', message: { text: 'New Password must not be the User ID ...' } });
    expect(fixed('x'.repeat(64))).toBeNull();
    expect(fixed(EMOJI_33)).toBeNull();
  });

  it('@D-SEC-07 is case-sensitive: the current password in another case is a new password', () => {
    expect(fixed('password')).toBeNull();
  });

  it('@D-SEC-07 the confirmation comes last and is compared without the pad the service drops', () => {
    expect(fixed('New pass 1', 'New pass 2')).toMatchObject({
      code: 'NEW_PASSWORD_MISMATCH',
      field: 'confirmPassword',
      message: { text: 'New Password and confirmation do not match ...' },
    });
    expect(fixed('New pass 1', 'new pass 1')?.code).toBe('NEW_PASSWORD_MISMATCH');
    expect(fixed('New pass 1', 'New pass 1 \0')).toBeNull();
    expect(fixed('Short1', 'Other')?.code).toBe('NEW_PASSWORD_LENGTH');
  });

  it('@D-SEC-07 outside a forced change only the blank checks run; the service owns the length rules', () => {
    const any = (newPassword: string) => passwordChangeProblem(false, { userId: 'USER0001', currentPassword: 'PASSWORD', newPassword, confirmPassword: newPassword });
    expect(any('')?.code).toBe('NEW_PASSWORD_REQUIRED');
    for (const next of ['a', 'PASSWORD', 'user0001', 'x'.repeat(65)]) expect(any(next)).toBeNull();
  });
});

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

const AUTH_FORMS = ['signOn', 'passwordChange', 'passwordChangeAnyMode'];
const generated = authForms as unknown as { schema: number; forms: Record<string, unknown>; lists: Record<string, string[]> };
const source = rules as unknown as { schema: number; forms: Record<string, unknown> };
const sourceLists = (lookups as unknown as { lists: Record<string, string[]> }).lists;
const authCases = (vectors as unknown as { shippedCases: Case[] }).shippedCases.filter((c) => AUTH_FORMS.includes(c.form));

describe('the generated sign-on and password change forms', () => {
  it('are those forms of rules.json, unchanged, with the lists they name', () => {
    expect(authRuleTable.formIds).toEqual(AUTH_FORMS);
    expect(generated.schema).toBe(source.schema);
    for (const id of AUTH_FORMS) expect(generated.forms[id]).toStrictEqual(source.forms[id]);
    for (const [name, list] of Object.entries(generated.lists)) expect(list).toStrictEqual(sourceLists[name]);
  });

  it('have shipped cases for each form', () => {
    for (const id of AUTH_FORMS) expect(authCases.filter((c) => c.form === id).length).toBeGreaterThan(5);
  });

  // The cases FormRulesVectorsTest runs on the services' Java table, here on the subset the pages import.
  it.each(authCases.map((c) => [c.name, c] as const))('%s', (_name, c) => {
    const result = authRuleTable.form(c.form).validate(c.input, c.today, c.changedFields ?? null, c.stored ?? {});
    expect(result.problems.map((p) => [p.field, p.code, p.message])).toEqual(c.problems);
    expect(result.wrongFields).toEqual(c.wrongFields);
  });
});
