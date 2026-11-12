import { describe, expect, it } from 'vitest';
import { decodeUserKey, encodeUserKey } from './userKey';

// Expected keys computed outside this helper (Node's Buffer base64url) and, where they overlap,
// the identity service's UserKeysTest.
const VECTORS: [string, string][] = [
  ['ADMIN001', 'QURNSU4wMDE'],
  ['JSMITH', 'SlNNSVRI'],
  ['\0', 'AA'],
  ['/', 'Lw'],
  ['%', 'JQ'],
  ['\\', 'XA'],
  [';', 'Ow'],
  ['?', 'Pw'],
  ['#', 'Iw'],
  ['+', 'Kw'],
  ['\u0001', 'AQ'],
  ['\u007f', 'fw'],
  ['\u0085', 'woU'],
  ['\t', 'CQ'],
  ['\u00a0', 'wqA'],
  ['\u00ff', 'w78'],
  ['\u00df', 'w58'],
  ['.', 'Lg'],
  ['..', 'Li4'],
  ['A/B', 'QS9C'],
  ['A/B\0%', 'QS9CACU'],
  ['\u00ff\u00ff\u00ff', 'w7_Dv8O_'],
  ['user000', 'dXNlcjAwMA'],
  ['', ''],
];

describe('user key', () => {
  it.each(VECTORS)('encodes %j as %s and back', (id, key) => {
    expect(encodeUserKey(id)).toBe(key);
    expect(decodeUserKey(key)).toBe(id);
  });

  it('round-trips every character a user ID may hold, alone, inside and repeated', () => {
    for (let code = 0; code <= 0xff; code++) {
      const c = String.fromCharCode(code);
      for (const id of [c, `A${c}B`, c.repeat(8)]) {
        const key = encodeUserKey(id);
        expect(key).toMatch(/^[A-Za-z0-9_-]*$/);
        expect(decodeUserKey(key)).toBe(id);
      }
    }
  });

  it('never trims or changes case', () => {
    expect(decodeUserKey(encodeUserKey(' user1 '))).toBe(' user1 ');
    expect(encodeUserKey('a')).not.toBe(encodeUserKey('A'));
  });

  it('decodes text above U+00FF, which the service then refuses by its own rule', () => {
    expect(decodeUserKey('5Lit')).toBe('\u4e2d');
    expect(decodeUserKey(encodeUserKey('\u{1F600}'))).toBe('\u{1F600}');
    expect(decodeUserKey(encodeUserKey('\ufeffA'))).toBe('\ufeffA');
  });

  it.each([
    ['QQ=='],
    ['QQ='],
    ['Q'],
    ['QURNS'],
    ['QS9C+'],
    ['QS/C'],
    ['QS9C '],
    ['QS%2F'],
    ['QS.C'],
    ['QR'],
    ['QUJ'],
    ['_w'],
    ['wIA'],
    ['7aCA'],
    ['w'],
    ['ww'],
  ])('refuses %j', (key) => {
    expect(decodeUserKey(key)).toBeNull();
  });

  it('takes at most 1024 key characters', () => {
    const longest = 'QUFB'.repeat(256);
    expect(decodeUserKey(longest)).toHaveLength(768);
    expect(decodeUserKey(`${longest}QUE`)).toBeNull();
  });
});
