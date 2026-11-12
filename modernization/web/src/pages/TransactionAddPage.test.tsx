import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { renderApp } from '@/test/renderApp';
import { today } from '@/validation/today';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const field = (name: string) => screen.getByTestId(name);
const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;
const submit = () => screen.getByTestId('submit');

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
};
const DATA_FIELDS = ['typeCd', 'catCd', 'source', 'description', 'amount', 'origDate', 'merchantId', 'merchantName', 'merchantCity', 'merchantZip'];

function fill(values: Partial<typeof FORM>) {
  for (const [name, value] of Object.entries(values)) fireEvent.change(field(name), { target: { value } });
}

async function openAdd(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await renderApp('/transactions/new', { signedOnAs: 'USER0001', ...options });
  await screen.findByRole('heading', { name: 'Add a transaction' });
  return app;
}

/** The service's date can differ from this device's (its own zone): answers of path carry day as procDate. */
function serviceDay(app: Awaited<ReturnType<typeof openAdd>>, path: string, day: string) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = new Request(input, init);
      app.requests.push(request.clone());
      const response = await app.server.handle(request);
      if (new URL(request.url).pathname !== path || !response.ok) return response;
      const body = (await response.json()) as Record<string, unknown>;
      return new Response(JSON.stringify({ ...body, procDate: day }), { status: response.status, headers: response.headers });
    }),
  );
}

async function review(app: Awaited<ReturnType<typeof openAdd>>, values: Partial<typeof FORM> = FORM) {
  fill(values);
  await app.user.click(submit());
  await waitFor(() => expect(messageText()).toBe('Confirm to add this transaction...'));
}

describe('Transaction add page (COTRN02C)', () => {
  it('@FLOW-TRNA-002 S2: opens empty with focus on Account ID, the processing date and nothing to review', async () => {
    await openAdd();
    await waitFor(() => expect(field('acctId')).toHaveFocus());
    for (const name of Object.keys(FORM)) expect(field(name)).toHaveValue('');
    expect(screen.getByTestId('review-empty')).toBeInTheDocument();
    expect(messageText()).toBeNull();
    // S2: the processing date is shown read-only, today; before any service answer only this device's date is known.
    expect(screen.getByTestId('proc-date')).toHaveTextContent(today());
    expect(screen.getByTestId('proc-date')).toHaveAttribute('data-source', 'device');
    expect(screen.getByTestId('proc-date-note')).toHaveTextContent(`Processing date ${today()} on this device. The service sets the processing date when it checks the transaction.`);
    expect(screen.queryByRole('textbox', { name: /processing date/i })).toBeNull();
  });

  it('@BR-TRNA-001 @BR-TRNA-002 Review sends the values as typed, shows the padded account and the masked card, and asks to confirm', async () => {
    const app = await openAdd();
    serviceDay(app, '/api/transactions/validate', '2031-02-03');
    await review(app);
    const [call] = app.apiCalls('/api/transactions/validate');
    expect(await call?.json()).toEqual(FORM);
    const panel = screen.getByTestId('review-panel');
    expect(within(panel).getByTestId('review-acct')).toHaveTextContent('00000000050');
    expect(within(panel).getByTestId('review-card')).toHaveTextContent(/^\*{12}5740$/);
    expect(within(panel).getByTestId('review-amount')).toHaveTextContent('-00000012.50');
    expect(within(panel).getByTestId('review-codes')).toHaveTextContent('01 / 0001');
    expect(within(panel).getByTestId('review-merchant')).toHaveTextContent('000000123');
    expect(within(panel).getByTestId('review-proc')).toHaveTextContent('2031-02-03');
    expect(screen.getByTestId('proc-date-note')).toHaveTextContent('Processing date 2031-02-03, from the service.');
    expect(screen.getByTestId('proc-date')).toHaveAttribute('data-source', 'service');
    expect(screen.getByTestId('resolved')).toHaveTextContent(/Account 00000000050, card \*{12}5740/);
    expect(field('acctId')).toHaveValue('50');
    expect(screen.getByRole('radio', { name: 'Yes' })).toHaveFocus();
    expect(app.apiCalls('/api/transactions').filter((r) => new URL(r.url).pathname === '/api/transactions')).toHaveLength(0);
    // An edit drops the review, not the date the service gave.
    fill({ description: 'Tea' });
    await waitFor(() => expect(screen.getByTestId('review-empty')).toBeInTheDocument());
    expect(screen.getByTestId('proc-date')).toHaveTextContent('2031-02-03');
  });

  it('@BR-TRNA-002 @DEF-TRNA-014 S20: No and Enter clear every field and the message, focus Account ID, and make no call', async () => {
    const app = await openAdd();
    await review(app);
    await app.user.click(screen.getByRole('radio', { name: 'No' }));
    await app.user.click(submit());
    await waitFor(() => expect(field('acctId')).toHaveFocus());
    for (const name of Object.keys(FORM)) expect(field(name)).toHaveValue('');
    expect(screen.getByRole('radio', { name: 'No' })).not.toBeChecked();
    expect(messageText()).toBeNull();
    expect(screen.getByTestId('review-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('resolved')).toBeNull();
    expect(app.apiCalls('/api/transactions/validate')).toHaveLength(1);
    expect(app.requests.filter((r) => r.method === 'POST' && new URL(r.url).pathname === '/api/transactions')).toHaveLength(0);
  });

  it('@FLOW-TRNA-002 a cleared form and a finished add show this device\'s date again, until the service answers', async () => {
    const app = await openAdd();
    serviceDay(app, '/api/transactions/validate', '2031-02-03');
    const procDate = () => screen.getByTestId('proc-date');
    await review(app);
    expect(procDate()).toHaveAttribute('data-source', 'service');
    await app.user.click(screen.getByRole('radio', { name: 'No' }));
    await app.user.click(submit());
    await waitFor(() => expect(procDate()).toHaveAttribute('data-source', 'device'));
    expect(procDate()).toHaveTextContent(today());
    await review(app);
    expect(procDate()).toHaveTextContent('2031-02-03');
    await app.user.click(screen.getByRole('radio', { name: 'Yes' }));
    await app.user.click(submit());
    await waitFor(() => expect(messageText()).toMatch(/^Transaction added successfully/));
    expect(procDate()).toHaveAttribute('data-source', 'device');
    expect(procDate()).toHaveTextContent(today());
  });

  it('@BR-TRNA-002 @BR-TRNA-005 Yes adds with an Idempotency-Key, shows the new ID and the posting hint, and clears the form', async () => {
    const app = await openAdd();
    await review(app);
    await app.user.keyboard('y');
    expect(screen.getByRole('radio', { name: 'Yes' })).toBeChecked();
    expect(submit()).toHaveTextContent('Add transaction');
    await app.user.keyboard('{Enter}');
    await waitFor(() => expect(messageText()).toBe('Transaction added successfully.  Your Tran ID is 9000000000000000.'));
    expect(screen.getByTestId('message')).toHaveAttribute('data-tone', 'success');
    expect(screen.getByTestId('add-hint')).toHaveTextContent('The transaction will be posted in the next batch cycle.');
    const [add] = app.requests.filter((r) => r.method === 'POST' && new URL(r.url).pathname === '/api/transactions');
    expect(add?.headers.get('Idempotency-Key')).toMatch(UUID);
    expect(add?.headers.get('X-XSRF-TOKEN')).toBeTruthy();
    expect(await add?.json()).toEqual({ ...FORM, confirm: 'Y' });
    for (const name of Object.keys(FORM)) expect(field(name)).toHaveValue('');
    expect(screen.getByRole('radio', { name: 'Yes' })).not.toBeChecked();
    expect(screen.getByTestId('review-empty')).toBeInTheDocument();
    await waitFor(() => expect(field('acctId')).toHaveFocus());
  });

  it('@BR-TRNA-002 Yes typed with the data adds on the first Enter', async () => {
    const app = await openAdd();
    fill(FORM);
    await app.user.click(screen.getByRole('radio', { name: 'Yes' }));
    await app.user.click(submit());
    await waitFor(() => expect(messageText()).toMatch(/^Transaction added successfully\. {2}Your Tran ID is 9000000000000000\.$/));
    expect(app.apiCalls('/api/transactions/validate')).toHaveLength(0);
  });

  it('@D-BAT-05 in the batch window the add waits with Try again; the retry keeps its key, an edit gets a new one', async () => {
    const app = await openAdd({ settings: { batchWindowOpen: true } });
    fill(FORM);
    await app.user.click(screen.getByRole('radio', { name: 'Yes' }));
    await app.user.click(submit());
    await waitFor(() => expect(messageText()).toBe('Transaction add is not available during the batch cycle. Try again later...'));
    expect(screen.getByTestId('message')).toHaveAttribute('data-tone', 'notice');
    expect(screen.getByText(/^Try again after \d{2}:\d{2}\.$/)).toBeInTheDocument();
    await app.user.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(app.requests.filter((r) => r.method === 'POST' && new URL(r.url).pathname === '/api/transactions')).toHaveLength(2));
    fill({ description: 'Tea' });
    app.settings.batchWindowOpen = false;
    await app.user.click(submit());
    await waitFor(() => expect(messageText()).toMatch(/^Transaction added successfully/));
    const keys = app.requests.filter((r) => r.method === 'POST' && new URL(r.url).pathname === '/api/transactions').map((r) => r.headers.get('Idempotency-Key'));
    expect(keys).toHaveLength(3);
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).not.toBe(keys[0]);
  });

  it('@VAL-TRNA-001 S3: Enter with neither account nor card shows the message on Account ID and makes no call', async () => {
    const app = await openAdd();
    fill({ ...FORM, acctId: '', cardNum: '' });
    for (const choice of [null, 'Yes', 'No']) {
      if (choice) await app.user.click(screen.getByRole('radio', { name: choice }));
      fill({ acctId: '   ', cardNum: '' });
      await app.user.click(submit());
      await waitFor(() => expect(messageText()).toBe('Account or Card Number must be entered...'));
      expect(field('acctId')).toHaveAttribute('aria-invalid', 'true');
      await waitFor(() => expect(field('acctId')).toHaveFocus());
      expect(field('description')).toHaveValue('Coffee');
    }
    expect(screen.queryByTestId('resolved')).toBeNull();
    expect(app.requests.filter((r) => new URL(r.url).pathname.startsWith('/api/transactions'))).toHaveLength(0);
  });

  it('@VAL-TRNA-006 a missing data field gets the focus, and the account and card found so far are shown', async () => {
    const app = await openAdd();
    fill({ ...FORM, merchantZip: '' });
    await app.user.click(submit());
    await waitFor(() => expect(messageText()).toBe('Merchant Zip can NOT be empty...'));
    await waitFor(() => expect(field('merchantZip')).toHaveFocus());
    expect(field('merchantZip')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('resolved')).toHaveTextContent(/Account 00000000050, card \*{12}5740/);
    expect(screen.queryByTestId('review-panel')).toBeNull();
  });

  it('@DEF-TRNA-004 an account with two cards lists them masked and takes the typed card', async () => {
    const app = await openAdd();
    fill({ ...FORM, acctId: '96' });
    await app.user.click(submit());
    await waitFor(() => expect(messageText()).toBe('Account has more than one card. Enter the Card Number...'));
    const choice = screen.getByTestId('card-choice');
    expect(within(choice).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['************0961', '************0962']);
    await waitFor(() => expect(field('cardNum')).toHaveFocus());
    fill({ cardNum: '9999000000000962' });
    expect(screen.queryByTestId('card-choice')).toBeNull();
    await app.user.click(submit());
    await waitFor(() => expect(screen.getByTestId('review-card')).toHaveTextContent(/^\*{12}0962$/));
  });

  it('@VAL-TRNA-006 no field is capped on the page: a long description goes as typed and the service answers', async () => {
    const app = await openAdd();
    for (const name of Object.keys(FORM)) expect(field(name)).not.toHaveAttribute('maxlength');
    const long = `${'x'.repeat(60)}\u{1F600}`;
    fill({ ...FORM, description: 'D'.repeat(61) });
    await app.user.click(submit());
    await waitFor(() => expect(messageText()).toBe('Description must not be longer than 60 characters.'));
    await waitFor(() => expect(field('description')).toHaveFocus());
    fill({ description: long });
    await app.user.click(submit());
    await waitFor(() => expect(messageText()).toBe('Description contains a character that is not supported.'));
    const calls = app.apiCalls('/api/transactions/validate');
    expect(((await calls.at(-1)?.json()) as { description: string }).description).toBe(long);
  });

  it('@BR-TRNA-006 @FLOW-TRNA-006 F5 copies the last transaction into the data fields, keeps the keys and asks to confirm', async () => {
    const app = await openAdd({ functionKeys: true });
    fill({ acctId: '50' });
    await app.user.keyboard('{F5}');
    await waitFor(() => expect(messageText()).toBe('Confirm to add this transaction...'));
    expect(field('acctId')).toHaveValue('50');
    expect(field('cardNum')).toHaveValue('');
    expect(field('description')).toHaveValue('Purchase at Kilback LLC');
    expect(field('amount')).toHaveValue('+00000603.22');
    expect(field('origDate')).toHaveValue('2022-06-10');
    expect(field('merchantZip')).toHaveValue('53200-7529');
    expect(app.apiCalls('/api/transactions/latest')).toHaveLength(1);
    expect(screen.getByTestId('review-amount')).toHaveTextContent('+00000603.22');
  });

  it('@BR-TRNA-006 @VAL-TRNA-001 S26: F5 checks the keys first: with none, nothing is copied and no call is made', async () => {
    const app = await openAdd({ functionKeys: true });
    await app.user.keyboard('{F5}');
    await waitFor(() => expect(messageText()).toBe('Account or Card Number must be entered...'));
    await waitFor(() => expect(field('acctId')).toHaveFocus());
    for (const name of DATA_FIELDS) expect(field(name)).toHaveValue('');
    expect(app.requests.filter((r) => new URL(r.url).pathname.startsWith('/api/transactions'))).toHaveLength(0);
  });

  it('@BR-TRNA-006 Copy last with Yes chosen adds the copy at once', async () => {
    const app = await openAdd();
    fill({ acctId: '50' });
    await app.user.click(screen.getByRole('radio', { name: 'Yes' }));
    await app.user.click(screen.getByTestId('copy-last'));
    await waitFor(() => expect(messageText()).toMatch(/^Transaction added successfully/));
    const [add] = app.requests.filter((r) => r.method === 'POST' && new URL(r.url).pathname === '/api/transactions');
    expect(await add?.json()).toMatchObject({ acctId: '50', description: 'Purchase at Kilback LLC', amount: '+00000603.22', confirm: 'Y' });
  });

  it('@FLOW-TRNA-005 F4 clears every field, the confirm choice and the message', async () => {
    const app = await openAdd({ functionKeys: true });
    await review(app);
    await app.user.click(screen.getByRole('radio', { name: 'Yes' }));
    await app.user.keyboard('{F4}');
    for (const name of Object.keys(FORM)) expect(field(name)).toHaveValue('');
    expect(screen.getByRole('radio', { name: 'Yes' })).not.toBeChecked();
    expect(messageText()).toBeNull();
    expect(screen.queryByTestId('resolved')).toBeNull();
    await waitFor(() => expect(field('acctId')).toHaveFocus());
  });

  it('@FLOW-TRNA-004 F3 goes back to the main menu', async () => {
    const app = await openAdd({ functionKeys: true });
    await app.user.keyboard('{F3}');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
  });

  it('@VAL-TRNA-013 @FLOW-TRNA-007 other function keys give the invalid-key text, keep the input and focus Account ID', async () => {
    const app = await openAdd({ functionKeys: true });
    fill({ acctId: '50', description: 'Coffee' });
    for (const key of ['{F7}', '{F8}', '{F12}']) {
      field('description').focus();
      await app.user.keyboard(key);
      expect(messageText()).toBe('Invalid key pressed. Please see below...');
      await waitFor(() => expect(field('acctId')).toHaveFocus());
    }
    expect(field('acctId')).toHaveValue('50');
    expect(field('description')).toHaveValue('Coffee');
    expect(app.apiCalls('/api/transactions')).toHaveLength(0);
  });

  it('@D-02 an ADMIN is sent to the admin menu', async () => {
    const app = await renderApp('/transactions/new', { signedOnAs: 'ADMIN001' });
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/admin'));
  });
});
