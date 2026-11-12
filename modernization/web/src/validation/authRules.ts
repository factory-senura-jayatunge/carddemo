import { RuleTable, type LookupsFile, type RulesFile } from '@carddemo/validation/rules';
import authForms from '@/api/generated/validation-forms/auth.json';
import { message } from '@/lib/messages';
import { toProblem, type KeyProblem } from './problem';
import { today } from './today';

/**
 * The sign-on and password change forms of platform/validation's rule table, so the page, the mock
 * and identity-service give the same code, text and field. The service stays authoritative: it
 * also checks what the page cannot, such as the user ID's length at sign-on.
 *
 * These pages are in the first page load, so they read only their own forms: gen:validation copies
 * them from rules.json unchanged, and they run on the same engine as the whole table.
 */
const generated = authForms as unknown as RulesFile & LookupsFile;
export const authRuleTable = new RuleTable(generated, generated);

export type SignOnField = 'userId' | 'password';

/** Form signOn: VAL-SGN-001, VAL-SGN-002, and the password's 64 code points. */
export const signOnProblem = (userId: string, password: string): KeyProblem<SignOnField> | null =>
  toProblem<SignOnField>(authRuleTable.form('signOn').validate({ userId, password }, today()));

export type PasswordChangeField = 'userId' | 'currentPassword' | 'newPassword' | 'confirmPassword';

/**
 * D-SEC-07, then the confirmation the web page adds. fixedMode is form passwordChange, for a change
 * the service asked for at sign-on; otherwise passwordChangeAnyMode, whose length rules only the
 * service knows. The confirmation is compared on the value the service keeps, without the pad.
 */
export function passwordChangeProblem(fixedMode: boolean, values: Record<PasswordChangeField, string>): KeyProblem<PasswordChangeField> | null {
  const rules = authRuleTable.form(fixedMode ? 'passwordChange' : 'passwordChangeAnyMode');
  const { confirmPassword, ...input } = values;
  const failed = toProblem<PasswordChangeField>(rules.validate(input, today()));
  if (failed) return failed;
  if (rules.value('newPassword', values.newPassword) === rules.value('newPassword', confirmPassword)) return null;
  return { code: 'NEW_PASSWORD_MISMATCH', message: message('NEW_PASSWORD_MISMATCH'), field: 'confirmPassword', invalid: ['confirmPassword'], blank: [] };
}
