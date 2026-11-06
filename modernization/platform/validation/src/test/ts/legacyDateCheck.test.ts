// Runs the TypeScript mirror on the vectors the Java tests use: node --test src/test/ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { COMPACT, DASHED, legacyDateCheck, resultLine } from '../../main/ts/legacyDateCheck.ts';

const vectorsFile = fileURLToPath(
  new URL('../resources/org/carddemo/platform/validation/csutldtc-vectors.json', import.meta.url),
);
const { vectors } = JSON.parse(readFileSync(vectorsFile, 'utf8')) as {
  vectors: { date: string; picture: string; severity: number; messageNumber: number; line: string }[];
};

test('every CSUTLDTC vector gives the COBOL result line', () => {
  assert.ok(vectors.length > 800);
  const differences = vectors
    .map((v) => ({ v, r: legacyDateCheck(v.date, v.picture) }))
    .filter(({ v, r }) => r.severity !== v.severity || r.messageNumber !== v.messageNumber || resultLine(r) !== v.line)
    .map(({ v, r }) => `[${v.date}|${v.picture}] cobol=${v.line} ts=${resultLine(r)}`);
  assert.deepEqual(differences, []);
});

test('the value is the date only when it is valid', () => {
  assert.equal(legacyDateCheck('2024-02-29', DASHED).value, '2024-02-29');
  assert.equal(legacyDateCheck('20220706', COMPACT).value, '2022-07-06');
  assert.equal(legacyDateCheck('1582-10-14', DASHED).value, null);
  assert.equal(legacyDateCheck(null, DASHED).messageNumber, 2507);
});
