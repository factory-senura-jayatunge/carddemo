import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { readCurrentAccount, setCurrentAccount } from '@/session/currentAccount';
import { renderApp } from '@/test/renderApp';

const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;
const messageTone = () => screen.queryByTestId('message')?.getAttribute('data-tone') ?? null;
const info = () => screen.getByTestId('info-line').textContent;
const page = () => screen.getByTestId('page-number').textContent;
const rows = () => screen.queryAllByTestId(/^card-row-\d+$/);
const rowAcct = (n: number) => screen.getByTestId(`row-acct-${n}`).textContent;
const rowCard = (n: number) => screen.getByTestId(`row-card-${n}`).textContent;
const acctFilter = () => screen.getByTestId('acct-filter');
const cardFilter = () => screen.getByTestId('card-filter');
const sel = (n: number) => screen.getByTestId(`sel-${n}`);
const PROMPT = 'TYPE S FOR DETAIL, U TO UPDATE ANY RECORD';

type App = Awaited<ReturnType<typeof renderApp>>;
type SearchBody = { acctId: string; cardNum: string; size: number; start?: string; after?: string; before?: string };
type SearchPage = { firstKey: string; lastKey: string };

/** CRDL-S1: page 1 of every card. */
async function openList(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await renderApp('/cards', { signedOnAs: 'USER0001', ...options });
  await screen.findByTestId('card-row-7');
  return app;
}

const searches = (app: App) => app.apiCalls('/api/cards/search');
const searchBodies = (app: App) => Promise.all(searches(app).map((r) => r.clone().json() as Promise<SearchBody>));
/** The paging tokens the page received, read again from the mock with the same request. */
async function pageKeys(app: App, body: SearchBody): Promise<SearchPage> {
  const csrf = /XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1] ?? '';
  const response = await app.server.handle(
    new Request(`${window.location.origin}/api/cards/search`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
      body: JSON.stringify(body),
    }),
  );
  return (await response.json()) as SearchPage;
}

const next = async (app: App) => app.user.click(screen.getByTestId('next'));
const previous = async (app: App) => app.user.click(screen.getByTestId('previous'));
const settle = async (app: App, calls: number) => waitFor(() => expect(searches(app)).toHaveLength(calls));

async function typeFilter(app: App, field: HTMLElement, value: string) {
  await app.user.clear(field);
  if (value) await app.user.type(field, value);
}

describe('Credit card list (COCRDLIC)', () => {
  it('CRDL-S20: without a session it goes to sign-on', async () => {
    const { router } = await renderApp('/cards');
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-on'));
  });

  it('CRDL-S1: menu option 3 lists cards 1 to 7 on page 1 with the information line and focus on the account filter', async () => {
    const { user, router, apiCalls } = await renderApp('/', { signedOnAs: 'USER0001' });
    await screen.findByRole('heading', { name: 'Main menu' });
    await user.type(screen.getByLabelText('Option number'), '3{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/cards'));
    await screen.findByTestId('card-row-7');
    expect(rows()).toHaveLength(7);
    expect(page()).toBe('1');
    expect([rowAcct(1), rowCard(1), screen.getByTestId('row-active-1').textContent]).toEqual(['00000000050', '************5740', 'Y']);
    expect([rowAcct(7), rowCard(7), screen.getByTestId('row-active-7').textContent]).toEqual(['00000000037', '************8931', 'Y']);
    expect(info()).toBe(PROMPT);
    expect(messageText()).toBeNull();
    expect(acctFilter()).toHaveValue('');
    expect(cardFilter()).toHaveValue('');
    await waitFor(() => expect(acctFilter()).toHaveFocus());
    expect(screen.getByTestId('view-1')).toBeInTheDocument();
    expect(screen.getByTestId('update-1')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Show/ })).toBeNull();
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CCLI · COCRDLIC');
    const [body] = await Promise.all(apiCalls('/api/cards/search').map((r) => r.clone().json() as Promise<SearchBody>));
    expect(body).toMatchObject({ acctId: '', cardNum: '', size: 7 });
    expect(body?.start ?? body?.after ?? body?.before).toBeUndefined();
  });

  it('CRDL-S2 CRDL-S3: Next shows page 2 from the last key; Previous lands on page 1 with no message', async () => {
    const app = await openList();
    await next(app);
    await waitFor(() => expect(page()).toBe('2'));
    expect(rowCard(1)).toBe('************1600');
    expect(rows()).toHaveLength(7);
    const first = await pageKeys(app, { acctId: '', cardNum: '', size: 7 });
    const bodies = await searchBodies(app);
    expect(bodies[1]?.after).toBe(first.lastKey);
    await previous(app);
    await waitFor(() => expect(page()).toBe('1'));
    expect(rowCard(1)).toBe('************5740');
    expect(messageText()).toBeNull();
    expect(info()).toBe(PROMPT);
  });

  it('CRDL-S4: Previous on page 1 keeps the rows and says there are no previous pages, information line blank', async () => {
    const app = await openList();
    await previous(app);
    await waitFor(() => expect(messageText()).toBe('NO PREVIOUS PAGES TO DISPLAY'));
    expect(messageTone()).toBe('info');
    expect(page()).toBe('1');
    expect(rowCard(1)).toBe('************5740');
    expect(info()).toBe('');
  });

  it('CRDL-S5 CRDL-S6: page 8 holds the last card; Next again says there are no more pages', async () => {
    const app = await openList();
    for (let n = 2; n <= 8; n += 1) {
      await next(app);
      await waitFor(() => expect(page()).toBe(String(n)));
    }
    expect(rows()).toHaveLength(1);
    expect([rowAcct(1), rowCard(1)]).toEqual(['00000000040', '************6588']);
    expect(messageText()).toBe('NO MORE RECORDS TO SHOW');
    expect(info()).toBe(PROMPT);
    const calls = searches(app).length;
    await next(app);
    await waitFor(() => expect(messageText()).toBe('NO MORE PAGES TO DISPLAY'));
    expect(searches(app).length).toBe(calls + 1);
    expect(page()).toBe('8');
    expect(rowCard(1)).toBe('************6588');
    expect(info()).toBe('');
  });

  it('CRDL-S7: an account filter lists its one card with the end notice', async () => {
    const app = await openList();
    await app.user.type(acctFilter(), '00000000001{Enter}');
    await waitFor(() => expect(rows()).toHaveLength(1));
    expect([rowAcct(1), rowCard(1)]).toEqual(['00000000001', '************3697']);
    expect(page()).toBe('1');
    expect(messageText()).toBe('NO MORE RECORDS TO SHOW');
  });

  it('CRDL-S8: an account with no card gives no rows and the no-records notice, information line blank', async () => {
    const app = await openList();
    await app.user.type(acctFilter(), '99999999999{Enter}');
    await waitFor(() => expect(messageText()).toBe('NO RECORDS FOUND FOR THIS SEARCH CONDITION.'));
    expect(rows()).toHaveLength(0);
    expect(page()).toBe('1');
    expect(info()).toBe('');
  });

  it('CRDL-S9 @VAL-CRDL-003: a short account filter is refused in the page; rows kept, row actions disabled, no call', async () => {
    const app = await openList();
    await app.user.type(acctFilter(), '12345{Enter}');
    expect(messageText()).toBe('ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER');
    expect(acctFilter()).toHaveAttribute('aria-invalid', 'true');
    expect(cardFilter()).not.toHaveAttribute('aria-invalid', 'true');
    expect(acctFilter()).toHaveFocus();
    expect(rows()).toHaveLength(7);
    expect(screen.getByTestId('view-1')).toBeDisabled();
    expect(screen.getByTestId('update-1')).toBeDisabled();
    expect(info()).toBe('');
    expect(searches(app)).toHaveLength(1);
  });

  it('CRDL-S10: a card filter that is not 16 digits is refused with the card field red and focused', async () => {
    const app = await openList();
    await app.user.type(cardFilter(), 'ABC{Enter}');
    expect(messageText()).toBe('CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER');
    expect(cardFilter()).toHaveAttribute('aria-invalid', 'true');
    expect(acctFilter()).not.toHaveAttribute('aria-invalid', 'true');
    expect(cardFilter()).toHaveFocus();
    expect(searches(app)).toHaveLength(1);
  });

  it('CRDL-S11 @VAL-CRDL-007: both filters invalid give the account message, both fields red, focus on the account', async () => {
    const app = await openList();
    await app.user.type(cardFilter(), 'ABC');
    await app.user.type(acctFilter(), '12345{Enter}');
    expect(messageText()).toBe('ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER');
    expect(acctFilter()).toHaveAttribute('aria-invalid', 'true');
    expect(cardFilter()).toHaveAttribute('aria-invalid', 'true');
    expect(acctFilter()).toHaveFocus();
    expect(searches(app)).toHaveLength(1);
  });

  it('CRDL-S12 CRDL-S27: a card filter finds its card; the PAN travels in the POST body only', async () => {
    const app = await openList();
    await app.user.type(cardFilter(), '0683586198171516{Enter}');
    await waitFor(() => expect(rows()).toHaveLength(1));
    expect([rowAcct(1), rowCard(1)]).toEqual(['00000000027', '************1516']);
    expect(messageText()).toBe('NO MORE RECORDS TO SHOW');
    expect((await searchBodies(app))[1]?.cardNum).toBe('0683586198171516');
    expect(app.requests.some((r) => r.url.includes('0683586198171516'))).toBe(false);
    expect(app.router.state.location.pathname + app.router.state.location.search).toBe('/cards');
  });

  it('CRDL-S13 @FLOW-CRDL-009: View on row 1 opens the card by its ID and stores the account and card, no PAN', async () => {
    const app = await openList();
    await app.user.click(screen.getByTestId('view-1'));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/cards/1'));
    expect(readCurrentAccount()).toEqual({ acctId: '00000000050', custId: null, cardId: '1' });
    expect(JSON.stringify(sessionStorage)).not.toContain('0500024453765740');
  });

  it('CRDL-S14 @FLOW-CRDL-010: Update on row 2 opens the card update page for that card', async () => {
    const app = await openList();
    await app.user.click(screen.getByTestId('update-2'));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/cards/2/edit'));
    expect(readCurrentAccount()).toEqual({ acctId: '00000000027', custId: null, cardId: '2' });
  });

  it('CRDL-S13 CRDL-S14 CRDL-S17: with shortcuts on, S and U act as View and Update, lower case included', async () => {
    const app = await openList({ functionKeys: true });
    await app.user.type(sel(1), 'S{Enter}');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/cards/1'));
    await screen.findByRole('heading', { name: 'Credit card' });
    await app.router.navigate('/cards');
    await screen.findByTestId('card-row-7');
    await app.user.type(sel(2), 'u{Enter}');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/cards/2/edit'));
    await screen.findByRole('heading', { name: 'Update credit card' });
    await app.router.navigate('/cards');
    await screen.findByTestId('card-row-7');
    await app.user.type(sel(2), 's{Enter}');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/cards/2'));
  });

  it('CRDL-S15 @VAL-CRDL-004: two selections are refused, both rows red, focus on the first, no call', async () => {
    const app = await openList({ functionKeys: true });
    await app.user.type(sel(1), 'S');
    await app.user.type(sel(3), 'U{Enter}');
    expect(messageText()).toBe('PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE');
    expect(screen.getByTestId('card-row-1')).toHaveAttribute('data-invalid');
    expect(screen.getByTestId('card-row-3')).toHaveAttribute('data-invalid');
    expect(screen.getByTestId('card-row-2')).not.toHaveAttribute('data-invalid');
    expect(sel(1)).toHaveFocus();
    expect(app.router.state.location.pathname).toBe('/cards');
    expect(searches(app)).toHaveLength(1);
  });

  it('CRDL-S16 CRDL-S23 @VAL-CRDL-005: an unknown code on page 2 is refused; the page and its rows stay', async () => {
    const app = await openList({ functionKeys: true });
    await next(app);
    await waitFor(() => expect(page()).toBe('2'));
    const calls = searches(app).length;
    await app.user.type(sel(2), 'X{Enter}');
    expect(messageText()).toBe('INVALID ACTION CODE');
    expect(screen.getByTestId('card-row-2')).toHaveAttribute('data-invalid');
    expect(sel(2)).toHaveFocus();
    expect(info()).toBe(PROMPT);
    expect(page()).toBe('2');
    expect(rowCard(1)).toBe('************1600');
    expect(searches(app)).toHaveLength(calls);
  });

  it('CRDL-S40: Next and Previous run the code checks first and do not move the page', async () => {
    const app = await openList({ functionKeys: true });
    await app.user.type(sel(2), 'X');
    await next(app);
    expect(messageText()).toBe('INVALID ACTION CODE');
    expect(screen.getByTestId('card-row-2')).toHaveAttribute('data-invalid');
    expect(page()).toBe('1');
    await app.user.clear(sel(2));
    await app.user.type(sel(1), 'S');
    await app.user.type(sel(3), 'S');
    await previous(app);
    expect(messageText()).toBe('PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE');
    expect(screen.getByTestId('card-row-1')).toHaveAttribute('data-invalid');
    expect(screen.getByTestId('card-row-3')).toHaveAttribute('data-invalid');
    expect(searches(app)).toHaveLength(1);
  });

  it('CRDL-S31: a valid mark is cleared when the page moves; Enter then refreshes page 2 without navigating', async () => {
    const app = await openList({ functionKeys: true });
    await app.user.type(sel(2), 'S');
    await next(app);
    await waitFor(() => expect(page()).toBe('2'));
    for (let n = 1; n <= 7; n += 1) expect(sel(n)).toHaveValue('');
    await app.user.click(screen.getByTestId('submit'));
    await settle(app, 3);
    expect(app.router.state.location.pathname).toBe('/cards');
    expect(page()).toBe('2');
  });

  it('CRDL-S39 @FLOW-CRDL-011: Enter with unchanged filters refreshes from the first key, page unchanged', async () => {
    const app = await openList();
    await next(app);
    await waitFor(() => expect(page()).toBe('2'));
    await app.user.click(screen.getByTestId('submit'));
    await settle(app, 3);
    const [, pageTwo, refresh] = await searchBodies(app);
    const keys = await pageKeys(app, pageTwo as SearchBody);
    expect(refresh?.start).toBe(keys.firstKey);
    expect(page()).toBe('2');
    expect(rowCard(1)).toBe('************1600');
    expect(info()).toBe(PROMPT);
  });

  it('CRDL-S22 CRDL-S38: a changed filter starts again at page 1, from Enter and from Next', async () => {
    const app = await openList();
    await next(app);
    await waitFor(() => expect(page()).toBe('2'));
    await next(app);
    await waitFor(() => expect(page()).toBe('3'));
    await app.user.type(acctFilter(), '00000000002{Enter}');
    await waitFor(() => expect(page()).toBe('1'));
    expect(rows()).toHaveLength(1);
    expect([rowAcct(1), rowCard(1)]).toEqual(['00000000002', '************7330']);
    expect(messageText()).toBe('NO MORE RECORDS TO SHOW');
    await typeFilter(app, acctFilter(), '00000000027');
    await next(app);
    await waitFor(() => expect(rowAcct(1)).toBe('00000000027'));
    expect(page()).toBe('1');
    const bodies = await searchBodies(app);
    expect(bodies.at(-1)).toMatchObject({ acctId: '00000000027' });
    expect(bodies.at(-1)?.after ?? bodies.at(-1)?.start).toBeUndefined();
  });

  it('CRDL-S35: a failed read empties the table, keeps the page number and shows the read-error text', async () => {
    const app = await openList();
    await next(app);
    await waitFor(() => expect(page()).toBe('2'));
    app.settings.faults = ['card-read'];
    await next(app);
    await waitFor(() => expect(messageText()).toBe('Error reading card data'));
    expect(messageTone()).toBe('error');
    expect(rows()).toHaveLength(0);
    expect(page()).toBe('2');
  });

  it('CRDL-S18: Back goes to the main menu with the store unchanged', async () => {
    setCurrentAccount({ acctId: '00000000001', custId: '000000001', cardId: '49' });
    const app = await openList();
    await app.user.click(screen.getByRole('button', { name: /^Back/ }));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
    expect(readCurrentAccount()).toEqual({ acctId: '00000000001', custId: '000000001', cardId: '49' });
  });

  it('CRDL-S19 CRDL-S24 @VAL-CRDL-006: with shortcuts on, F5, F1, F12 and F4 give the invalid-key text and call nothing', async () => {
    const app = await openList({ functionKeys: true });
    await app.user.type(acctFilter(), '00000000001');
    for (const key of ['{F5}', '{F1}', '{F12}', '{F4}']) {
      await app.user.keyboard(key);
      expect(messageText()).toBe('Invalid key pressed. Please see below...');
    }
    expect(acctFilter()).toHaveValue('00000000001');
    expect(searches(app)).toHaveLength(1);
    await app.user.keyboard('{F8}');
    await waitFor(() => expect(rowAcct(1)).toBe('00000000001'));
  });

  it('CRDL-S25: an ADMIN gets the masked list with View only and a Show control on each card number', async () => {
    const app = await openList({ signedOnAs: 'ADMIN001' });
    expect(rowCard(1)).toBe('************5740');
    expect(screen.getByTestId('view-1')).toBeInTheDocument();
    expect(screen.queryByTestId('update-1')).toBeNull();
    expect(screen.getAllByRole('button', { name: /^Show card number ending/ })).toHaveLength(7);
    expect(app.apiCalls('/api/cards/1/unmask')).toHaveLength(0);
  });

  it('CRDL-S42: Show on row 1 calls unmaskCard with cardNum and shows the clear number', async () => {
    const app = await openList({ signedOnAs: 'ADMIN001' });
    await app.user.click(screen.getByRole('button', { name: 'Show card number ending 5740' }));
    await waitFor(() => expect(rowCard(1)).toBe('0500024453765740'));
    const bodies = await Promise.all(app.apiCalls('/api/cards/1/unmask').map((r) => r.clone().json()));
    expect(bodies).toEqual([{ fields: ['cardNum'] }]);
    expect(rowCard(2)).toBe('************1516');
  });

  it('@VAL-CRDL-005 for an ADMIN, U is an invalid code (ruling on CRDL question 1); S still views', async () => {
    const app = await openList({ signedOnAs: 'ADMIN001', functionKeys: true });
    await app.user.type(sel(2), 'U{Enter}');
    expect(messageText()).toBe('INVALID ACTION CODE');
    expect(screen.getByTestId('card-row-2')).toHaveAttribute('data-invalid');
    expect(app.router.state.location.pathname).toBe('/cards');
    await app.user.clear(sel(2));
    await app.user.type(sel(2), 'S{Enter}');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/cards/2'));
  });
});
