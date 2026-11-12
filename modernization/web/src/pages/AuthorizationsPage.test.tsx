import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { readCurrentAccount, setCurrentAccount } from '@/session/currentAccount';
import { renderApp } from '@/test/renderApp';

const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;
const messageTone = () => screen.queryByTestId('message')?.getAttribute('data-tone') ?? null;
const acct = () => screen.getByTestId('acct-id');
const rowIds = () => screen.queryAllByTestId(/^row-tran-\d$/).map((cell) => cell.textContent);
const tran = (authId: number) => `TRN${String(authId).padStart(12, '0')}`;

type App = Awaited<ReturnType<typeof renderApp>>;
type Options = Parameters<typeof renderApp>[1];

async function openPage(options: Options = {}) {
  const app = await renderApp('/authorizations', { signedOnAs: 'USER0001', ...options, settings: { authorizationInstalled: true, ...options?.settings } });
  await screen.findByRole('heading', { name: 'Pending authorizations' });
  return app;
}

async function lookUp(app: App, acctId: string) {
  await app.user.clear(acct());
  await app.user.type(acct(), acctId === '' ? '{Enter}' : `${acctId}{Enter}`);
}

async function openAccount1(options: Options = {}) {
  const app = await openPage(options);
  await lookUp(app, '00000000001');
  await waitFor(() => expect(rowIds()).toHaveLength(5));
  return app;
}

describe('Pending authorizations (COPAUS0C)', () => {
  it('FLOW-AUSM-001: an empty search with focus on the account number and the screen ID', async () => {
    const app = await openPage();
    expect(acct()).toHaveValue('');
    await waitFor(() => expect(acct()).toHaveFocus());
    expect(messageText()).toBeNull();
    expect(screen.queryByTestId('auth-table')).toBeNull();
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CPVS · COPAUS0C');
    expect(app.apiCalls('/api/accounts')).toHaveLength(0);
  });

  it('A1 BR-AUSM-001 BR-AUSM-002 BR-AUSM-003: header, totals and the first five rows, newest first', async () => {
    await openAccount1();
    expect(screen.getByTestId('auth-name')).toHaveTextContent('Immanuel M Kessler');
    expect(screen.getByTestId('auth-cust-id')).toHaveTextContent('000000001');
    expect(screen.getByTestId('auth-address2')).toHaveTextContent('Altenwerthshire,NC,12546');
    expect(screen.getByTestId('auth-phone')).toHaveTextContent('(908)119-8310');
    expect(screen.getByTestId('auth-credit-limit')).toHaveTextContent('$2,020.00');
    expect(screen.getByTestId('auth-cash-limit')).toHaveTextContent('$1,020.00');
    expect(screen.getByTestId('auth-approved-count')).toHaveTextContent('8');
    expect(screen.getByTestId('auth-declined-count')).toHaveTextContent('4');
    expect(rowIds()).toEqual([1012, 1011, 1010, 1009, 1008].map(tran));
    expect(screen.getByTestId('row-approval-1')).toHaveTextContent('A');
    expect(screen.getByTestId('auth-row-1')).toHaveTextContent('Approved');
    expect(screen.getByTestId('row-approval-2')).toHaveTextContent('D');
    expect(screen.getByTestId('row-match-1')).toHaveTextContent('P');
    expect(screen.getByTestId('row-amount-1')).toHaveTextContent('$50.00');
    expect(screen.getByTestId('row-date-1')).toHaveTextContent(/^\d{2}\/\d{2}\/\d{2}$/);
    expect(screen.getByTestId('row-time-1')).toHaveTextContent(/^\d{2}:\d{2}:\d{2}$/);
    expect(messageText()).toBeNull();
    expect(readCurrentAccount()).toMatchObject({ acctId: '00000000001', custId: '000000001' });
  });

  it('DEF-AUSM-004: the name keeps its spaces and is cut at 25; each address value stops at a double space; totals are shown in full', async () => {
    const app = await openPage();
    await lookUp(app, '00000000097');
    await screen.findByTestId('auth-customer');
    expect(screen.getByTestId('auth-name').textContent).toBe('Maximiliana   Vandenbergh');
    expect(screen.getByTestId('auth-address1').textContent).toBe('12,Unit 4');
    expect(screen.getByTestId('auth-address2').textContent).toBe('Saint,FL,33706');
    expect(screen.getByTestId('auth-phone').textContent).toBe('(727)555-0197');
    expect(screen.getByTestId('auth-approved-count').textContent).toBe('1,234');
    expect(screen.getByTestId('auth-approved-amount').textContent).toBe('$123,456.78');
    expect(screen.getByTestId('auth-declined-amount').textContent).toBe('$987,654,321.00');
    expect(screen.getByTestId('auth-credit-balance').textContent).toBe('$1,234,567,890.12');
    await lookUp(app, '00000000001');
    await waitFor(() => expect(screen.getByTestId('auth-address1').textContent).toBe('618 Deshaun Route,Apt. 80'));
  });

  it('A7 VAL-AUSM-005: Next and Previous page through the list; at either end the web says so without a call', async () => {
    const app = await openAccount1();
    const lists = () => app.apiCalls('/api/accounts/00000000001/authorizations').filter((r) => !r.url.includes('/summary'));
    await app.user.click(screen.getByTestId('previous'));
    expect(messageText()).toBe('You are already at the top of the page...');
    expect(messageTone()).toBe('error');
    expect(lists()).toHaveLength(1);
    await app.user.click(screen.getByTestId('next'));
    await waitFor(() => expect(rowIds()).toEqual([1007, 1006, 1005, 1004, 1003].map(tran)));
    expect(messageText()).toBeNull();
    expect(screen.getByTestId('page-number')).toHaveTextContent('2');
    await app.user.click(screen.getByTestId('next'));
    await waitFor(() => expect(rowIds()).toEqual([1002, 1001].map(tran)));
    await app.user.click(screen.getByTestId('next'));
    expect(messageText()).toBe('You are already at the bottom of the page...');
    expect(lists()).toHaveLength(3);
    expect(rowIds()).toEqual([1002, 1001].map(tran));
    await app.user.click(screen.getByTestId('previous'));
    await waitFor(() => expect(rowIds()).toEqual([1007, 1006, 1005, 1004, 1003].map(tran)));
    await app.user.click(screen.getByTestId('previous'));
    await waitFor(() => expect(rowIds()).toEqual([1012, 1011, 1010, 1009, 1008].map(tran)));
    expect(screen.getByTestId('page-number')).toHaveTextContent('1');
    const after = new URL(lists()[1]?.url ?? '').searchParams;
    expect(after.get('size')).toBe('5');
    expect(after.get('after')).toBeTruthy();
  });

  it('A9: exactly five rows: Next reports the bottom with no call', async () => {
    const app = await openPage();
    await lookUp(app, '00000000002');
    await waitFor(() => expect(rowIds()).toHaveLength(5));
    await app.user.click(screen.getByTestId('next'));
    expect(messageText()).toBe('You are already at the bottom of the page...');
  });

  it('A2: an account with no summary shows zeros and an empty list', async () => {
    const app = await openPage();
    await lookUp(app, '00000000003');
    await screen.findByTestId('auth-no-summary');
    expect(screen.getByTestId('auth-approved-count')).toHaveTextContent('0');
    expect(screen.getByTestId('auth-approved-amount')).toHaveTextContent('$0.00');
    expect(screen.getByTestId('no-rows')).toBeInTheDocument();
    expect(messageText()).toBeNull();
  });

  it('VAL-AUSM-001 VAL-AUSM-002 A3 A4: required, numeric and not-found texts mark the field', async () => {
    const app = await openPage();
    await lookUp(app, '');
    expect(messageText()).toBe('Please enter Acct Id...');
    expect(acct()).toHaveAttribute('aria-invalid', 'true');
    expect(app.apiCalls('/api/accounts')).toHaveLength(0);
    for (const typed of ['123', '0000000000A', '1']) {
      await lookUp(app, typed);
      await waitFor(() => expect(messageText()).toBe('Acct Id must be Numeric ...'));
      expect(acct()).toHaveValue(typed);
    }
    await lookUp(app, '99999999999');
    await waitFor(() => expect(messageText()).toBe('Account:99999999999 not found in ACCT file.'));
    expect(acct()).toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByTestId('auth-table')).toBeNull();
  });

  it('A6 ERR-AUSM-001 ERR-AUSM-003: a missing cross-reference or customer shows the totals with the error text', async () => {
    const app = await openPage();
    await lookUp(app, '00000000098');
    await waitFor(() => expect(messageText()).toBe('Account:00000000098 not found in XREF file.'));
    expect(messageTone()).toBe('error');
    expect(screen.getByTestId('auth-customer-empty')).toBeInTheDocument();
    expect(screen.getByTestId('auth-totals')).toBeInTheDocument();
    expect(acct()).not.toHaveAttribute('aria-invalid', 'true');
    await lookUp(app, '00000000099');
    await waitFor(() => expect(messageText()).toBe('Customer:000000099 not found in CUST file.'));
    expect(rowIds()).toHaveLength(2);
  });

  it('A23: each failed read shows its text, without codes', async () => {
    const cases: [string, string][] = [
      ['account-read', ' System error while reading ACCT file.'],
      ['xref-read', ' System error while reading XREF file.'],
      ['customer-read', ' System error while reading CUST file.'],
      ['auth-summary-read', ' System error while reading AUTH Summary'],
      ['auth-detail-read', ' System error while reading AUTH Details'],
    ];
    const app = await openPage();
    for (const [fault, text] of cases) {
      app.settings.faults = [fault];
      await lookUp(app, '00000000001');
      await waitFor(() => expect(messageText()).toBe(text));
      expect(rowIds()).toHaveLength(0);
    }
  });

  it('FLOW-AUSM-002: the current account is shown at once', async () => {
    setCurrentAccount({ acctId: '00000000002', custId: '000000002', cardId: '7' });
    await openPage();
    await waitFor(() => expect(rowIds()).toHaveLength(5));
    expect(acct()).toHaveValue('00000000002');
    expect(readCurrentAccount()).toEqual({ acctId: '00000000002', custId: '000000002', cardId: '7' });
  });

  it('BR-AUSM-007: View opens the row; BR-AUSM-008: Back and F3 return to the menu', async () => {
    const app = await openAccount1({ functionKeys: true });
    await app.user.click(within(screen.getByTestId('auth-row-2')).getByRole('link', { name: `View authorization ${tran(1011)}` }));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/authorizations/1011'));
    await screen.findByRole('heading', { name: 'Authorization details' });
    await app.router.navigate('/authorizations');
    await screen.findByRole('heading', { name: 'Pending authorizations' });
    await app.user.keyboard('{F3}');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
  });

  it('F7 and F8 page with the function keys on; other keys are invalid', async () => {
    const app = await openAccount1({ functionKeys: true });
    await app.user.keyboard('{F8}');
    await waitFor(() => expect(rowIds()[0]).toBe(tran(1007)));
    await app.user.keyboard('{F7}');
    await waitFor(() => expect(rowIds()[0]).toBe(tran(1012)));
    await app.user.keyboard('{F5}');
    expect(messageText()).toBe('Invalid key pressed. Please see below...');
  });
});

describe('authorization routes and option 11', () => {
  it('web.md Routes: without the module the pages do not exist', async () => {
    await renderApp('/authorizations', { signedOnAs: 'USER0001' });
    await screen.findByRole('heading', { name: 'Page not found' });
    expect(screen.queryByRole('heading', { name: 'Pending authorizations' })).toBeNull();
  });

  it('C1: an ADMIN goes to the admin menu', async () => {
    const app = await renderApp('/authorizations', { signedOnAs: 'ADMIN001', settings: { authorizationInstalled: true } });
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/admin'));
  });

  it('BR-MENU-004: with the module installed option 11 opens the summary', async () => {
    const app = await renderApp('/', { signedOnAs: 'USER0001', settings: { authorizationInstalled: true } });
    await screen.findByRole('heading', { name: 'Main menu' });
    await waitFor(() => expect(screen.getByTestId('option-11')).toHaveAttribute('data-status', 'available'));
    await app.user.type(screen.getByLabelText('Option number'), '11{Enter}');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/authorizations'));
    await screen.findByRole('heading', { name: 'Pending authorizations' });
  });
});
