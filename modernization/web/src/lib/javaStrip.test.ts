import { describe, expect, it } from 'vitest';
import { isJavaWhitespace, strip, stripTrailing } from './javaStrip';

// Every code point for which String.valueOf(Character.toChars(c)).strip() is empty, printed by OpenJDK 21.0.13.
const JDK21 = [
  0x9, 0xa, 0xb, 0xc, 0xd, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2008, 0x2009,
  0x200a, 0x2028, 0x2029, 0x205f, 0x3000,
];

describe("Java's String.strip in the client", () => {
  it('removes exactly the characters Java removes, over every code point', () => {
    const found: number[] = [];
    for (let c = 0; c <= 0x10ffff; c++) {
      if (c >= 0xd800 && c <= 0xdfff) continue;
      if (strip(String.fromCodePoint(c)) === '') found.push(c);
    }
    expect(found).toEqual(JDK21);
  });

  it('keeps the no-break spaces and the BOM that JavaScript trim removes', () => {
    for (const c of ['\u00a0', '\u2007', '\u202f', '\ufeff']) {
      expect(strip(`${c}Kessler${c}`)).toBe(`${c}Kessler${c}`);
      expect(isJavaWhitespace(c.charCodeAt(0))).toBe(false);
    }
  });

  it('removes the information separators that JavaScript trim keeps', () => {
    expect(strip('\u001c\u001dKessler\u001e\u001f')).toBe('Kessler');
  });

  it('strips only the end with stripTrailing, and is linear on long runs', () => {
    expect(stripTrailing(' Ann \t\u3000')).toBe(' Ann');
    const long = `${' '.repeat(200_000)}x${' '.repeat(200_000)}`;
    expect(strip(long)).toBe('x');
  });
});
