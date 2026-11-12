import type { EntryKey } from '@/messages';

/**
 * The checks the web app runs before calling a service, in the legacy edit
 * order. The services repeat them and stay authoritative.
 */
export interface FieldProblem<F extends string = string> {
  field: F;
  /** The catalogue message to show; the entry pages use these rules, so it is an entry key. */
  key: EntryKey;
}

/** Blank as COBOL sees it: spaces or nothing. */
export const isBlank = (value: string): boolean => value.trim() === '';

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
  if (isBlank(acctId)) return { kind: 'problem', problem: { field: 'acctId', key: 'ACCT_ID_REQUIRED' } };
  const value = confirm.trim();
  if (value === 'N' || value === 'n') return { kind: 'clear' };
  if (value === 'Y' || value === 'y') return { kind: 'pay' };
  if (value === '') return { kind: 'preview' };
  return { kind: 'problem', problem: { field: 'confirm', key: 'INVALID_CONFIRM' } };
}
