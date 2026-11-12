import { DASHED, legacyDateCheck } from '@carddemo/validation/legacyDateCheck';
import type { MessageKey } from '@/messages';
import { today } from '@/validation/today';
import { maskPan, type CoreData } from './accounts';
import { decimal, json, problem, readBody, text } from './http';
import { sampleTransactions, type SampleTransaction } from './transactionData';
import { fillMessage, messages } from './messages';

/**
 * transaction-service's add (TRNA) in mock mode: validate, add and copy last, with the checks of
 * TransactionAddRules and TransactionAddService in their order. An add writes one waiting row,
 * which the next batch cycle would post (D-27); nothing else changes.
 */
export interface AddState {
  nextId: number;
  waiting: SampleTransaction[];
  /** Stored adds by user and Idempotency-Key, with the body they were made with. */
  keys: Record<string, { body: string; tranId: string }>;
}

export interface AddContext {
  request: Request;
  core: CoreData;
  adds: AddState;
  userId: string;
  role: 'ADMIN' | 'USER';
  faults: readonly string[];
  batchWindowOpen: boolean;
}

// Generated IDs start here, above every sample transaction.
export const initialAdds = (): AddState => ({ nextId: 9_000_000_000_000_000, waiting: [], keys: {} });

/**
 * One mock-only account with two cards, for CARD_CHOICE_REQUIRED: no sample account has more than
 * one. It exists only for the add, so the account and card pages never see it.
 */
export const TWO_CARD_ACCOUNT = '00000000096';
const EXTRA_XREF = [
  { acctId: TWO_CARD_ACCOUNT, cardNum: '9999000000000961' },
  { acctId: TWO_CARD_ACCOUNT, cardNum: '9999000000000962' },
];

// app/data/ASCII/trantype.txt and trancatg.txt.
const TYPES = new Set(['01', '02', '03', '04', '05', '06', '07']);
const CATEGORIES = new Set([
  '010001', '010002', '010003', '010004', '010005',
  '020001', '020002', '020003',
  '030001', '030002', '030003',
  '040001', '040002', '040003',
  '050001',
  '060001', '060002',
  '070001',
]);

const DATA = [
  ['typeCd', 'Type CD', 2],
  ['catCd', 'Category CD', 4],
  ['source', 'Source', 10],
  ['description', 'Description', 60],
  ['amount', 'Amount', 12],
  ['origDate', 'Orig Date', 10],
  ['merchantId', 'Merchant ID', 9],
  ['merchantName', 'Merchant Name', 30],
  ['merchantCity', 'Merchant City', 25],
  ['merchantZip', 'Merchant Zip', 10],
] as const;
const FREE_TEXT = ['source', 'description', 'merchantName', 'merchantCity', 'merchantZip'] as const;
const AUDIT_FIELDS = 'amount,cardNum,catCd,description,merchantCity,merchantId,merchantName,merchantZip,origDate,source,typeCd';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const AMOUNT = /^[+-][0-9]{8}\.[0-9]{2}$/;
const DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/;

/** Only trailing U+0020 is pad. */
const trimTrailing = (value: string) => value.replace(/ +$/, '');
const empty = (value: string) => /^ *$/.test(value);
/** 1 to n digits between spaces, left-padded with zeros; null for anything else. */
function pad(value: string, length: number): string | null {
  const digits = value.replace(/^ +| +$/g, '');
  return /^[0-9]+$/.test(digits) && digits.length <= length ? digits.padStart(length, '0') : null;
}
const latin1 = (value: string) => {
  for (let i = 0; i < value.length; i += 1) if (value.charCodeAt(i) > 0xff) return false;
  return true;
};
const label = (name: string) => DATA.find(([n]) => n === name)?.[1] ?? name;
const cut = (value: string, length: number) => trimTrailing(value.slice(0, length));

/** +99999999.99 of an amount cut to eight integer digits (NUM-TRNA-001). */
function amountDisplay(cents: number): string {
  const abs = Math.abs(cents) % 10_000_000_000;
  return `${cents < 0 ? '-' : '+'}${String(Math.floor(abs / 100)).padStart(8, '0')}.${String(abs % 100).padStart(2, '0')}`;
}

interface Checked {
  acctId: string;
  cardNum: string;
  typeCd: string;
  catCd: string;
  source: string;
  description: string;
  cents: number;
  origDate: string;
  merchantId: string;
  merchantName: string;
  merchantCity: string;
  merchantZip: string;
}

type Form = Record<string, string>;
type Outcome = { checked: Checked; passed: Record<string, string> } | { response: Response };

const formOf = (body: Record<string, unknown>): Form =>
  Object.fromEntries(
    ['acctId', 'cardNum', ...DATA.map(([name]) => name), 'confirm'].map((name) => [name, text(body[name])]),
  );

/** Validate steps 2 and 3; a problem after the keys carries the values checked so far. */
function check(ctx: AddContext, form: Form): Outcome {
  const passed: Record<string, string> = {};
  const fail = (status: number, key: MessageKey, field: string, values?: Record<string, string>, extra: Record<string, unknown> = {}) => ({
    response: problem(status, key, field, { ...passed, ...extra }, undefined, values),
  });
  const typedAcct = form.acctId ?? '';
  const typedCard = form.cardNum ?? '';
  const noAcct = empty(typedAcct);
  const noCard = empty(typedCard);
  if (noAcct && noCard) return fail(400, 'KEY_REQUIRED', 'acctId');
  const acct = noAcct ? null : pad(typedAcct, 11);
  if (!noAcct && acct === null) return fail(400, 'TRNA_INVALID_ACCT_ID', 'acctId');
  const card = noCard ? null : pad(typedCard, 16);
  if (!noCard && card === null) return fail(400, 'TRNA_INVALID_CARD_NUM', 'cardNum');

  const xref = [...ctx.core.xref, ...EXTRA_XREF];
  let resolved: [string, string];
  if (acct !== null) {
    if (ctx.faults.includes('xref-read')) return fail(500, 'TRNA_XREF_LOOKUP_FAILED', 'acctId');
    const cards = xref.filter((x) => x.acctId === acct).map((x) => x.cardNum).sort();
    if (cards.length === 0) return fail(404, 'ACCOUNT_NOT_FOUND', 'acctId');
    if (card !== null) {
      if (!cards.includes(card)) return fail(400, 'CARD_ACCOUNT_MISMATCH', 'cardNum');
      resolved = [acct, card];
    } else if (cards.length > 1) {
      return fail(400, 'CARD_CHOICE_REQUIRED', 'cardNum', undefined, { cards: cards.map(maskPan) });
    } else {
      resolved = [acct, cards[0] ?? ''];
    }
  } else {
    if (ctx.faults.includes('xref-read')) return fail(500, 'TRNA_CARD_LOOKUP_FAILED', 'cardNum');
    const row = xref.find((x) => x.cardNum === card);
    if (!row || card === null) return fail(404, 'TRNA_CARD_NOT_FOUND', 'cardNum');
    resolved = [row.acctId, card];
  }
  passed.acctId = resolved[0];
  passed.cardNum = maskPan(resolved[1]);

  const value = (name: string) => form[name] ?? '';
  for (const [name, text] of DATA) if (empty(value(name))) return fail(400, 'FIELD_REQUIRED', name, { label: text });
  for (const name of FREE_TEXT) if (!latin1(value(name))) return fail(400, 'TRNA_UNSUPPORTED_CHARACTER', name, { label: label(name) });
  for (const [name, text, length] of DATA) {
    if (trimTrailing(value(name)).length > length) return fail(400, 'TRNA_TOO_LONG', name, { label: text, maxLength: String(length) });
  }
  const typeCd = pad(value('typeCd'), 2);
  if (typeCd === null) return fail(400, 'TYPE_NOT_NUMERIC', 'typeCd');
  const catCd = pad(value('catCd'), 4);
  if (catCd === null) return fail(400, 'CATEGORY_NOT_NUMERIC', 'catCd');
  passed.typeCd = typeCd;
  passed.catCd = catCd;
  if (ctx.faults.includes('reference-read')) return fail(500, 'REFERENCE_LOOKUP_FAILED', 'typeCd');
  if (!TYPES.has(typeCd)) return fail(400, 'TYPE_NOT_FOUND', 'typeCd');
  if (!CATEGORIES.has(typeCd + catCd)) return fail(400, 'CATEGORY_NOT_FOUND', 'catCd');

  const amount = trimTrailing(value('amount'));
  if (!AMOUNT.test(amount)) return fail(400, 'INVALID_AMOUNT', 'amount');
  const origDate = trimTrailing(value('origDate'));
  if (!DATE.test(origDate)) return fail(400, 'INVALID_ORIG_DATE_FORMAT', 'origDate');
  const magnitude = Number(amount.slice(1, 9)) * 100 + Number(amount.slice(10));
  const cents = amount.startsWith('-') && magnitude !== 0 ? -magnitude : magnitude;
  passed.amount = decimal(cents);
  passed.amountDisplay = amountDisplay(cents);
  if (legacyDateCheck(origDate, DASHED).severity !== 0) return fail(400, 'INVALID_ORIG_DATE', 'origDate');
  const merchantId = pad(value('merchantId'), 9);
  if (merchantId === null) return fail(400, 'MERCHANT_ID_NOT_NUMERIC', 'merchantId');
  passed.merchantId = merchantId;

  return {
    checked: {
      acctId: resolved[0],
      cardNum: resolved[1],
      typeCd,
      catCd,
      source: trimTrailing(value('source')),
      description: trimTrailing(value('description')),
      cents,
      origDate,
      merchantId,
      merchantName: trimTrailing(value('merchantName')),
      merchantCity: trimTrailing(value('merchantCity')),
      merchantZip: trimTrailing(value('merchantZip')),
    },
    passed,
  };
}

const userOnly = (ctx: AddContext) => (ctx.role === 'USER' ? null : problem(403, 'ACCESS_DENIED', null));

async function validate(ctx: AddContext): Promise<Response> {
  const form = formOf(await readBody(ctx.request));
  const outcome = check(ctx, form);
  if ('response' in outcome) return outcome.response;
  const c = outcome.checked;
  return json(200, {
    acctId: c.acctId,
    cardNum: maskPan(c.cardNum),
    typeCd: c.typeCd,
    catCd: c.catCd,
    merchantId: c.merchantId,
    amount: decimal(c.cents),
    amountDisplay: amountDisplay(c.cents),
    procDate: today(),
    message: messages.CONFIRM_ADD_TRANSACTION.text,
    severity: messages.CONFIRM_ADD_TRANSACTION.severity,
    field: 'confirm',
  });
}

const result = (tranId: string) => ({
  tranId,
  severity: 'success',
  field: 'acctId',
  message: fillMessage('TRANSACTION_ADDED', { tranId }),
  hint: { message: messages.TRANSACTION_POSTING_HINT.text, severity: messages.TRANSACTION_POSTING_HINT.severity },
});

async function add(ctx: AddContext): Promise<Response> {
  const form = formOf(await readBody(ctx.request));
  const outcome = check(ctx, form);
  if ('response' in outcome) return outcome.response;
  const { checked: c, passed } = outcome;
  if (form.confirm !== 'Y' && form.confirm !== 'y') return problem(400, 'INVALID_CONFIRM', 'confirm', passed);
  const key = ctx.request.headers.get('Idempotency-Key') ?? '';
  if (!UUID.test(key)) return problem(400, 'IDEMPOTENCY_KEY_REQUIRED', null);
  const { acctId: _acct, ...row } = c;
  const body = JSON.stringify(row);
  const stored = ctx.adds.keys[`${ctx.userId}:${key.toLowerCase()}`];
  if (stored) {
    if (stored.body !== body) return problem(422, 'TRAN_IDEMPOTENCY_KEY_REUSED', null);
    return json(201, result(stored.tranId), { 'Idempotent-Replayed': 'true' });
  }
  if (ctx.batchWindowOpen) return problem(503, 'TRAN_ADD_BATCH_WINDOW', 'acctId', {}, { 'Retry-After': '300' });
  if (ctx.faults.includes('tran-add')) return problem(500, 'ADD_FAILED', 'acctId');
  const tranId = String(ctx.adds.nextId).padStart(16, '0');
  ctx.adds.nextId += 1;
  const origTs = `${c.origDate}${' '.repeat(16)}`;
  ctx.adds.waiting.push({
    tranId,
    typeCd: c.typeCd,
    catCd: c.catCd,
    source: c.source,
    description: c.description,
    amount: c.cents,
    merchantId: c.merchantId,
    merchantName: c.merchantName,
    merchantCity: c.merchantCity,
    merchantZip: c.merchantZip,
    cardNum: c.cardNum,
    origTs,
    procTs: `${today()}${' '.repeat(16)}`,
  });
  ctx.adds.keys[`${ctx.userId}:${key.toLowerCase()}`] = { body, tranId };
  ctx.core.audit.push({ actor: ctx.userId, action: 'CREATE', subjectType: 'DAILY_TRAN', subjectKey: tranId, fieldNames: AUDIT_FIELDS });
  return json(201, result(tranId));
}

/** The data fields of the transaction with the highest ID, master or waiting (BR-TRNA-006). */
function latest(ctx: AddContext): Response {
  if (ctx.faults.includes('tran-read')) return problem(500, 'TRAN_LOOKUP_FAILED', 'acctId');
  const t = ctx.adds.waiting.at(-1) ?? sampleTransactions.at(-1);
  if (!t) return problem(404, 'NO_TRANSACTION', 'acctId');
  return json(200, {
    typeCd: trimTrailing(t.typeCd),
    catCd: trimTrailing(t.catCd),
    source: trimTrailing(t.source),
    description: cut(t.description, 60),
    amount: decimal(t.amount),
    amountDisplay: amountDisplay(t.amount),
    origDate: trimTrailing(t.origTs.slice(0, 10)),
    procDate: today(),
    merchantId: trimTrailing(t.merchantId),
    merchantName: cut(t.merchantName, 30),
    merchantCity: cut(t.merchantCity, 25),
    merchantZip: trimTrailing(t.merchantZip),
  });
}

/** Answers the add's three calls; null for any other request. */
export async function routeTransactionAdd(method: string, path: string, ctx: AddContext): Promise<Response | null> {
  const call =
    method === 'POST' && path === '/api/transactions'
      ? add
      : method === 'POST' && path === '/api/transactions/validate'
        ? validate
        : method === 'GET' && path === '/api/transactions/latest'
          ? latest
          : null;
  if (!call) return null;
  return userOnly(ctx) ?? call(ctx);
}
