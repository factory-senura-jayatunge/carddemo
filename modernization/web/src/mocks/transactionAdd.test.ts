import { describe, expect, it } from 'vitest';
import { createMockServer, memoryStore, type MockSettings } from './server';

const base: MockSettings = { latencyMs: 0, batchWindowOpen: false, uiSwitches: {}, authorizationInstalled: false };
type Body = Record<string, unknown>;

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
  const call = async (method: string, path: string, body?: unknown, headers: Record<string, string> = {}) => {
    const response = await server.handle(
      new Request(`${window.location.origin}${path}`, {
        method,
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf, ...headers },
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    );
    return { status: response.status, headers: response.headers, body: (await response.json()) as Body };
  };
  return { call, settings: live };
}

const FORM = {
  acctId: '50',
  cardNum: '',
  typeCd: '1',
  catCd: '1',
  source: 'POS TERM',
  description: 'Coffee',
  amount: '-00000012.50',
  origDate: '2022-07-06',
  merchantId: '123',
  merchantName: 'Cafe',
  merchantCity: 'Seattle',
  merchantZip: '98101',
  confirm: '',
};
const KEY = crypto.randomUUID();
const OTHER_KEY = crypto.randomUUID();

describe('transaction-service mock: add (TRNA)', () => {
  it('@BR-TRNA-001 @BR-TRNA-002 validate pads the keys, looks up the card and asks to confirm', async () => {
    const { call } = await session();
    const { status, body } = await call('POST', '/api/transactions/validate', FORM);
    expect(status).toBe(200);
    expect(body).toMatchObject({
      acctId: '00000000050',
      cardNum: '************5740',
      typeCd: '01',
      catCd: '0001',
      merchantId: '000000123',
      amount: '-12.50',
      amountDisplay: '-00000012.50',
      message: 'Confirm to add this transaction...',
      field: 'confirm',
    });
    expect(body.procDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    const byCard = await call('POST', '/api/transactions/validate', { ...FORM, acctId: '', cardNum: '500024453765740' });
    expect(byCard.body).toMatchObject({ acctId: '00000000050', cardNum: '************5740' });
  });

  it('@VAL-TRNA-001 @VAL-TRNA-002 @VAL-TRNA-003 @VAL-TRNA-004 @VAL-TRNA-005 the keys, in order: required, digits (the card even with an account), cross-reference', async () => {
    const { call } = await session();
    const code = async (form: Body) => {
      const { status, body } = await call('POST', '/api/transactions/validate', { ...FORM, ...form });
      return [status, body.code, body.field, body.message];
    };
    expect(await code({ acctId: '  ', cardNum: '' })).toEqual([400, 'KEY_REQUIRED', 'acctId', 'Account or Card Number must be entered...']);
    expect(await code({ acctId: '5a' })).toEqual([400, 'INVALID_ACCT_ID', 'acctId', 'Account ID must be Numeric...']);
    expect(await code({ cardNum: '12x' })).toEqual([400, 'INVALID_CARD_NUM', 'cardNum', 'Card Number must be Numeric...']);
    expect(await code({ acctId: '99999' })).toEqual([404, 'ACCOUNT_NOT_FOUND', 'acctId', 'Account ID NOT found...']);
    expect(await code({ cardNum: '9999000000000961' })).toEqual([400, 'CARD_ACCOUNT_MISMATCH', 'cardNum', 'Card Number does not belong to this Account...']);
    expect(await code({ acctId: '', cardNum: '1' })).toEqual([404, 'CARD_NOT_FOUND', 'cardNum', 'Card Number NOT found...']);
    const choice = await call('POST', '/api/transactions/validate', { ...FORM, acctId: '96' });
    expect(choice.body).toMatchObject({ code: 'CARD_CHOICE_REQUIRED', field: 'cardNum', cards: ['************0961', '************0962'] });
    const chosen = await call('POST', '/api/transactions/validate', { ...FORM, acctId: '96', cardNum: '9999000000000962' });
    expect(chosen.body).toMatchObject({ acctId: '00000000096', cardNum: '************0962' });
  });

  it('@VAL-TRNA-006 every data field is required before any character or length check, and problems carry the values checked so far', async () => {
    const { call } = await session();
    const empty = await call('POST', '/api/transactions/validate', { ...FORM, typeCd: ' ', description: 'x'.repeat(61) });
    expect(empty.body).toMatchObject({ code: 'FIELD_REQUIRED', field: 'typeCd', message: 'Type CD can NOT be empty...', acctId: '00000000050', cardNum: '************5740' });
    const zip = await call('POST', '/api/transactions/validate', { ...FORM, merchantZip: '' });
    expect(zip.body).toMatchObject({ field: 'merchantZip', message: 'Merchant Zip can NOT be empty...' });
    const emoji = await call('POST', '/api/transactions/validate', { ...FORM, typeCd: '123', description: '\u{1F600}'.repeat(33) });
    expect(emoji.body).toMatchObject({ code: 'UNSUPPORTED_CHARACTER', field: 'description', message: 'Description contains a character that is not supported.' });
    const latin1 = await call('POST', '/api/transactions/validate', { ...FORM, merchantName: '\u00e9'.repeat(31) });
    expect(latin1.body).toMatchObject({ code: 'TOO_LONG', field: 'merchantName', message: 'Merchant Name must not be longer than 30 characters.' });
    const padded = await call('POST', '/api/transactions/validate', { ...FORM, source: `${'S'.repeat(10)}   ` });
    expect(padded.status).toBe(200);
  });

  it('@VAL-TRNA-007 @VAL-TRNA-008 @VAL-TRNA-009 @VAL-TRNA-010 @VAL-TRNA-011 codes, references, amount, date and merchant ID, in order', async () => {
    const { call } = await session();
    const code = async (form: Body) => (await call('POST', '/api/transactions/validate', { ...FORM, ...form })).body;
    expect(await code({ typeCd: 'A1' })).toMatchObject({ code: 'TYPE_NOT_NUMERIC', field: 'typeCd' });
    const category = await code({ catCd: '1.0' });
    // The codes join the passed values only once both are numeric.
    expect(category).toMatchObject({ code: 'CATEGORY_NOT_NUMERIC', field: 'catCd' });
    expect(category).not.toHaveProperty('typeCd');
    expect(await code({ typeCd: '9' })).toMatchObject({ code: 'TYPE_NOT_FOUND', field: 'typeCd', typeCd: '09' });
    expect(await code({ catCd: '9' })).toMatchObject({ code: 'CATEGORY_NOT_FOUND', field: 'catCd', catCd: '0009' });
    expect(await code({ amount: '12.50' })).toMatchObject({ code: 'INVALID_AMOUNT', message: 'Amount should be in format -99999999.99' });
    expect(await code({ origDate: '07/06/2022' })).toMatchObject({ code: 'INVALID_ORIG_DATE_FORMAT', field: 'origDate' });
    const date = await code({ amount: '+00001234.50', origDate: '2022-02-30' });
    expect(date).toMatchObject({ code: 'INVALID_ORIG_DATE', amount: '1234.50', amountDisplay: '+00001234.50' });
    expect(await code({ origDate: '1500-01-01' })).toMatchObject({ code: 'INVALID_ORIG_DATE' });
    expect(await code({ merchantId: '12a' })).toMatchObject({ code: 'MERCHANT_ID_NOT_NUMERIC', field: 'merchantId' });
  });

  it('@BR-TRNA-002 @BR-TRNA-005 add needs Y and a key, answers with the ID and the posting hint, and replays the same key and body', async () => {
    const { call } = await session();
    const noConfirm = await call('POST', '/api/transactions', { ...FORM, confirm: 'N' }, { 'Idempotency-Key': KEY });
    expect(noConfirm.body).toMatchObject({ code: 'INVALID_CONFIRM', field: 'confirm', acctId: '00000000050', merchantId: '000000123' });
    const noKey = await call('POST', '/api/transactions', { ...FORM, confirm: 'Y' });
    expect(noKey.body).toMatchObject({ code: 'IDEMPOTENCY_KEY_REQUIRED', field: null });
    const added = await call('POST', '/api/transactions', { ...FORM, confirm: 'y' }, { 'Idempotency-Key': KEY });
    expect(added.status).toBe(201);
    expect(added.body).toEqual({
      tranId: '9000000000000000',
      severity: 'success',
      field: 'acctId',
      message: 'Transaction added successfully.  Your Tran ID is 9000000000000000.',
      hint: { message: 'The transaction will be posted in the next batch cycle.', severity: 'info' },
    });
    const replay = await call('POST', '/api/transactions', { ...FORM, acctId: '00000000050', confirm: 'Y' }, { 'Idempotency-Key': KEY });
    expect(replay.status).toBe(201);
    expect(replay.headers.get('Idempotent-Replayed')).toBe('true');
    expect(replay.body.tranId).toBe('9000000000000000');
    const reused = await call('POST', '/api/transactions', { ...FORM, description: 'Tea', confirm: 'Y' }, { 'Idempotency-Key': KEY });
    expect([reused.status, reused.body.code]).toEqual([422, 'TRAN_IDEMPOTENCY_KEY_REUSED']);
    const next = await call('POST', '/api/transactions', { ...FORM, confirm: 'Y' }, { 'Idempotency-Key': OTHER_KEY });
    expect(next.body.tranId).toBe('9000000000000001');
  });

  it('@BR-TRNA-006 copy last gives the newest data fields, a waiting add included', async () => {
    const { call } = await session();
    const first = await call('GET', '/api/transactions/latest');
    expect(first.status).toBe(200);
    expect(Object.keys(first.body).sort()).toEqual(
      ['amount', 'amountDisplay', 'catCd', 'description', 'merchantCity', 'merchantId', 'merchantName', 'merchantZip', 'origDate', 'procDate', 'source', 'typeCd'].sort(),
    );
    await call('POST', '/api/transactions', { ...FORM, confirm: 'Y' }, { 'Idempotency-Key': KEY });
    const latest = await call('GET', '/api/transactions/latest');
    expect(latest.body).toMatchObject({
      typeCd: '01',
      catCd: '0001',
      source: 'POS TERM',
      description: 'Coffee',
      amount: '-12.50',
      amountDisplay: '-00000012.50',
      origDate: '2022-07-06',
      merchantId: '000000123',
      merchantName: 'Cafe',
      merchantCity: 'Seattle',
      merchantZip: '98101',
    });
  });

  it('@D-BAT-05 an add in the batch window is refused with Retry-After; a replay is still answered', async () => {
    const { call, settings } = await session();
    await call('POST', '/api/transactions', { ...FORM, confirm: 'Y' }, { 'Idempotency-Key': KEY });
    settings.batchWindowOpen = true;
    const refused = await call('POST', '/api/transactions', { ...FORM, confirm: 'Y' }, { 'Idempotency-Key': OTHER_KEY });
    expect([refused.status, refused.body.code, refused.body.field, refused.headers.get('Retry-After')]).toEqual([503, 'BATCH_WINDOW', 'acctId', '300']);
    const replay = await call('POST', '/api/transactions', { ...FORM, confirm: 'Y' }, { 'Idempotency-Key': KEY });
    expect(replay.status).toBe(201);
  });

  it('@D-02 the add is for a USER only', async () => {
    const { call } = await session('ADMIN001');
    for (const [method, path] of [
      ['POST', '/api/transactions/validate'],
      ['POST', '/api/transactions'],
      ['GET', '/api/transactions/latest'],
    ] as const) {
      const { status, body } = await call(method, path, method === 'POST' ? FORM : undefined);
      expect([status, body.code]).toEqual([403, 'ACCESS_DENIED']);
    }
  });
});
