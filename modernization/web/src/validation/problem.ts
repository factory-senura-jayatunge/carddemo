import type { ValidationResult } from '@carddemo/validation/rules';
import type { ScreenMessage } from '@/lib/messages';

/**
 * A page's view of a platform/validation rule result. The rule table gives the code, text and
 * marked fields the service gives; the service stays authoritative.
 */
export interface KeyProblem<F extends string> {
  code: string;
  message: ScreenMessage;
  /** Where the cursor goes. */
  field: F;
  /** Every field shown in red. */
  invalid: F[];
  /** The red fields that are empty, which get the required marker. */
  blank: F[];
}

/** The codes a rule gives for a field the user left empty (a required check, or no key at all). */
const EMPTY_CODE = /(^NO_INPUT$|REQUIRED$)/;

/** The first problem's text and field, every failed field red. */
export function toProblem<F extends string>(result: ValidationResult): KeyProblem<F> | null {
  const [first] = result.problems;
  if (!first) return null;
  const blank = result.wrongFields.filter((f) => result.problems.some((p) => p.field === f && EMPTY_CODE.test(p.code)));
  return {
    code: first.code,
    message: { text: first.message, tone: 'error' },
    field: first.field as F,
    invalid: result.wrongFields as F[],
    blank: blank as F[],
  };
}
