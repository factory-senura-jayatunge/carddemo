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
  const raw = (method: string, path: string, body?: unknown) =>
    server.handle(
      new Request(`${window.location.origin}${path}`, {
        method,
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    );
  const call = async (method: string, path: string, body?: unknown) => {
    const response = await raw(method, path, body);
    return { status: response.status, headers: response.headers, body: (await response.json()) as Body };
  };
  return { call, raw, settings: live };
}

const CUSTOM = { type: 'CUSTOM', startMonth: '7', startDay: '1', startYear: '2022', endMonth: '07', endDay: '31', endYear: '2022', confirm: '' };
const PATH = '/api/reports/transactions';

describe('transaction-service mock: reports (RPTS)', () => {
  it('@VAL-RPTS-001 a type is required', async () => {
    const { call } = await session();
    const { status, body } = await call('POST', PATH, { type: null });
    expect([status, body.code, body.field, body.message]).toEqual([400, 'REPORT_TYPE_REQUIRED', 'monthly', 'Select a report type to print report...']);
  });

  it('@VAL-RPTS-002 @VAL-RPTS-003 @VAL-RPTS-004 custom: every part required first, then each part, then the dates, then the order', async () => {
    const { call } = await session();
    const code = async (parts: Body) => {
      const { body } = await call('POST', PATH, { ...CUSTOM, ...parts });
      return [body.code, body.field];
    };
    expect(await code({ startMonth: ' ', endYear: 'x' })).toEqual(['START_MONTH_REQUIRED', 'startMonth']);
    expect(await code({ endYear: '' })).toEqual(['END_YEAR_REQUIRED', 'endYear']);
    expect(await code({ startMonth: '13' })).toEqual(['START_MONTH_INVALID', 'startMonth']);
    expect(await code({ startDay: '1a' })).toEqual(['START_DAY_INVALID', 'startDay']);
    expect(await code({ startYear: '22' })).toEqual(['START_YEAR_INVALID', 'startYear']);
    expect(await code({ endDay: '32' })).toEqual(['END_DAY_INVALID', 'endDay']);
    expect(await code({ startMonth: '2', startDay: '30' })).toEqual(['START_DATE_INVALID', 'startMonth']);
    expect(await code({ endMonth: '00' })).toEqual(['END_DATE_INVALID', 'endMonth']);
    expect(await code({ startYear: '2023' })).toEqual(['DATE_RANGE_INVALID', 'startMonth']);
  });

  it('@BR-RPTS-002 @BR-RPTS-004 @BR-RPTS-005 a blank confirm asks with the resolved range; anything but Y or y is refused with its first character', async () => {
    const { call } = await session();
    const custom = await call('POST', PATH, CUSTOM);
    expect(custom.status).toBe(422);
    expect(custom.body).toMatchObject({ code: 'CONFIRM_REQUIRED', field: 'confirm', message: 'Please confirm to print the Custom report...', startDate: '2022-07-01', endDate: '2022-07-31' });
    const monthly = await call('POST', PATH, { type: 'MONTHLY', confirm: '  ' });
    expect(monthly.body.message).toBe('Please confirm to print the Monthly report...');
    expect(monthly.body.startDate).toMatch(/^\d{4}-\d{2}-01$/);
    const yearly = await call('POST', PATH, { type: 'YEARLY' });
    expect(String(yearly.body.endDate)).toMatch(/^\d{4}-12-31$/);
    const invalid = await call('POST', PATH, { ...CUSTOM, confirm: 'no' });
    expect([invalid.status, invalid.body.message]).toEqual([400, '"n" is not a valid value to confirm...']);
    const emoji = await call('POST', PATH, { ...CUSTOM, confirm: '\u{1F600}x' });
    expect(emoji.body.message).toBe('"\u{1F600}" is not a valid value to confirm...');
  });

  it('@BR-RPTS-008 Y submits; the run moves on as it is read, and its file downloads once completed', async () => {
    const { call, raw } = await session();
    const submitted = await call('POST', PATH, { ...CUSTOM, confirm: 'Y' });
    expect(submitted.status).toBe(202);
    expect(submitted.headers.get('Location')).toBe('/api/reports/transactions/1');
    expect(submitted.body).toMatchObject({ runId: 1, type: 'CUSTOM', startDate: '2022-07-01', endDate: '2022-07-31', status: 'REQUESTED', downloadable: false, requestedBy: 'USER0001', message: 'Custom report submitted for printing ...' });
    expect((await call('GET', `${PATH}/1/file`)).body.code).toBe('REPORT_NOT_READY');
    const running = await call('GET', `${PATH}/1`);
    expect(running.body).toMatchObject({ status: 'RUNNING', message: null, stalled: false });
    expect(running.body.startedAt).toEqual(expect.any(String));
    expect((await call('GET', `${PATH}/1`)).body).toMatchObject({ status: 'COMPLETED', downloadable: true });
    const file = await raw('GET', `${PATH}/1/file`);
    expect(file.status).toBe(200);
    expect(file.headers.get('Content-Disposition')).toBe('attachment; filename="TRANREPT-1.txt"');
    expect(file.headers.get('Content-Type')).toBe('text/plain; charset=US-ASCII');
    const content = await file.text();
    expect(content).toContain('Date Range: 2022-07-01 to 2022-07-31');
    expect(content).not.toMatch(/\d{16} \d{16}/);
  });

  it('a run ID must be a positive number that fits a BIGINT; an unknown one is not found', async () => {
    const { call } = await session();
    for (const id of ['abc', '0', '-1', '9223372036854775808', '99999999999999999999']) expect((await call('GET', `${PATH}/${id}`)).body.code).toBe('INVALID_RUN_ID');
    expect((await call('GET', `${PATH}/99`)).body.code).toBe('REPORT_RUN_NOT_FOUND');
  });

  it('fault report-not-started: a REQUESTED run reads as NOT_STARTED and stays REQUESTED underneath', async () => {
    const { call, settings } = await session('USER0001', { faults: ['report-not-started'] });
    await call('POST', PATH, { ...CUSTOM, confirm: 'Y' });
    expect((await call('GET', `${PATH}/1`)).body).toMatchObject({ runId: 1, status: 'NOT_STARTED', downloadable: false });
    expect((await call('GET', `${PATH}/1`)).body.status).toBe('NOT_STARTED');
    settings.faults = [];
    expect((await call('GET', `${PATH}/1`)).body.status).toBe('RUNNING');
  });

  it('faults: the trigger, the job, a stalled run and a missing file', async () => {
    const { call, settings } = await session('USER0001', { faults: ['report-trigger'] });
    const trigger = await call('POST', PATH, { ...CUSTOM, confirm: 'Y' });
    expect([trigger.status, trigger.body.code, trigger.body.field]).toEqual([503, 'REPORT_TRIGGER_FAILED', 'monthly']);
    expect((await call('GET', `${PATH}/1`)).body).toMatchObject({ status: 'FAILED', failureCode: 'TRIGGER_FAILED' });
    settings.faults = ['report-job'];
    await call('POST', PATH, { ...CUSTOM, confirm: 'Y' });
    await call('GET', `${PATH}/2`);
    expect((await call('GET', `${PATH}/2`)).body).toMatchObject({ status: 'FAILED', failureCode: 'JOB_FAILED', downloadable: false });
    settings.faults = ['report-stall'];
    await call('POST', PATH, { ...CUSTOM, confirm: 'Y' });
    await call('GET', `${PATH}/3`);
    expect((await call('GET', `${PATH}/3`)).body).toMatchObject({ status: 'RUNNING', stalled: true });
    settings.faults = ['report-file'];
    await call('POST', PATH, { ...CUSTOM, confirm: 'Y' });
    await call('GET', `${PATH}/4`);
    await call('GET', `${PATH}/4`);
    expect((await call('GET', `${PATH}/4/file`)).body.code).toBe('REPORT_FILE_MISSING');
  });

  it('@D-02 only a USER requests a report', async () => {
    const { call } = await session('ADMIN001');
    expect((await call('POST', PATH, { ...CUSTOM, confirm: 'Y' })).body.code).toBe('ACCESS_DENIED');
  });
});
