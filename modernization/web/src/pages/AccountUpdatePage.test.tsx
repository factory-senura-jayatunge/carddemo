import { fireEvent, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { readCurrentAccount } from '@/session/currentAccount';
import { renderApp } from '@/test/renderApp';

const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;
const info = () => screen.getByTestId('info-line').textContent;
const input = (field: string) => screen.getByTestId(field);
const acct = () => input('acct-id');

type App = Awaited<ReturnType<typeof renderApp>>;

async function openPage(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await renderApp('/accounts/edit', { signedOnAs: 'USER0001', ...options });
  await screen.findByRole('heading', { name: 'Update account' });
  return app;
}

/** ACUP-S4: the details of account 00000000001. */
async function openAccount(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await openPage(options);
  await app.user.type(acct(), '00000000001{Enter}');
  await screen.findByTestId('activeStatus');
  return app;
}

async function replace(app: App, field: string, value: string) {
  await app.user.clear(input(field));
  if (value) await app.user.type(input(field), value);
}

const checkChanges = async (app: App) => app.user.click(screen.getByRole('button', { name: 'Check changes' }));
const sentBodies = async (app: App, path: string, method: string) =>
  Promise.all(app.requests.filter((r) => new URL(r.url).pathname === path && r.method === method).map((r) => r.clone().json() as Promise<Record<string, unknown>>));

/** A Bill Pay payment from another tab: it changes the balance, so the account row's version moves. */
async function payFromElsewhere(app: App, acctId: string) {
  const csrf = /XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1] ?? '';
  const response = await app.server.handle(
    new Request(`${window.location.origin}/api/billpay/accounts/${acctId}/payments`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf, 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ confirm: 'Y' }),
    }),
  );
  expect(response.status).toBe(201);
}

describe('Account update (COACTUPC)', () => {
  it('ACUP-S1: without a session it goes to sign-on', async () => {
    const { router } = await renderApp('/accounts/edit');
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-on'));
  });

  it('ACUP-S2: main menu option 2 opens an empty search with the prompt and focus on the account number', async () => {
    const { user, router } = await renderApp('/', { signedOnAs: 'USER0001' });
    await screen.findByRole('heading', { name: 'Main menu' });
    await user.type(screen.getByLabelText('Option number'), '2{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/accounts/edit'));
    await screen.findByRole('heading', { name: 'Update account' });
    expect(acct()).toHaveValue('');
    expect(info()).toBe('Enter or update id of account to update');
    expect(messageText()).toBeNull();
    await waitFor(() => expect(acct()).toHaveFocus());
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CAUP · COACTUPC');
  });

  it('ACUP-S3 ACUP-S21: a blank or short account number is refused in the page, with no call', async () => {
    const app = await openPage();
    await app.user.type(acct(), '{Enter}');
    expect(messageText()).toBe('Account number not provided');
    expect(screen.getByTestId('acct-id-marker')).toHaveTextContent('*');
    expect(acct()).toHaveAttribute('aria-invalid', 'true');
    await app.user.type(acct(), '123{Enter}');
    expect(messageText()).toBe('Account Number if supplied must be a 11 digit Non-Zero Number');
    expect(screen.queryByTestId('acct-id-marker')).toBeNull();
    expect(acct()).toHaveAttribute('aria-invalid', 'true');
    expect(app.apiCalls('/api/accounts')).toHaveLength(0);
  });

  it('ACUP-S5: an unknown account stays in Search with the account number red', async () => {
    // 00000000099 is a Bill Pay fixture in the mock, so an unused number stands in for the contract's.
    const app = await openPage();
    await app.user.type(acct(), '00000000077{Enter}');
    await waitFor(() => expect(messageText()).toBe('Account 00000000077 not found.'));
    expect(acct()).toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByTestId('activeStatus')).toBeNull();
    expect(readCurrentAccount()).toBeNull();
  });

  it('ACUP-S4: shows the account, the customer with masked write-only values, and sets the current account', async () => {
    await openAccount();
    expect(input('activeStatus')).toHaveValue('Y');
    expect([input('openYear'), input('openMonth'), input('openDay')].map((e) => (e as HTMLInputElement).value)).toEqual(['2014', '11', '20']);
    expect(input('creditLimit')).toHaveValue('2020.00');
    expect(input('cashCreditLimit')).toHaveValue('1020.00');
    expect(screen.getByTestId('currentBalance')).toHaveTextContent('194.00');
    expect(screen.getByTestId('custId')).toHaveTextContent('000000001');
    expect(screen.getByTestId('ssn-masked')).toHaveTextContent('***-**-3888');
    expect(screen.getByTestId('dob-masked')).toHaveTextContent('****-06-08');
    expect(screen.getByTestId('govtIssuedId-masked')).toHaveTextContent('****************8437');
    expect(screen.getByTestId('eftAccountId-masked')).toHaveTextContent('******1756');
    expect(screen.queryByTestId('ssn1')).toBeNull();
    expect(screen.queryByRole('button', { name: /^Show/ })).toBeNull();
    expect(input('ficoScore')).toHaveValue('274');
    expect(input('city')).toHaveValue('Altenwerthshire');
    expect(input('stateCd')).toHaveValue('NC');
    expect(input('zip5')).toHaveValue('12546');
    expect(screen.getByTestId('countryCd')).toHaveTextContent('USA');
    expect([input('phone1Area'), input('phone1Prefix'), input('phone1Line')].map((e) => (e as HTMLInputElement).value)).toEqual(['908', '119', '8310']);
    expect([input('phone2Area'), input('phone2Prefix'), input('phone2Line')].map((e) => (e as HTMLInputElement).value)).toEqual(['373', '693', '8684']);
    expect(info()).toBe('Update account details presented above.');
    expect(acct()).toHaveAttribute('readonly');
    expect(readCurrentAccount()).toMatchObject({ acctId: '00000000001', custId: '000000001' });
    expect(readCurrentAccount()?.cardId).toBeTruthy();
  });

  it('ACUP-S6: Enter without changes says so, focuses Account Status and calls nothing', async () => {
    const app = await openAccount();
    await checkChanges(app);
    expect(messageText()).toBe('No change detected with respect to values fetched.');
    await waitFor(() => expect(input('activeStatus')).toHaveFocus());
    expect(app.apiCalls('/api/accounts/00000000001/validate')).toHaveLength(0);
  });

  it('ACUP-S7: one changed field goes to Confirm, and only it is sent', async () => {
    const app = await openAccount();
    await replace(app, 'creditLimit', '2500.00');
    await checkChanges(app);
    await screen.findByRole('button', { name: /^Save/ });
    expect(info()).toBe('Changes validated.Press F5 to save');
    expect(input('creditLimit')).toHaveAttribute('readonly');
    const [body] = await sentBodies(app, '/api/accounts/00000000001/validate', 'POST');
    expect(body?.changes).toEqual({ creditLimit: '2500.00' });
  });

  it('ACUP-S8 to ACUP-S11: Confirm, Enter again, Save, then Enter gives an empty search', async () => {
    const app = await openAccount();
    await replace(app, 'ficoScore', '700');
    await replace(app, 'phone2Area', '908');
    await replace(app, 'zip5', '27601');
    await checkChanges(app);
    const save = await screen.findByRole('button', { name: /^Save/ });
    // S9: Enter in Confirm shows Confirm again, with no call.
    fireEvent.submit(acct().closest('form') as HTMLFormElement);
    expect(info()).toBe('Changes validated.Press F5 to save');
    expect(app.apiCalls('/api/accounts/00000000001/validate')).toHaveLength(1);
    await app.user.click(save);
    await waitFor(() => expect(info()).toBe('Changes committed to database'));
    const [put] = await sentBodies(app, '/api/accounts/00000000001', 'PUT');
    expect(put?.changes).toEqual({ ficoScore: '700', zip5: '27601', zipPlus4: '', phone2Area: '908', phone2Prefix: '693', phone2Line: '8684' });
    const ifMatch = app.requests.find((r) => r.method === 'PUT')?.headers.get('If-Match');
    expect(ifMatch).toMatch(/^"[0-9a-f]{16}\.[0-9a-f]{16}"$/);
    expect(input('ficoScore')).toHaveValue('700');
    expect(input('zip5')).toHaveValue('27601');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Update another account' })).toHaveFocus());
    // S11
    await app.user.keyboard('{Enter}');
    await waitFor(() => expect(info()).toBe('Enter or update id of account to update'));
    expect(acct()).toHaveValue('');
    expect(screen.queryByTestId('activeStatus')).toBeNull();
  });

  it('ACUP-S12: Cancel reads the account again and drops the edits', async () => {
    const app = await openAccount();
    await replace(app, 'ficoScore', '700');
    await checkChanges(app);
    await screen.findByRole('button', { name: /^Save/ });
    await app.user.click(screen.getByRole('button', { name: /^Cancel/ }));
    await waitFor(() => expect(input('ficoScore')).toHaveValue('274'));
    expect(info()).toBe('Update account details presented above.');
    expect(messageText()).toBeNull();
    expect(app.apiCalls('/api/accounts/00000000001/update-form')).toHaveLength(2);
    expect(app.requests.filter((r) => r.method === 'PUT')).toHaveLength(0);
  });

  it('ACUP-S13: a payment since the fetch gives Record changed; the edit stays and the new balance is marked', async () => {
    const app = await openAccount();
    await replace(app, 'creditLimit', '2500.00');
    await checkChanges(app);
    const save = await screen.findByRole('button', { name: /^Save/ });
    await payFromElsewhere(app, '00000000001');
    await app.user.click(save);
    await waitFor(() => expect(messageText()).toBe('Record changed by some one else. Please review'));
    expect(screen.getByTestId('message')).toHaveAttribute('data-tone', 'error');
    expect(input('creditLimit')).toHaveValue('2500.00');
    expect(screen.getByTestId('currentBalance-moved')).toBeInTheDocument();
    expect(screen.getByTestId('currentBalance')).toHaveTextContent('.00');
    await checkChanges(app);
    await app.user.click(await screen.findByRole('button', { name: /^Save/ }));
    await waitFor(() => expect(info()).toBe('Changes committed to database'));
    expect(input('creditLimit')).toHaveValue('2500.00');
  });

  it.each([
    ['ACUP-S14', 'account-lock', 'Could not lock account record for update'],
    ['ACUP-S15', 'customer-lock', 'Could not lock customer record for update'],
  ])('%s: a %s failure is a failed save; Enter then starts a new search', async (_id, fault, text) => {
    const app = await openAccount();
    await replace(app, 'creditLimit', '2500.00');
    await checkChanges(app);
    const save = await screen.findByRole('button', { name: /^Save/ });
    app.settings.faults = [fault];
    await app.user.click(save);
    await waitFor(() => expect(messageText()).toBe(text));
    expect(info()).toBe('Changes unsuccessful. Please try again');
    await app.user.click(screen.getByRole('button', { name: 'Update another account' }));
    expect(acct()).toHaveValue('');
  });

  it('ACUP-S17: with shortcuts on, F5 outside Confirm is an invalid key and keeps the input', async () => {
    const app = await openAccount({ functionKeys: true });
    await replace(app, 'ficoScore', '700');
    fireEvent.keyDown(window, { key: 'F5' });
    expect(messageText()).toBe('Invalid key pressed. Please see below...');
    expect(input('ficoScore')).toHaveValue('700');
    expect(app.apiCalls('/api/accounts/00000000001/validate')).toHaveLength(0);
  });

  it('ACUP-S18: Back returns to the main menu with the role unchanged', async () => {
    const app = await openAccount({ functionKeys: true });
    await replace(app, 'ficoScore', '700');
    fireEvent.keyDown(window, { key: 'F3' });
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
    await screen.findByRole('heading', { name: 'Main menu' });
  });

  it('ACUP-S23: a ZIP that does not match the state is refused in the page; State and Zip red, focus State', async () => {
    const app = await openAccount();
    await replace(app, 'zip5', '12547');
    await checkChanges(app);
    expect(messageText()).toBe('Invalid zip code for state');
    expect(input('stateCd')).toHaveAttribute('aria-invalid', 'true');
    expect(input('zip5')).toHaveAttribute('aria-invalid', 'true');
    await waitFor(() => expect(input('stateCd')).toHaveFocus());
    expect(app.apiCalls('/api/accounts/00000000001/validate')).toHaveLength(0);
  });

  it('ACUP-S8 ACUP-S10: focus reaches Save and then the next step even when a frame runs before React shows them', async () => {
    // A frame callback that runs before the commit finds no button yet; the page must not depend on frame timing.
    const frame = vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      queueMicrotask(() => callback(performance.now()));
      return 0;
    });
    try {
      const app = await openAccount();
      await replace(app, 'ficoScore', '700');
      await checkChanges(app);
      const save = await screen.findByRole('button', { name: /^Save/ });
      await waitFor(() => expect(save).toHaveFocus());
      await app.user.click(save);
      await waitFor(() => expect(screen.getByRole('button', { name: 'Update another account' })).toHaveFocus());
    } finally {
      frame.mockRestore();
    }
  });

  it('ACUP-S28: Change opens the SSN for a new value; Keep current restores the mask', async () => {
    const app = await openAccount();
    await app.user.click(screen.getByRole('button', { name: 'Change SSN' }));
    await waitFor(() => expect(input('ssn1')).toHaveFocus());
    await app.user.type(input('ssn1'), '987');
    await app.user.type(input('ssn2'), '65');
    await app.user.type(input('ssn3'), '4321');
    await checkChanges(app);
    expect(messageText()).toBe('SSN: First 3 chars: should not be 000, 666, or between 900 and 999');
    expect(input('ssn1')).toHaveAttribute('aria-invalid', 'true');
    await app.user.click(screen.getByRole('button', { name: 'Keep the current SSN' }));
    expect(screen.queryByTestId('ssn1')).toBeNull();
    expect(screen.getByTestId('ssn-masked')).toHaveTextContent('***-**-3888');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Change SSN' })).toHaveFocus());
  });

  it('ACUP-S29: an ADMIN is sent to the admin menu and never fetches the form', async () => {
    const { router, apiCalls } = await renderApp('/accounts/edit', { signedOnAs: 'ADMIN001' });
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin'));
    expect(apiCalls('/api/accounts')).toHaveLength(0);
  });

  it('ACUP-S32: the batch window lets validate through and refuses the save with the account-update text', async () => {
    const app = await openAccount({ settings: { batchWindowOpen: true } });
    await replace(app, 'creditLimit', '2500.00');
    await checkChanges(app);
    await app.user.click(await screen.findByRole('button', { name: /^Save/ }));
    await waitFor(() => expect(messageText()).toBe('Account update is not available during the batch cycle. Try again later...'));
    expect(info()).toBe('Changes unsuccessful. Please try again');
    expect(screen.getByText(/^Try again after/)).toBeInTheDocument();
  });

  it('ACUP-S48: an account with no card shows the account fields only', async () => {
    const app = await openPage();
    await app.user.type(acct(), '00000000098{Enter}');
    await screen.findByTestId('activeStatus');
    expect(screen.getByTestId('no-customer')).toBeInTheDocument();
    expect(screen.queryByTestId('ficoScore')).toBeNull();
    expect(readCurrentAccount()).toEqual({ acctId: '00000000098', custId: null, cardId: null });
    await replace(app, 'creditLimit', '2500.00');
    await checkChanges(app);
    await app.user.click(await screen.findByRole('button', { name: /^Save/ }));
    await waitFor(() => expect(info()).toBe('Changes committed to database'));
    const put = app.requests.find((r) => r.method === 'PUT');
    expect(put?.headers.get('If-Match')).toMatch(/^"[0-9a-f]{16}"$/);
  });
});
