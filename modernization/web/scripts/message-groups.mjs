// How the web app splits the message catalogue: the group of every key. The entry groups load with
// the first page; every other group loads only with the lazy pages that import it, so a feature's
// texts never reach the initial chunk (scripts/check-bundle.mjs checks this on the build).
//
// A key's group comes from its catalogue source: the area code of a spec item (VAL-RPTS-001, also with
// text after it), else a decision named below. Any other source (target, CSMSG01Y) is placed by a key
// prefix, else by the CORE_KEYS list. Anything else fails the generator: an area, decision or key with
// no group here, so a new key is placed on purpose and never lands in the entry by default.

/** In this order: the group files, the types and the merged table follow it. */
export const GROUPS = ['core', 'bill', 'account', 'card', 'authorization', 'transaction', 'report', 'user', 'reference'];

/** The groups the entry chunk may hold: sign-on, menus and Bill Pay are not lazy pages. */
export const ENTRY_GROUPS = ['core', 'bill'];

const AREAS = {
  SGN: 'core',
  MENU: 'core',
  BILL: 'bill',
  ACVW: 'account',
  ACUP: 'account',
  CRDV: 'card',
  CRDL: 'card',
  CRDU: 'card',
  AUSM: 'authorization',
  AUDT: 'authorization',
  TRNL: 'transaction',
  TRNV: 'transaction',
  TRNA: 'transaction',
  RPTS: 'report',
  USRA: 'user',
  USRU: 'user',
  USRD: 'user',
  USRL: 'user',
  TTUP: 'reference',
  TTLS: 'reference',
};

const DECISIONS = {
  'D-17': 'core',
  'D-SEC-07': 'core',
  'D-27': 'transaction',
  'D-48': 'report',
  'D-61': 'account',
};

const PREFIXES = [
  ['TT_', 'reference'],
  ['USER_', 'user'],
  ['TRAN_', 'transaction'],
  ['TRNA_', 'transaction'],
  ['REPORT_', 'report'],
  ['INVALID_RUN_ID', 'report'],
  ['AUSM_', 'authorization'],
  ['AUTH_', 'authorization'],
  ['AUTHORIZATION_', 'authorization'],
  ['INVALID_AUTH_ID', 'authorization'],
  ['INVALID_FRAUD_ACTION', 'authorization'],
  ['CARD_', 'card'],
  ['ACCOUNT_UPDATE_', 'account'],
];

/** The keys with no area, decision or prefix that belong to the entry: generic platform problems, the session and sign-off. */
export const CORE_KEYS = [
  'UNAUTHENTICATED',
  'ACCESS_DENIED',
  'CSRF_REJECTED',
  'INVALID_REQUEST',
  'NOT_FOUND',
  'METHOD_NOT_ALLOWED',
  'INTERNAL_ERROR',
  'INVALID_KEY',
  'PRECONDITION_REQUIRED',
  'INVALID_IF_MATCH',
  'INVALID_PAGE_REQUEST',
  'INVALID_PAGE_SIZE',
  'INVALID_CURSOR',
  'INVALID_UNMASK_FIELD',
  'IDEMPOTENCY_KEY_REQUIRED',
  'IDEMPOTENCY_KEY_REUSED',
  'BATCH_WINDOW',
  'TOO_MANY_ATTEMPTS',
  'SESSION_EXPIRED',
  'SIGNED_OFF',
  'UNMASK_AUDIT_FAILED',
  'FIELD_NOT_EDITABLE',
  'FIELD_NOT_EDITABLE_UNKNOWN',
];

/** The group of a catalogue key; throws when neither its source nor the key places it. */
export function groupOf(key, source) {
  if (Object.hasOwn(DECISIONS, source)) return DECISIONS[source];
  if (/^D-/.test(source)) throw new Error(`${key}: decision ${source} has no message group in scripts/message-groups.mjs`);
  const area = /^[A-Z]+-([A-Z]+)-[0-9]+\b/.exec(source)?.[1];
  if (area !== undefined) {
    if (!Object.hasOwn(AREAS, area)) throw new Error(`${key}: area ${area} (${source}) has no message group in scripts/message-groups.mjs`);
    return AREAS[area];
  }
  const prefixed = PREFIXES.find(([prefix]) => key.startsWith(prefix))?.[1];
  if (prefixed !== undefined) return prefixed;
  if (CORE_KEYS.includes(key)) return 'core';
  throw new Error(
    `${key}: source ${source} is not a spec item or a decision, and the key has no group prefix and is not in CORE_KEYS in scripts/message-groups.mjs`,
  );
}

/** The keys of a generated group file, in file order. */
export const keysOfGroupFile = (text) => [...text.matchAll(/^ {2}([A-Z][A-Z0-9_]*): \{ code: /gm)].map((m) => m[1]);

/** The keys of a generated group file with their texts as written (quotes and backslashes still escaped). */
export const entriesOfGroupFile = (text) =>
  [...text.matchAll(/^ {2}([A-Z][A-Z0-9_]*): \{ code: '(?:[^'\\]|\\.)*', text: '((?:[^'\\]|\\.)*)'/gm)].map((m) => ({ key: m[1], text: m[2] }));
