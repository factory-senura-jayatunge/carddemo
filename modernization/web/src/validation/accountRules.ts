import { isEmpty } from '@carddemo/validation/rules';
import { form, today, type FormId } from './forms';
import { toProblem, type KeyProblem } from './problem';

/**
 * The key and filter checks of the account and card pages. They run through
 * platform/validation's rule table, so the page and the service give the same
 * code, text and marked fields; the service stays authoritative.
 */
export { toProblem, type KeyProblem };

/** FormRules "empty": only spaces, or `*` followed by spaces (BR-ACUP-105). */
export const isEmptyInput = (value: string | null | undefined): boolean => isEmpty(value);

const check = <F extends string>(id: FormId, input: Record<F, string>): KeyProblem<F> | null => toProblem<F>(form(id).validate(input, today()));

/** Account view steps 1 and 2, form accountView: VAL-ACVW-001, VAL-ACVW-002. */
export const checkAccountViewId = (acctId: string) => check('accountView', { acctId });

/** Account update search step, form accountUpdateLookup: VAL-ACUP-001, VAL-ACUP-002. */
export const checkAccountUpdateId = (acctId: string) => check('accountUpdateLookup', { acctId });

export type CardKeyField = 'acctId' | 'cardNum';

/** Card view and card update keys, form cardLookup: VAL-CRDV-001 to VAL-CRDV-006, VAL-CRDU-001 to VAL-CRDU-003. */
export const checkCardKeys = (acctId: string, cardNum: string) => check<CardKeyField>('cardLookup', { acctId, cardNum });

/** A card list filter is blank when it holds only spaces and zeros (VAL-CRDL-001, VAL-CRDL-002); `*` is not blank here. */
export const isBlankFilter = (value: string | null | undefined): boolean => /^[ 0]*$/.test(value ?? '');

/** Card list filters, form cardSearch: VAL-CRDL-001, VAL-CRDL-002, VAL-CRDL-007 (account first, both marked). */
export const checkCardFilters = (acctId: string, cardNum: string) => check<CardKeyField>('cardSearch', { acctId, cardNum });
