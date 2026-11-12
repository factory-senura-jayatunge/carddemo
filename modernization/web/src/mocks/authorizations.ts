import { strip } from '@/lib/javaStrip';
import { today } from '@/validation/forms';
import { maskPan, type CoreData } from './accounts';
import { decimal, json, problem, readBody, text } from './http';
import { fillMessage, messages } from './messages';

/**
 * authorization-service in mock mode: the AUSM summary and list (§7, §8) and
 * the AUDT detail and fraud toggle (§9) of the authorization-service contract,
 * in their check order. The legacy load has no ASCII form, so the rows are
 * synthetic: account 00000000001 has twelve authorizations (three pages),
 * 00000000002 exactly five, 00000000099 a summary whose customer is missing,
 * 00000000097 a long name with a blank middle name and wide totals, and
 * 00000000003 and 00000000098 no summary at all.
 */

export interface PauthDetail {
  authId: number;
  acctId: string;
  cardNum: string;
  /** ISO-8601 local date and time with milliseconds; the list order. */
  processedAt: string;
  authType: string;
  /** The stored expiry, MMYY. */
  cardExpiry: string;
  messageSource: string;
  processingCode: string;
  transactionAmt: number;
  mcc: string;
  posEntryMode: string;
  merchant: { id: string; name: string; city: string; state: string; zip: string };
  transactionId: string;
  respCode: string;
  reason: string;
  approvedAmt: number;
  matchStatus: 'P' | 'D' | 'E' | 'M';
  matchedTranId: string | null;
  fraud: 'F' | 'R' | null;
  fraudRptDate: string | null;
  version: number;
}

export interface PauthSummary {
  acctId: string;
  approvedCnt: number;
  declinedCnt: number;
  approvedAmt: number;
  declinedAmt: number;
  creditBalance: number;
  cashBalance: number;
}

export interface PauthData {
  summaries: Record<string, PauthSummary>;
  details: PauthDetail[];
}

export interface AuthorizationContext {
  request: Request;
  core: CoreData;
  pauth: PauthData;
  userId: string;
  role: 'ADMIN' | 'USER';
  faults: readonly string[];
}

const MERCHANTS = [
  { id: 'MERCH0000000001', name: 'GROCERY STORE', city: 'SPRINGFIELD', state: 'IL', zip: '62701', mcc: '5411' },
  { id: 'MERCH0000000002', name: 'CITY FUEL', city: 'CHAMPAIGN', state: 'IL', zip: '61820', mcc: '5541' },
  { id: 'MERCH0000000003', name: 'BOOK NOOK', city: 'PEORIA', state: 'IL', zip: '61602', mcc: '5942' },
  { id: 'MERCH0000000004', name: 'RIVERSIDE DINER', city: 'NAPERVILLE', state: 'IL', zip: '60540', mcc: '5812' },
];

interface RowSpec {
  amount: number;
  reason?: string;
  match?: PauthDetail['matchStatus'];
  matchedTranId?: string;
  fraud?: 'F' | 'R';
  fraudRptDate?: string;
  authType?: string;
}

const pad = (n: number, width = 2) => String(n).padStart(width, '0');

/** Rows of one account, oldest first, one day apart from `start`. */
function rows(core: CoreData, acctId: string, firstId: number, start: string, specs: RowSpec[]): PauthDetail[] {
  const xref = core.xref.filter((x) => x.acctId === acctId).sort((a, b) => (a.cardNum < b.cardNum ? -1 : 1))[0];
  const cardNum = xref?.cardNum ?? '0000000000000000';
  return specs.map((spec, i) => {
    const at = new Date(`${start}T00:00:00`);
    at.setDate(at.getDate() + i);
    at.setHours(9 + (i % 9), (i * 7) % 60, (i * 13) % 60, 0);
    const processedAt = `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}T${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())}.000`;
    const merchant = MERCHANTS[i % MERCHANTS.length] ?? MERCHANTS[0]!;
    const reason = spec.reason ?? '0000';
    const approved = reason === '0000';
    const authId = firstId + i;
    return {
      authId,
      acctId,
      cardNum,
      processedAt,
      authType: spec.authType ?? 'SALE',
      cardExpiry: '0428',
      messageSource: 'POS   ',
      processingCode: '000000',
      transactionAmt: spec.amount,
      mcc: merchant.mcc,
      posEntryMode: '01',
      merchant: { id: merchant.id, name: merchant.name, city: merchant.city, state: merchant.state, zip: merchant.zip },
      transactionId: `TRN${pad(authId, 12)}`,
      respCode: approved ? '00' : '05',
      reason,
      approvedAmt: approved ? spec.amount : 0,
      matchStatus: spec.match ?? (approved ? 'P' : 'D'),
      matchedTranId: spec.matchedTranId ?? null,
      fraud: spec.fraud ?? null,
      fraudRptDate: spec.fraudRptDate ?? null,
      version: 0x7d0 + authId,
    };
  });
}

function totals(acctId: string, details: PauthDetail[]): PauthSummary {
  const mine = details.filter((d) => d.acctId === acctId);
  const approved = mine.filter((d) => d.respCode === '00');
  const declined = mine.filter((d) => d.respCode !== '00');
  const sum = (list: PauthDetail[], pick: (d: PauthDetail) => number) => list.reduce((total, d) => total + pick(d), 0);
  return {
    acctId,
    approvedCnt: approved.length,
    declinedCnt: declined.length,
    approvedAmt: sum(approved, (d) => d.approvedAmt),
    declinedAmt: sum(declined, (d) => d.transactionAmt),
    creditBalance: sum(approved.filter((d) => d.matchStatus === 'P'), (d) => d.approvedAmt),
    cashBalance: 0,
  };
}

export function initialPauth(core: CoreData): PauthData {
  const details = [
    ...rows(core, '00000000001', 1001, '2026-10-29', [
      { amount: 2500, match: 'M', matchedTranId: '0000000000000301' },
      { amount: 4210, match: 'E' },
      { amount: 150000, reason: '4100' },
      { amount: 1299 },
      { amount: 6000, fraud: 'R', fraudRptDate: '2026-11-03' },
      { amount: 2000, reason: '4200' },
      { amount: 875 },
      { amount: 12000, fraud: 'F', fraudRptDate: '2026-11-06' },
      { amount: 3333, authType: 'CASH' },
      { amount: 0, reason: '9000' },
      { amount: 1950, reason: '1234' },
      { amount: 5000 },
    ]),
    ...rows(core, '00000000002', 2001, '2026-11-02', [{ amount: 1000 }, { amount: 2000 }, { amount: 3000, reason: '4100' }, { amount: 4000 }, { amount: 5000 }]),
    ...rows(core, '00000000099', 9901, '2026-11-05', [{ amount: 700 }, { amount: 800 }]),
    ...rows(core, '00000000097', 9701, '2026-11-07', [{ amount: 12345 }]),
  ];
  const summaries: Record<string, PauthSummary> = {};
  for (const acctId of ['00000000001', '00000000002', '00000000099']) summaries[acctId] = totals(acctId, details);
  // Totals far beyond the legacy screen widths (8 and 5 integer digits, 3-digit counts).
  summaries['00000000097'] = {
    acctId: '00000000097',
    approvedCnt: 1234,
    declinedCnt: 7,
    approvedAmt: 12_345_678,
    declinedAmt: 98_765_432_100,
    creditBalance: 123_456_789_012,
    cashBalance: 1_234_567,
  };
  return { summaries, details };
}

const REASONS: Record<string, string> = {
  '0000': 'APPROVED',
  '3100': 'INVALID CARD',
  '4100': 'INSUFFICNT FUND',
  '4200': 'CARD NOT ACTIVE',
  '4300': 'ACCOUNT CLOSED',
  '4400': 'EXCED DAILY LMT',
  '5100': 'CARD FRAUD',
  '5200': 'MERCHANT FRAUD',
  '5300': 'LOST CARD',
  '9000': 'UNKNOWN',
};

const ORDER = (a: PauthDetail, b: PauthDetail) =>
  a.processedAt === b.processedAt ? b.authId - a.authId : a.processedAt < b.processedAt ? 1 : -1;
const newestFirst = (pauth: PauthData, acctId: string) => pauth.details.filter((d) => d.acctId === acctId).sort(ORDER);

const yymmdd = (iso: string) => `${iso.slice(5, 7)}/${iso.slice(8, 10)}/${iso.slice(2, 4)}`;
const hhmmss = (iso: string) => iso.slice(11, 19);
const version = (n: number) => n.toString(16).padStart(16, '0');
const etag = (d: PauthDetail) => ({ ETag: `"${version(d.version)}"` });

const row = (d: PauthDetail) => ({
  authId: d.authId,
  transactionId: d.transactionId,
  requestDate: yymmdd(d.processedAt),
  requestTime: hhmmss(d.processedAt),
  authType: d.authType,
  approval: d.respCode === '00' ? 'A' : 'D',
  matchStatus: d.matchStatus,
  approvedAmount: decimal(d.approvedAmt),
});

function detailView(pauth: PauthData, d: PauthDetail) {
  const list = newestFirst(pauth, d.acctId);
  const next = list[list.findIndex((x) => x.authId === d.authId) + 1];
  return {
    ...row(d),
    acctId: d.acctId,
    version: version(d.version),
    cardNum: maskPan(d.cardNum),
    respCode: d.respCode,
    reason: d.reason,
    reasonText: REASONS[d.reason] ? `${d.reason}-${REASONS[d.reason]}` : '9999-ERROR',
    authIdCode: pad(d.authId % 1_000_000, 6),
    processingCode: d.processingCode,
    transactionAmount: decimal(d.transactionAmt),
    posEntryMode: d.posEntryMode,
    messageSource: d.messageSource,
    merchantCategoryCode: d.mcc,
    cardExpiry: `${d.cardExpiry.slice(0, 2)}/${d.cardExpiry.slice(2, 4)}`,
    matchedTranId: d.matchStatus === 'M' ? d.matchedTranId : null,
    fraud: {
      flag: d.fraud,
      reportDate: d.fraudRptDate,
      display: d.fraud && d.fraudRptDate ? `${d.fraud}-${yymmdd(d.fraudRptDate)}` : '-',
    },
    merchant: { ...d.merchant },
    nextOlderAuthId: next ? next.authId : null,
  };
}

const base64url = (value: string) => btoa(value).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const cursorOf = (d: PauthDetail) => base64url(`${d.processedAt}|${d.authId}`);
function readCursor(value: string): { at: string; id: number } | null {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) return null;
  try {
    const [at, id, extra] = atob(value.replace(/-/g, '+').replace(/_/g, '/')).split('|');
    if (extra !== undefined || !at || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}$/.test(at) || !id || !/^[1-9]\d*$/.test(id)) return null;
    return { at, id: Number(id) };
  } catch {
    return null;
  }
}
/** True when `d` comes after the cursor position in list order (older). */
const olderThan = (d: PauthDetail, c: { at: string; id: number }) => d.processedAt < c.at || (d.processedAt === c.at && d.authId < c.id);
const newerThan = (d: PauthDetail, c: { at: string; id: number }) => d.processedAt > c.at || (d.processedAt === c.at && d.authId > c.id);

const accessDenied = () => problem(403, 'ACCESS_DENIED', null);

/** §7.2 steps 1 to 3: the typed account ID, then the account. */
function checkAccount(ctx: AuthorizationContext, acctId: string): Response | null {
  if (strip(acctId) === '') return problem(400, 'ACCOUNT_ID_REQUIRED', 'acctId');
  if (!/^\d{11}$/.test(acctId)) return problem(400, 'ACCOUNT_ID_NOT_NUMERIC', 'acctId');
  if (ctx.faults.includes('account-read')) return problem(500, 'AUTH_ACCOUNT_READ_FAILED', null);
  if (!ctx.core.accounts[acctId]) return problem(404, 'AUSM_ACCOUNT_NOT_FOUND', 'acctId', {}, undefined, { acctId });
  return null;
}

function summary(ctx: AuthorizationContext, acctId: string): Response {
  const refused = checkAccount(ctx, acctId);
  if (refused) return refused;
  const account = ctx.core.accounts[acctId]!;
  if (ctx.faults.includes('xref-read')) return problem(500, 'AUTH_XREF_READ_FAILED', null);
  const xref = ctx.core.xref.filter((x) => x.acctId === acctId).sort((a, b) => (a.cardNum < b.cardNum ? -1 : 1))[0];
  let customer = null;
  let note: string | null = null;
  if (!xref) note = fillMessage('AUTH_XREF_NOT_FOUND', { acctId });
  else {
    if (ctx.faults.includes('customer-read')) return problem(500, 'AUTH_CUSTOMER_READ_FAILED', null);
    const c = ctx.core.customers[xref.custId];
    if (!c) note = fillMessage('AUTH_CUSTOMER_NOT_FOUND', { custId: xref.custId });
    else {
      // Each name is cut at its first space, as STRING ... DELIMITED BY SPACE does.
      const firstWord = (value: string) => value.split(' ')[0] ?? '';
      const trimEnd = (value: string) => value.replace(/ +$/, '');
      customer = {
        custId: c.custId,
        name: `${firstWord(c.firstName)} ${c.middleName.slice(0, 1) || ' '} ${firstWord(c.lastName)}`,
        addressLine1: `${trimEnd(c.addrLine1)},${trimEnd(c.addrLine2)}`,
        addressLine2: `${trimEnd(c.city)},${trimEnd(c.stateCd)},${trimEnd(c.zip.slice(0, 5))}`,
        phone: c.phone1.slice(0, 15),
      };
    }
  }
  if (ctx.faults.includes('auth-summary-read')) return problem(500, 'AUTH_SUMMARY_READ_FAILED', null);
  const s = ctx.pauth.summaries[acctId];
  return json(200, {
    acctId,
    customer,
    creditLimit: decimal(account.creditLimit),
    cashLimit: decimal(account.cashCreditLimit),
    summaryFound: s !== undefined,
    approvedCount: s?.approvedCnt ?? 0,
    declinedCount: s?.declinedCnt ?? 0,
    approvedAmount: decimal(s?.approvedAmt ?? 0),
    declinedAmount: decimal(s?.declinedAmt ?? 0),
    creditBalance: decimal(s?.creditBalance ?? 0),
    cashBalance: decimal(s?.cashBalance ?? 0),
    message: note,
    severity: note ? 'error' : null,
  });
}

function list(ctx: AuthorizationContext, acctId: string, query: URLSearchParams): Response {
  const size = query.get('size');
  if (size !== null && size !== '5') return problem(400, 'AUSM_INVALID_PAGE_SIZE', null);
  const after = query.get('after');
  const before = query.get('before');
  if (after !== null && before !== null) return problem(400, 'AUSM_INVALID_PAGE_REQUEST', null);
  const raw = after ?? before;
  const cursor = raw === null ? null : readCursor(raw);
  if (raw !== null && !cursor) return problem(400, 'INVALID_CURSOR', null);
  const refused = checkAccount(ctx, acctId);
  if (refused) return refused;
  if (ctx.faults.includes('auth-detail-read')) return problem(500, 'AUTH_DETAIL_READ_FAILED', null);
  const all = ctx.pauth.summaries[acctId] ? newestFirst(ctx.pauth, acctId) : [];
  let page: PauthDetail[];
  if (cursor && after !== null) page = all.filter((d) => olderThan(d, cursor)).slice(0, 5);
  else if (cursor) page = all.filter((d) => newerThan(d, cursor)).slice(-5);
  else page = all.slice(0, 5);
  const first = page[0];
  const last = page.at(-1);
  return json(200, {
    items: page.map(row),
    hasNext: last ? all.some((d) => olderThan(d, { at: last.processedAt, id: last.authId })) : false,
    hasPrevious: first ? all.some((d) => newerThan(d, { at: first.processedAt, id: first.authId })) : false,
    firstKey: first ? cursorOf(first) : null,
    lastKey: last ? cursorOf(last) : null,
  });
}

/** §9.1: a positive integer, else nothing is read. */
const authIdOf = (value: string) => (/^[1-9]\d{0,17}$/.test(value) ? Number(value) : null);

function readDetail(ctx: AuthorizationContext, rawId: string): { detail: PauthDetail } | { response: Response } {
  const authId = authIdOf(rawId);
  if (authId === null) return { response: problem(400, 'INVALID_AUTH_ID', null) };
  if (ctx.faults.includes('auth-detail-read')) return { response: problem(500, 'AUDT_DETAIL_READ_FAILED', null) };
  const detail = ctx.pauth.details.find((d) => d.authId === authId);
  if (!detail) return { response: problem(404, 'AUTHORIZATION_NOT_FOUND', null) };
  return { detail };
}

function getDetail(ctx: AuthorizationContext, rawId: string): Response {
  const read = readDetail(ctx, rawId);
  if ('response' in read) return read.response;
  if (ctx.faults.includes('auth-next-read')) return problem(500, 'AUTH_NEXT_READ_FAILED', null);
  return json(200, detailView(ctx.pauth, read.detail), etag(read.detail));
}

async function changeFraud(ctx: AuthorizationContext, rawId: string): Promise<Response> {
  if (authIdOf(rawId) === null) return problem(400, 'INVALID_AUTH_ID', null);
  const action = text((await readBody(ctx.request)).action);
  if (action !== 'F' && action !== 'R') return problem(400, 'INVALID_FRAUD_ACTION', null);
  const ifMatch = ctx.request.headers.get('If-Match');
  if (ifMatch === null || strip(ifMatch) === '') return problem(428, 'PRECONDITION_REQUIRED', null);
  const tag = /^"([0-9a-f]{16})"$/.exec(strip(ifMatch))?.[1];
  if (!tag) return problem(400, 'INVALID_IF_MATCH', null);
  const read = readDetail(ctx, rawId);
  if ('response' in read) return read.response;
  const { detail } = read;
  const toggle = detail.fraud === 'F' ? 'R' : 'F';
  if (tag !== version(detail.version) || action !== toggle) {
    return problem(409, 'RECORD_CHANGED', null, { current: detailView(ctx.pauth, detail) }, etag(detail));
  }
  if (ctx.faults.includes('fraud-update') || ctx.faults.includes('audit')) return problem(500, 'FRAUD_UPDATE_FAILED', null);
  detail.fraud = action;
  detail.fraudRptDate = today();
  detail.version += 1;
  const key = action === 'F' ? 'AUTH_MARKED_FRAUD' : 'AUTH_FRAUD_REMOVED';
  return json(200, { message: messages[key].text, severity: messages[key].severity, detail: detailView(ctx.pauth, detail) }, etag(detail));
}

/** Answers the authorization-service paths, or null for a path it does not own. */
export async function routeAuthorizations(method: string, url: URL, ctx: AuthorizationContext): Promise<Response | null> {
  const path = url.pathname;
  const byAccount = /^\/api\/accounts\/([^/]*)\/authorizations(\/summary)?$/.exec(path);
  const byId = /^\/api\/authorizations\/([^/]*)(\/fraud)?$/.exec(path);
  if (!byAccount && !byId) return null;
  // C1: every authorization endpoint is USER only, an ADMIN included (contract §2).
  if (ctx.role !== 'USER') return accessDenied();
  if (byAccount && method === 'GET') {
    const acctId = decodeURIComponent(byAccount[1] ?? '');
    return byAccount[2] ? summary(ctx, acctId) : list(ctx, acctId, url.searchParams);
  }
  if (byId) {
    const authId = decodeURIComponent(byId[1] ?? '');
    if (!byId[2] && method === 'GET') return getDetail(ctx, authId);
    if (byId[2] && method === 'POST') return changeFraud(ctx, authId);
  }
  return null;
}
