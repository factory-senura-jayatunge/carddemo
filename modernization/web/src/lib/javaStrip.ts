/**
 * Java's String.strip and String.stripTrailing, which the services apply to typed values.
 * They remove Character.isWhitespace characters, a different set from JavaScript's trim:
 * U+00A0, U+2007, U+202F and U+FEFF are kept, and U+001C to U+001F are removed. The client
 * must strip as the service does, or its change detection and the values it sends disagree
 * with what the service compares and writes.
 */
export const isJavaWhitespace = (code: number): boolean =>
  (code >= 0x09 && code <= 0x0d) ||
  (code >= 0x1c && code <= 0x20) ||
  code === 0x1680 ||
  (code >= 0x2000 && code <= 0x2006) ||
  (code >= 0x2008 && code <= 0x200a) ||
  code === 0x2028 ||
  code === 0x2029 ||
  code === 0x205f ||
  code === 0x3000;

// Loops, not /\s+$/-style patterns: a trailing-class regex is quadratic on long runs of spaces.
export function stripTrailing(value: string): string {
  let end = value.length;
  while (end > 0 && isJavaWhitespace(value.charCodeAt(end - 1))) end--;
  return value.slice(0, end);
}

export function strip(value: string): string {
  let start = 0;
  while (start < value.length && isJavaWhitespace(value.charCodeAt(start))) start++;
  return stripTrailing(value.slice(start));
}
