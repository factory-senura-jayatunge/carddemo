import type { MessageCode } from '@/messages';

/**
 * The checks the web app runs before calling a service, in the legacy edit
 * order. The services repeat them and stay authoritative.
 */
export interface FieldProblem<F extends string = string> {
  field: F;
  code: MessageCode;
}

/** Blank as COBOL sees it: spaces or nothing. */
export const isBlank = (value: string): boolean => value.trim() === '';

/** VAL-SGN-001, VAL-SGN-002. */
export function checkSignOn(userId: string, password: string): FieldProblem<'userId' | 'password'> | null {
  if (isBlank(userId)) return { field: 'userId', code: 'USER_ID_REQUIRED' };
  if (isBlank(password)) return { field: 'password', code: 'PASSWORD_REQUIRED' };
  return null;
}

export const NEW_PASSWORD_RULE =
  '8 to 64 characters. Upper and lower case count, and spaces are allowed. It must differ from your current password and from your user ID.';

/** D-SEC-07 in fixed mode, then the confirmation the web form adds. */
export function checkPasswordChange(
  userId: string,
  currentPassword: string,
  newPassword: string,
  confirmPassword: string,
): FieldProblem<'userId' | 'currentPassword' | 'newPassword' | 'confirmPassword'> | null {
  if (isBlank(userId)) return { field: 'userId', code: 'USER_ID_REQUIRED' };
  if (isBlank(currentPassword)) return { field: 'currentPassword', code: 'PASSWORD_REQUIRED' };
  if (newPassword === '') return { field: 'newPassword', code: 'NEW_PASSWORD_REQUIRED' };
  const length = [...newPassword].length;
  if (length < 8 || length > 64) return { field: 'newPassword', code: 'NEW_PASSWORD_LENGTH' };
  if (newPassword === currentPassword) return { field: 'newPassword', code: 'NEW_PASSWORD_SAME' };
  if (newPassword.toUpperCase() === userId.trim().toUpperCase()) return { field: 'newPassword', code: 'NEW_PASSWORD_IS_USER_ID' };
  if (newPassword !== confirmPassword) return { field: 'confirmPassword', code: 'NEW_PASSWORD_MISMATCH' };
  return null;
}

/**
 * The menu option field: right-justified and zero-filled to two characters
 * (NUM-MENU-001), then a number from 1 to the option count (VAL-MENU-001).
 * Returns the option number, or null when it is not valid.
 */
export function parseMenuOption(typed: string, optionCount: number): number | null {
  const trimmed = typed.trim();
  if (!/^\d{1,2}$/.test(trimmed)) return null;
  const option = Number(trimmed);
  return option >= 1 && option <= optionCount ? option : null;
}

export type BillPayStep =
  | { kind: 'problem'; problem: FieldProblem<'acctId' | 'confirm'> }
  | { kind: 'clear' }
  | { kind: 'preview' }
  | { kind: 'pay' };

/** What Enter does on the Bill Pay page, in COBIL00C's order (web contract, Bill Pay page). */
export function billPayStep(acctId: string, confirm: string): BillPayStep {
  if (isBlank(acctId)) return { kind: 'problem', problem: { field: 'acctId', code: 'ACCT_ID_REQUIRED' } };
  const value = confirm.trim();
  if (value === 'N' || value === 'n') return { kind: 'clear' };
  if (value === 'Y' || value === 'y') return { kind: 'pay' };
  if (value === '') return { kind: 'preview' };
  return { kind: 'problem', problem: { field: 'confirm', code: 'INVALID_CONFIRM' } };
}
