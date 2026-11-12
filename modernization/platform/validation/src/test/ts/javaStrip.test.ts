// The mirror's strip against the set OpenJDK 21 removes.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isJavaWhitespace, strip, stripTrailing } from '../../main/ts/javaStrip.ts';

// Every code point for which String.valueOf(Character.toChars(c)).strip() is empty, printed by OpenJDK 21.0.13.
const JDK21 = [
  0x9, 0xa, 0xb, 0xc, 0xd, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2008, 0x2009,
  0x200a, 0x2028, 0x2029, 0x205f, 0x3000,
];

test('strip removes exactly the characters Java removes, over every code point', () => {
  const found: number[] = [];
  for (let c = 0; c <= 0x10ffff; c++) {
    if (c >= 0xd800 && c <= 0xdfff) continue;
    if (strip(String.fromCodePoint(c)) === '') found.push(c);
  }
  assert.deepEqual(found, JDK21);
});

test('strip keeps the no-break spaces, the BOM, U+0085 and NUL', () => {
  for (const c of ['\u00a0', '\u2007', '\u202f', '\ufeff', '\u0085', '\u0000']) {
    assert.equal(strip(`${c}Kessler${c}`), `${c}Kessler${c}`);
    assert.equal(isJavaWhitespace(c.charCodeAt(0)), false);
  }
  assert.equal(strip('\u001c\u001dKessler\u001e\u001f'), 'Kessler');
});

test('stripTrailing strips only the end and is linear on long runs', () => {
  assert.equal(stripTrailing(' Ann \t\u3000'), ' Ann');
  assert.equal(strip(`${' '.repeat(200_000)}x${' '.repeat(200_000)}`), 'x');
});
