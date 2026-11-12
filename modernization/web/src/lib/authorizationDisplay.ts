/**
 * Display widths of the pending authorization summary (DEF-AUSM-004, BR-AUSM-001).
 * The API returns full values; the screen keeps the legacy cuts of the name
 * and the address lines, and shows every other value in full.
 */
const WIDTH = 25;

/** The first `width` characters (code points, so a surrogate pair is never split). */
const cut = (value: string, width = WIDTH) => Array.from(value).slice(0, width).join('');

/** The name as the API builds it, spaces kept (a blank middle name leaves three), cut at 25. */
export function summaryName(name: string): string {
  return cut(name);
}

/**
 * An address line as the API joins it (values separated by a bare comma): each
 * value up to its first double space, joined again, cut at 25.
 */
export function summaryAddress(line: string): string {
  return cut(
    line
      .split(',')
      .map((part) => part.split('  ')[0] ?? '')
      .join(','),
  );
}

const MATCH: Record<string, string> = { P: 'Pending', D: 'Declined', E: 'Expired', M: 'Matched' };
const APPROVAL: Record<string, string> = { A: 'Approved', D: 'Declined' };

/** The match status in words (PA-MATCH-STATUS 88 levels), or undefined for a value outside them. */
export const matchLabel = (code: string): string | undefined => MATCH[code];
export const approvalLabel = (code: string): string | undefined => APPROVAL[code];

const count = new Intl.NumberFormat('en-US');
export const formatCount = (n: number) => count.format(n);
