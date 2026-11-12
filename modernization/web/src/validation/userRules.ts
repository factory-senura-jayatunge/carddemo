import { form, today } from './forms';
import { toProblem, type KeyProblem } from './problem';

/**
 * The add and update user forms of platform/validation's rule table, so the page, the mock and
 * identity-service give the same code, text and field. The service stays authoritative.
 */
const USER_FORMS = {
  add: { fixed: 'userAdd', legacy: 'userAddLegacy' },
  update: { fixed: 'userUpdate', legacy: 'userUpdateLegacy' },
} as const;

/** The add and update user forms; the Legacy ones while passwords are in legacy mode (DEF-SGN-011). */
export const userFormRules = (step: 'add' | 'update', legacyPasswords: boolean) => form(USER_FORMS[step][legacyPasswords ? 'legacy' : 'fixed']);

/** VAL-USRA-001, VAL-USRU-002 and D-SEC-07 for a user's fields, in screen order. */
export const userProblem = <F extends string>(step: 'add' | 'update', legacyPasswords: boolean, values: Record<F, string>): KeyProblem<F> | null =>
  toProblem<F>(userFormRules(step, legacyPasswords).validate(values, today()));
