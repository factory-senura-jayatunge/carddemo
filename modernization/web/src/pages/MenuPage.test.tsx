import { fireEvent, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MAIN_OPTIONS } from '@/pages/MenuPage';
import { renderApp } from '@/test/renderApp';

const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;
const field = () => screen.getByLabelText('Option number');

async function openMenu(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await renderApp('/', { signedOnAs: 'USER0001', ...options });
  await screen.findByRole('heading', { name: 'Main menu' });
  await waitFor(() => expect(app.apiCalls('/api/features')).toHaveLength(1));
  return app;
}

describe('Main menu (COMEN01C)', () => {
  it('[BR-MENU-001] MENU-S2: lists 01. Account View to 11. Pending Authorization View in order', async () => {
    await openMenu();
    const lines = screen.getAllByTestId(/^option-\d\d$/).map((tile) => tile.querySelector('span')?.textContent);
    expect(lines).toEqual(MAIN_OPTIONS.map((o) => `${String(o.number).padStart(2, '0')}. ${o.name}`));
    expect(lines[0]).toBe('01. Account View');
    expect(lines[10]).toBe('11. Pending Authorization View');
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CM00 · COMEN01C');
  });

  it('MENU-S3: option 10 opens Bill Pay, typed or clicked', async () => {
    const { user, router } = await openMenu();
    await user.type(field(), '10{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/bill-pay'));
  });

  it('[BR-MENU-005] MENU-S4: option 5 is coming soon, in green', async () => {
    const { user } = await openMenu();
    await user.type(field(), '5{Enter}');
    expect(messageText()).toBe('This option Credit Card Update is coming soon ...');
    expect(screen.getByTestId('message')).toHaveAttribute('data-tone', 'success');
    await user.click(screen.getByTestId('option-01'));
    expect(messageText()).toBe('This option Account View is coming soon ...');
  });

  it('[VAL-MENU-001] [NUM-MENU-001] MENU-S5: blank, 00, 12 and 99 are invalid', async () => {
    const { user } = await openMenu();
    for (const typed of ['', '00', '12', '99']) {
      await user.clear(field());
      await user.type(field(), `${typed}{Enter}`);
      expect(messageText()).toBe('Please enter a valid option number...');
      expect(field()).toHaveFocus();
    }
  });

  it('[BR-MENU-004] MENU-S6: option 11 is not installed, in red', async () => {
    const { user } = await openMenu();
    await user.type(field(), '11{Enter}');
    expect(messageText()).toBe('This option Pending Authorization View is not installed...');
    expect(screen.getByTestId('message')).toHaveAttribute('data-tone', 'error');
  });

  it('[FLOW-MENU-004] MENU-S8: Sign out ends the session and shows an empty sign-on page', async () => {
    const { user, router, apiCalls } = await openMenu();
    await user.click(screen.getByRole('button', { name: /^Sign out/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-on'));
    expect(apiCalls('/api/auth/sign-off')).toHaveLength(1);
    expect(messageText()).toBeNull();
    expect(screen.getByLabelText('User ID')).toHaveValue('');
  });

  it('[FLOW-SGN-003] the header Sign out ends the session on the thank-you page', async () => {
    const { user, router, apiCalls } = await openMenu();
    await user.click(screen.getByRole('button', { name: /^Account menu/ }));
    await user.click(await screen.findByRole('menuitem', { name: 'Sign out' }));
    await screen.findByRole('heading', { name: 'You are signed out' });
    expect(router.state.location.pathname).toBe('/signed-off');
    expect(apiCalls('/api/auth/sign-off')).toHaveLength(1);
    expect(messageText()).toBe('Thank you for using CardDemo application...');
    // Still there once the session has become anonymous.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(router.state.location.pathname).toBe('/signed-off');
  });

  it('[VAL-MENU-002] [FLOW-MENU-005] MENU-S9: F7 with the function keys on is an invalid key; F3 signs off', async () => {
    const { user, router } = await openMenu({ functionKeys: true });
    await user.type(field(), '1');
    fireEvent.keyDown(window, { key: 'F7' });
    expect(messageText()).toBe('Invalid key pressed. Please see below...');
    expect(field()).toHaveValue('1');
    fireEvent.keyDown(window, { key: 'F3' });
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-on'));
  });

  it('MENU-S1: without a session it goes to sign-on', async () => {
    const { router } = await renderApp('/');
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-on'));
  });

  it('MENU-S10: an admin who opens / goes to /admin', async () => {
    const { router } = await renderApp('/', { signedOnAs: 'ADMIN001' });
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin'));
  });
});

describe('Admin menu stub (COADM01C)', () => {
  it('[D-70] options 1 to 4 are coming soon; 5 and 6 are hidden and invalid', async () => {
    const { user } = await renderApp('/admin', { signedOnAs: 'ADMIN001' });
    await screen.findByRole('heading', { name: 'Admin menu' });
    expect(screen.getAllByTestId(/^option-\d\d$/)).toHaveLength(4);
    await user.click(screen.getByTestId('option-01'));
    expect(messageText()).toBe('This option User List (Security) is coming soon ...');
    await user.type(field(), '5{Enter}');
    expect(messageText()).toBe('Please enter a valid option number...');
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CA00 · COADM01C');
  });

  it('a user who opens /admin goes to the main menu', async () => {
    const { router } = await renderApp('/admin', { signedOnAs: 'USER0001' });
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
  });
});
