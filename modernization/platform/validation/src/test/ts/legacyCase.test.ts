// Runs the TypeScript mirror of LegacyCase.upper on the vectors the Java test uses: node --test src/test/ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { legacyUpper } from '../../main/ts/legacyCase.ts';

const vectorsFile = fileURLToPath(
  new URL('../resources/org/carddemo/platform/validation/legacy-case-vectors.json', import.meta.url),
);
const { vectors } = JSON.parse(readFileSync(vectorsFile, 'utf8')) as {
  vectors: { in: string; out: string; note: string }[];
};

test('every legacy upper-case vector gives the Java result', () => {
  assert.ok(vectors.length >= 17);
  const differences = vectors
    .filter((v) => legacyUpper(v.in) !== v.out)
    .map((v) => `${v.note}: expected ${JSON.stringify(v.out)}, got ${JSON.stringify(legacyUpper(v.in))}`);
  assert.deepEqual(differences, []);
});

test('only a to z change, over every UTF-16 code unit', () => {
  const changed: number[] = [];
  for (let c = 0; c <= 0xffff; c++) {
    const s = String.fromCharCode(c);
    const out = legacyUpper(s);
    assert.equal(out.length, 1);
    if (out !== s) {
      changed.push(c);
      assert.equal(out.charCodeAt(0), c - 0x20);
    }
  }
  assert.deepEqual(changed, Array.from({ length: 26 }, (_, i) => 0x61 + i));
});
