import { fireEvent, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderApp } from '@/test/renderApp';

const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;
const messageTone = () => screen.queryByTestId('message')?.getAttribute('data-tone') ?? null;
const rowIds = () => screen.queryAllByTestId(/^row-id-\d+$/).map((cell) => cell.textContent);
const pageNumber = () => screen.getByTestId('page-number').textContent;
const search = () => screen.getByTestId('tran-search');

type App = Awaited<ReturnType<typeof renderApp>>;

async function openList(path = '/transactions', options: Parameters<typeof renderApp>[1] = {}) {
  const app = await renderApp(path, { signedOnAs: 'USER0001', ...options });
  await screen.findByRole('heading', { name: 'Transactions', level: 1 });
  await screen.findByTestId('tran-row-1');
  return app;
}

async function listCalls(app: App, n: number) {
  await waitFor(() => expect(app.apiCalls('/api/transactions').filter((r) => new URL(r.url).pathname === '/api/transactions')).toHaveLength(n));
}

const query = (app: App) => new URLSearchParams(app.router.state.location.search);

describe('Transaction list (COTRN00C)', () => {
  it('@BR-TRNL-001 @BR-TRNL-002 @BR-TRNL-006 @FLOW-TRNL-002 menu option 6 opens the first ten transactions for a USER, with no message', async () => {
    const { user, router } = await renderApp('/', { signedOnAs: 'USER0001' });
    await screen.findByRole('heading', { name: 'Main menu' });
    await user.type(screen.getByLabelText('Option number'), '6{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/transactions'));
    await screen.findByTestId('tran-row-10');
    expect(rowIds()).toHaveLength(10);
    expect(rowIds()[0]).toBe('0000000000683580');
    expect(screen.getByTestId('row-date-1')).toHaveTextContent('06/10/22');
    expect(screen.getByTestId('row-desc-1')).toHaveTextContent('Purchase at Abshire-Lowe');
    expect(screen.getByTestId('row-amount-1')).toHaveTextContent('+00000504.77');
    expect(screen.getByTestId('row-amount-2')).toHaveTextContent('-00000919.00');
    expect(pageNumber()).toBe('1');
    expect(messageText()).toBeNull();
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CT00 · COTRN00C');
    // Without the function keys there is no Sel column; View opens a row.
    expect(screen.queryByTestId('sel-1')).toBeNull();
    await waitFor(() => expect(search()).toHaveFocus());
  });

  it('@FLOW-TRNL-005 @FLOW-TRNL-006 @BR-TRNL-004 @VAL-TRNL-004 Next and Previous page by ten; Previous on page 1 says so and calls nothing', async () => {
    const app = await openList();
    await app.user.click(screen.getByTestId('previous'));
    expect(messageText()).toBe('You are already at the top of the page...');
    expect(messageTone()).toBe('info');
    await listCalls(app, 1);
    await app.user.click(screen.getByTestId('next'));
    await waitFor(() => expect(rowIds()[0]).toBe('0000000025430891'));
    expect(pageNumber()).toBe('2');
    expect(query(app).get('first')).toBe('0000000025430891');
    expect(query(app).get('page')).toBe('2');
    expect(messageText()).toBeNull();
    await app.user.click(screen.getByTestId('previous'));
    await waitFor(() => expect(rowIds()[0]).toBe('0000000000683580'));
    expect(pageNumber()).toBe('1');
    expect(messageText()).toBe('You have reached the top of the page...');
  });

  it('@BR-TRNL-005 @VAL-TRNL-005 the place in the list survives a reload; the last page has the bottom notice and Next stays there', async () => {
    const app = await openList('/transactions?first=0000000957864065&page=30');
    expect(pageNumber()).toBe('30');
    expect(rowIds()).toHaveLength(10);
    expect(rowIds()[9]).toBe('0000000996722787');
    expect(messageText()).toBe('You have reached the bottom of the page...');
    await app.user.click(screen.getByTestId('next'));
    expect(messageText()).toBe('You are already at the bottom of the page...');
    await listCalls(app, 1);
  });

  it('@VAL-TRNL-001 @DEF-TRNL-006 a short ID starts the list there; a non-numeric one is flagged without a call', async () => {
    const app = await openList();
    await app.user.type(search(), '1774260{Enter}');
    await waitFor(() => expect(rowIds()[0]).toBe('0000000001774260'));
    expect(pageNumber()).toBe('1');
    await app.user.clear(search());
    await app.user.type(search(), '12a{Enter}');
    expect(messageText()).toBe('Tran ID must be Numeric ...');
    expect(search()).toHaveAttribute('aria-invalid', 'true');
    expect(search()).toHaveFocus();
    expect(rowIds()[0]).toBe('0000000001774260');
    await listCalls(app, 2);
  });

  it('@VAL-TRNL-001 the search field has no length cap: 16 digits with trailing spaces are sent as typed, 17 digits get the service rule', async () => {
    const app = await openList();
    await app.user.type(search(), '0000000001774260  {Enter}');
    await waitFor(() => expect(rowIds()[0]).toBe('0000000001774260'));
    expect(search()).toHaveValue('0000000001774260  ');
    await app.user.clear(search());
    await app.user.type(search(), '00000000017742600{Enter}');
    expect(messageText()).toBe('Tran ID must be Numeric ...');
    await listCalls(app, 2);
  });

  it('@DEF-TRNL-005 a blank search shows the page again from its first row', async () => {
    const app = await openList();
    await app.user.click(screen.getByTestId('next'));
    await waitFor(() => expect(pageNumber()).toBe('2'));
    await app.user.click(screen.getByTestId('submit'));
    await listCalls(app, 3);
    const last = app.apiCalls('/api/transactions').at(-1);
    expect(new URL(last?.url ?? '').searchParams.get('start')).toBe('0000000025430891');
    expect(pageNumber()).toBe('2');
    expect(rowIds()[0]).toBe('0000000025430891');
  });

  it('@BR-TRNL-003 @VAL-TRNL-002 @FLOW-TRNL-010 with the function keys on, the first non-blank Sel decides and only S opens it', async () => {
    const app = await openList('/transactions', { functionKeys: true });
    await app.user.type(screen.getByTestId('sel-2'), 'x');
    await app.user.type(screen.getByTestId('sel-3'), 's');
    await app.user.click(screen.getByTestId('submit'));
    expect(messageText()).toBe('Invalid selection. Valid value is S');
    expect(screen.getByTestId('sel-2')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('tran-row-2')).toHaveAttribute('data-invalid', 'true');
    await app.user.clear(screen.getByTestId('sel-2'));
    // The selection comes before the search: S opens the view even with a bad search ID.
    await app.user.type(search(), 'zz');
    await app.user.click(screen.getByTestId('submit'));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/transactions/0000000006292564'));
    await screen.findByTestId('tran-panel');
  });

  it('@VAL-TRNL-002 a bad Sel and a bad search ID: the search message wins', async () => {
    const app = await openList('/transactions', { functionKeys: true });
    await app.user.type(screen.getByTestId('sel-1'), 'u');
    await app.user.type(search(), 'q');
    await app.user.click(screen.getByTestId('submit'));
    expect(messageText()).toBe('Tran ID must be Numeric ...');
    expect(screen.getByTestId('sel-1')).not.toHaveAttribute('aria-invalid', 'true');
  });

  it('@VAL-TRNL-003 @FLOW-TRNL-004 an unknown key with the function keys on is refused; F3 goes back to the menu', async () => {
    const app = await openList('/transactions', { functionKeys: true });
    fireEvent.keyDown(window, { key: 'F4' });
    expect(messageText()).toBe('Invalid key pressed. Please see below...');
    fireEvent.keyDown(window, { key: 'F3' });
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
  });

  it('@ERR-TRNL-001 a failed read shows the lookup message', async () => {
    await renderApp('/transactions', { signedOnAs: 'USER0001', settings: { faults: ['tran-read'] } });
    await waitFor(() => expect(messageText()).toBe('Unable to lookup transaction...'));
    expect(rowIds()).toHaveLength(0);
  });

  it('without a session it goes to sign-on', async () => {
    const { router } = await renderApp('/transactions');
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-on'));
  });
});

describe('Transaction view (COTRN01C)', () => {
  it('@BR-TRNV-003 @BR-TRNV-002 @DEF-TRNV-004 View on a row shows it without Enter, the card masked and no Show for a USER', async () => {
    const app = await openList();
    await app.user.click(screen.getByTestId('view-1'));
    await screen.findByTestId('tran-panel');
    expect(app.router.state.location.pathname).toBe('/transactions/0000000000683580');
    expect(screen.getByTestId('tran-id-input')).toHaveValue('0000000000683580');
    expect(screen.getByTestId('tran-desc')).toHaveTextContent('Purchase at Abshire-Lowe');
    expect(screen.getByTestId('tran-amount')).toHaveTextContent('+00000504.77');
    expect(screen.getByTestId('tran-card')).toHaveTextContent('************7065');
    expect(screen.getByTestId('tran-merchant-name')).toHaveTextContent('Abshire-Lowe');
    expect(screen.queryByTestId('tran-card-toggle')).toBeNull();
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CT01 · COTRN01C');
  });

  it('@FLOW-TRNV-004 Back to list returns to page 1 of the list', async () => {
    const app = await openList();
    await app.user.click(screen.getByTestId('next'));
    await waitFor(() => expect(pageNumber()).toBe('2'));
    await app.user.click(screen.getByTestId('view-1'));
    await screen.findByTestId('tran-panel');
    await app.user.click(screen.getByTestId('back'));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/transactions'));
    await waitFor(() => expect(rowIds()[0]).toBe('0000000000683580'));
    expect(pageNumber()).toBe('1');
  });

  it('@FLOW-TRNV-002 @VAL-TRNV-001 @VAL-TRNV-002 @BR-TRNV-001 menu option 7: empty, ID required, short IDs padded, an unknown ID clears the details', async () => {
    const { user, router, apiCalls } = await renderApp('/', { signedOnAs: 'USER0001' });
    await screen.findByRole('heading', { name: 'Main menu' });
    await user.type(screen.getByLabelText('Option number'), '7{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/transactions/lookup'));
    await screen.findByTestId('tran-panel-empty');
    const id = screen.getByTestId('tran-id-input');
    await waitFor(() => expect(id).toHaveFocus());
    expect(messageText()).toBeNull();
    await user.click(screen.getByTestId('submit'));
    expect(messageText()).toBe('Tran ID can NOT be empty...');
    expect(id).toHaveAttribute('aria-invalid', 'true');
    expect(apiCalls('/api/transactions')).toHaveLength(0);
    await user.type(id, '683580{Enter}');
    await screen.findByTestId('tran-panel');
    expect(screen.getByTestId('tran-id')).toHaveTextContent('0000000000683580');
    expect(messageText()).toBeNull();
    await user.clear(id);
    await user.type(id, '12345{Enter}');
    await waitFor(() => expect(messageText()).toBe('Transaction ID NOT found...'));
    expect(screen.queryByTestId('tran-panel')).toBeNull();
    expect(id).toHaveValue('12345');
    // No length cap: a 17-character ID reaches the service, which does not find it.
    await user.clear(id);
    await user.type(id, '00000000006835801{Enter}');
    await waitFor(() => expect(apiCalls('/api/transactions/00000000006835801')).toHaveLength(1));
    expect(id).toHaveValue('00000000006835801');
    await user.clear(id);
    await user.type(id, '0000000000683580  {Enter}');
    await screen.findByTestId('tran-panel');
  });

  it('@FLOW-TRNV-005 @FLOW-TRNV-006 @VAL-TRNV-003 with the function keys on: F4 clears, F7 is refused, F5 opens the list', async () => {
    const app = await renderApp('/transactions/0000000001774260', { signedOnAs: 'USER0001', functionKeys: true });
    await screen.findByTestId('tran-panel');
    expect(screen.getByTestId('tran-amount')).toHaveTextContent('-00000919.00');
    fireEvent.keyDown(window, { key: 'F7' });
    expect(messageText()).toBe('Invalid key pressed. Please see below...');
    fireEvent.keyDown(window, { key: 'F4' });
    await screen.findByTestId('tran-panel-empty');
    expect(screen.getByTestId('tran-id-input')).toHaveValue('');
    expect(messageText()).toBeNull();
    fireEvent.keyDown(window, { key: 'F5' });
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/transactions'));
  });

  it('@DEF-TRNV-004 an ADMIN shows the card number through the audited unmask, and hides it again', async () => {
    const app = await renderApp('/transactions/0000000000683580', { signedOnAs: 'ADMIN001' });
    await screen.findByTestId('tran-panel');
    expect(screen.getByTestId('tran-card')).toHaveTextContent('************7065');
    await app.user.click(screen.getByRole('button', { name: 'Show card number' }));
    await waitFor(() => expect(screen.getByTestId('tran-card')).toHaveTextContent('4859452612877065'));
    const calls = app.apiCalls('/api/transactions/0000000000683580/unmask');
    expect(calls).toHaveLength(1);
    expect(await calls[0]?.json()).toEqual({ fields: ['cardNum'] });
    expect(calls[0]?.headers.get('X-XSRF-TOKEN')).toBeTruthy();
    await app.user.click(screen.getByRole('button', { name: 'Hide card number' }));
    expect(screen.getByTestId('tran-card')).toHaveTextContent('************7065');
  });

  it('an unmask whose audit fails keeps the card masked and says why', async () => {
    const app = await renderApp('/transactions/0000000000683580', { signedOnAs: 'ADMIN001', settings: { faults: ['audit'] } });
    await screen.findByTestId('tran-panel');
    await app.user.click(screen.getByRole('button', { name: 'Show card number' }));
    await waitFor(() => expect(messageText()).toBe('Unable to record the unmask request'));
    expect(screen.getByTestId('tran-card')).toHaveTextContent('************7065');
  });
});
