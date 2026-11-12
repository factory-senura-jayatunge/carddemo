import { describe, expect, it } from 'vitest';
import { createMockServer, memoryStore, type MockSettings } from './server';

const settings: MockSettings = { latencyMs: 0, batchWindowOpen: false, uiSwitches: {}, authorizationInstalled: false };

const signOn = (server: ReturnType<typeof createMockServer>, userId: string, password: string) =>
  server.handle(
    new Request(`${window.location.origin}/api/auth/sign-on`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId, password }),
    }),
  );

/** Signs USER0001 on and returns a caller that sends the session's CSRF token. */
async function signedOnUser(server: ReturnType<typeof createMockServer>) {
  expect((await signOn(server, 'USER0001', 'PASSWORD')).status).toBe(200);
  const csrf = /XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1] ?? '';
  return (method: string, path: string, body?: unknown, headers: Record<string, string> = {}) =>
    server.handle(
      new Request(`${window.location.origin}${path}`, {
        method,
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf, ...headers },
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    );
}

describe('contract mock', () => {
  it('@DEF-SGN-002 counts every one of overlapping failed sign-ons', async () => {
    const server = createMockServer(memoryStore(), () => settings);
    const answers = await Promise.all(Array.from({ length: 30 }, (_, i) => signOn(server, 'USER0003', `WRONG${i}`)));
    expect(answers.map((a) => a.status)).toEqual(Array<number>(30).fill(401));
    // 30 failures reach the per-address limit; 5 of them have locked USER0003.
    expect((await signOn(server, 'USER0003', 'PASSWORD')).status).toBe(429);
  });

  it('CRDU ignores members outside the four card fields: no error, no echo, nothing else written', async () => {
    const call = await signedOnUser(createMockServer(memoryStore(), () => settings));
    const before = (await (await call('GET', '/api/cards/1/update-form')).json()) as Record<string, string>;
    const extras = { acctId: '00000000049', cardNum: '4000002000000000', cvvCd: '987' };
    const changes = { embossedName: 'PAT EXTRA', activeStatus: before.activeStatus, expiryMonth: before.expiryMonth, expiryYear: before.expiryYear };

    const saved = await call('PUT', '/api/cards/1', { changes: { ...changes, ...extras } }, { 'If-Match': `"${before.version}"` });
    expect(saved.status).toBe(200);
    const answer = await saved.text();
    for (const value of Object.values(extras)) expect(answer).not.toContain(value);
    expect(answer).not.toContain('cvv');

    const after = (await (await call('GET', '/api/cards/1/update-form')).json()) as Record<string, string>;
    expect(after).toMatchObject({ ...changes, cardId: before.cardId, acctId: before.acctId, cardNum: before.cardNum });
    expect(after).not.toHaveProperty('cvvCd');
    expect(after.version).not.toBe(before.version);
  });

  it('@D-08 ACUP-S31: a name outside the label table gets the fixed text on field changes, never echoed', async () => {
    const call = await signedOnUser(createMockServer(memoryStore(), () => settings));
    const form = (await (await call('GET', '/api/accounts/00000000001/update-form')).json()) as { version: string };
    const save = (changes: Record<string, string>) =>
      call('PUT', '/api/accounts/00000000001', { changes }, { 'If-Match': `"${form.version}"` });
    for (const name of ['cvv', 'CurrentBalance', '<b>123-45-6789</b>', 'x'.repeat(10_000)]) {
      const answer = await save({ ficoScore: '700', [name]: '1' });
      expect(answer.status).toBe(400);
      const body = await answer.text();
      expect(JSON.parse(body)).toMatchObject({ code: 'FIELD_NOT_EDITABLE', field: 'changes', message: 'This field cannot be changed on this screen.' });
      expect(body).not.toContain(name);
    }
    const known = (await (await save({ custId: '1' })).json()) as Record<string, string>;
    expect(known).toMatchObject({ code: 'FIELD_NOT_EDITABLE', field: 'custId', message: 'Customer id cannot be changed on this screen.' });
    const after = (await (await call('GET', '/api/accounts/00000000001/update-form')).json()) as { version: string; ficoScore: string };
    expect(after).toMatchObject({ version: form.version, ficoScore: '274' });
  });

  it('@D-61 ACUP-S48: on an account with no card a customer field is refused with its label', async () => {
    const call = await signedOnUser(createMockServer(memoryStore(), () => settings));
    const form = (await (await call('GET', '/api/accounts/00000000098/update-form')).json()) as { version: string };
    const answer = await call('PUT', '/api/accounts/00000000098', { changes: { ficoScore: '700' } }, { 'If-Match': `"${form.version}"` });
    expect(answer.status).toBe(400);
    expect(await answer.json()).toMatchObject({ code: 'FIELD_NOT_EDITABLE', field: 'ficoScore', message: 'FICO Score cannot be changed on this screen.' });
  });

  it('§2: versions are lower-case hex; If-Match must be quoted, a body version must not be', async () => {
    const call = await signedOnUser(createMockServer(memoryStore(), () => settings));
    const form = (await (await call('GET', '/api/accounts/00000000001/update-form')).json()) as { version: string };
    expect(form.version).toMatch(/^[0-9a-f]{16}\.[0-9a-f]{16}$/);
    const changes = { creditLimit: '2500.00' };
    const validate = (version: string) => call('POST', '/api/accounts/00000000001/validate', { version, changes });
    expect((await validate(form.version)).status).toBe(200);
    expect((await validate(form.version.toUpperCase())).status).toBe(400);
    expect((await validate(`"${form.version}"`)).status).toBe(400);
    expect((await validate(`${form.version}.${form.version.slice(0, 16)}`)).status).toBe(400);
    const save = (ifMatch?: string) => call('PUT', '/api/accounts/00000000001', { changes }, ifMatch === undefined ? {} : { 'If-Match': ifMatch });
    expect((await save()).status).toBe(428);
    expect((await save(`"${form.version.toUpperCase()}"`)).status).toBe(400);
    expect((await save(form.version)).status).toBe(400);
    expect((await save(`W/"${form.version}"`)).status).toBe(400);
    expect((await save(`"${form.version}"`)).status).toBe(200);

    const card = (await (await call('GET', '/api/cards/1/update-form')).json()) as { version: string };
    expect(card.version).toMatch(/^[0-9a-f]{16}$/);
    const cardSave = (ifMatch: string) => call('PUT', '/api/cards/1', { changes: { embossedName: 'PAT CASE', activeStatus: 'Y', expiryMonth: '03', expiryYear: '2026' } }, { 'If-Match': ifMatch });
    expect((await cardSave(`"${card.version.toUpperCase()}"`)).status).toBe(400);
    expect((await cardSave(`"${card.version}.${card.version}"`)).status).toBe(400);
  });

  it('CRDU-S9 CRDU-S36: status, month and year are compared and written without surrounding spaces', async () => {
    const call = await signedOnUser(createMockServer(memoryStore(), () => settings));
    type CardForm = { embossedName: string; activeStatus: string; expiryMonth: string; expiryYear: string; version: string };
    const before = (await (await call('GET', '/api/cards/1/update-form')).json()) as CardForm;
    const same = { embossedName: before.embossedName, activeStatus: ` ${before.activeStatus.toLowerCase()} `, expiryMonth: ` ${before.expiryMonth}`, expiryYear: `${before.expiryYear} ` };
    const noChange = await call('POST', '/api/cards/1/validate', { version: before.version, changes: same });
    expect(await noChange.json()).toMatchObject({ code: 'NO_CHANGE' });
    const saved = await call('PUT', '/api/cards/1', { changes: { ...same, embossedName: 'PAT SPACES' } }, { 'If-Match': `"${before.version}"` });
    expect(saved.status).toBe(200);
    const after = (await (await call('GET', '/api/cards/1/update-form')).json()) as Record<string, string>;
    expect(after).toMatchObject({ embossedName: 'PAT SPACES', activeStatus: before.activeStatus, expiryMonth: before.expiryMonth, expiryYear: before.expiryYear });
  });

  it('CRDU-S9: the name end and the status, month and year are stripped as Java strips them', async () => {
    const call = await signedOnUser(createMockServer(memoryStore(), () => settings));
    type CardForm = { embossedName: string; activeStatus: string; expiryMonth: string; expiryYear: string; version: string };
    const before = (await (await call('GET', '/api/cards/1/update-form')).json()) as CardForm;
    const validate = async (changes: Record<string, string>) => (await (await call('POST', '/api/cards/1/validate', { version: before.version, changes })).json()) as Record<string, unknown>;
    const javaSpaces = { embossedName: `${before.embossedName}\u001f\t`, activeStatus: `\u001c${before.activeStatus}`, expiryMonth: `${before.expiryMonth}\u3000`, expiryYear: before.expiryYear };
    expect(await validate(javaSpaces)).toMatchObject({ code: 'NO_CHANGE' });
    expect(await validate({ ...javaSpaces, embossedName: `${before.embossedName}\u00a0` })).not.toMatchObject({ code: 'NO_CHANGE' });
  });

  it('ACUP-S35 ACUP-S49: the stored phone 1 prefix 119 also fails whenever phone 1 is changed', async () => {
    const call = await signedOnUser(createMockServer(memoryStore(), () => settings));
    const form = (await (await call('GET', '/api/accounts/00000000001/update-form')).json()) as { version: string };
    const phone1 = async (phone1Area: string, phone1Prefix = '119') =>
      (await (await call('POST', '/api/accounts/00000000001/validate', { version: form.version, changes: { phone1Area, phone1Prefix, phone1Line: '8310' } })).json()) as Record<string, unknown>;
    expect(await phone1('800')).toMatchObject({ message: 'Phone Number 1: Not valid North America general purpose area code', invalidFields: ['phone1Area', 'phone1Prefix'] });
    expect(await phone1('800', '555')).toMatchObject({ invalidFields: ['phone1Area'] });
    expect(await phone1('90')).toMatchObject({ message: 'Phone Number 1: Area code must be A 3 digit number.', invalidFields: ['phone1Area', 'phone1Prefix'] });
  });

  it('CRDL v8: cursors, size and filters are checked in the contract order', async () => {
    const call = await signedOnUser(createMockServer(memoryStore(), () => settings));
    const list = async (body: Record<string, unknown>) => {
      const answer = await call('POST', '/api/cards/search', body);
      return { status: answer.status, ...((await answer.json()) as { code?: string; items?: unknown[] }) };
    };
    expect((await list({ start: '', after: 'MQ' })).status).toBe(200);
    expect((await list({ start: '   ' })).items).toHaveLength(7);
    for (const bad of ['MR', '!!', 'MQ==', 'MA', 'MDE', 'LTE', btoa('9223372036854775808').replace(/=+$/, '')]) {
      expect((await list({ after: bad })).code, bad).toBe('INVALID_CURSOR');
    }
    const gone = btoa('999').replace(/=+$/, '');
    expect((await list({ after: gone, size: 8 })).code).toBe('INVALID_PAGE_SIZE');
    expect((await list({ after: gone, acctId: '123' })).code).toBe('INVALID_ACCT_FILTER');
    expect((await list({ after: gone })).code).toBe('INVALID_CURSOR');
    expect((await list({ acctId: 12345678901 })).code).toBe('INVALID_ACCT_FILTER');
    expect((await list({ cardNum: true })).code).toBe('INVALID_CARD_FILTER');
  });

  it('CRDU-S44: a well-formed unknown 19-digit card ID on save is the lock failure, not 404', async () => {
    const call = await signedOnUser(createMockServer(memoryStore(), () => settings));
    const changes = { embossedName: 'PAT NONE', activeStatus: 'Y', expiryMonth: '03', expiryYear: '2030' };
    const tag = '"0000000000000001"';
    expect((await call('PUT', '/api/cards/9223372036854775807', { changes }, { 'If-Match': tag })).status).toBe(409);
    expect((await call('PUT', '/api/cards/9223372036854775807', { changes })).status).toBe(428);
    expect((await call('PUT', '/api/cards/9223372036854775808', { changes }, { 'If-Match': tag })).status).toBe(404);
    expect((await call('GET', '/api/cards/9223372036854775807')).status).toBe(404);
  });
});
