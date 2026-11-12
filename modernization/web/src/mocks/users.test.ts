import { describe, expect, it } from 'vitest';
import { encodeUserKey } from '@/lib/userKey';
import { createMockServer, memoryStore, type MockSettings } from './server';

const base: MockSettings = { latencyMs: 0, batchWindowOpen: false, uiSwitches: {}, authorizationInstalled: false };
const origin = window.location.origin;

interface Problem {
  code: string;
  message: string;
  field: string | null;
  severity: string;
  current?: { userId: string; version: string };
}
interface Page {
  items: { userId: string }[];
  hasNext: boolean;
  hasPrevious: boolean;
  firstKey: string | null;
  lastKey: string | null;
  message: string | null;
  severity: string | null;
}

async function session(userId = 'ADMIN001', overrides: Partial<MockSettings> = {}) {
  const settings = { ...base, ...overrides };
  const server = createMockServer(memoryStore(), () => settings);
  const signOn = await server.handle(
    new Request(`${origin}/api/auth/sign-on`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId, password: 'PASSWORD' }),
    }),
  );
  expect(signOn.status).toBe(200);
  let csrf = /XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1] ?? '';
  /** Signs another user on to the same server; the mock holds one session at a time. */
  const switchTo = async (other: string) => {
    const answer = await server.handle(
      new Request(`${origin}/api/auth/sign-on`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: other, password: 'PASSWORD' }) }),
    );
    expect(answer.status).toBe(200);
    csrf = /XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1] ?? '';
  };
  const call = (method: string, path: string, body?: unknown, headers: Record<string, string> = {}) =>
    server.handle(
      new Request(`${origin}${path}`, {
        method,
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf, ...headers },
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    );
  const page = async (query = '') => {
    const response = await call('GET', `/api/users${query}`);
    expect(response.status).toBe(200);
    return (await response.json()) as Page;
  };
  const fetchUser = async (id: string) => {
    const response = await call('GET', `/api/users/key/${encodeUserKey(id)}`);
    return { response, body: (await response.clone().json()) as { version: string; userId: string; firstName: string; lastName: string; userType: string } };
  };
  return { server, settings, call, page, fetchUser, switchTo };
}

const ids = (page: Page) => page.items.map((i) => i.userId);
const problemOf = async (response: Response) => (await response.json()) as Problem;
const newUser = (over: Record<string, string> = {}) => ({ firstName: 'JANE', lastName: 'DOE', userId: 'JDOE0001', password: 'Secret#2026', userType: 'U', ...over });

describe('user administration mock', () => {
  it('@BR-USRL-001 lists active users ten a page in code-point order, with the bottom message on the last page', async () => {
    const { page } = await session();
    const first = await page();
    expect(ids(first)).toEqual(['ADMIN001', 'ADMIN002', 'ADMIN003', 'ADMIN004', 'ADMIN005', 'CHANGE01', 'TEST0001', 'TYPEX001', 'USER0001', 'USER0002']);
    expect(first).toMatchObject({ hasNext: true, hasPrevious: false, message: null, severity: null });
    expect(JSON.stringify(first)).not.toMatch(/password|PASSWORD/);

    const second = await page(`?after=${first.lastKey}`);
    expect(ids(second)).toEqual(['USER0003', 'USER0004', 'USER0005']);
    expect(second).toMatchObject({ hasNext: false, hasPrevious: true, message: 'You have reached the bottom of the page...', severity: 'info' });

    const back = await page(`?before=${second.firstKey}`);
    expect(ids(back)).toEqual(ids(first));
    expect(back).toMatchObject({ hasNext: true, hasPrevious: false, message: 'You have reached the top of the page...' });
  });

  it('@D-02 refuses a USER and an anonymous caller', async () => {
    const { call } = await session('USER0001');
    for (const [method, path] of [['GET', '/api/users'], ['POST', '/api/users'], ['GET', '/api/users/key/QURNSU4wMDE'], ['DELETE', '/api/users/key/QURNSU4wMDE']] as const) {
      const response = await call(method, path, method === 'POST' ? newUser() : undefined);
      expect(response.status).toBe(403);
      expect((await problemOf(response)).code).toBe('ACCESS_DENIED');
    }
    const anonymous = createMockServer(memoryStore(), () => base);
    expect((await anonymous.handle(new Request(`${origin}/api/users`))).status).toBe(401);
  });

  it('@D-75 searches by a case-insensitive prefix in key form; no match is the top message, not an error', async () => {
    const { page } = await session();
    const users = await page(`?startKey=${encodeUserKey('user')}`);
    expect(ids(users)).toEqual(['USER0001', 'USER0002', 'USER0003', 'USER0004', 'USER0005']);
    expect(users.message).toBe('You have reached the bottom of the page...');
    const none = await page(`?startKey=${encodeUserKey('zz')}`);
    expect(none).toMatchObject({ items: [], hasNext: false, hasPrevious: false, firstKey: null, lastKey: null, message: 'You are at the top of the page...' });
    expect(await page(`?startKey=${encodeUserKey('user000')}`)).toEqual(await page('?start=user000'));
  });

  it('keeps paging inside the search: the tokens carry the prefix', async () => {
    const { page } = await session();
    const admins = await page(`?startKey=${encodeUserKey('ADMIN')}&size=2`);
    expect(ids(admins)).toEqual(['ADMIN001', 'ADMIN002']);
    const more = await page(`?after=${admins.lastKey}&size=2`);
    expect(ids(more)).toEqual(['ADMIN003', 'ADMIN004']);
    const last = await page(`?after=${more.lastKey}&size=2`);
    expect(ids(last)).toEqual(['ADMIN005']);
    expect(last.message).toBe('You have reached the bottom of the page...');
  });

  it('refuses mixed positions, bad sizes, forged tokens and bad search keys', async () => {
    const { call } = await session();
    const code = async (query: string) => (await problemOf(await call('GET', `/api/users${query}`))).code;
    expect(await code('?start=a&after=x')).toBe('INVALID_PAGE_REQUEST');
    expect(await code('?start=a&startKey=YQ')).toBe('INVALID_PAGE_REQUEST');
    expect(await code('?startKey=YQ&after=x')).toBe('INVALID_PAGE_REQUEST');
    expect(await code('?size=11')).toBe('INVALID_PAGE_SIZE');
    expect(await code('?size=0')).toBe('INVALID_PAGE_SIZE');
    expect(await code(`?after=${encodeUserKey('0:nobody')}`)).toBe('INVALID_CURSOR');
    expect(await code('?after=QQ==')).toBe('INVALID_CURSOR');
    for (const key of ['YQ==', 'Y+', 'YR', 'Y']) expect(await code(`?startKey=${encodeURIComponent(key)}`)).toBe('INVALID_REQUEST');
  });

  it('@VAL-USRA-001 checks the add fields in screen order, one message at a time', async () => {
    const { call } = await session();
    const steps: [Record<string, string>, string, string][] = [
      [{ firstName: '', lastName: '', userId: '', password: '', userType: '' }, 'First Name can NOT be empty...', 'firstName'],
      [{ firstName: 'A', lastName: '', userId: '', password: '', userType: '' }, 'Last Name can NOT be empty...', 'lastName'],
      [{ firstName: 'A', lastName: 'B', userId: '', password: '', userType: '' }, 'User ID can NOT be empty...', 'userId'],
      [{ firstName: 'A', lastName: 'B', userId: 'NEW1', password: '', userType: '' }, 'Password can NOT be empty...', 'password'],
      [{ firstName: 'A', lastName: 'B', userId: 'NEW1', password: 'Secret#2026', userType: '' }, 'User Type can NOT be empty...', 'userType'],
    ];
    for (const [body, text, field] of steps) {
      const answer = await problemOf(await call('POST', '/api/users', body));
      expect([answer.message, answer.field]).toEqual([text, field]);
    }
  });

  it('@BR-USRA-002 adds a user as typed, canonical ID a to z only, and the password signs on case-sensitively', async () => {
    const { call, server } = await session();
    const added = await call('POST', '/api/users', newUser({ userId: 'jsmith', firstName: 'José', lastName: 'Ñúñez' }));
    expect(added.status).toBe(201);
    expect(added.headers.get('Location')).toBe(`/api/users/key/${encodeUserKey('JSMITH')}`);
    const body = (await added.json()) as { message: string; severity: string; user: { userId: string; firstName: string; version: string } };
    expect(body).toMatchObject({ message: 'User JSMITH has been added ...', severity: 'success', user: { userId: 'JSMITH', firstName: 'José' } });
    expect(added.headers.get('ETag')).toBe(`"${body.user.version}"`);
    expect(JSON.stringify(body)).not.toContain('Secret');

    const signOn = (password: string) =>
      server.handle(new Request(`${origin}/api/auth/sign-on`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: 'JSMITH', password }) }));
    expect((await signOn('SECRET#2026')).status).toBe(401);
    expect((await signOn('Secret#2026')).status).toBe(200);

    const { call: admin } = await session();
    expect(((await (await admin('POST', '/api/users', newUser({ userId: 'éaßÿ' }))).json()) as { user: { userId: string } }).user.userId).toBe('éAßÿ');
  });

  it('@D-53 refuses spaces inside, long IDs, characters above U+00FF and other types; keeps NUL and U+00A0 in names', async () => {
    const { call } = await session();
    const answer = async (over: Record<string, string>) => problemOf(await call('POST', '/api/users', newUser(over)));
    expect((await answer({ userId: 'AB CD' })).code).toBe('USER_ID_INVALID');
    expect((await answer({ userId: 'ABCDEFGHI' })).message).toBe('User ID must not be longer than 8 characters.');
    expect((await answer({ firstName: '中' })).message).toBe('First Name contains a character that is not supported.');
    expect((await answer({ lastName: '😀' })).message).toBe('Last Name contains a character that is not supported.');
    expect((await answer({ firstName: 'A'.repeat(21) })).message).toBe('First Name must not be longer than 20 characters.');
    expect((await answer({ userType: 'a' })).code).toBe('USER_TYPE_INVALID');
    expect((await answer({ userType: 'X' })).message).toBe('User Type must be A or U...');
    expect((await answer({ password: 'short' })).message).toBe('New Password must be 8 to 64 characters ...');
    expect((await answer({ userId: 'pw000001', password: 'PW000001' })).code).toBe('NEW_PASSWORD_IS_USER_ID');
    const kept = await call('POST', '/api/users', newUser({ firstName: 'A\0B\u00a0C' }));
    expect(kept.status).toBe(201);
    expect(((await kept.json()) as { user: { firstName: string } }).user.firstName).toBe('A\0B\u00a0C');
  });

  it('@VAL-USRA-002 refuses an existing ID with the cursor on first name', async () => {
    const { call } = await session();
    const response = await call('POST', '/api/users', newUser({ userId: 'admin001' }));
    expect(response.status).toBe(409);
    expect(await problemOf(response)).toMatchObject({ code: 'USER_ID_EXISTS', message: 'User ID already exist...', field: 'firstName' });
  });

  it('@D-BAT-05 refuses writes in the batch window and still reads', async () => {
    const { call, fetchUser, page } = await session('ADMIN001', { batchWindowOpen: true });
    const { body } = await fetchUser('USER0001');
    const etag = { 'If-Match': `"${body.version}"` };
    for (const response of [
      await call('POST', '/api/users', newUser()),
      await call('PUT', `/api/users/key/${encodeUserKey('USER0001')}`, { ...body, lastName: 'X' }, etag),
      await call('DELETE', `/api/users/key/${encodeUserKey('USER0001')}`, undefined, etag),
    ]) {
      expect(response.status).toBe(503);
      expect(response.headers.get('Retry-After')).toBe('300');
      expect((await problemOf(response)).message).toBe('User changes are not available during the batch cycle. Try again later...');
    }
    expect((await page()).items).toHaveLength(10);
  });

  it('@VAL-USRU-003 fetches by key with a version and ETag; an unknown or undecodable key is refused', async () => {
    const { call, fetchUser } = await session();
    const { response, body } = await fetchUser('user0001');
    expect(response.status).toBe(200);
    expect(body).toEqual({ userId: 'USER0001', firstName: 'LAWRENCE', lastName: 'THOMAS', userType: 'U', version: expect.stringMatching(/^[0-9a-f]{16}$/) as string });
    expect(response.headers.get('ETag')).toBe(`"${body.version}"`);
    const missing = await fetchUser('NOBODY');
    expect(missing.response.status).toBe(404);
    expect(missing.body).toMatchObject({ message: 'User ID NOT found...', field: 'userId' });
    const bad = await call('GET', '/api/users/key/QR');
    expect(bad.status).toBe(400);
    expect((await problemOf(bad)).code).toBe('INVALID_REQUEST');
  });

  it('@BR-USRU-004 @D-17 saves with If-Match: no change, a change, a stale version, missing and bad headers', async () => {
    const { call, fetchUser } = await session();
    const key = encodeUserKey('USER0001');
    const { body } = await fetchUser('USER0001');
    const form = { firstName: body.firstName, lastName: body.lastName, userType: body.userType, password: '' };
    const ifMatch = { 'If-Match': `"${body.version}"` };
    expect((await call('PUT', `/api/users/key/${key}`, form)).status).toBe(428);
    expect((await problemOf(await call('PUT', `/api/users/key/${key}`, form, { 'If-Match': '*' }))).code).toBe('INVALID_IF_MATCH');
    expect(await problemOf(await call('PUT', `/api/users/key/${key}`, form, ifMatch))).toMatchObject({ code: 'NO_CHANGE', message: 'Please modify to update ...', field: 'firstName' });

    const saved = await call('PUT', `/api/users/key/${key}`, { ...form, lastName: 'THOMSON' }, ifMatch);
    expect(saved.status).toBe(200);
    const result = (await saved.json()) as { message: string; user: { version: string } };
    expect(result.message).toBe('User USER0001 has been updated ...');
    expect(result.user.version).not.toBe(body.version);

    const stale = await call('PUT', `/api/users/key/${key}`, { ...form, lastName: 'OTHER' }, ifMatch);
    expect(stale.status).toBe(409);
    const conflict = await problemOf(stale);
    expect(conflict).toMatchObject({ code: 'RECORD_CHANGED', current: { userId: 'USER0001', version: result.user.version } });
    expect(stale.headers.get('ETag')).toBe(`"${result.user.version}"`);
  });

  it('@DEF-USRU-011 a set password must differ from the current one and then signs on', async () => {
    const { call, fetchUser, server } = await session();
    const key = encodeUserKey('USER0003');
    const { body } = await fetchUser('USER0003');
    const form = { firstName: body.firstName, lastName: body.lastName, userType: body.userType };
    expect((await problemOf(await call('PUT', `/api/users/key/${key}`, { ...form, password: 'PASSWORD' }, { 'If-Match': `"${body.version}"` }))).code).toBe('NEW_PASSWORD_SAME');
    expect((await call('PUT', `/api/users/key/${key}`, { ...form, password: 'NewPass#1' }, { 'If-Match': `"${body.version}"` })).status).toBe(200);
    const signOn = await server.handle(new Request(`${origin}/api/auth/sign-on`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: 'USER0003', password: 'NewPass#1' }) }));
    expect(signOn.status).toBe(200);
  });

  it('@D-06 refuses deleting yourself and the last administrator; deletes logically and allows the re-add', async () => {
    const { call, fetchUser, page, server } = await session();
    const del = async (id: string) => {
      const { body } = await fetchUser(id);
      return call('DELETE', `/api/users/key/${encodeUserKey(id)}`, undefined, { 'If-Match': `"${body.version}"` });
    };
    expect(await problemOf(await del('ADMIN001'))).toMatchObject({ code: 'CANNOT_DELETE_SELF', message: 'You cannot delete your own User ID...', field: 'userId' });

    const deleted = await del('USER0004');
    expect(deleted.status).toBe(200);
    expect(await deleted.json()).toEqual({ message: 'User USER0004 has been deleted ...', severity: 'success' });
    expect(ids(await page(`?startKey=${encodeUserKey('USER')}`))).not.toContain('USER0004');
    expect((await fetchUser('USER0004')).response.status).toBe(404);
    const signOn = await server.handle(new Request(`${origin}/api/auth/sign-on`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: 'USER0004', password: 'PASSWORD' }) }));
    expect(signOn.status).toBe(401);

    const readded = await call('POST', '/api/users', newUser({ userId: 'USER0004', userType: 'A' }));
    expect(readded.status).toBe(201);
    expect(((await readded.json()) as { user: { userType: string } }).user.userType).toBe('A');
  });

  it('@D-06 an administrator may delete another while one remains', async () => {
    const { fetchUser, call, switchTo } = await session();
    const del = async (id: string) => {
      const { body } = await fetchUser(id);
      return call('DELETE', `/api/users/key/${encodeUserKey(id)}`, undefined, { 'If-Match': `"${body.version}"` });
    };
    for (const id of ['ADMIN003', 'ADMIN004', 'ADMIN005']) expect((await del(id)).status).toBe(200);
    await switchTo('ADMIN002');
    expect((await del('ADMIN001')).status).toBe(200);
    expect((await problemOf(await del('ADMIN002'))).code).toBe('CANNOT_DELETE_SELF');
  });

  it('refuses a delete with a stale version', async () => {
    const { call, fetchUser } = await session();
    const { body } = await fetchUser('USER0002');
    const key = encodeUserKey('USER0002');
    await call('PUT', `/api/users/key/${key}`, { firstName: 'X', lastName: body.lastName, userType: 'U' }, { 'If-Match': `"${body.version}"` });
    const stale = await call('DELETE', `/api/users/key/${key}`, undefined, { 'If-Match': `"${body.version}"` });
    expect(stale.status).toBe(409);
    expect((await problemOf(stale)).code).toBe('RECORD_CHANGED');
  });

  it('maps forced database failures to the legacy texts', async () => {
    const { call, settings, fetchUser } = await session();
    const { body } = await fetchUser('USER0001');
    const key = encodeUserKey('USER0001');
    const ifMatch = { 'If-Match': `"${body.version}"` };
    settings.faults = ['user-add', 'user-update', 'user-delete'];
    expect((await problemOf(await call('POST', '/api/users', newUser()))).message).toBe('Unable to Add User...');
    expect((await problemOf(await call('PUT', `/api/users/key/${key}`, { ...body, lastName: 'Y' }, ifMatch))).message).toBe('Unable to Update User...');
    expect((await problemOf(await call('DELETE', `/api/users/key/${key}`, undefined, ifMatch))).message).toBe('Unable to Update User...');
    settings.faults = ['user-read'];
    const read = await call('GET', '/api/users');
    expect(read.status).toBe(500);
    expect(await problemOf(read)).toMatchObject({ message: 'Unable to lookup User...', field: 'start' });
  });

  it.each(['\0', '/', '%', '\\', ';', '?', '#', '+', '\u0001', '\u007f', '\u0085', '\t', '\u00a0', 'ÿ', 'ß', '.', '..'])(
    'adds, finds, updates and deletes the user ID %j through the key form',
    async (c) => {
      const { call, fetchUser, page } = await session();
      const id = `K${c}1`;
      const added = await call('POST', '/api/users', newUser({ userId: id }));
      expect(added.status).toBe(201);
      const canonicalId = ((await added.json()) as { user: { userId: string } }).user.userId;
      expect(ids(await page(`?startKey=${encodeUserKey(canonicalId)}`))).toEqual([canonicalId]);
      const { body } = await fetchUser(canonicalId);
      const key = encodeUserKey(canonicalId);
      const saved = await call('PUT', `/api/users/key/${key}`, { firstName: 'NEW', lastName: body.lastName, userType: body.userType }, { 'If-Match': `"${body.version}"` });
      expect(saved.status).toBe(200);
      const version = ((await saved.json()) as { user: { version: string } }).user.version;
      expect((await call('DELETE', `/api/users/key/${key}`, undefined, { 'If-Match': `"${version}"` })).status).toBe(200);
    },
  );
});
