import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import type { RouteObject } from 'react-router';
import { describe, expect, it } from 'vitest';
import { MAIN_OPTIONS, MenuPage, type MenuOption } from '@/pages/MenuPage';
import { routes } from '@/router';
import { RequireRole } from '@/session/RequireRole';
import { renderApp } from '@/test/renderApp';

const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;
const field = () => screen.getByLabelText('Option number');

/** The app's routes with the main menu showing `options` instead of MAIN_OPTIONS. */
function withMainOptions(options: MenuOption[]): RouteObject[] {
  const swap = (list: RouteObject[]): RouteObject[] =>
    list.map((route) => {
      if (route.index) {
        return {
          ...route,
          element: (
            <RequireRole allow="USER">
              <MenuPage variant="main" options={options} />
            </RequireRole>
          ),
        };
      }
      return route.children ? { ...route, children: swap(route.children) } : route;
    });
  return swap(routes);
}

async function openMenu(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await renderApp('/', { signedOnAs: 'USER0001', ...options });
  await screen.findByRole('heading', { name: 'Main menu' });
  await waitFor(() => expect(app.apiCalls('/api/features')).toHaveLength(1));
  return app;
}

describe('Main menu (COMEN01C)', () => {
  it('@BR-MENU-001 @FLOW-MENU-002 MENU-S2: lists 01. Account View to 11. Pending Authorization View in order, with no message', async () => {
    await openMenu();
    expect(messageText()).toBeNull();
    expect(field()).toHaveValue('');
    const lines = screen.getAllByTestId(/^option-\d\d$/).map((tile) => tile.querySelector('span')?.textContent);
    expect(lines).toEqual(MAIN_OPTIONS.map((o) => `${String(o.number).padStart(2, '0')}. ${o.name}`));
    expect(lines[0]).toBe('01. Account View');
    expect(lines[10]).toBe('11. Pending Authorization View');
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CM00 · COMEN01C');
  });

  it('@FLOW-MENU-003 MENU-S3: option 10 and Enter open Bill Pay', async () => {
    const { user, router } = await openMenu();
    await user.type(field(), '10{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/bill-pay'));
  });

  it('@BR-MENU-002 @FLOW-MENU-003 MENU-S4: option 8 opens Transaction Add and option 9 Transaction Reports', async () => {
    const { user, router } = await openMenu();
    expect(screen.getByTestId('option-08')).toHaveAttribute('data-status', 'available');
    expect(screen.getByTestId('option-09')).toHaveAttribute('data-status', 'available');
    await user.type(field(), '8{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/transactions/new'));
    await screen.findByRole('heading', { name: 'Add a transaction' });
    await act(() => router.navigate('/'));
    await screen.findByRole('heading', { name: 'Main menu' });
    await user.click(screen.getByTestId('option-09'));
    await waitFor(() => expect(router.state.location.pathname).toBe('/reports/transactions'));
    await screen.findByRole('heading', { name: 'Transaction report' });
  });

  it('@BR-MENU-005 an option without a page is coming soon, in green, and the menu stays', async () => {
    const placeholder = MAIN_OPTIONS.map((option) => (option.number === 8 ? { number: 8, name: option.name } : option));
    const { user, router } = await openMenu({ routes: withMainOptions(placeholder) });
    expect(screen.getByTestId('option-08')).toHaveAttribute('data-status', 'coming-soon');
    expect(screen.getByTestId('option-08')).toHaveTextContent('Coming soon');
    await user.type(field(), '8{Enter}');
    expect(messageText()).toBe('This option Transaction Add is coming soon ...');
    expect(screen.getByTestId('message')).toHaveAttribute('data-tone', 'success');
    expect(field()).toHaveFocus();
    expect(router.state.location.pathname).toBe('/');
    await user.click(screen.getByTestId('option-08'));
    expect(messageText()).toBe('This option Transaction Add is coming soon ...');
    expect(router.state.location.pathname).toBe('/');
  });

  it.each([
    ['1', '/accounts/view'],
    ['2', '/accounts/edit'],
    ['3', '/cards'],
    ['4', '/cards/lookup'],
    ['5', '/cards/edit'],
    ['6', '/transactions'],
    ['7', '/transactions/lookup'],
  ])('options 1 to 7 are converted: %s opens %s', async (typed, path) => {
    const { user, router } = await openMenu();
    expect(screen.getByTestId(`option-0${typed}`)).toHaveAttribute('data-status', 'available');
    await user.type(field(), `${typed}{Enter}`);
    await waitFor(() => expect(router.state.location.pathname).toBe(path));
  });

  it('@VAL-MENU-001 @NUM-MENU-001 @DEF-MENU-001 MENU-S5: blank, 00, 12 and 99 are invalid', async () => {
    const { user } = await openMenu();
    for (const typed of ['', '00', '12', '99']) {
      await user.clear(field());
      await user.type(field(), `${typed}{Enter}`);
      expect(messageText()).toBe('Please enter a valid option number...');
      expect(field()).toHaveFocus();
    }
  });

  it('@BR-MENU-004 MENU-S6: option 11 is not installed, in red', async () => {
    const { user } = await openMenu();
    await user.type(field(), '11{Enter}');
    expect(messageText()).toBe('This option Pending Authorization View is not installed...');
    expect(screen.getByTestId('message')).toHaveAttribute('data-tone', 'error');
  });

  it('@FLOW-MENU-004 MENU-S8: Sign out ends the session and shows an empty sign-on page', async () => {
    const { user, router, apiCalls } = await openMenu();
    await user.click(screen.getByRole('button', { name: /^Sign out/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-on'));
    await screen.findByRole('heading', { name: 'Sign on' });
    expect(apiCalls('/api/auth/sign-off')).toHaveLength(1);
    expect(messageText()).toBeNull();
    expect(screen.getByLabelText('User ID')).toHaveValue('');
  });

  it('@FLOW-SGN-003 the header Sign out ends the session on the thank-you page', async () => {
    const { user, router, apiCalls } = await openMenu();
    await user.click(screen.getByRole('button', { name: /^Account menu/ }));
    await user.click(await screen.findByRole('menuitem', { name: 'Sign out' }));
    await screen.findByRole('heading', { name: 'You are signed out' });
    expect(router.state.location.pathname).toBe('/signed-off');
    expect(apiCalls('/api/auth/sign-off')).toHaveLength(1);
    expect(messageText()).toBe('Thank you for using CardDemo application...');
    // Still there once the session has become anonymous.
    await waitFor(() => expect(screen.queryByRole('button', { name: /^Account menu/ })).toBeNull());
    expect(router.state.location.pathname).toBe('/signed-off');
  });

  it('@VAL-MENU-002 @FLOW-MENU-005 MENU-S9: F7 with the function keys on is an invalid key; F3 signs off', async () => {
    const { user, router } = await openMenu({ functionKeys: true });
    await user.type(field(), '1');
    fireEvent.keyDown(window, { key: 'F7' });
    expect(messageText()).toBe('Invalid key pressed. Please see below...');
    expect(field()).toHaveValue('1');
    fireEvent.keyDown(window, { key: 'F3' });
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-on'));
  });

  it('@DEF-MENU-005 MENU-S1: without a session it goes to sign-on', async () => {
    const { router } = await renderApp('/');
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-on'));
  });

  it('MENU-S10: an admin who opens / goes to /admin', async () => {
    const { router } = await renderApp('/', { signedOnAs: 'ADMIN001' });
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin'));
  });
});

describe('Admin menu stub (COADM01C)', () => {
  it('@D-70 options 1 to 4 open the user pages; 5 and 6 are hidden and invalid; 7 is the read-only lookup (D-09)', async () => {
    const { user, router } = await renderApp('/admin', { signedOnAs: 'ADMIN001' });
    await screen.findByRole('heading', { name: 'Admin menu' });
    const tiles = screen.getAllByTestId(/^option-\d\d$/).map((tile) => tile.querySelector('span')?.textContent);
    expect(tiles).toEqual([
      '01. User List (Security)',
      '02. User Add (Security)',
      '03. User Update (Security)',
      '04. User Delete (Security)',
      '07. Account and Card View',
    ]);
    for (const n of ['01', '02', '03', '04', '07']) expect(screen.getByTestId(`option-${n}`)).toHaveAttribute('data-status', 'available');
    for (const typed of ['5', '6', '8']) {
      await user.clear(field());
      await user.type(field(), `${typed}{Enter}`);
      expect(messageText()).toBe('Please enter a valid option number...');
    }
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CA00 · COADM01C');
    await user.clear(field());
    await user.type(field(), '7{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/lookup'));
  });

  it.each([
    ['1', '/admin/users', null],
    ['2', '/admin/users/new', null],
    ['3', '/admin/users', 'update'],
    ['4', '/admin/users', 'delete'],
  ])('option %s opens %s', async (typed, path, intent) => {
    const { user, router } = await renderApp('/admin', { signedOnAs: 'ADMIN001' });
    await screen.findByRole('heading', { name: 'Admin menu' });
    await user.type(field(), `${typed}{Enter}`);
    await waitFor(() => expect(router.state.location.pathname).toBe(path));
    expect((router.state.location.state as { intent?: string } | null)?.intent ?? null).toBe(intent);
  });

  it('a user who opens /admin goes to the main menu', async () => {
    const { router } = await renderApp('/admin', { signedOnAs: 'USER0001' });
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
  });
});
