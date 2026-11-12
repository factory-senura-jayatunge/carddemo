// Java's String.strip and String.stripTrailing, mirrored for FormRules: they remove the characters
// for which Character.isWhitespace is true. JavaScript's trim and \s remove a different set: they
// also remove U+00A0, U+2007, U+202F and U+FEFF, and keep U+001C to U+001F. U+0085 and NUL are
// neither, so both keep them.
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

// Loops, not a trailing-class regex, which is quadratic on a long run of spaces.
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
