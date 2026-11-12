import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { readCurrentAccount } from '@/session/currentAccount';
import { renderApp } from '@/test/renderApp';

const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;
const messageTone = () => screen.queryByTestId('message')?.getAttribute('data-tone') ?? null;
const info = () => screen.getByTestId('info-line').textContent;
const acct = () => screen.getByTestId('acct-id');
const card = () => screen.getByTestId('card-num');
const acctMarker = () => screen.queryByTestId('acct-id-marker');
const cardMarker = () => screen.queryByTestId('card-num-marker');
const PROMPT = 'Please enter Account and Card Number';
const SHOWN = '   Displaying requested details';
const NOT_FOUND = 'Did not find cards for this search condition';
const INVALID_KEY = 'Invalid key pressed. Please see below...';

type App = Awaited<ReturnType<typeof renderApp>>;

async function openLookup(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await renderApp('/cards/lookup', { signedOnAs: 'USER0001', ...options });
  await screen.findByRole('heading', { name: 'Credit card', level: 1 });
  return app;
}

async function lookUp(app: App, acctId: string, cardNum: string) {
  await app.user.clear(acct());
  if (acctId) await app.user.type(acct(), acctId);
  await app.user.clear(card());
  if (cardNum) await app.user.type(card(), cardNum);
  await app.user.click(screen.getByTestId('submit'));
}

/** CRDV-S3: View on the row of 0500024453765740 in the card list. */
async function openFromList(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await renderApp('/cards', { signedOnAs: 'USER0001', ...options });
  await app.user.click(await screen.findByTestId('view-1'));
  await screen.findByTestId('card-panel');
  return app;
}

function expectSampleCard() {
  expect(screen.getByTestId('card-name')).toHaveTextContent('Aniya Von');
  expect(screen.getByTestId('card-active')).toHaveTextContent('Y');
  expect(screen.getByTestId('card-expiry-month')).toHaveTextContent('03');
  expect(screen.getByTestId('card-expiry-year')).toHaveTextContent('2023');
  expect(screen.getByTestId('card-num-shown')).toHaveTextContent('************5740');
  expect(screen.getByTestId('card-acct')).toHaveTextContent('00000000050');
  expect(info()).toBe(SHOWN);
  expect(messageText()).toBeNull();
}

describe('Credit card view (COCRDSLC)', () => {
  it('CRDV-S19: /cards/lookup and /cards/:cardId without a session go to sign-on', async () => {
    const { router } = await renderApp('/cards/1');
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-on'));
  });

  it('CRDV-S1 CRDV-S2: menu option 4 opens empty editable keys, the prompt, no message and focus on the account number', async () => {
    const { user, router } = await renderApp('/', { signedOnAs: 'USER0001' });
    await screen.findByRole('heading', { name: 'Main menu' });
    await user.type(screen.getByLabelText('Option number'), '4{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/cards/lookup'));
    await screen.findByRole('heading', { name: 'Credit card', level: 1 });
    expect(acct()).toHaveValue('');
    expect(card()).toHaveValue('');
    expect(acct()).not.toHaveAttribute('readonly');
    expect(card()).not.toHaveAttribute('readonly');
    expect(info()).toBe(PROMPT);
    expect(messageText()).toBeNull();
    // DEF-CRDV-012: the primary button is labelled View.
    expect(screen.getByTestId('submit')).toHaveTextContent('View');
    expect(screen.getByTestId('card-panel-empty')).toBeInTheDocument();
    await waitFor(() => expect(acct()).toHaveFocus());
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CCDL · COCRDSLC');
  });

  it('CRDV-S3: View on a list row reads the card by its ID and shows it with read-only keys', async () => {
    const app = await openFromList();
    expect(app.router.state.location.pathname).toBe('/cards/1');
    expect(app.apiCalls('/api/cards/1')).toHaveLength(1);
    expect(app.apiCalls('/api/cards/lookup')).toHaveLength(0);
    expect(acct()).toHaveValue('00000000050');
    expect(card()).toHaveValue('************5740');
    expect(acct()).toHaveAttribute('readonly');
    expect(card()).toHaveAttribute('readonly');
    expectSampleCard();
    expect(screen.queryByRole('button', { name: /^Show/ })).toBeNull();
    expect(readCurrentAccount()).toEqual({ acctId: '00000000050', custId: null, cardId: '1' });
  });

  it('CRDV-S4 @VAL-CRDV-008: Refresh reads the card again; F5 with shortcuts on gives the invalid-key text and keeps the card', async () => {
    const app = await openFromList({ functionKeys: true });
    await app.user.click(screen.getByTestId('submit'));
    await waitFor(() => expect(app.apiCalls('/api/cards/1')).toHaveLength(2));
    expectSampleCard();
    await app.user.keyboard('{F5}');
    expect(messageText()).toBe(INVALID_KEY);
    expect(screen.getByTestId('card-name')).toHaveTextContent('Aniya Von');
    expect(app.apiCalls('/api/cards/1')).toHaveLength(2);
  });

  it('CRDV-S5 CRDL-S21: Back from a card opened on the list returns to page 1 of the list with empty filters', async () => {
    const app = await openFromList();
    await app.user.click(screen.getByRole('button', { name: /^Back to card list/ }));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/cards'));
    await screen.findByTestId('card-row-7');
    expect(screen.getByTestId('page-number')).toHaveTextContent('1');
    expect(screen.getByTestId('acct-filter')).toHaveValue('');
    expect(screen.getByTestId('card-filter')).toHaveValue('');
  });

  it('CRDV-S6: Back from the lookup goes to the main menu with the role unchanged', async () => {
    const app = await openLookup();
    await app.user.click(screen.getByRole('button', { name: /^Back/ }));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
    const me = await app.server.handle(new Request(`${window.location.origin}/api/auth/me`));
    expect(((await me.json()) as { role: string }).role).toBe('USER');
  });

  it('CRDV-S7: both keys empty: No input received, both fields red with the * marker, focus on the account, no call', async () => {
    const app = await openLookup();
    await app.user.click(screen.getByTestId('submit'));
    expect(messageText()).toBe('No input received');
    expect(acct()).toHaveAttribute('aria-invalid', 'true');
    expect(card()).toHaveAttribute('aria-invalid', 'true');
    expect(acctMarker()).toHaveTextContent('*');
    expect(cardMarker()).toHaveTextContent('*');
    await waitFor(() => expect(acct()).toHaveFocus());
    expect(info()).toBe(PROMPT);
    expect(app.apiCalls('/api/cards')).toHaveLength(0);
  });

  it('CRDV-S8 CRDV-S9: a blank or all-zero account says it was not provided; the card is kept and not red', async () => {
    const app = await openLookup();
    for (const typed of ['', '00000000000']) {
      await lookUp(app, typed, '0500024453765740');
      expect(messageText()).toBe('Account number not provided');
      expect(acct()).toHaveAttribute('aria-invalid', 'true');
      expect(acctMarker()).toHaveTextContent('*');
      expect(card()).toHaveValue('0500024453765740');
      expect(card()).not.toHaveAttribute('aria-invalid', 'true');
      expect(cardMarker()).toBeNull();
      await waitFor(() => expect(acct()).toHaveFocus());
    }
    expect(app.apiCalls('/api/cards')).toHaveLength(0);
  });

  it('CRDV-S10 @VAL-CRDV-008: an invalid account keeps its typed value, red, with the * marker beside it', async () => {
    const app = await openLookup();
    await lookUp(app, '123', '0500024453765740');
    expect(messageText()).toBe('Account number must be a non zero 11 digit number');
    expect(acct()).toHaveValue('123');
    expect(acct()).toHaveAttribute('aria-invalid', 'true');
    expect(acctMarker()).toHaveTextContent('*');
    await waitFor(() => expect(acct()).toHaveFocus());
    expect(app.apiCalls('/api/cards')).toHaveLength(0);
  });

  it('CRDV-S11: an empty card number says so, card red with the * marker, focus on the card', async () => {
    const app = await openLookup();
    await lookUp(app, '00000000050', '');
    expect(messageText()).toBe('Card number not provided');
    expect(card()).toHaveAttribute('aria-invalid', 'true');
    expect(cardMarker()).toHaveTextContent('*');
    expect(acct()).not.toHaveAttribute('aria-invalid', 'true');
    await waitFor(() => expect(card()).toHaveFocus());
    expect(app.apiCalls('/api/cards')).toHaveLength(0);
  });

  it('CRDV-S12 @VAL-CRDV-008: an invalid card number keeps ABCD, red, with the * marker, focus on the card', async () => {
    const app = await openLookup();
    await lookUp(app, '00000000050', 'ABCD');
    expect(messageText()).toBe('Card number must be a non zero 16 digit number');
    expect(card()).toHaveValue('ABCD');
    expect(card()).toHaveAttribute('aria-invalid', 'true');
    expect(cardMarker()).toHaveTextContent('*');
    await waitFor(() => expect(card()).toHaveFocus());
    expect(app.apiCalls('/api/cards')).toHaveLength(0);
  });

  it('CRDV-S13 CRDV-S24: valid keys show the card with editable keys in normal colour; the PAN is sent in the body only', async () => {
    const app = await openLookup();
    await lookUp(app, '00000000050', '0500024453765740');
    await screen.findByTestId('card-panel');
    expectSampleCard();
    expect(acct()).toHaveValue('00000000050');
    expect(card()).toHaveValue('0500024453765740');
    expect(acct()).not.toHaveAttribute('readonly');
    expect(acct()).not.toHaveAttribute('aria-invalid', 'true');
    expect(card()).not.toHaveAttribute('aria-invalid', 'true');
    await waitFor(() => expect(acct()).toHaveFocus());
    const [body] = await Promise.all(app.apiCalls('/api/cards/lookup').map((r) => r.clone().json() as Promise<unknown>));
    expect(body).toEqual({ acctId: '00000000050', cardNum: '0500024453765740' });
    expect(app.requests.some((r) => r.url.includes('0500024453765740'))).toBe(false);
    expect(readCurrentAccount()).toEqual({ acctId: '00000000050', custId: null, cardId: '1' });
    expect(JSON.stringify(sessionStorage)).not.toContain('0500024453765740');
  });

  it('CRDV-S14 CRDV-S15: a card of another account and an unknown card give the same not-found text, both keys red and kept', async () => {
    const app = await openLookup();
    await lookUp(app, '00000000050', '0500024453765740');
    await screen.findByTestId('card-panel');
    await waitFor(() => expect(acct()).toHaveFocus());
    for (const [acctId, cardNum] of [
      ['00000000001', '0500024453765740'],
      ['00000000050', '9999999999999999'],
    ] as const) {
      await lookUp(app, acctId, cardNum);
      await waitFor(() => expect(messageText()).toBe(NOT_FOUND));
      expect(acct()).toHaveValue(acctId);
      expect(card()).toHaveValue(cardNum);
      expect(acct()).toHaveAttribute('aria-invalid', 'true');
      expect(card()).toHaveAttribute('aria-invalid', 'true');
      expect(screen.queryByTestId('card-panel')).toBeNull();
      expect(info()).toBe(PROMPT);
      await waitFor(() => expect(acct()).toHaveFocus());
    }
  });

  it('CRDV-S16: a failed card read on the lookup marks the account only and focuses it', async () => {
    const app = await openLookup({ settings: { faults: ['card-read'] } });
    await lookUp(app, '00000000050', '0500024453765740');
    await waitFor(() => expect(messageText()).toBe('Error reading card data'));
    expect(messageTone()).toBe('error');
    expect(acct()).toHaveAttribute('aria-invalid', 'true');
    expect(card()).not.toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByTestId('card-panel')).toBeNull();
    await waitFor(() => expect(acct()).toHaveFocus());
  });

  it('CRDV-S16 CRDV-S17: on the list path a read failure or an unknown card ID shows the message, empty details and Back', async () => {
    const failing = await renderApp('/cards/1', { signedOnAs: 'USER0001', settings: { faults: ['card-read'] } });
    await waitFor(() => expect(messageText()).toBe('Error reading card data'));
    expect(screen.queryByTestId('card-panel')).toBeNull();
    expect(screen.queryByTestId('submit')).toBeNull();
    failing.settings.faults = [];
    for (const cardId of ['0', 'abc', '999']) {
      await failing.router.navigate(`/cards/${cardId}`);
      await waitFor(() => expect(failing.apiCalls(`/api/cards/${cardId}`)).toHaveLength(1));
      await waitFor(() => expect(messageText()).toBe(NOT_FOUND));
      expect(screen.queryByTestId('card-panel')).toBeNull();
      expect(screen.getByRole('button', { name: /^Back to card list/ })).toBeInTheDocument();
    }
  });

  it('CRDV-S18 @DEF-CRDV-014: on the list path F4 is an invalid key; Enter shows the card again; Back returns to the list', async () => {
    const app = await openFromList({ functionKeys: true });
    await app.user.keyboard('{F4}');
    expect(messageText()).toBe(INVALID_KEY);
    expect(acct()).toHaveValue('00000000050');
    expect(screen.getByTestId('card-name')).toHaveTextContent('Aniya Von');
    await app.user.click(screen.getByTestId('submit'));
    await waitFor(() => expect(messageText()).toBeNull());
    expectSampleCard();
    await app.user.keyboard('{F3}');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/cards'));
  });

  it('@FLOW-CRDV-002 on the lookup F4 clears keys, details and message, focus on the account; F7 is an invalid key', async () => {
    const app = await openLookup({ functionKeys: true });
    await lookUp(app, '00000000050', '0500024453765740');
    await screen.findByTestId('card-panel');
    await app.user.keyboard('{F7}');
    expect(messageText()).toBe(INVALID_KEY);
    expect(screen.getByTestId('card-panel')).toBeInTheDocument();
    await app.user.keyboard('{F4}');
    expect(acct()).toHaveValue('');
    expect(card()).toHaveValue('');
    expect(messageText()).toBeNull();
    expect(screen.queryByTestId('card-panel')).toBeNull();
    expect(info()).toBe(PROMPT);
    await waitFor(() => expect(acct()).toHaveFocus());
  });

  it('CRDV-S20 CRDV-S21: an ADMIN from the admin lookup sees the card masked; Show calls unmaskCard and reveals the number', async () => {
    const app = await renderApp('/admin/lookup', { signedOnAs: 'ADMIN001' });
    await app.user.click(await screen.findByTestId('lookup-card'));
    await screen.findByRole('heading', { name: 'Credit card', level: 1 });
    await lookUp(app, '00000000050', '0500024453765740');
    await screen.findByTestId('card-panel');
    expect(screen.getByTestId('card-num-shown')).toHaveTextContent('************5740');
    await app.user.click(screen.getByRole('button', { name: 'Show card number' }));
    await waitFor(() => expect(screen.getByTestId('card-num-shown')).toHaveTextContent('0500024453765740'));
    const bodies = await Promise.all(app.apiCalls('/api/cards/1/unmask').map((r) => r.clone().json()));
    expect(bodies).toEqual([{ fields: ['cardNum'] }]);
    await app.user.click(screen.getByRole('button', { name: /^Back/ }));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/admin/lookup'));
  });

  it('CRDV-S22: a failed audit insert on unmask shows its text and keeps the number masked', async () => {
    const app = await openFromList({ signedOnAs: 'ADMIN001' });
    app.settings.faults = ['audit'];
    await app.user.click(screen.getByRole('button', { name: 'Show card number' }));
    await waitFor(() => expect(messageText()).toBe('Unable to record the unmask request'));
    expect(screen.getByTestId('card-num-shown')).toHaveTextContent('************5740');
  });
});
