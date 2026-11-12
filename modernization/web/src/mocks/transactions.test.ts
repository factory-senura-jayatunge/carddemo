import { describe, expect, it } from 'vitest';
import { createMockServer, memoryStore, type MockSettings } from './server';
import { tranAmountDisplay } from './transactions';

type Row = { tranId: string; amount: string; amountDisplay: string; description: string; origDate: string };
type Page = {
  items: Row[];
  firstKey: string | null;
  lastKey: string | null;
  hasNext: boolean;
  hasPrevious: boolean;
  message: string | null;
  severity: string | null;
};
type Problem = { code: string; message: string; field: string | null };

const base: MockSettings = { latencyMs: 0, batchWindowOpen: false, uiSwitches: {}, authorizationInstalled: false };

async function session(userId = 'USER0001', settings: Partial<MockSettings> = {}) {
  const live = { ...base, ...settings };
  const store = memoryStore();
  const server = createMockServer(store, () => live);
  const signOn = await server.handle(
    new Request(`${window.location.origin}/api/auth/sign-on`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId, password: 'PASSWORD' }),
    }),
  );
  expect(signOn.status).toBe(200);
  const csrf = /XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1] ?? '';
  const call = (method: string, path: string, body?: unknown) =>
    server.handle(
      new Request(`${window.location.origin}${path}`, {
        method,
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    );
  const list = async (query = '') => (await (await call('GET', `/api/transactions${query}`)).json()) as Page;
  const code = async (method: string, path: string, body?: unknown) => {
    const response = await call(method, path, body);
    return { status: response.status, ...((await response.json()) as Problem) };
  };
  return { call, list, code, settings: live };
}

const ids = (page: Page) => page.items.map((r) => r.tranId);

describe('transaction-service mock', () => {
  it('@BR-TRNL-001 @BR-TRNL-002 @NUM-TRNL-001 @NUM-TRNL-002 @BR-TRNL-006: ten rows in ID order for a USER, each with its date, 26 characters of description and amount', async () => {
    const { list } = await session();
    const first = await list();
    expect(first.items).toHaveLength(10);
    expect(ids(first)).toEqual([...ids(first)].sort());
    expect(first.items[0]).toEqual({
      tranId: '0000000000683580',
      amount: '504.77',
      amountDisplay: '+00000504.77',
      description: 'Purchase at Abshire-Lowe',
      origDate: '06/10/22',
    });
    expect(first.items[1]).toMatchObject({ tranId: '0000000001774260', amountDisplay: '-00000919.00', description: 'Return item at Nitzsche, N' });
    expect(first).toMatchObject({
      firstKey: '0000000000683580',
      lastKey: '0000000021711604',
      hasNext: true,
      hasPrevious: false,
      message: null,
      severity: null,
    });
  });

  it('@FLOW-TRNL-005 @FLOW-TRNL-006 @VAL-TRNL-005 pages forward to the bottom and back to the top, with the notices', async () => {
    const { list } = await session();
    const first = await list();
    const second = await list(`?after=${first.lastKey}`);
    expect(ids(second)[0]).toBe('0000000025430891');
    expect([second.hasPrevious, second.hasNext, second.message]).toEqual([true, true, null]);
    expect(ids(await list(`?before=${second.firstKey}`))).toEqual(ids(first));
    const top = await list(`?before=${second.firstKey}`);
    expect([top.hasPrevious, top.message, top.severity]).toEqual([false, 'You have reached the top of the page...', 'info']);
    const last = await list('?start=957864065');
    expect(ids(last)).toHaveLength(10);
    expect(last.lastKey).toBe('0000000996722787');
    expect([last.hasPrevious, last.hasNext, last.message]).toEqual([true, false, 'You have reached the bottom of the page...']);
    const past = await list(`?after=${last.lastKey}`);
    expect([past.items.length, past.hasNext, past.hasPrevious, past.message]).toEqual([0, false, true, 'You have reached the bottom of the page...']);
  });

  it('@VAL-TRNL-001 @DEF-TRNL-006 the start ID: trailing spaces dropped, 1 to 16 digits, padded on the left; above the last ID is an empty page', async () => {
    const { list, code } = await session();
    expect(ids(await list('?start=1774260%20%20'))[0]).toBe('0000000001774260');
    expect(ids(await list('?start=0000000001774260'))[0]).toBe('0000000001774260');
    expect(ids(await list('?start=1774261'))[0]).toBe('0000000006292564');
    expect(ids(await list('?start=%20%20'))[0]).toBe('0000000000683580');
    const above = await list('?start=9999999999999999');
    expect([above.items.length, above.hasPrevious, above.message]).toEqual([0, true, 'You are at the top of the page...']);
    for (const bad of ['12a', '%201', '1%09', '1%C2%A0', '12345678901234567', '-1']) {
      expect(await code('GET', `/api/transactions?start=${bad}`)).toMatchObject({
        status: 400,
        code: 'INVALID_SEARCH_TRAN_ID',
        message: 'Tran ID must be Numeric ...',
        field: 'searchTranId',
      });
    }
  });

  it('checks the cursor, then the page size, then the start ID', async () => {
    const { code } = await session();
    const cursor = { status: 400, code: 'INVALID_CURSOR', field: 'searchTranId' };
    expect(await code('GET', '/api/transactions?after=0000000000683580&before=0000000001774260')).toMatchObject(cursor);
    expect(await code('GET', '/api/transactions?start=1&after=0000000000683580')).toMatchObject(cursor);
    expect(await code('GET', '/api/transactions?after=683580')).toMatchObject(cursor);
    expect(await code('GET', '/api/transactions?before=x&size=0&start=x')).toMatchObject(cursor);
    expect(await code('GET', '/api/transactions?size=11&start=x')).toMatchObject({ status: 400, code: 'INVALID_PAGE_SIZE' });
    expect(await code('GET', '/api/transactions?size=0')).toMatchObject({ code: 'INVALID_PAGE_SIZE' });
    const three = (await (await (await session()).call('GET', '/api/transactions?size=3')).json()) as Page;
    expect(three.items).toHaveLength(3);
  });

  it('@VAL-TRNV-001 @VAL-TRNV-002 @BR-TRNV-001 the view: blank is required, digits are padded, anything else is not found; the card is masked', async () => {
    const { call, code } = await session();
    const view = (await (await call('GET', '/api/transactions/683580')).json()) as Record<string, string>;
    expect(view).toMatchObject({
      tranId: '0000000000683580',
      typeCd: '01',
      catCd: '0001',
      source: 'POS TERM',
      amount: '504.77',
      amountDisplay: '+00000504.77',
      cardNum: '************7065',
      merchantName: 'Abshire-Lowe',
    });
    expect(await code('GET', '/api/transactions/%20%20')).toMatchObject({
      status: 400,
      code: 'TRAN_ID_REQUIRED',
      message: 'Tran ID can NOT be empty...',
      field: 'tranId',
    });
    for (const missing of ['12345', 'abc', '%201', '12345678901234567', '%C3%BF']) {
      expect(await code('GET', `/api/transactions/${missing}`)).toMatchObject({ status: 404, code: 'TRAN_NOT_FOUND', message: 'Transaction ID NOT found...' });
    }
  });

  it('a read failure gives the lookup messages', async () => {
    const { code } = await session('USER0001', { faults: ['tran-read'] });
    expect(await code('GET', '/api/transactions')).toMatchObject({ status: 500, code: 'TRAN_LIST_FAILED', message: 'Unable to lookup transaction...' });
    expect(await code('GET', '/api/transactions/683580')).toMatchObject({
      status: 500,
      code: 'TRAN_LOOKUP_FAILED',
      message: 'Unable to lookup Transaction...',
    });
  });

  it('@DEF-TRNV-004 unmask: ADMIN only, the card number only, checked before the ID, and audited', async () => {
    const user = await session();
    expect(await user.code('POST', '/api/transactions/683580/unmask', { fields: ['cardNum'] })).toMatchObject({ status: 403, code: 'ACCESS_DENIED' });
    const admin = await session('ADMIN001');
    expect(await admin.code('POST', '/api/transactions/nope/unmask', { fields: ['merchantId'] })).toMatchObject({
      status: 400,
      code: 'INVALID_UNMASK_FIELD',
      message: 'Unmask one or more of: cardNum',
    });
    expect(await admin.code('POST', '/api/transactions/683580/unmask', { fields: [] })).toMatchObject({ code: 'INVALID_UNMASK_FIELD' });
    expect(await admin.code('POST', '/api/transactions/12345/unmask', { fields: ['cardNum'] })).toMatchObject({ status: 404, code: 'TRAN_NOT_FOUND' });
    const shown = await admin.call('POST', '/api/transactions/683580/unmask', { fields: ['cardNum'] });
    expect(shown.status).toBe(200);
    expect(shown.headers.get('Cache-Control')).toBe('no-store');
    expect(await shown.json()).toEqual({ fields: { cardNum: '4859452612877065' } });
    const failing = await session('ADMIN001', { faults: ['audit'] });
    expect(await failing.code('POST', '/api/transactions/683580/unmask', { fields: ['cardNum'] })).toMatchObject({ status: 500, code: 'UNMASK_AUDIT_FAILED' });
  });

  it('@NUM-TRNL-001 @DEF-TRNL-002 the amount display keeps every digit up to nine', () => {
    expect(tranAmountDisplay(0)).toBe('+00000000.00');
    expect(tranAmountDisplay(-5)).toBe('-00000000.05');
    expect(tranAmountDisplay(9999999999)).toBe('+99999999.99');
    expect(tranAmountDisplay(-99999999999)).toBe('-999999999.99');
  });
});
