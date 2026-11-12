import { act, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderApp } from '@/test/renderApp';
import { today } from '@/validation/today';

const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;
const runMessageText = () => document.querySelector('#report-run-message [data-testid="message-text"]')?.textContent ?? null;
const pageMessageText = () => document.querySelector('#report-message [data-testid="message-text"]')?.textContent ?? null;
const submit = () => screen.getByTestId('submit');
const radio = (name: string) => screen.getByRole('radio', { name: new RegExp(`^${name}`) });
const PATH = '/api/reports/transactions';

function thisMonth() {
  const [y, m] = today().split('-').map(Number) as [number, number];
  const last = new Date(y, m, 0).getDate();
  const mm = String(m).padStart(2, '0');
  return { start: `${y}-${mm}-01`, end: `${y}-${mm}-${last}` };
}

async function openReports(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await renderApp('/reports/transactions', { signedOnAs: 'USER0001', ...options });
  await screen.findByRole('heading', { name: 'Transaction report' });
  return app;
}

type App = Awaited<ReturnType<typeof openReports>>;
const posts = (app: App) => app.requests.filter((r) => r.method === 'POST' && new URL(r.url).pathname === PATH);
const statusReads = (app: App) => app.requests.filter((r) => /^\/api\/reports\/transactions\/\d+$/.test(new URL(r.url).pathname));

async function requestMonthly(app: App) {
  await app.user.click(radio('Monthly'));
  await app.user.click(radio('Yes'));
  await app.user.click(submit());
  await waitFor(() => expect(pageMessageText()).toBe('Monthly report submitted for printing ...'));
}

const tick = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms));

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('Transaction report page (CORPT00C)', () => {
  it('@FLOW-RPTS-002 opens with no period chosen and focus on Monthly', async () => {
    await openReports();
    for (const name of ['Monthly', 'Yearly', 'Custom', 'Yes', 'No']) expect(radio(name)).not.toBeChecked();
    await waitFor(() => expect(radio('Monthly')).toHaveFocus());
    expect(screen.queryByTestId('custom-dates')).toBeNull();
    expect(screen.getByTestId('run-empty')).toBeInTheDocument();
  });

  it('@VAL-RPTS-001 Continue with no period asks for one, on Monthly', async () => {
    const app = await openReports();
    await app.user.click(submit());
    await waitFor(() => expect(messageText()).toBe('Select a report type to print report...'));
    expect(await posts(app)[0]?.json()).toEqual({ type: null, confirm: '' });
    await waitFor(() => expect(radio('Monthly')).toHaveFocus());
  });

  it('@BR-RPTS-002 @BR-RPTS-005 Monthly asks to confirm and shows the month it covers', async () => {
    const app = await openReports();
    await app.user.click(radio('Monthly'));
    await app.user.click(submit());
    await waitFor(() => expect(messageText()).toBe('Please confirm to print the Monthly report...'));
    const { start, end } = thisMonth();
    expect(screen.getByTestId('report-range')).toHaveTextContent(`It covers the transactions processed from ${start} to ${end}.`);
    await waitFor(() => expect(radio('Yes')).toHaveFocus());
    expect(statusReads(app)).toHaveLength(0);
  });

  it('@BR-RPTS-003 Yearly covers the current year', async () => {
    const app = await openReports();
    await app.user.click(radio('Yearly'));
    await app.user.click(submit());
    const year = today().slice(0, 4);
    await waitFor(() => expect(screen.getByTestId('report-range')).toHaveTextContent(`from ${year}-01-01 to ${year}-12-31`));
    expect(messageText()).toBe('Please confirm to print the Yearly report...');
  });

  it('@BR-RPTS-004 @VAL-RPTS-002 Custom sends the six parts as typed; a missing part gets the focus', async () => {
    const app = await openReports();
    await app.user.click(radio('Custom'));
    const parts = { startMonth: '7', startDay: '1', startYear: '2022', endMonth: '07', endDay: '', endYear: '2022' };
    for (const [name, value] of Object.entries(parts)) if (value) await app.user.type(screen.getByTestId(name), value);
    await app.user.click(submit());
    await waitFor(() => expect(messageText()).toBe('End Date - Day can NOT be empty...'));
    await waitFor(() => expect(screen.getByTestId('endDay')).toHaveFocus());
    expect(screen.getByTestId('endDay')).toHaveAttribute('aria-invalid', 'true');
    expect(await posts(app)[0]?.json()).toEqual({ type: 'CUSTOM', ...parts, confirm: '' });
    await app.user.type(screen.getByTestId('endDay'), '31');
    await app.user.click(submit());
    await waitFor(() => expect(screen.getByTestId('report-range')).toHaveTextContent('from 2022-07-01 to 2022-07-31'));
    expect(messageText()).toBe('Please confirm to print the Custom report...');
  });

  it('@VAL-RPTS-004 an impossible custom date is reported on the start month', async () => {
    const app = await openReports();
    await app.user.click(radio('Custom'));
    const parts = { startMonth: '02', startDay: '30', startYear: '2022', endMonth: '03', endDay: '01', endYear: '2022' };
    for (const [name, value] of Object.entries(parts)) await app.user.type(screen.getByTestId(name), value);
    await app.user.click(submit());
    await waitFor(() => expect(messageText()).toBe('Start Date - Not a valid date...'));
    await waitFor(() => expect(screen.getByTestId('startMonth')).toHaveFocus());
  });

  it('@FLOW-RPTS-008 @BR-RPTS-005 No clears the period, the dates and the message, with focus on Monthly; nothing is requested', async () => {
    const app = await openReports();
    await app.user.click(radio('Custom'));
    for (const [name, value] of Object.entries({ startMonth: '07', startDay: '01', startYear: '2022', endMonth: '07', endDay: '31', endYear: '2022' })) {
      await app.user.type(screen.getByTestId(name), value);
    }
    await app.user.click(submit());
    await waitFor(() => expect(messageText()).toBe('Please confirm to print the Custom report...'));
    await app.user.click(radio('No'));
    expect(submit()).toHaveTextContent('Start over');
    await app.user.click(submit());
    await waitFor(() => expect(radio('Custom')).not.toBeChecked());
    expect(screen.queryByTestId('custom-dates')).toBeNull();
    expect(radio('No')).not.toBeChecked();
    expect(messageText()).toBeNull();
    await waitFor(() => expect(radio('Monthly')).toHaveFocus());
    expect(posts(app).map((r) => r.headers.get('content-type'))).toHaveLength(2);
    expect(await posts(app)[1]?.json()).toMatchObject({ type: 'CUSTOM', confirm: '' });
    expect(screen.getByTestId('run-empty')).toBeInTheDocument();
  });

  it('@BR-RPTS-005 No still runs the checks first: an invalid date is shown, not cleared', async () => {
    const app = await openReports();
    await app.user.click(radio('Custom'));
    await app.user.type(screen.getByTestId('startMonth'), '13');
    await app.user.click(radio('No'));
    await app.user.click(submit());
    await waitFor(() => expect(messageText()).toBe('Start Date - Day can NOT be empty...'));
    expect(screen.getByTestId('startMonth')).toHaveValue('13');
  });

  it('@BR-RPTS-008 @DEF-RPTS-006 Yes requests the run, clears the form and follows the run until the file can be downloaded', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    const app = await openReports();
    await requestMonthly(app);
    expect(screen.getByTestId('message')).toHaveAttribute('data-tone', 'success');
    expect(radio('Monthly')).not.toBeChecked();
    expect(radio('Yes')).not.toBeChecked();
    await waitFor(() => expect(radio('Monthly')).toHaveFocus());
    const { start, end } = thisMonth();
    expect(screen.getByTestId('run-id')).toHaveTextContent('1');
    expect(screen.getByTestId('run-status')).toHaveTextContent('Requested');
    expect(screen.getByTestId('run-period')).toHaveTextContent(`${start} to ${end}`);
    expect(screen.getByTestId('run-status-text')).toHaveAttribute('aria-live', 'polite');
    expect(screen.queryByTestId('download')).toBeNull();

    // Every 5 seconds while REQUESTED or RUNNING; nothing after COMPLETED. The checks before
    // each read leave a margin because shouldAdvanceTime also moves the clock while waitFor waits.
    await tick(4000);
    expect(statusReads(app)).toHaveLength(0);
    await tick(1000);
    await waitFor(() => expect(screen.getByTestId('run-status')).toHaveTextContent('Running'));
    expect(statusReads(app)).toHaveLength(1);
    await tick(4000);
    expect(statusReads(app)).toHaveLength(1);
    await tick(1000);
    await waitFor(() => expect(screen.getByTestId('run-status')).toHaveTextContent('Ready'));
    expect(statusReads(app)).toHaveLength(2);
    await tick(30_000);
    expect(statusReads(app)).toHaveLength(2);

    const blobs: Blob[] = [];
    const names: string[] = [];
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn((blob: Blob) => (blobs.push(blob), 'blob:report')), revokeObjectURL: vi.fn() }));
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      names.push(this.download);
    });
    await app.user.click(screen.getByTestId('download'));
    await waitFor(() => expect(names).toEqual(['TRANREPT-1.txt']));
    expect(app.apiCalls('/api/reports/transactions/1/file')).toHaveLength(1);
    expect(await blobs[0]?.text()).toContain(`Date Range: ${start} to ${end}`);
  });

  it('@DEF-RPTS-006 a stalled run shows the notice and is still read every 5 seconds, since it is still RUNNING', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    const app = await openReports({ settings: { faults: ['report-stall'] } });
    await requestMonthly(app);
    await tick(5000);
    await waitFor(() => expect(screen.getByTestId('run-status')).toHaveTextContent('Running'));
    await tick(5000);
    await waitFor(() => expect(screen.getByTestId('run-status')).toHaveTextContent('Stalled'));
    expect(runMessageText()).toBe('The report run has stopped responding. Contact operations...');
    expect(screen.getByTestId('run-status-text')).toHaveTextContent('The report job stopped responding.');
    const reads = statusReads(app).length;
    await tick(5000);
    await waitFor(() => expect(statusReads(app)).toHaveLength(reads + 1));
    await tick(5000);
    await waitFor(() => expect(statusReads(app)).toHaveLength(reads + 2));
    expect(screen.queryByTestId('download')).toBeNull();
  });

  it('@DEF-RPTS-006 a run that did not start is shown as such and no longer read', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    const app = await openReports({ settings: { faults: ['report-not-started'] } });
    await requestMonthly(app);
    await tick(5000);
    await waitFor(() => expect(screen.getByTestId('run-status')).toHaveTextContent('Not started'));
    expect(screen.getByTestId('run-status-text')).toHaveTextContent('The report job has not started in the expected time. It may still start later; this page no longer checks it.');
    expect(screen.queryByRole('list', { name: 'Progress' })).toBeNull();
    expect(screen.queryByTestId('download')).toBeNull();
    await tick(60_000);
    expect(statusReads(app)).toHaveLength(1);
  });

  it('@DEF-RPTS-006 a failed job shows its reason and no download', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    const app = await openReports({ settings: { faults: ['report-job'] } });
    await requestMonthly(app);
    await tick(5000);
    await tick(5000);
    await waitFor(() => expect(screen.getByTestId('run-status')).toHaveTextContent('Failed'));
    expect(screen.getByTestId('run-status-text')).toHaveTextContent('The report did not finish. Reason: JOB_FAILED.');
    expect(screen.queryByTestId('download')).toBeNull();
  });

  it('@ERR-RPTS-001 a trigger failure is shown on Monthly and no run is followed', async () => {
    const app = await openReports({ settings: { faults: ['report-trigger'] } });
    await app.user.click(radio('Monthly'));
    await app.user.click(radio('Yes'));
    await app.user.click(submit());
    await waitFor(() => expect(messageText()).toBe('Unable to start the report. Try again later...'));
    await waitFor(() => expect(radio('Monthly')).toHaveFocus());
    expect(screen.getByTestId('run-empty')).toBeInTheDocument();
  });

  it('a missing report file is reported in the run panel', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    const app = await openReports({ settings: { faults: ['report-file'] } });
    await requestMonthly(app);
    await tick(5000);
    await tick(5000);
    await waitFor(() => expect(screen.getByTestId('download')).toBeInTheDocument());
    await app.user.click(screen.getByTestId('download'));
    await waitFor(() => expect(runMessageText()).toBe('The report file is not available...'));
  });

  it('leaving the page stops the polling', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    const app = await openReports();
    await requestMonthly(app);
    await app.user.click(screen.getByTestId('back'));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
    await tick(30_000);
    expect(statusReads(app)).toHaveLength(0);
  });

  it('@VAL-RPTS-006 @FLOW-RPTS-005 F4, F5 and F7 give the invalid-key text on Monthly and keep the input', async () => {
    const app = await openReports({ functionKeys: true });
    await app.user.click(radio('Yearly'));
    for (const key of ['{F4}', '{F5}', '{F7}']) {
      await app.user.keyboard(key);
      expect(messageText()).toBe('Invalid key pressed. Please see below...');
    }
    expect(radio('Yearly')).toBeChecked();
    await waitFor(() => expect(radio('Monthly')).toHaveFocus());
    expect(posts(app)).toHaveLength(0);
  });

  it('@FLOW-RPTS-004 F3 goes back to the main menu', async () => {
    const app = await openReports({ functionKeys: true });
    await app.user.keyboard('{F3}');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
  });

  it('@D-02 an ADMIN is sent to the admin menu', async () => {
    const app = await renderApp('/reports/transactions', { signedOnAs: 'ADMIN001' });
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/admin'));
  });
});
