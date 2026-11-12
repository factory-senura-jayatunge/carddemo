/**
 * The key form of a user ID in a URL (identity-service contract §1, Transport): base64url
 * (RFC 4648 section 5, no padding) of the UTF-8 bytes of exactly the text, so every ID of
 * U+0000 to U+00FF fits a path segment, NUL, `/`, `%` and `..` included.
 */

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
const VALUE = new Map([...ALPHABET].map((c, i) => [c, i]));
const MAX_KEY_LENGTH = 1024;

/** The key of `text`, never trimmed or case-changed. */
export function encodeUserKey(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i] ?? 0;
    const b1 = bytes[i + 1] ?? 0;
    const b2 = bytes[i + 2] ?? 0;
    const chunk = (b0 << 16) | (b1 << 8) | b2;
    const chars = i + 1 >= bytes.length ? 2 : i + 2 >= bytes.length ? 3 : 4;
    for (let c = 0; c < chars; c++) out += ALPHABET[(chunk >> (18 - 6 * c)) & 63];
  }
  return out;
}

/**
 * The text of a key, or null when the key is not the one spelling the service accepts:
 * padding, a character outside the alphabet, a length no encoding gives, non-zero trailing
 * bits, bytes that are not UTF-8, or more than 1024 characters.
 */
export function decodeUserKey(key: string): string | null {
  if (key.length > MAX_KEY_LENGTH || key.length % 4 === 1) return null;
  const values: number[] = [];
  for (const c of key) {
    const value = VALUE.get(c);
    if (value === undefined) return null;
    values.push(value);
  }
  const bytes: number[] = [];
  for (let i = 0; i < values.length; i += 4) {
    const group = values.slice(i, i + 4);
    const chunk = group.reduce((acc, v, n) => acc | (v << (18 - 6 * n)), 0);
    const count = group.length - 1;
    // The bits below the last whole byte must be zero, or a second spelling of the same bytes would pass.
    if (count < 3 && (chunk & ((1 << (8 * (3 - count))) - 1)) !== 0) return null;
    for (let n = 0; n < count; n++) bytes.push((chunk >> (16 - 8 * n)) & 255);
  }
  try {
    return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(new Uint8Array(bytes));
  } catch {
    return null;
  }
}
