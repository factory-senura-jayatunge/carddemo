import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { readCurrentAccount } from '@/session/currentAccount';
import { renderApp } from '@/test/renderApp';
import { isUnchanged } from './CardUpdatePage';

const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;
const messageTone = () => screen.queryByTestId('message')?.getAttribute('data-tone') ?? null;
const info = () => screen.queryByTestId('info-line')?.textContent ?? null;
const input = (field: string) => screen.getByTestId(field);
const acct = () => input('acct-id');
const card = () => input('card-num');
const PROMPT = 'Please enter Account and Card Number';
const SHOWN = 'Details of selected card shown above';
const ERRORS = 'Update card details presented above.';
const VALIDATED = 'Changes validated.Press F5 to save';
const COMMITTED = 'Changes committed to database';
const UNSUCCESSFUL = 'Changes unsuccessful. Please try again';
const INVALID_KEY = 'Invalid key pressed. Please see below...';
const NOT_FOUND = 'Did not find cards for this search condition';

type App = Awaited<ReturnType<typeof renderApp>>;
type CardForm = { cardId: string; embossedName: string; activeStatus: string; expiryMonth: string; expiryYear: string; version: string };

async function openSearch(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await renderApp('/cards/edit', { signedOnAs: 'USER0001', ...options });
  await screen.findByRole('heading', { name: 'Update credit card' });
  return app;
}

async function find(app: App, acctId: string, cardNum: string) {
  await app.user.clear(acct());
  if (acctId) await app.user.type(acct(), acctId);
  await app.user.clear(card());
  if (cardNum) await app.user.type(card(), cardNum);
  await app.user.click(screen.getByRole('button', { name: 'Find card' }));
}

/** CRDU-S7: the sample card 0500024453765740 of account 00000000050 in Details. */
async function openCard(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await openSearch(options);
  await find(app, '00000000050', '0500024453765740');
  await screen.findByTestId('embossedName');
  // The page moves focus to the card name after the render that shows it; typing before that would land in the wrong field.
  await waitFor(() => expect(input('embossedName')).toHaveFocus());
  return app;
}

async function replace(app: App, field: string, value: string) {
  await app.user.clear(input(field));
  if (value) await app.user.type(input(field), value);
}

const review = async (app: App) => app.user.click(screen.getByRole('button', { name: 'Review changes' }));
const saveButton = () => screen.findByRole('button', { name: /^Save/ });

/** CRDU-S13: status N, reviewed, in Confirm. */
async function toConfirm(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await openCard(options);
  await replace(app, 'activeStatus', 'N');
  await review(app);
  await saveButton();
  return app;
}

const bodies = (app: App, path: string, method: string) =>
  Promise.all(app.requests.filter((r) => new URL(r.url).pathname === path && r.method === method).map((r) => r.clone().json() as Promise<Record<string, unknown>>));
const puts = (app: App) => app.requests.filter((r) => r.method === 'PUT');

/** Another session saves the card: reads its update form, then writes it with the version read. */
async function saveElsewhere(app: App, changes: Partial<CardForm>) {
  const csrf = /XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1] ?? '';
  const read = await app.server.handle(new Request(`${window.location.origin}/api/cards/1/update-form`));
  const stored = (await read.json()) as CardForm;
  const { embossedName, activeStatus, expiryMonth, expiryYear } = { ...stored, ...changes };
  const response = await app.server.handle(
    new Request(`${window.location.origin}/api/cards/1`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf, 'If-Match': `"${stored.version}"` },
      body: JSON.stringify({ changes: { embossedName, activeStatus, expiryMonth, expiryYear } }),
    }),
  );
  expect(response.status).toBe(200);
  return ((await response.json()) as { form: CardForm }).form;
}

describe('Credit card update (COCRDUPC)', () => {
  it('CRDU-S2: without a session it goes to sign-on', async () => {
    const { router } = await renderApp('/cards/edit');
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-on'));
  });

  it('CRDU-S27: an ADMIN cannot open card update', async () => {
    const { router, apiCalls } = await renderApp('/cards/1/edit', { signedOnAs: 'ADMIN001' });
    await waitFor(() => expect(router.state.location.pathname).not.toBe('/cards/1/edit'));
    expect(apiCalls('/api/cards/1/update-form')).toHaveLength(0);
  });

  it('CRDU-S1: menu option 5 opens Search with empty keys, the prompt, no message and focus on the account number', async () => {
    const { user, router } = await renderApp('/', { signedOnAs: 'USER0001' });
    await screen.findByRole('heading', { name: 'Main menu' });
    await user.type(screen.getByLabelText('Option number'), '5{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/cards/edit'));
    await screen.findByRole('heading', { name: 'Update credit card' });
    expect(acct()).toHaveValue('');
    expect(card()).toHaveValue('');
    expect(info()).toBe(PROMPT);
    expect(messageText()).toBeNull();
    expect(screen.queryByTestId('embossedName')).toBeNull();
    await waitFor(() => expect(acct()).toHaveFocus());
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CCUP · COCRDUPC');
  });

  it('CRDU-S3 CRDU-S4: empty keys are refused in the page with red fields and the * marker, no call', async () => {
    const app = await openSearch();
    await app.user.click(screen.getByRole('button', { name: 'Find card' }));
    expect(messageText()).toBe('No input received');
    expect(acct()).toHaveAttribute('aria-invalid', 'true');
    expect(card()).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('acct-id-marker')).toHaveTextContent('*');
    expect(screen.getByTestId('card-num-marker')).toHaveTextContent('*');
    await waitFor(() => expect(acct()).toHaveFocus());
    await find(app, '00000000050', '');
    expect(messageText()).toBe('Card number not provided');
    expect(card()).toHaveAttribute('aria-invalid', 'true');
    expect(acct()).not.toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('card-num-marker')).toBeInTheDocument();
    expect(screen.queryByTestId('acct-id-marker')).toBeNull();
    await waitFor(() => expect(card()).toHaveFocus());
    expect(app.apiCalls('/api/cards')).toHaveLength(0);
  });

  it('CRDU-S5 CRDU-S26: an account of 50 or 0 gives the shared format text, the typed value kept in red', async () => {
    const app = await openSearch();
    for (const typed of ['50', '0']) {
      await find(app, typed, '0500024453765740');
      expect(messageText()).toBe('Account number must be a non zero 11 digit number');
      expect(acct()).toHaveValue(typed);
      expect(acct()).toHaveAttribute('aria-invalid', 'true');
      await waitFor(() => expect(acct()).toHaveFocus());
    }
    expect(app.apiCalls('/api/cards')).toHaveLength(0);
  });

  it('CRDU-S6 CRDU-S8: an unknown card, or a card of another account, stays in Search with both keys red', async () => {
    const app = await openSearch();
    for (const [acctId, cardNum] of [
      ['00000000050', '9999999999999999'],
      ['00000000099', '0500024453765740'],
    ] as const) {
      await find(app, acctId, cardNum);
      await waitFor(() => expect(messageText()).toBe(NOT_FOUND));
      expect(acct()).toHaveAttribute('aria-invalid', 'true');
      expect(card()).toHaveAttribute('aria-invalid', 'true');
      expect(acct()).toHaveValue(acctId);
      expect(screen.queryByTestId('embossedName')).toBeNull();
      expect(screen.getByRole('button', { name: 'Find card' })).toBeInTheDocument();
      await waitFor(() => expect(acct()).toHaveFocus());
    }
  });

  it('CRDU-S7: valid keys show the details as stored, read-only keys and focus on the card name', async () => {
    const app = await openCard();
    expect(input('embossedName')).toHaveValue('Aniya Von');
    expect(input('activeStatus')).toHaveValue('Y');
    expect(input('expiryMonth')).toHaveValue('03');
    expect(input('expiryYear')).toHaveValue('2023');
    expect(card()).toHaveValue('************5740');
    expect(acct()).toHaveAttribute('readonly');
    expect(card()).toHaveAttribute('readonly');
    expect(info()).toBe(SHOWN);
    expect(messageText()).toBeNull();
    await waitFor(() => expect(input('embossedName')).toHaveFocus());
    const [lookup] = await bodies(app, '/api/cards/update-lookup', 'POST');
    expect(lookup).toEqual({ acctId: '00000000050', cardNum: '0500024453765740' });
    expect(readCurrentAccount()).toEqual({ acctId: '00000000050', custId: null, cardId: '1' });
  });

  it('CRDU-S9 CRDU-S36: no change, or only the case of the status, says so with no call; focus on the card name', async () => {
    const app = await openCard();
    await review(app);
    expect(messageText()).toBe('No change detected with respect to values fetched.');
    expect(input('embossedName')).not.toHaveAttribute('aria-invalid', 'true');
    await waitFor(() => expect(input('embossedName')).toHaveFocus());
    await replace(app, 'activeStatus', 'y');
    await review(app);
    expect(messageText()).toBe('No change detected with respect to values fetched.');
    expect(screen.getByRole('button', { name: 'Review changes' })).toBeInTheDocument();
    await waitFor(() => expect(input('embossedName')).toHaveFocus());
    expect(app.apiCalls('/api/cards/1/validate')).toHaveLength(0);
  });

  it('CRDU-S10: a case-only change of the name is a change and reaches Confirm', async () => {
    const app = await openCard();
    await replace(app, 'embossedName', 'aniya von');
    await review(app);
    await saveButton();
    expect(info()).toBe(VALIDATED);
  });

  it('CRDU-S11 CRDU-S35: a name with a digit or an apostrophe is refused in Errors; a * name is not provided and marked', async () => {
    const app = await openCard();
    for (const name of ['Aniya Von2', "O'Brien"]) {
      await replace(app, 'embossedName', name);
      await review(app);
      expect(messageText()).toBe('Card name can only contain alphabets and spaces');
      expect(input('embossedName')).toHaveAttribute('aria-invalid', 'true');
      expect(info()).toBe(ERRORS);
    }
    await replace(app, 'embossedName', '*');
    await review(app);
    expect(messageText()).toBe('Card name not provided');
    expect(screen.getByTestId('embossedName-marker')).toHaveTextContent('*');
    expect(app.apiCalls('/api/cards/1/validate')).toHaveLength(0);
  });

  it('CRDU-S12: status y, month 13 and year 2100 give the month text; month and year red, status not', async () => {
    const app = await openCard();
    await replace(app, 'activeStatus', 'y');
    await replace(app, 'expiryMonth', '13');
    await replace(app, 'expiryYear', '2100');
    await review(app);
    expect(messageText()).toBe('Card expiry month must be between 1 and 12');
    expect(input('expiryMonth')).toHaveAttribute('aria-invalid', 'true');
    expect(input('expiryYear')).toHaveAttribute('aria-invalid', 'true');
    expect(input('activeStatus')).not.toHaveAttribute('aria-invalid', 'true');
    await waitFor(() => expect(input('expiryMonth')).toHaveFocus());
  });

  it('CRDU-S32: a changed expiry in the past is refused with both parts red', async () => {
    const app = await openCard();
    await replace(app, 'expiryMonth', '12');
    await replace(app, 'expiryYear', '2024');
    await review(app);
    expect(messageText()).toBe('Card expiry date cannot be in the past');
    expect(input('expiryMonth')).toHaveAttribute('aria-invalid', 'true');
    expect(input('expiryYear')).toHaveAttribute('aria-invalid', 'true');
  });

  it('CRDU-S34: a one-digit month is sent with its leading zero', async () => {
    const app = await openCard();
    await replace(app, 'expiryMonth', '3');
    await replace(app, 'activeStatus', 'N');
    await review(app);
    await saveButton();
    const [body] = await bodies(app, '/api/cards/1/validate', 'POST');
    expect(body?.changes).toEqual({ embossedName: 'Aniya Von', activeStatus: 'N', expiryMonth: '03', expiryYear: '2023' });
  });

  it('CRDU-S13 CRDU-S14 CRDU-S16: Confirm, Save with If-Match, then Enter gives an empty Search', async () => {
    const app = await toConfirm({ functionKeys: true });
    expect(info()).toBe(VALIDATED);
    for (const field of ['embossedName', 'activeStatus', 'expiryMonth', 'expiryYear']) expect(input(field)).toHaveAttribute('readonly');
    expect(screen.getByLabelText('shortcut F5')).toBeInTheDocument();
    expect(screen.getByLabelText('shortcut F12')).toBeInTheDocument();
    expect(puts(app)).toHaveLength(0);
    const [validate] = await bodies(app, '/api/cards/1/validate', 'POST');
    await app.user.click(await saveButton());
    await waitFor(() => expect(info()).toBe(COMMITTED));
    expect(messageTone()).not.toBe('error');
    const put = puts(app)[0];
    expect(put?.headers.get('If-Match')).toBe(`"${String(validate?.version)}"`);
    expect(((await put?.clone().json()) as Record<string, unknown>).changes).toEqual({ embossedName: 'Aniya Von', activeStatus: 'N', expiryMonth: '03', expiryYear: '2023' });
    expect(input('activeStatus')).toHaveValue('N');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Update another card' })).toHaveFocus());
    await app.user.keyboard('{Enter}');
    await waitFor(() => expect(info()).toBe(PROMPT));
    expect(acct()).toHaveValue('');
    expect(card()).toHaveValue('');
    expect(messageText()).toBeNull();
    expect(screen.queryByTestId('embossedName')).toBeNull();
  });

  it('CRDU-S23 CRDU-S17: Update on the list opens Details without Search; after Save, Enter returns to the list', async () => {
    const app = await renderApp('/cards', { signedOnAs: 'USER0001' });
    await app.user.click(await screen.findByTestId('update-1'));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/cards/1/edit'));
    await screen.findByTestId('embossedName');
    expect(screen.queryByRole('button', { name: 'Find card' })).toBeNull();
    expect(input('embossedName')).toHaveValue('Aniya Von');
    expect(acct()).toHaveValue('00000000050');
    expect(info()).toBe(SHOWN);
    await waitFor(() => expect(input('embossedName')).toHaveFocus());
    await replace(app, 'activeStatus', 'N');
    await review(app);
    await app.user.click(await saveButton());
    await waitFor(() => expect(info()).toBe(COMMITTED));
    expect(screen.getByTestId('submit')).toHaveTextContent('Back to card list');
    await waitFor(() => expect(screen.getByTestId('submit')).toHaveFocus());
    await app.user.keyboard('{Enter}');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/cards'));
    await screen.findByTestId('card-row-1');
    expect(screen.getByTestId('page-number')).toHaveTextContent('1');
    expect(screen.getByTestId('row-active-1')).toHaveTextContent('N');
  });

  it('CRDU-S23: focus reaches the card name and then Save even when a frame runs before React shows them', async () => {
    // A frame callback that runs before the commit finds neither field yet; the page must not depend on frame timing.
    const frame = vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      queueMicrotask(() => callback(performance.now()));
      return 0;
    });
    try {
      const app = await renderApp('/cards/1/edit', { signedOnAs: 'USER0001' });
      await screen.findByTestId('embossedName');
      await waitFor(() => expect(input('embossedName')).toHaveFocus());
      await replace(app, 'activeStatus', 'N');
      await review(app);
      const save = await saveButton();
      await waitFor(() => expect(save).toHaveFocus());
    } finally {
      frame.mockRestore();
    }
  });

  it('CRDU-S18: a save after another session changed the status keeps the edits and marks only the changed status', async () => {
    // The contract's S18 has this session also set status N; save step 3 (change detection before the version check)
    // would answer that with NO_CHANGE, so this session changes the name instead.
    const app = await openCard();
    await replace(app, 'embossedName', 'Aniya Smith');
    await review(app);
    const save = await saveButton();
    await saveElsewhere(app, { activeStatus: 'N' });
    await app.user.click(save);
    await waitFor(() => expect(messageText()).toBe('Record changed by some one else. Please review'));
    expect(messageTone()).toBe('error');
    expect(info()).toBe(ERRORS);
    expect(input('embossedName')).toHaveValue('Aniya Smith');
    expect(input('activeStatus')).toHaveValue('Y');
    expect(input('embossedName')).not.toHaveAttribute('readonly');
    expect(screen.getByTestId('activeStatus-current')).toHaveTextContent('N');
    expect(screen.queryByTestId('embossedName-current')).toBeNull();
    expect(puts(app)).toHaveLength(1);
  });

  it('CRDU-S18 CRDU-S30: after a record change the next Enter validates against the new version, and Save succeeds', async () => {
    const app = await toConfirm();
    const saved = await saveElsewhere(app, { embossedName: 'Aniya Smith' });
    await app.user.click(await saveButton());
    await waitFor(() => expect(messageText()).toBe('Record changed by some one else. Please review'));
    expect(input('embossedName')).toHaveValue('Aniya Von');
    expect(screen.getByTestId('embossedName-current')).toHaveTextContent('Aniya Smith');
    expect(screen.queryByTestId('activeStatus-current')).toBeNull();
    await review(app);
    await saveButton();
    const sent = await bodies(app, '/api/cards/1/validate', 'POST');
    expect(sent.at(-1)?.version).toBe(saved.version);
    await app.user.click(await saveButton());
    await waitFor(() => expect(info()).toBe(COMMITTED));
    expect(puts(app).at(-1)?.headers.get('If-Match')).toBe(`"${saved.version}"`);
  });

  it('CRDU-S19 CRDU-S42: a lock failure keeps the values with the unsuccessful line; Save again succeeds', async () => {
    const app = await toConfirm({ settings: { faults: ['card-lock'] } });
    await app.user.click(await saveButton());
    await waitFor(() => expect(messageText()).toBe('Could not lock record for update'));
    expect(info()).toBe(UNSUCCESSFUL);
    expect(input('activeStatus')).toHaveValue('N');
    app.settings.faults = [];
    await app.user.click(await saveButton());
    await waitFor(() => expect(info()).toBe(COMMITTED));
    expect(puts(app)).toHaveLength(2);
  });

  it('CRDU-S37: a failed rewrite gives its text and keeps the values', async () => {
    const app = await toConfirm({ settings: { faults: ['card-update'] } });
    await app.user.click(await saveButton());
    await waitFor(() => expect(messageText()).toBe('Update of record failed'));
    expect(info()).toBe(UNSUCCESSFUL);
    expect(input('activeStatus')).toHaveValue('N');
  });

  it('CRDU-S31: in a batch window validate passes and the save is refused with the card-update text and a retry time', async () => {
    const app = await toConfirm({ settings: { batchWindowOpen: true } });
    await app.user.click(await saveButton());
    await waitFor(() => expect(messageText()).toBe('Card update is not available while the batch cycle runs. Try again later...'));
    expect(info()).toBe(UNSUCCESSFUL);
    expect(screen.getByText(/^Try again after/)).toBeInTheDocument();
    expect(input('activeStatus')).toHaveValue('N');
  });

  it('CRDU-S20: F12 in Confirm cancels: Details with the stored values, no write', async () => {
    const app = await toConfirm({ functionKeys: true });
    await app.user.keyboard('{F12}');
    await waitFor(() => expect(input('activeStatus')).toHaveValue('Y'));
    expect(info()).toBe(SHOWN);
    expect(messageText()).toBeNull();
    expect(app.apiCalls('/api/cards/1/update-form')).toHaveLength(1);
    expect(puts(app)).toHaveLength(0);
  });

  it('CRDU-S21 CRDU-S22 CRDU-S43 @VAL-CRDU-011: in Details F5, F7, F12 and F4 give the invalid-key text, input kept', async () => {
    const app = await openCard({ functionKeys: true });
    await replace(app, 'embossedName', 'Aniya Smith');
    for (const key of ['{F5}', '{F7}', '{F12}', '{F4}']) {
      await app.user.keyboard(key);
      expect(messageText()).toBe(INVALID_KEY);
      expect(input('embossedName')).toHaveValue('Aniya Smith');
    }
    expect(app.apiCalls('/api/cards/1')).toHaveLength(0);
    expect(puts(app)).toHaveLength(0);
  });

  it('CRDU-S24: Back from the menu path goes to the main menu, from the list path to the list', async () => {
    const app = await openCard();
    await app.user.click(screen.getByRole('button', { name: /^Back/ }));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
    await screen.findByRole('heading', { name: 'Main menu' });
    await app.router.navigate('/cards/1/edit');
    await screen.findByTestId('embossedName');
    await app.user.click(screen.getByRole('button', { name: /^Back to card list/ }));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/cards'));
  });

  it('CRDU-S41: leaving with an unsaved edit asks first; Keep editing stays with the edit, Leave goes without a write', async () => {
    const app = await openCard();
    await replace(app, 'embossedName', 'Aniya Smith');
    await app.user.click(screen.getByRole('button', { name: /^Back/ }));
    await screen.findByText('Leave without saving?');
    await app.user.click(screen.getByTestId('stay'));
    await waitFor(() => expect(screen.queryByText('Leave without saving?')).toBeNull());
    expect(app.router.state.location.pathname).toBe('/cards/edit');
    expect(input('embossedName')).toHaveValue('Aniya Smith');
    await app.user.click(screen.getByRole('button', { name: /^Back/ }));
    await app.user.click(await screen.findByTestId('leave'));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
    expect(puts(app)).toHaveLength(0);
  });

  it('@FLOW-CRDU-011 Cancel in Details drops the edits and reads the card again', async () => {
    const app = await openCard();
    await replace(app, 'embossedName', 'Aniya Smith');
    await app.user.click(screen.getByTestId('cancel'));
    await waitFor(() => expect(input('embossedName')).toHaveValue('Aniya Von'));
    expect(info()).toBe(SHOWN);
    expect(app.apiCalls('/api/cards/1/update-form')).toHaveLength(1);
  });
});

describe('CRDU no-change check (VAL-CRDU-010, D-51)', () => {
  const form = { cardId: '1', acctId: '00000000050', cardNum: '0500024453765740', embossedName: 'Aniya Von', activeStatus: 'Y', expiryMonth: '03', expiryYear: '2023', version: '0000000000000001', message: null, severity: null };
  const same = { embossedName: 'Aniya Von', activeStatus: 'Y', expiryMonth: '03', expiryYear: '2023' };

  it('strips as the service does: Java whitespace counts as nothing, a no-break space as a change', () => {
    expect(isUnchanged({ embossedName: 'Aniya Von\u001f\t', activeStatus: '\u001c y', expiryMonth: '03\u3000', expiryYear: ' 2023' }, form)).toBe(true);
    expect(isUnchanged({ ...same, embossedName: 'Aniya Von\u00a0' }, form)).toBe(false);
    expect(isUnchanged({ ...same, activeStatus: '\ufeffY' }, form)).toBe(false);
    expect(isUnchanged({ ...same, embossedName: ' Aniya Von' }, form)).toBe(false);
  });
});
