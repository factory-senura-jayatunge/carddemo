// COBOL FUNCTION UPPER-CASE on legacy text, mirrored from platform/legacy-format's LegacyCase.upper:
// a to z become A to Z and nothing else changes. toUpperCase would also change é to É and ß to SS,
// which changes the value and its length.
export function legacyUpper(value: string): string {
  return value.replace(/[a-z]+/g, (run) => run.toUpperCase());
}
