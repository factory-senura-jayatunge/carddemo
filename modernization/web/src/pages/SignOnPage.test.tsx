import { fireEvent, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderApp } from '@/test/renderApp';

const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;
const userId = () => screen.getByLabelText('User ID');
const password = () => screen.getByLabelText('Password', { selector: 'input' });

async function openSignOn(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await renderApp('/sign-on', options);
  await screen.findByRole('heading', { name: 'Sign on' });
  return app;
}

async function signOn(app: Awaited<ReturnType<typeof openSignOn>>, id: string, pw: string) {
  if (id) await app.user.type(userId(), id);
  if (pw) await app.user.type(password(), pw);
  await app.user.click(screen.getByRole('button', { name: 'Sign on' }));
}

describe('Sign-on page (COSGN00C)', () => {
  it('[FLOW-SGN-001] SGN-S1: opens with focus on User ID; the first call is GET /api/auth/me', async () => {
    const app = await openSignOn();
    await waitFor(() => expect(userId()).toHaveFocus());
    expect(new URL(app.requests[0]?.url ?? '').pathname).toBe('/api/auth/me');
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CC00 · COSGN00C');
  });

  it('[VAL-SGN-001] SGN-S2: both blank', async () => {
    const app = await openSignOn();
    await signOn(app, '', '');
    expect(messageText()).toBe('Please enter User ID ...');
    expect(userId()).toHaveFocus();
    expect(userId()).toHaveAttribute('aria-invalid', 'true');
  });

  it('[VAL-SGN-002] SGN-S3: password blank', async () => {
    const app = await openSignOn();
    await signOn(app, 'ADMIN001', '');
    expect(messageText()).toBe('Please enter Password ...');
    expect(password()).toHaveFocus();
  });

  it('[BR-SGN-004] SGN-S4: an admin lands on /admin', async () => {
    const app = await openSignOn();
    await signOn(app, 'ADMIN001', 'PASSWORD');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/admin'));
    expect(await screen.findByRole('heading', { name: 'Admin menu' })).toBeInTheDocument();
  });

  it('[DEF-SGN-011] SGN-S5 fixed: passwords are case-sensitive, user IDs are not', async () => {
    const app = await openSignOn();
    await signOn(app, 'user0001', 'password');
    await waitFor(() => expect(messageText()).toBe('Wrong User ID or Password. Try again ...'));
    expect(password()).toHaveFocus();
    await app.user.clear(password());
    await app.user.type(password(), 'PASSWORD');
    await app.user.click(screen.getByRole('button', { name: 'Sign on' }));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
  });

  it('[DEF-SGN-003] SGN-S6 fixed: an unknown user gets the neutral message on Password', async () => {
    const app = await openSignOn();
    await signOn(app, 'NOUSER01', 'PASSWORD');
    await waitFor(() => expect(messageText()).toBe('Wrong User ID or Password. Try again ...'));
    expect(password()).toHaveFocus();
  });

  it('[FLOW-SGN-003] SGN-S8: Exit shows the thank-you page', async () => {
    const app = await openSignOn();
    await app.user.click(screen.getByRole('button', { name: /^Exit/ }));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/signed-off'));
    expect(messageText()).toBe('Thank you for using CardDemo application...');
    expect(screen.getByRole('link', { name: 'Sign on again' })).toHaveAttribute('href', '/sign-on');
  });

  it('[VAL-SGN-005] [FLOW-SGN-004] SGN-S9: F5 with the function keys on is an invalid key; F3 exits', async () => {
    const app = await openSignOn({ functionKeys: true });
    await app.user.type(userId(), 'USER0001');
    fireEvent.keyDown(window, { key: 'F5' });
    expect(messageText()).toBe('Invalid key pressed. Please see below...');
    expect(userId()).toHaveValue('USER0001');
    fireEvent.keyDown(window, { key: 'F4' });
    expect(messageText()).toBe('Invalid key pressed. Please see below...');
    fireEvent.keyDown(window, { key: 'F3' });
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/signed-off'));
  });

  it('SGN-S11 fixed: a mixed-case password signs on as typed', async () => {
    const app = await openSignOn();
    await signOn(app, 'TEST0001', 'Secret12');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
  });

  it('[DEF-SGN-005] SGN-S13 fixed: an unknown user type is refused', async () => {
    const app = await openSignOn();
    await signOn(app, 'TYPEX001', 'PASSWORD');
    await waitFor(() => expect(messageText()).toBe('User not authorized. Contact your administrator ...'));
    expect(userId()).toHaveFocus();
  });

  it('[DEF-SGN-002] SGN-S12 fixed: five wrong passwords lock the user', async () => {
    const app = await openSignOn();
    await app.user.type(userId(), 'USER0002');
    for (let i = 0; i < 5; i += 1) {
      await app.user.clear(password());
      await app.user.type(password(), 'WRONGPWD{Enter}');
      await waitFor(() => expect(messageText()).toBe('Wrong User ID or Password. Try again ...'));
    }
    await app.user.clear(password());
    await app.user.type(password(), 'PASSWORD{Enter}');
    await waitFor(() => expect(app.apiCalls('/api/auth/sign-on')).toHaveLength(6));
    expect(messageText()).toBe('Wrong User ID or Password. Try again ...');
    expect(app.router.state.location.pathname).toBe('/sign-on');
  });

  it('[D-SEC-07] SGN-S14: a forced change checks each rule and lands on home', async () => {
    const app = await openSignOn();
    await signOn(app, 'CHANGE01', 'PASSWORD');
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/sign-on/change-password'));
    expect(messageText()).toBe('Password change required. Enter a new Password ...');
    expect(screen.getByTestId('current-password')).toHaveValue('PASSWORD');
    const next = screen.getByTestId('new-password');
    const repeat = screen.getByTestId('confirm-password');
    const change = () => app.user.click(screen.getByRole('button', { name: 'Change password' }));
    await change();
    expect(messageText()).toBe('Please enter new Password ...');
    await app.user.type(next, 'short');
    await change();
    expect(messageText()).toBe('New Password must be 8 to 64 characters ...');
    await app.user.clear(next);
    await app.user.type(next, 'change01');
    await change();
    expect(messageText()).toBe('New Password must not be the User ID ...');
    await app.user.clear(next);
    await app.user.type(next, 'A new pass phrase');
    await app.user.type(repeat, 'A new pass phrasE');
    await change();
    expect(messageText()).toBe('New Password and confirmation do not match ...');
    expect(repeat).toHaveFocus();
    await app.user.clear(repeat);
    await app.user.type(repeat, 'A new pass phrase');
    await change();
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/'));
    expect(app.apiCalls('/api/auth/password')).toHaveLength(1);
  });

  it('[BR-SGN-004] a signed-on user who opens /sign-on goes home', async () => {
    const { router } = await renderApp('/sign-on', { signedOnAs: 'ADMIN001' });
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin'));
  });
});
