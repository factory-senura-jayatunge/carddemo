import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { encodeUserKey } from '@/lib/userKey';
import { renderApp } from '@/test/renderApp';
import { isUnchanged, pageSaysNoChange } from './UserEditPage';
import { EMPTY_USER } from './UserFormFields';

const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;
const messageTone = () => screen.queryByTestId('message')?.getAttribute('data-tone') ?? null;
const rowIds = () => screen.queryAllByTestId(/^row-id-\d+$/).map((cell) => cell.textContent);
const pageNumber = () => screen.getByTestId('page-number').textContent;
const EMOJI_33 = '\u{1F600}'.repeat(33);

type App = Awaited<ReturnType<typeof renderApp>>;

/** A call to the mock outside the page, in the signed-on session (another admin's change). */
async function direct(app: App, method: string, path: string, body?: unknown, headers: Record<string, string> = {}) {
  const csrf = /XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1] ?? '';
  return app.server.handle(
    new Request(`${window.location.origin}${path}`, {
      method,
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf, ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  );
}
const stored = async (app: App, id: string) =>
  (await (await direct(app, 'GET', `/api/users/key/${encodeUserKey(id)}`)).json()) as {
    version: string;
    firstName: string;
    lastName: string;
    userType: string;
  };

async function openList(path = '/admin/users', functionKeys = false) {
  const app = await renderApp(path, { signedOnAs: 'ADMIN001', functionKeys });
  await screen.findByTestId('row-id-10');
  return app;
}

async function openEdit(id: string) {
  const app = await renderApp(`/admin/users/${encodeUserKey(id)}/edit`, { signedOnAs: 'ADMIN001' });
  await screen.findByTestId('locked-id');
  return app;
}

describe('User list (COUSR00C)', () => {
  it('@BR-USRL-001 @BR-USRL-006 shows ten users a page in user ID order, and pages forward and back', async () => {
    const { user } = await openList();
    expect(rowIds()).toEqual(['ADMIN001', 'ADMIN002', 'ADMIN003', 'ADMIN004', 'ADMIN005', 'CHANGE01', 'TEST0001', 'TYPEX001', 'USER0001', 'USER0002']);
    expect(pageNumber()).toBe('1');
    expect(screen.getByTestId('row-name-1')).toHaveTextContent('MARGARET GOLD');
    expect(screen.getByTestId('row-type-1')).toHaveTextContent('Administrator');
    expect(screen.getByTestId('row-type-9')).toHaveTextContent('User');
    expect(screen.getByTestId('row-type-8')).toHaveTextContent('Type X');
    expect(within(screen.getByTestId('user-row-1')).getByText('You')).toBeInTheDocument();
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CU00 · COUSR00C');
    await user.click(screen.getByTestId('next'));
    await waitFor(() => expect(rowIds()).toEqual(['USER0003', 'USER0004', 'USER0005']));
    expect(pageNumber()).toBe('2');
    await user.click(screen.getByTestId('previous'));
    await waitFor(() => expect(rowIds()[0]).toBe('ADMIN001'));
    expect(pageNumber()).toBe('1');
  });

  it('@VAL-USRL-003 @VAL-USRL-004 Previous on page 1 and Next on the last page ask the service nothing', async () => {
    const app = await openList();
    const calls = () => app.apiCalls('/api/users').length;
    const before = calls();
    await app.user.click(screen.getByTestId('previous'));
    expect(calls()).toBe(before);
    await app.user.click(screen.getByTestId('next'));
    await screen.findByText('USER0005');
    const atBottom = calls();
    await app.user.click(screen.getByTestId('next'));
    expect(calls()).toBe(atBottom);
    expect(rowIds()).toEqual(['USER0003', 'USER0004', 'USER0005']);
  });

  it('@Q-USRL-001 a search lists the user IDs that start with it, letters in either case', async () => {
    const app = await openList();
    await app.user.type(screen.getByTestId('user-search'), 'user000{Enter}');
    await waitFor(() => expect(rowIds()).toEqual(['USER0001', 'USER0002', 'USER0003', 'USER0004', 'USER0005']));
    expect(pageNumber()).toBe('1');
    const last = app.apiCalls('/api/users').at(-1)!;
    expect(new URL(last.url).searchParams.get('startKey')).toBe(encodeUserKey('user000'));
  });

  it('@Q-USRL-001 a padded search goes as typed and the service strips the pad', async () => {
    const app = await openList();
    const search = screen.getByTestId('user-search');
    expect(search).not.toHaveAttribute('maxlength');
    await app.user.type(search, '  user000   {Enter}');
    await waitFor(() => expect(rowIds()).toEqual(['USER0001', 'USER0002', 'USER0003', 'USER0004', 'USER0005']));
    const last = app.apiCalls('/api/users').at(-1)!;
    expect(new URL(last.url).searchParams.get('startKey')).toBe(encodeUserKey('  user000   '));
  });

  it('@BR-USRL-003 @BR-USRU-002 Update on a row opens that user, and Back returns to the list', async () => {
    const { user, router } = await openList();
    await user.click(screen.getByTestId('update-10'));
    await waitFor(() => expect(router.state.location.pathname).toBe(`/admin/users/${encodeUserKey('USER0002')}/edit`));
    expect(await screen.findByTestId('locked-id')).toHaveTextContent('USER0002');
    await user.click(screen.getByRole('button', { name: /^Back/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/users'));
  });

  it('@BR-USRL-004 @BR-USRD-002 Delete on a row opens the confirmation for that user', async () => {
    const { user, router } = await openList();
    await user.click(screen.getByTestId('delete-9'));
    await waitFor(() => expect(router.state.location.pathname).toBe(`/admin/users/${encodeUserKey('USER0001')}/delete`));
    expect(await screen.findByTestId('delete-id')).toHaveTextContent('USER0001');
    expect(screen.getByTestId('confirm-bar')).toHaveTextContent('Delete USER0001 (LAWRENCE THOMAS)?');
  });

  it('@VAL-USRL-003 @VAL-USRL-004 Previous on page 1 and Next at the bottom say so', async () => {
    const app = await openList();
    await app.user.click(screen.getByTestId('previous'));
    expect(messageText()).toBe('You are already at the top of the page...');
    await app.user.click(screen.getByTestId('next'));
    await screen.findByText('USER0005');
    await app.user.click(screen.getByTestId('next'));
    expect(messageText()).toBe('You are already at the bottom of the page...');
    expect(messageTone()).toBe('info');
  });

  it('@BR-USRL-003 @BR-USRL-004 without the function keys there is no Sel column', async () => {
    await openList();
    expect(screen.queryByTestId('sel-1')).toBeNull();
    expect(screen.getByTestId('info-line')).toHaveTextContent('Choose Update or Delete on a user\u2019s row.');
  });

  it('@BR-USRL-003 @BR-USRL-005 with the function keys on, u in Sel opens that user for update', async () => {
    const { user, router } = await openList('/admin/users', true);
    await user.type(screen.getByTestId('sel-10'), 'u{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe(`/admin/users/${encodeUserKey('USER0002')}/edit`));
  });

  it('@BR-USRL-004 @BR-USRL-005 with the function keys on, D in Sel opens the delete confirmation; the first mark wins', async () => {
    const { user, router } = await openList('/admin/users', true);
    await user.type(screen.getByTestId('sel-9'), 'D');
    await user.type(screen.getByTestId('sel-10'), 'U');
    await user.click(screen.getByTestId('submit'));
    await waitFor(() => expect(router.state.location.pathname).toBe(`/admin/users/${encodeUserKey('USER0001')}/delete`));
  });

  it('@VAL-USRL-001 @DEF-USRL-004 an invalid first mark is named, its row is marked and nothing is fetched', async () => {
    const app = await openList('/admin/users', true);
    const before = app.apiCalls('/api/users').length;
    await app.user.type(screen.getByTestId('sel-2'), 'x');
    await app.user.type(screen.getByTestId('sel-3'), 'u');
    await app.user.click(screen.getByTestId('submit'));
    expect(messageText()).toBe('Invalid selection. Valid values are U and D');
    expect(messageTone()).toBe('error');
    expect(screen.getByTestId('sel-2')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('sel-2')).toHaveFocus();
    expect(screen.getByTestId('sel-2')).toHaveValue('x');
    expect(screen.getByTestId('sel-3')).not.toHaveAttribute('aria-invalid', 'true');
    expect(app.apiCalls('/api/users')).toHaveLength(before);
    expect(app.router.state.location.pathname).toBe('/admin/users');
  });

  it('@DEF-USRL-012 marks are cleared when another page is shown', async () => {
    const app = await openList('/admin/users', true);
    await app.user.type(screen.getByTestId('sel-2'), 'D');
    await app.user.click(screen.getByTestId('next'));
    await waitFor(() => expect(rowIds()).toEqual(['USER0003', 'USER0004', 'USER0005']));
    await app.user.click(screen.getByTestId('previous'));
    await waitFor(() => expect(rowIds()[0]).toBe('ADMIN001'));
    for (let n = 1; n <= 10; n += 1) expect(screen.getByTestId(`sel-${n}`)).toHaveValue('');
    await app.user.click(screen.getByTestId('submit'));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/admin/users'));
    expect(screen.queryByTestId('confirm-bar')).toBeNull();
  });

  it('@D-70 admin options 1 to 4 open the user pages; 3 and 4 say what to choose', async () => {
    const { user, router } = await renderApp('/admin', { signedOnAs: 'ADMIN001' });
    await screen.findByRole('heading', { name: 'Admin menu' });
    await user.click(screen.getByTestId('option-04'));
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/users'));
    expect(await screen.findByText('Find the user you want to delete, then choose Delete on their row.')).toBeInTheDocument();
  });

  it('@D-02 a USER who opens the user list goes to the main menu', async () => {
    const { router } = await renderApp('/admin/users', { signedOnAs: 'USER0001' });
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
  });
});

describe('Add user (COUSR01C)', () => {
  async function openAdd() {
    const app = await renderApp('/admin/users/new', { signedOnAs: 'ADMIN001' });
    await screen.findByRole('heading', { name: 'Add user' });
    return app;
  }
  const fill = async (app: App, values: Record<string, string>) => {
    for (const [testId, value] of Object.entries(values)) await app.user.type(screen.getByTestId(testId), value);
  };

  it('@VAL-USRA-001 the first missing field is named and gets the focus', async () => {
    const app = await openAdd();
    expect(screen.getByTestId('firstName')).toHaveFocus();
    await app.user.click(screen.getByTestId('submit'));
    await waitFor(() => expect(messageText()).toBe('First Name can NOT be empty...'));
    expect(screen.getByTestId('firstName')).toHaveAttribute('aria-invalid', 'true');
    await fill(app, { firstName: 'Ada', lastName: 'Lovelace', userId: 'ADA00001', password: 'Analytic1' });
    await app.user.click(screen.getByTestId('submit'));
    await waitFor(() => expect(messageText()).toBe('User Type can NOT be empty...'));
    expect(screen.getByRole('radio', { name: 'Administrator' })).toHaveFocus();
  });

  it('@BR-USRA-001 @BR-USRA-002 adds the user, clears the form and puts the cursor back on first name', async () => {
    const app = await openAdd();
    await fill(app, { firstName: 'Ada', lastName: 'Lovelace', userId: 'ada00001', password: 'Analytic1' });
    await app.user.click(screen.getByRole('radio', { name: 'User' }));
    await app.user.click(screen.getByTestId('submit'));
    await waitFor(() => expect(messageText()).toBe('User ADA00001 has been added ...'));
    expect(messageTone()).toBe('success');
    for (const id of ['firstName', 'lastName', 'userId', 'password']) expect(screen.getByTestId(id)).toHaveValue('');
    expect(screen.getByRole('radio', { name: 'User' })).not.toBeChecked();
    await waitFor(() => expect(screen.getByTestId('firstName')).toHaveFocus());
    const body = (await app.apiCalls('/api/users').at(-1)!.clone().json()) as Record<string, string>;
    expect(body).toEqual({ firstName: 'Ada', lastName: 'Lovelace', userId: 'ada00001', password: 'Analytic1', userType: 'U' });
    expect(await stored(app, 'ADA00001')).toMatchObject({ firstName: 'Ada', lastName: 'Lovelace', userType: 'U' });
  });

  it('@BR-USRA-001 @D-SEC-07 a padded user ID and a 33-emoji password go to the service as typed', async () => {
    const app = await openAdd();
    expect(screen.getByTestId('userId')).not.toHaveAttribute('maxlength');
    expect(screen.getByTestId('password')).not.toHaveAttribute('maxlength');
    await fill(app, { firstName: 'Ada', lastName: 'Lovelace', userId: '  ada00002  ' });
    await app.user.click(screen.getByTestId('password'));
    await app.user.paste(EMOJI_33);
    expect(screen.getByTestId('password')).toHaveValue(EMOJI_33);
    await app.user.click(screen.getByRole('radio', { name: 'User' }));
    await app.user.click(screen.getByTestId('submit'));
    await waitFor(() => expect(messageText()).toBe('User ADA00002 has been added ...'));
    const body = (await app.apiCalls('/api/users').at(-1)!.clone().json()) as Record<string, string>;
    expect(body).toEqual({ firstName: 'Ada', lastName: 'Lovelace', userId: '  ada00002  ', password: EMOJI_33, userType: 'U' });
    expect(await stored(app, 'ADA00002')).toMatchObject({ firstName: 'Ada', lastName: 'Lovelace', userType: 'U' });
  });

  it('@VAL-USRA-001 the page checks the fields with the service\'s rules before it sends anything', async () => {
    const app = await openAdd();
    await fill(app, { firstName: 'Ada', lastName: 'Lovelace', userId: 'ada00001', password: 'ADA00001' });
    await app.user.click(screen.getByRole('radio', { name: 'User' }));
    await app.user.click(screen.getByTestId('submit'));
    expect(messageText()).toBe('New Password must not be the User ID ...');
    expect(screen.getByTestId('password')).toHaveFocus();
    expect(screen.getByTestId('password')).toHaveAttribute('aria-invalid', 'true');
    await app.user.clear(screen.getByTestId('firstName'));
    await app.user.type(screen.getByTestId('firstName'), 'J\u0100');
    await app.user.click(screen.getByTestId('submit'));
    expect(messageText()).toBe('First Name contains a character that is not supported.');
    expect(screen.getByTestId('firstName')).toHaveFocus();
    expect(app.requests.some((r) => r.method === 'POST')).toBe(false);
  });

  it('@VAL-USRA-002 an existing user ID is refused and the form keeps what was typed', async () => {
    const app = await openAdd();
    await fill(app, { firstName: 'Ada', lastName: 'Lovelace', userId: 'USER0001', password: 'Analytic1' });
    await app.user.click(screen.getByRole('radio', { name: 'Administrator' }));
    await app.user.click(screen.getByTestId('submit'));
    await waitFor(() => expect(messageText()).toBe('User ID already exist...'));
    expect(screen.getByTestId('userId')).toHaveValue('USER0001');
  });

  it('@D-BAT-05 in the batch window the refusal says when to try again', async () => {
    const app = await openAdd();
    app.settings.batchWindowOpen = true;
    await fill(app, { firstName: 'Ada', lastName: 'Lovelace', userId: 'ADA00001', password: 'Analytic1' });
    await app.user.click(screen.getByRole('radio', { name: 'User' }));
    await app.user.click(screen.getByTestId('submit'));
    await waitFor(() => expect(messageText()).toBe('User changes are not available during the batch cycle. Try again later...'));
    expect(screen.getByTestId('message')).toHaveTextContent(/Try again after \d/);
  });

  it('@VAL-USRA-003 F4 clears the form when the function keys are on', async () => {
    const app = await renderApp('/admin/users/new', { signedOnAs: 'ADMIN001', functionKeys: true });
    await screen.findByRole('heading', { name: 'Add user' });
    await app.user.type(screen.getByTestId('lastName'), 'Lovelace');
    await app.user.keyboard('{F4}');
    expect(screen.getByTestId('lastName')).toHaveValue('');
  });
});

describe('Update user (COUSR02C)', () => {
  it('@BR-USRU-002 shows the user with its ID locked and the password hidden', async () => {
    await openEdit('ADMIN002');
    expect(screen.getByTestId('locked-id')).toHaveTextContent('ADMIN002');
    expect(screen.getByTestId('firstName')).toHaveValue('RUSSELL');
    expect(screen.getByRole('radio', { name: 'Administrator' })).toBeChecked();
    expect(screen.queryByTestId('password')).toBeNull();
    expect(screen.getByTestId('password-toggle')).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CU02 · COUSR02C');
  });

  it('@BR-USRU-004 saving with nothing changed writes nothing and asks for a change', async () => {
    const app = await openEdit('USER0002');
    const before = (await stored(app, 'USER0002')).version;
    await app.user.click(screen.getByTestId('save'));
    await waitFor(() => expect(messageText()).toBe('Please modify to update ...'));
    expect((await stored(app, 'USER0002')).version).toBe(before);
    expect(app.requests.some((r) => r.method === 'PUT')).toBe(false);
    expect(screen.getByTestId('firstName')).toHaveFocus();
  });

  it('@BR-USRU-004 @VAL-USRU-002 "no change" compares as the service and is said by the page only after its field checks would pass', () => {
    const user = { userId: 'USER0002', firstName: 'AJITH', lastName: 'KUMAR', userType: 'U', version: '0000000000000001' } as const;
    const values = { ...EMPTY_USER, userId: 'USER0002', firstName: 'AJITH', lastName: 'KUMAR', userType: 'U' };
    // Trailing U+0020 is the pad on both sides; any other trailing whitespace is a change.
    expect(pageSaysNoChange({ ...values, firstName: 'AJITH   ', lastName: 'KUMAR ' }, { ...user, firstName: 'AJITH  ' })).toBe(true);
    expect(isUnchanged({ ...values, firstName: 'AJITH\u00a0' }, user)).toBe(false);
    expect(isUnchanged({ ...values, firstName: 'AJITH\t' }, user)).toBe(false);
    expect(isUnchanged({ ...values, firstName: ' AJITH' }, user)).toBe(false);
    expect(isUnchanged({ ...values, firstName: 'ajith' }, user)).toBe(false);
    // Only spaces and NUL leave the password unset; anything else is a change.
    expect(pageSaysNoChange({ ...values, password: ' \0 ' }, user)).toBe(true);
    expect(isUnchanged({ ...values, password: '\t' }, user)).toBe(false);
    expect(isUnchanged({ ...values, userType: 'A' }, user)).toBe(false);
    // Unchanged, but the service refuses the field before it compares: the service answers.
    for (const stored of ['', '   ', '\t', '\0', '\u3000', 'J\u0100', 'A'.repeat(21)]) {
      const odd = { ...user, firstName: stored };
      expect(isUnchanged({ ...values, firstName: stored }, odd)).toBe(true);
      expect(pageSaysNoChange({ ...values, firstName: stored }, odd)).toBe(false);
      expect(pageSaysNoChange({ ...values, lastName: stored }, { ...user, lastName: stored })).toBe(false);
    }
    expect(pageSaysNoChange({ ...values, firstName: 'A'.repeat(20) + '  ' }, { ...user, firstName: 'A'.repeat(20) })).toBe(true);
    expect(pageSaysNoChange({ ...values, userType: 'X' }, { ...user, userType: 'X' })).toBe(false);
  });

  it('@BR-USRU-001 the PF5 prompt shows only with the function keys on', async () => {
    await openEdit('USER0002');
    expect(screen.queryByTestId('info-line')).toBeNull();
  });

  it('@BR-USRU-001 with the function keys on the page prompts for PF5', async () => {
    await renderApp(`/admin/users/${encodeUserKey('USER0002')}/edit`, { signedOnAs: 'ADMIN001', functionKeys: true });
    await screen.findByTestId('locked-id');
    expect(screen.getByTestId('info-line')).toHaveTextContent('Press PF5 key to save your updates ...');
  });

  it('@BR-USRU-003 saves a changed name with If-Match and shows the saved user', async () => {
    const app = await openEdit('USER0002');
    const { version } = await stored(app, 'USER0002');
    await app.user.clear(screen.getByTestId('lastName'));
    await app.user.type(screen.getByTestId('lastName'), 'KUMARAN');
    expect(screen.getByTestId('discard')).toBeEnabled();
    await app.user.click(screen.getByTestId('save'));
    await waitFor(() => expect(messageText()).toBe('User USER0002 has been updated ...'));
    const put = app.requests.find((r) => r.method === 'PUT')!;
    expect(put.headers.get('If-Match')).toBe(`"${version}"`);
    expect(await put.clone().json()).toEqual({ firstName: 'AJITH', lastName: 'KUMARAN', userType: 'U' });
    expect(screen.getByTestId('lastName')).toHaveValue('KUMARAN');
    expect(screen.getByTestId('discard')).toBeDisabled();
  });

  it('@VAL-USRU-002 a cleared first name is refused and keeps the focus', async () => {
    const app = await openEdit('USER0002');
    await app.user.clear(screen.getByTestId('firstName'));
    await app.user.click(screen.getByTestId('save'));
    await waitFor(() => expect(messageText()).toBe('First Name can NOT be empty...'));
    expect(screen.getByTestId('firstName')).toHaveFocus();
  });

  it('@VAL-USRU-002 a new password the rules refuse is named on the password, without a PUT', async () => {
    const app = await openEdit('USER0003');
    await app.user.click(screen.getByTestId('password-toggle'));
    await waitFor(() => expect(screen.getByTestId('password')).toHaveFocus());
    await app.user.type(screen.getByTestId('password'), 'Short1');
    await app.user.click(screen.getByTestId('save'));
    expect(messageText()).toBe('New Password must be 8 to 64 characters ...');
    expect(screen.getByTestId('password')).toHaveFocus();
    expect(app.requests.some((r) => r.method === 'PUT')).toBe(false);
  });

  it('@DEF-USRU-011 a new password is sent only when set', async () => {
    const app = await openEdit('USER0003');
    await app.user.click(screen.getByTestId('password-toggle'));
    await waitFor(() => expect(screen.getByTestId('password')).toHaveFocus());
    await app.user.type(screen.getByTestId('password'), 'Lauritz99');
    await app.user.click(screen.getByTestId('save'));
    await waitFor(() => expect(messageText()).toBe('User USER0003 has been updated ...'));
    const put = app.requests.find((r) => r.method === 'PUT')!;
    expect(await put.clone().json()).toMatchObject({ password: 'Lauritz99' });
    expect(screen.queryByTestId('password')).toBeNull();
  });

  it('@D-SEC-07 a 33-emoji new password is sent as typed', async () => {
    const app = await openEdit('USER0003');
    await app.user.click(screen.getByTestId('password-toggle'));
    await waitFor(() => expect(screen.getByTestId('password')).toHaveFocus());
    expect(screen.getByTestId('password')).not.toHaveAttribute('maxlength');
    await app.user.paste(EMOJI_33);
    expect(screen.getByTestId('password')).toHaveValue(EMOJI_33);
    await app.user.click(screen.getByTestId('save'));
    await waitFor(() => expect(messageText()).toBe('User USER0003 has been updated ...'));
    const put = app.requests.find((r) => r.method === 'PUT')!;
    expect(await put.clone().json()).toMatchObject({ password: EMOJI_33 });
  });

  it('@D-17 a change saved meanwhile is shown next to the edits, which stay', async () => {
    const app = await openEdit('USER0004');
    const { version } = await stored(app, 'USER0004');
    const other = await direct(
      app,
      'PUT',
      `/api/users/key/${encodeUserKey('USER0004')}`,
      { firstName: 'AVERARDO', lastName: 'MAZZINI', userType: 'U' },
      { 'If-Match': `"${version}"` },
    );
    expect(other.status).toBe(200);
    await app.user.clear(screen.getByTestId('firstName'));
    await app.user.type(screen.getByTestId('firstName'), 'AVE');
    await app.user.click(screen.getByTestId('save'));
    await screen.findByTestId('lastName-current');
    expect(screen.getByTestId('lastName-current')).toHaveTextContent('Now saved as MAZZINI');
    expect(screen.getByTestId('firstName')).toHaveValue('AVE');
    expect(messageTone()).toBe('error');
    await app.user.click(screen.getByTestId('save'));
    await waitFor(() => expect(messageText()).toBe('User USER0004 has been updated ...'));
    expect(await stored(app, 'USER0004')).toMatchObject({ firstName: 'AVE', lastName: 'MAZZI' });
  });

  it('@VAL-USRU-003 an unknown user shows the refusal and a way back', async () => {
    await renderApp(`/admin/users/${encodeUserKey('NOBODY01')}/edit`, { signedOnAs: 'ADMIN001' });
    await screen.findByTestId('missing-user');
    expect(messageText()).toBe('User ID NOT found...');
    expect(screen.queryByTestId('retry')).toBeNull();
  });

  it('@VAL-USRU-003 a key that does not decode asks the service nothing', async () => {
    const app = await renderApp('/admin/users/not+a+key/edit', { signedOnAs: 'ADMIN001' });
    await screen.findByTestId('missing-user');
    expect(messageText()).toBe('User ID NOT found...');
    expect(app.apiCalls('/api/users')).toHaveLength(0);
  });

  it('@D-53 a stored type the form cannot keep is named so the admin chooses', async () => {
    await openEdit('TYPEX001');
    expect(screen.getByTestId('stored-type')).toHaveTextContent('Stored type X, which cannot sign on.');
    expect(screen.getByRole('radio', { name: 'Administrator' })).not.toBeChecked();
    expect(screen.getByRole('radio', { name: 'User' })).not.toBeChecked();
  });
});

describe('Delete user (COUSR03C)', () => {
  it('@BR-USRD-003 @BR-USRD-004 deletes after the confirmation and returns to the list with the result', async () => {
    const app = await renderApp(`/admin/users/${encodeUserKey('USER0005')}/delete`, { signedOnAs: 'ADMIN001' });
    await screen.findByTestId('confirm-bar');
    expect(screen.getByTestId('delete-first-name')).toHaveTextContent('LEE');
    await waitFor(() => expect(screen.getByTestId('delete-id')).toHaveFocus());
    await app.user.click(screen.getByTestId('confirm-delete'));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/admin/users'));
    await screen.findByTestId('row-id-10');
    expect(messageText()).toBe('User USER0005 has been deleted ...');
    expect(app.requests.find((r) => r.method === 'DELETE')!.headers.get('If-Match')).toMatch(/^"[0-9a-f]{16}"$/);
    await app.user.click(screen.getByTestId('next'));
    await waitFor(() => expect(rowIds()).toEqual(['USER0003', 'USER0004']));
  });

  it('@BR-USRD-001 the PF5 prompt shows only with the function keys on', async () => {
    await renderApp(`/admin/users/${encodeUserKey('USER0004')}/delete`, { signedOnAs: 'ADMIN001' });
    await screen.findByTestId('confirm-bar');
    expect(screen.queryByTestId('info-line')).toBeNull();
  });

  it('@BR-USRD-001 with the function keys on the page prompts for PF5', async () => {
    await renderApp(`/admin/users/${encodeUserKey('USER0004')}/delete`, { signedOnAs: 'ADMIN001', functionKeys: true });
    await screen.findByTestId('confirm-bar');
    expect(screen.getByTestId('info-line')).toHaveTextContent('Press PF5 key to delete this user ...');
  });

  it('@VAL-USRD-002 a key that does not decode names the missing user and asks the service nothing', async () => {
    const app = await renderApp('/admin/users/not+a+key/delete', { signedOnAs: 'ADMIN001' });
    await screen.findByTestId('missing-user');
    expect(messageText()).toBe('User ID NOT found...');
    expect(app.apiCalls('/api/users')).toHaveLength(0);
  });

  it('@Q-USRD-002 deleting yourself is refused and the user stays', async () => {
    const app = await renderApp(`/admin/users/${encodeUserKey('ADMIN001')}/delete`, { signedOnAs: 'ADMIN001' });
    await screen.findByTestId('confirm-bar');
    await app.user.click(screen.getByTestId('confirm-delete'));
    await waitFor(() => expect(messageText()).toBe('You cannot delete your own User ID...'));
    expect(app.router.state.location.pathname).toBe(`/admin/users/${encodeUserKey('ADMIN001')}/delete`);
    expect(screen.getByTestId('confirm-bar')).toBeInTheDocument();
  });

  it('@VAL-USRD-003 Cancel goes back without deleting', async () => {
    const app = await renderApp(`/admin/users/${encodeUserKey('USER0004')}/delete`, { signedOnAs: 'ADMIN001' });
    await screen.findByTestId('confirm-bar');
    await app.user.click(screen.getByTestId('cancel'));
    await waitFor(() => expect(app.router.state.location.pathname).not.toMatch(/delete$/));
    expect(app.requests.some((r) => r.method === 'DELETE')).toBe(false);
  });

  it('@VAL-USRD-002 a user deleted meanwhile becomes the missing panel', async () => {
    const app = await renderApp(`/admin/users/${encodeUserKey('USER0004')}/delete`, { signedOnAs: 'ADMIN001' });
    await screen.findByTestId('confirm-bar');
    const { version } = await stored(app, 'USER0004');
    expect((await direct(app, 'DELETE', `/api/users/key/${encodeUserKey('USER0004')}`, undefined, { 'If-Match': `"${version}"` })).status).toBe(200);
    await app.user.click(screen.getByTestId('confirm-delete'));
    await screen.findByTestId('missing-user');
    expect(messageText()).toBe('User ID NOT found...');
  });
});
