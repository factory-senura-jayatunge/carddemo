import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { readCurrentAccount, setCurrentAccount } from '@/session/currentAccount';
import { renderApp } from '@/test/renderApp';

const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;
const messageTone = () => screen.queryByTestId('message')?.getAttribute('data-tone') ?? null;
const info = () => screen.getByTestId('info-line').textContent;
const acct = () => screen.getByTestId('acct-id');
const PROMPT = 'Enter or update id of account to display';
const FORMAT = 'Account Filter must  be a non-zero 11 digit number';

type App = Awaited<ReturnType<typeof renderApp>>;

async function openPage(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await renderApp('/accounts/view', { signedOnAs: 'USER0001', ...options });
  await screen.findByRole('heading', { name: 'Account view' });
  return app;
}

async function lookUp(app: App, acctId: string) {
  await app.user.clear(acct());
  await app.user.type(acct(), `${acctId}{Enter}`);
}

async function openAccount1(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await openPage(options);
  await lookUp(app, '00000000001');
  await screen.findByTestId('customer-panel');
  return app;
}

const unmaskBodies = (app: App) =>
  Promise.all(app.apiCalls('/api/accounts/00000000001/unmask').map((r) => r.clone().json() as Promise<{ fields: string[] }>));

describe('Account view (COACTVWC)', () => {
  it('ACVW-S1: without a session it goes to sign-on', async () => {
    const { router } = await renderApp('/accounts/view');
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-on'));
  });

  it('ACVW-S2: main menu option 1 opens an empty field, the prompt, no message and focus on the account number', async () => {
    const { user, router } = await renderApp('/', { signedOnAs: 'USER0001' });
    await screen.findByRole('heading', { name: 'Main menu' });
    await user.type(screen.getByLabelText('Option number'), '1{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/accounts/view'));
    await screen.findByRole('heading', { name: 'Account view' });
    expect(acct()).toHaveValue('');
    expect(acct()).not.toHaveAttribute('aria-invalid', 'true');
    expect(info()).toBe(PROMPT);
    expect(messageText()).toBeNull();
    expect(screen.queryByTestId('account-panel')).toBeNull();
    await waitFor(() => expect(acct()).toHaveFocus());
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CAVW · COACTVWC');
  });

  it('ACVW-S3 @VAL-ACVW-001: a blank account number puts * in the red field, clears the store and calls nothing', async () => {
    setCurrentAccount({ acctId: '00000000001', custId: '000000001', cardId: '49' });
    const app = await openPage();
    await app.user.type(acct(), '{Enter}');
    expect(messageText()).toBe('No input received');
    expect(messageTone()).toBe('error');
    expect(acct()).toHaveValue('*');
    expect(acct()).toHaveAttribute('aria-invalid', 'true');
    await waitFor(() => expect(acct()).toHaveFocus());
    expect(readCurrentAccount()).toBeNull();
    expect(app.apiCalls('/api/accounts')).toHaveLength(0);
    expect(info()).toBe(PROMPT);
  });

  it('ACVW-S4 ACVW-S5 @VAL-ACVW-002: a short, non-numeric or all-zero number keeps the input in red, with no call', async () => {
    const app = await openPage();
    for (const typed of ['12345', '0000000000A', '00000000000']) {
      await lookUp(app, typed);
      expect(messageText()).toBe(FORMAT);
      expect(acct()).toHaveValue(typed);
      expect(acct()).toHaveAttribute('aria-invalid', 'true');
    }
    expect(app.apiCalls('/api/accounts')).toHaveLength(0);
    expect(info()).toBe(PROMPT);
  });

  it('ACVW-S6: shows account 00000000001 with its customer, PII masked, and sets the current account', async () => {
    const app = await openAccount1();
    expect(screen.getByTestId('acct-active')).toHaveTextContent('Y');
    expect(screen.getByTestId('acct-open-date')).toHaveTextContent('2014-11-20');
    expect(screen.getByTestId('acct-expiry-date')).toHaveTextContent('2025-05-20');
    expect(screen.getByTestId('acct-reissue-date')).toHaveTextContent('2025-05-20');
    expect(screen.getByTestId('acct-credit-limit').textContent).toBe('+      2,020.00');
    expect(screen.getByTestId('acct-cash-limit').textContent).toBe('+      1,020.00');
    expect(screen.getByTestId('acct-balance').textContent).toBe('+        194.00');
    expect(screen.getByTestId('acct-cycle-credit').textContent).toBe('+           .00');
    expect(screen.getByTestId('acct-cycle-debit').textContent).toBe('+           .00');
    expect(screen.getByTestId('acct-group')).toHaveTextContent('\u2014');
    expect(screen.getByTestId('cust-id')).toHaveTextContent('000000001');
    expect(screen.getByTestId('cust-name')).toHaveTextContent('Immanuel Madeline Kessler');
    expect(screen.getByTestId('cust-address')).toHaveTextContent('618 Deshaun Route');
    expect(screen.getByTestId('cust-address')).toHaveTextContent('Apt. 802');
    expect(screen.getByTestId('cust-city')).toHaveTextContent('Altenwerthshire');
    expect(screen.getByTestId('cust-state')).toHaveTextContent('NC');
    expect(screen.getByTestId('cust-zip')).toHaveTextContent('12546');
    expect(screen.getByTestId('cust-country')).toHaveTextContent('USA');
    expect(screen.getByTestId('cust-phone1')).toHaveTextContent('(908)119-8310');
    expect(screen.getByTestId('cust-phone2')).toHaveTextContent('(373)693-8684');
    expect(screen.getByTestId('cust-fico')).toHaveTextContent('274');
    expect(screen.getByTestId('cust-primary')).toHaveTextContent('Y');
    expect(screen.getByTestId('cust-ssn')).toHaveTextContent('***-**-3888');
    expect(screen.getByTestId('cust-dob')).toHaveTextContent('****-06-08');
    expect(screen.getByTestId('cust-govtIssuedId')).toHaveTextContent('****************8437');
    expect(screen.getByTestId('cust-eftAccountId')).toHaveTextContent('******1756');
    expect(screen.getByTestId('card-num')).toHaveTextContent('************3697');
    // A USER sees no Show control (§1).
    expect(screen.queryByRole('button', { name: /^Show/ })).toBeNull();
    expect(messageText()).toBeNull();
    expect(acct()).not.toHaveAttribute('aria-invalid', 'true');
    expect(info()).toBe(PROMPT);
    expect(readCurrentAccount()).toMatchObject({ acctId: '00000000001', custId: '000000001' });
    expect(readCurrentAccount()?.cardId).toBeTruthy();
    expect(app.apiCalls('/api/accounts/00000000001/unmask')).toHaveLength(0);
  });

  it('ACVW-S7: account 00000000003 shows customer 3 with the full ZIP', async () => {
    const app = await openPage();
    await lookUp(app, '00000000003');
    await screen.findByTestId('customer-panel');
    expect(screen.getByTestId('cust-id')).toHaveTextContent('000000003');
    expect(screen.getByTestId('cust-zip')).toHaveTextContent('19852-6716');
  });

  it('ACVW-S8 ACVW-S16: an unknown account after a view keeps the typed ID in red, clears the panels and the store', async () => {
    const app = await openAccount1();
    await lookUp(app, '99999999999');
    await waitFor(() => expect(messageText()).toBe('Account 99999999999 not found.'));
    expect(acct()).toHaveValue('99999999999');
    expect(acct()).toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByTestId('account-panel')).toBeNull();
    expect(screen.queryByTestId('customer-panel')).toBeNull();
    expect(screen.queryByText('Immanuel Madeline Kessler')).toBeNull();
    expect(readCurrentAccount()).toBeNull();
  });

  it('ACVW-S10: a customer the cross-reference names but that is missing stops the view, field in normal colour', async () => {
    // 00000000099 is the mock's account whose cross-reference names the missing customer 000000099.
    setCurrentAccount({ acctId: '00000000001', custId: '000000001', cardId: '49' });
    const app = await openPage();
    await lookUp(app, '00000000099');
    await waitFor(() => expect(messageText()).toBe('Customer 000000099 not found.'));
    expect(messageTone()).toBe('error');
    expect(acct()).not.toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByTestId('account-panel')).toBeNull();
    expect(screen.queryByTestId('customer-panel')).toBeNull();
    expect(readCurrentAccount()).toBeNull();
    await waitFor(() => expect(acct()).toHaveFocus());
  });

  it('ACVW-S12 ACVW-S21: a failed read gives its text; account and cross-reference failures make the field red, a customer failure does not', async () => {
    const app = await openPage({ settings: { faults: ['account-read'] } });
    await lookUp(app, '00000000001');
    await waitFor(() => expect(messageText()).toBe('Error reading account data'));
    expect(acct()).toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByTestId('account-panel')).toBeNull();

    app.settings.faults = ['xref-read'];
    await lookUp(app, '00000000001');
    await waitFor(() => expect(messageText()).toBe('Error reading card cross-reference data'));
    expect(acct()).toHaveAttribute('aria-invalid', 'true');

    app.settings.faults = ['customer-read'];
    await lookUp(app, '00000000001');
    await waitFor(() => expect(messageText()).toBe('Error reading customer data'));
    expect(acct()).not.toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByTestId('account-panel')).toBeNull();
    expect(readCurrentAccount()).toBeNull();
  });

  it('ACVW-S22: an account with no card shows the account panel and the information text; the store has no customer or card', async () => {
    const app = await openPage();
    await lookUp(app, '00000000098');
    await screen.findByTestId('account-panel');
    expect(messageText()).toBe('No card is linked to this account.');
    expect(messageTone()).toBe('info');
    // Ruling on contract v5, ACVW question 1: an information message marks no field.
    expect(acct()).not.toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('customer-panel-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('card-num')).toBeNull();
    expect(readCurrentAccount()).toEqual({ acctId: '00000000098', custId: null, cardId: null });
  });

  it('ACVW-S13 @FLOW-ACVW-005: Back from the view opened from the menu returns to the main menu; Escape does too', async () => {
    const app = await openAccount1();
    await app.user.click(screen.getByRole('button', { name: /^Back/ }));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
    await screen.findByRole('heading', { name: 'Main menu' });
    const me = await app.server.handle(new Request(`${window.location.origin}/api/auth/me`));
    expect(((await me.json()) as { role: string }).role).toBe('USER');
    await app.router.navigate('/accounts/view');
    await screen.findByRole('heading', { name: 'Account view' });
    await app.user.keyboard('{Escape}');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
  });

  it('ACVW-S14 @VAL-ACVW-006: with shortcuts on, F5, F7 and F4 give the invalid-key text, keep the input and call nothing', async () => {
    const app = await openPage({ functionKeys: true });
    await app.user.type(acct(), '00000000001');
    for (const key of ['{F5}', '{F7}', '{F4}']) {
      await app.user.keyboard(key);
      expect(messageText()).toBe('Invalid key pressed. Please see below...');
      expect(acct()).toHaveValue('00000000001');
    }
    expect(app.apiCalls('/api/accounts')).toHaveLength(0);
    await app.user.keyboard('{F3}');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
  });

  it('ACVW-S14: an invalid key after a view keeps the panels shown', async () => {
    const app = await openAccount1({ functionKeys: true });
    await app.user.keyboard('{F8}');
    expect(messageText()).toBe('Invalid key pressed. Please see below...');
    expect(screen.getByTestId('customer-panel')).toBeInTheDocument();
    expect(app.apiCalls('/api/accounts/00000000001')).toHaveLength(1);
  });

  it('ACVW-S18 ACVW-S28: an ADMIN sees the view masked, with a Show control on each of the five masked fields', async () => {
    const app = await openAccount1({ signedOnAs: 'ADMIN001' });
    expect(screen.getByTestId('cust-ssn')).toHaveTextContent('***-**-3888');
    for (const id of ['cust-ssn', 'cust-dob', 'cust-govtIssuedId', 'cust-eftAccountId', 'card-num']) {
      expect(screen.getByTestId(`${id}-toggle`)).toHaveTextContent('Show');
    }
    expect(app.apiCalls('/api/accounts/00000000001/unmask')).toHaveLength(0);
  });

  it('ACVW-S28: Show on SSN calls unmaskAccount for that one field and shows the clear value; Hide masks it again', async () => {
    const app = await openAccount1({ signedOnAs: 'ADMIN001' });
    await app.user.click(screen.getByRole('button', { name: 'Show SSN' }));
    await waitFor(() => expect(screen.getByTestId('cust-ssn')).toHaveTextContent('020-97-3888'));
    expect(await unmaskBodies(app)).toEqual([{ fields: ['ssn'] }]);
    expect(screen.getByTestId('cust-dob')).toHaveTextContent('****-06-08');
    await app.user.click(screen.getByRole('button', { name: 'Hide SSN' }));
    expect(screen.getByTestId('cust-ssn')).toHaveTextContent('***-**-3888');
  });

  it('ACVW-S19: an ADMIN reveals each of the other fields one call at a time', async () => {
    const app = await openAccount1({ signedOnAs: 'ADMIN001' });
    const expected: [string, string, string][] = [
      ['date of birth', 'cust-dob', '1961-06-08'],
      ['government ID', 'cust-govtIssuedId', '00000000000049368437'],
      ['EFT account', 'cust-eftAccountId', '0053581756'],
      ['card number', 'card-num', '9680294154603697'],
    ];
    for (const [label, id, clear] of expected) {
      await app.user.click(screen.getByRole('button', { name: `Show ${label}` }));
      await waitFor(() => expect(screen.getByTestId(id)).toHaveTextContent(clear));
    }
    expect(await unmaskBodies(app)).toEqual([{ fields: ['dob'] }, { fields: ['govtIssuedId'] }, { fields: ['eftAccountId'] }, { fields: ['cardNum'] }]);
  });

  it('ACVW-S21: a failed audit insert on unmask shows its text and no clear value', async () => {
    const app = await openAccount1({ signedOnAs: 'ADMIN001' });
    app.settings.faults = ['audit'];
    await app.user.click(screen.getByRole('button', { name: 'Show SSN' }));
    await waitFor(() => expect(messageText()).toBe('Unable to record the unmask request'));
    expect(screen.getByTestId('cust-ssn')).toHaveTextContent('***-**-3888');
    expect(screen.getByTestId('customer-panel')).toBeInTheDocument();
  });

  it('@FLOW-ACVW-005 an ADMIN on account view with no route state goes Back to the admin lookup', async () => {
    const app = await openPage({ signedOnAs: 'ADMIN001' });
    await app.user.click(screen.getByRole('button', { name: /^Back/ }));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/admin/lookup'));
  });
});
