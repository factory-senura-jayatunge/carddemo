import { describe, expect, it } from 'vitest';
import { createMockServer, memoryStore, type MockSettings } from './server';

type Detail = {
  authId: number;
  version: string;
  reasonText: string;
  nextOlderAuthId: number | null;
  matchStatus: string;
  matchedTranId: string | null;
  authIdCode: string;
  cardNum: string;
  fraud: { flag: string | null; reportDate: string | null; display: string };
};
type Page = { items: { authId: number }[]; hasNext: boolean; hasPrevious: boolean; firstKey: string | null; lastKey: string | null };

const base: MockSettings = { latencyMs: 0, batchWindowOpen: false, uiSwitches: {}, authorizationInstalled: true };

async function session(userId = 'USER0001', settings: Partial<MockSettings> = {}) {
  const live = { ...base, ...settings };
  const server = createMockServer(memoryStore(), () => live);
  const signOn = await server.handle(
    new Request(`${window.location.origin}/api/auth/sign-on`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId, password: 'PASSWORD' }),
    }),
  );
  expect(signOn.status).toBe(200);
  const csrf = /XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1] ?? '';
  const call = (method: string, path: string, body?: unknown, headers: Record<string, string> = {}) =>
    server.handle(
      new Request(`${window.location.origin}${path}`, {
        method,
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf, ...headers },
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    );
  return { call, settings: live };
}

const ids = (page: Page) => page.items.map((i) => i.authId);

describe('authorization-service mock', () => {
  it('A7: pages of five newest first, forward and back, with hasNext and hasPrevious', async () => {
    const { call } = await session();
    const list = async (query = '') => (await (await call('GET', `/api/accounts/00000000001/authorizations${query}`)).json()) as Page;
    const first = await list();
    expect(ids(first)).toEqual([1012, 1011, 1010, 1009, 1008]);
    expect([first.hasPrevious, first.hasNext]).toEqual([false, true]);
    const second = await list(`?after=${first.lastKey}&size=5`);
    expect(ids(second)).toEqual([1007, 1006, 1005, 1004, 1003]);
    const third = await list(`?after=${second.lastKey}`);
    expect(ids(third)).toEqual([1002, 1001]);
    expect([third.hasPrevious, third.hasNext]).toEqual([true, false]);
    const back = await list(`?before=${third.firstKey}`);
    expect(ids(back)).toEqual(ids(second));
    const start = await list(`?before=${back.firstKey}`);
    expect(ids(start)).toEqual(ids(first));
    expect(start.hasPrevious).toBe(false);
  });

  it('A9: exactly five rows have no next page; an account without a summary has an empty page', async () => {
    const { call } = await session();
    const five = (await (await call('GET', '/api/accounts/00000000002/authorizations')).json()) as Page;
    expect(five.items).toHaveLength(5);
    expect(five.hasNext).toBe(false);
    const none = (await (await call('GET', '/api/accounts/00000000003/authorizations')).json()) as Page;
    expect(none).toEqual({ items: [], hasNext: false, hasPrevious: false, firstKey: null, lastKey: null });
  });

  it('A3 A4: the account checks in order, and the paging parameters', async () => {
    const { call } = await session();
    const code = async (path: string) => ((await (await call('GET', path)).json()) as { code: string; message: string }).code;
    expect(await code('/api/accounts/%20/authorizations/summary')).toBe('ACCOUNT_ID_REQUIRED');
    expect(await code('/api/accounts/123/authorizations/summary')).toBe('ACCOUNT_ID_NOT_NUMERIC');
    expect(await code('/api/accounts/0000000000A/authorizations')).toBe('ACCOUNT_ID_NOT_NUMERIC');
    const missing = await call('GET', '/api/accounts/99999999999/authorizations/summary');
    expect(missing.status).toBe(404);
    expect(await missing.json()).toMatchObject({ code: 'ACCOUNT_NOT_FOUND', message: 'Account:99999999999 not found in ACCT file.', field: 'acctId' });
    expect(await code('/api/accounts/00000000001/authorizations?size=6')).toBe('INVALID_PAGE_SIZE');
    expect(await code('/api/accounts/00000000001/authorizations?after=a&before=b')).toBe('INVALID_PAGE_REQUEST');
    expect(await code('/api/accounts/00000000001/authorizations?after=!!')).toBe('INVALID_CURSOR');
  });

  it('A1 A6: the summary with its customer, and a partial view without a cross-reference', async () => {
    const { call } = await session();
    const one = (await (await call('GET', '/api/accounts/00000000001/authorizations/summary')).json()) as Record<string, unknown>;
    expect(one).toMatchObject({
      acctId: '00000000001',
      customer: { custId: '000000001', name: 'Immanuel M Kessler', addressLine1: '618 Deshaun Route,Apt. 802', addressLine2: 'Altenwerthshire,NC,12546', phone: '(908)119-8310' },
      creditLimit: '2020.00',
      summaryFound: true,
      approvedCount: 8,
      declinedCount: 4,
      message: null,
    });
    const noXref = (await (await call('GET', '/api/accounts/00000000098/authorizations/summary')).json()) as Record<string, unknown>;
    expect(noXref).toMatchObject({ customer: null, summaryFound: false, approvedCount: 0, approvedAmount: '0.00', message: 'Account:00000000098 not found in XREF file.', severity: 'error' });
    const noCustomer = (await (await call('GET', '/api/accounts/00000000099/authorizations/summary')).json()) as Record<string, unknown>;
    expect(noCustomer).toMatchObject({ customer: null, summaryFound: true, message: 'Customer:000000099 not found in CUST file.' });
    const wide = (await (await call('GET', '/api/accounts/00000000097/authorizations/summary')).json()) as { customer: { name: string } };
    expect(wide.customer.name).toBe('Maximiliana   Vandenberghe-Oppenheimer');
  });

  it('A10 A11 A12 A13: detail fields, the reason table, the next older row and the match', async () => {
    const { call } = await session();
    const get = async (id: number) => {
      const response = await call('GET', `/api/authorizations/${id}`);
      return { etag: response.headers.get('ETag'), body: (await response.json()) as Detail };
    };
    const newest = await get(1012);
    expect(newest.etag).toBe(`"${newest.body.version}"`);
    expect(newest.body).toMatchObject({ reasonText: '0000-APPROVED', nextOlderAuthId: 1011, authIdCode: '001012', cardNum: expect.stringMatching(/^\*{12}\d{4}$/) as string });
    expect((await get(1011)).body.reasonText).toBe('9999-ERROR');
    expect((await get(1001)).body).toMatchObject({ nextOlderAuthId: null, matchStatus: 'M', matchedTranId: '0000000000000301' });
    expect((await get(1008)).body.fraud).toEqual({ flag: 'F', reportDate: '2026-11-06', display: 'F-11/06/26' });
    expect((await call('GET', '/api/authorizations/0')).status).toBe(400);
    expect((await call('GET', '/api/authorizations/77')).status).toBe(404);
  });

  it('A14 A15 A16: fraud F then R with If-Match; a stale version or a wrong action is 409 with current', async () => {
    const { call } = await session();
    const before = (await (await call('GET', '/api/authorizations/1012')).json()) as Detail;
    expect((await call('POST', '/api/authorizations/1012/fraud', { action: 'F' })).status).toBe(428);
    const wrong = await call('POST', '/api/authorizations/1012/fraud', { action: 'R' }, { 'If-Match': `"${before.version}"` });
    expect(wrong.status).toBe(409);
    const marked = await call('POST', '/api/authorizations/1012/fraud', { action: 'F' }, { 'If-Match': `"${before.version}"` });
    expect(marked.status).toBe(200);
    const result = (await marked.json()) as { message: string; severity: string; detail: Detail };
    expect(result).toMatchObject({ message: 'AUTH MARKED FRAUD...', severity: 'info', detail: { fraud: { flag: 'F' } } });
    expect(marked.headers.get('ETag')).toBe(`"${result.detail.version}"`);
    const stale = await call('POST', '/api/authorizations/1012/fraud', { action: 'R' }, { 'If-Match': `"${before.version}"` });
    expect(stale.status).toBe(409);
    const conflict = (await stale.json()) as { code: string; field: unknown; current: Detail };
    expect(conflict).toMatchObject({ code: 'RECORD_CHANGED', field: null, current: { version: result.detail.version, fraud: { flag: 'F' } } });
    const removed = await call('POST', '/api/authorizations/1012/fraud', { action: 'R' }, { 'If-Match': `"${result.detail.version}"` });
    expect(((await removed.json()) as { message: string }).message).toBe('AUTH FRAUD REMOVED...');
  });

  it('A17: a failed fraud write changes nothing', async () => {
    const { call, settings } = await session();
    const before = (await (await call('GET', '/api/authorizations/1012')).json()) as Detail;
    settings.faults = ['fraud-update'];
    const failed = await call('POST', '/api/authorizations/1012/fraud', { action: 'F' }, { 'If-Match': `"${before.version}"` });
    expect(failed.status).toBe(500);
    expect(await failed.json()).toMatchObject({ code: 'FRAUD_UPDATE_FAILED', message: ' System error while FRAUD Tagging, ROLLBACK' });
    settings.faults = [];
    expect(await (await call('GET', '/api/authorizations/1012')).json()).toEqual(before);
  });

  it('A20: an ADMIN gets 403 on every endpoint; without the module nothing answers', async () => {
    const admin = await session('ADMIN001');
    for (const [method, path] of [
      ['GET', '/api/accounts/00000000001/authorizations/summary'],
      ['GET', '/api/accounts/00000000001/authorizations'],
      ['GET', '/api/authorizations/1012'],
      ['POST', '/api/authorizations/1012/fraud'],
    ] as const) {
      const response = await admin.call(method, path, method === 'POST' ? { action: 'F' } : undefined);
      expect(response.status).toBe(403);
    }
    const absent = await session('USER0001', { authorizationInstalled: false });
    expect((await absent.call('GET', '/api/authorizations/1012')).status).toBe(404);
  });
});
