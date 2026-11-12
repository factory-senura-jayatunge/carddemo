import { maskPan, type CoreData } from './accounts';
import { decimal, json, problem, readBody } from './http';
import { sampleTransactions, type SampleTransaction } from './transactionData';
import { messages } from './messages';

/**
 * transaction-service in mock mode: the list (TRNL), the view (TRNV) and the
 * ADMIN unmask of the card number, in the service's check order. Reads only
 * master rows, so an open batch window changes nothing here.
 */
export interface TransactionContext {
  request: Request;
  url: URL;
  core: CoreData;
  userId: string;
  role: 'ADMIN' | 'USER';
  faults: readonly string[];
}

const NO_STORE = { 'Cache-Control': 'no-store', Pragma: 'no-cache' };
const PAGE_MAX = 10;
const KEY = /^[0-9]{16}$/;
const SEARCH = /^[0-9]{1,16}$/;
const PRINTABLE = /^[\x20-\x7e]*$/;
/** The service removes trailing spaces only; any other whitespace stays and fails the digit rule. */
const stripTrailing = (value: string) => value.replace(/ +$/, '');

/** +99999999.99, widened to nine integer digits from 100,000,000.00 (NUM-TRNL-001, DEF-TRNL-002). */
export function tranAmountDisplay(cents: number): string {
  const abs = Math.abs(cents);
  return `${cents < 0 ? '-' : '+'}${String(Math.floor(abs / 100)).padStart(8, '0')}.${String(abs % 100).padStart(2, '0')}`;
}

/** mm/dd/yy of a YYYY-MM-DD... timestamp (NUM-TRNL-002); empty when there is none. */
const shortDate = (ts: string) => (/^\d{4}-\d{2}-\d{2}/.test(ts) ? `${ts.slice(5, 7)}/${ts.slice(8, 10)}/${ts.slice(2, 4)}` : '');

const row = (t: SampleTransaction) => ({
  tranId: t.tranId,
  amount: decimal(t.amount),
  amountDisplay: tranAmountDisplay(t.amount),
  description: stripTrailing(t.description.slice(0, 26)),
  origDate: shortDate(t.origTs),
});

const view = (t: SampleTransaction) => ({
  tranId: t.tranId,
  typeCd: t.typeCd,
  catCd: t.catCd,
  source: t.source,
  description: t.description,
  amount: decimal(t.amount),
  amountDisplay: tranAmountDisplay(t.amount),
  cardNum: maskPan(t.cardNum),
  merchantId: t.merchantId,
  merchantName: t.merchantName,
  merchantCity: t.merchantCity,
  merchantZip: t.merchantZip,
  origTs: t.origTs,
  procTs: t.procTs,
});

type Notice = 'TRAN_LIST_BOTTOM' | 'TRAN_LIST_TOP' | 'TRAN_LIST_NO_ROWS' | null;

function page(items: SampleTransaction[], hasNext: boolean, hasPrevious: boolean, notice: Notice) {
  return json(200, {
    items: items.map(row),
    firstKey: items[0]?.tranId ?? null,
    lastKey: items.at(-1)?.tranId ?? null,
    hasNext,
    hasPrevious,
    message: notice === null ? null : messages[notice].text,
    severity: notice === null ? null : 'info',
  });
}

function list(ctx: TransactionContext): Response {
  const query = ctx.url.searchParams;
  const start = query.get('start');
  const after = query.get('after');
  const before = query.get('before');
  const size = query.get('size');
  const searched = start !== null && stripTrailing(start) !== '';
  const given = [searched, after !== null && after !== '', before !== null && before !== ''].filter(Boolean).length;
  if (given > 1 || (after !== null && !KEY.test(after)) || (before !== null && !KEY.test(before))) {
    return problem(400, 'CARD_LIST_INVALID_CURSOR', 'searchTranId');
  }
  let rows = PAGE_MAX;
  if (size !== null) {
    const n = /^[0-9]{1,9}$/.test(size) ? Number(size) : 0;
    if (n < 1 || n > PAGE_MAX) return problem(400, 'INVALID_PAGE_SIZE', 'searchTranId', {}, undefined, { max: String(PAGE_MAX) });
    rows = n;
  }
  const search = searched ? stripTrailing(start) : '';
  if (searched && !SEARCH.test(search)) return problem(400, 'INVALID_SEARCH_TRAN_ID', 'searchTranId');
  if (ctx.faults.includes('tran-read')) return problem(500, 'TRAN_LIST_FAILED', 'searchTranId');

  const all = sampleTransactions;
  const exists = (test: (id: string) => boolean) => all.some((t) => test(t.tranId));
  const forward = (from: (id: string) => boolean, key: string | null, previousTest: (id: string) => boolean) => {
    const found = all.filter((t) => from(t.tranId)).slice(0, rows + 1);
    const items = found.slice(0, rows);
    const hasNext = found.length > rows;
    const first = items[0];
    const hasPrevious = key === null ? false : first ? exists((id) => id < first.tranId) : exists(previousTest);
    return { items, hasNext, hasPrevious };
  };

  if (before !== null) {
    const earlier = all.filter((t) => t.tranId < before);
    if (earlier.length <= rows) {
      const top = forward(
        () => true,
        null,
        () => false,
      );
      return page(top.items, top.hasNext, false, top.items.length ? 'TRAN_LIST_TOP' : 'TRAN_LIST_NO_ROWS');
    }
    const items = earlier.slice(-rows);
    const hasPrevious = earlier.length > rows;
    const last = items.at(-1)?.tranId ?? before;
    return page(
      items,
      exists((id) => id > last),
      hasPrevious,
      hasPrevious ? null : 'TRAN_LIST_TOP',
    );
  }
  if (after !== null) {
    const next = forward(
      (id) => id > after,
      after,
      (id) => id <= after,
    );
    return page(next.items, next.hasNext, next.hasPrevious, next.items.length === 0 || !next.hasNext ? 'TRAN_LIST_BOTTOM' : null);
  }
  const key = searched ? search.padStart(16, '0') : null;
  const first = forward(key === null ? () => true : (id) => id >= key, key, (id) => key !== null && id < key);
  const notice: Notice = first.items.length === 0 ? 'TRAN_LIST_NO_ROWS' : first.hasNext ? null : 'TRAN_LIST_BOTTOM';
  return page(first.items, first.hasNext, first.hasPrevious, notice);
}

type Found = { value: SampleTransaction } | { response: Response };

/** The view's key rule: blank is required, 1 to 16 digits are padded, anything else is looked up as typed and is not found. */
function find(ctx: TransactionContext, typed: string): Found {
  const id = stripTrailing(typed);
  if (id === '') return { response: problem(400, 'TRAN_ID_REQUIRED', 'tranId') };
  const notFound = { response: problem(404, 'TRAN_NOT_FOUND', 'tranId') };
  if (id.length > 16 || !PRINTABLE.test(id)) return notFound;
  if (ctx.faults.includes('tran-read')) return { response: problem(500, 'TRAN_LOOKUP_FAILED', 'tranId') };
  const key = SEARCH.test(id) ? id.padStart(16, '0') : id;
  const found = sampleTransactions.find((t) => t.tranId === key);
  return found ? { value: found } : notFound;
}

async function unmask(ctx: TransactionContext, typed: string): Promise<Response> {
  if (ctx.role !== 'ADMIN') return problem(403, 'ACCESS_DENIED', null);
  const body = await readBody(ctx.request);
  const fields = body.fields;
  if (!Array.isArray(fields) || fields.length === 0 || fields.some((f) => f !== 'cardNum')) {
    return problem(400, 'INVALID_UNMASK_FIELD', 'fields', {}, undefined, { fields: 'cardNum' });
  }
  const found = find(ctx, typed);
  if ('response' in found) return found.response;
  if (ctx.faults.includes('audit')) return problem(500, 'UNMASK_AUDIT_FAILED', null);
  ctx.core.audit.push({ actor: ctx.userId, action: 'UNMASK', subjectType: 'TRANSACTION', subjectKey: found.value.tranId, fieldNames: 'cardNum' });
  return json(200, { fields: { cardNum: found.value.cardNum } }, NO_STORE);
}

/** Answers /api/transactions and its sub-paths; null for any other path. */
export async function routeTransactions(method: string, path: string, ctx: TransactionContext): Promise<Response | null> {
  if (path === '/api/transactions' && method === 'GET') return list(ctx);
  const one = /^\/api\/transactions\/([^/]*)(\/unmask)?$/.exec(path);
  if (!one) return null;
  const typed = decodeURIComponent(one[1] ?? '');
  if (!one[2] && method === 'GET') {
    const found = find(ctx, typed);
    return 'response' in found ? found.response : json(200, view(found.value));
  }
  if (one[2] && method === 'POST') return unmask(ctx, typed);
  return null;
}
