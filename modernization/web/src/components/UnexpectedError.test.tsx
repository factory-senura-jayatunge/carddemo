import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderApp } from '@/test/renderApp';

const UNEXPECTED = 'An unexpected error occurred. Please try again later ...';

/** The error page: the platform's text, its correlation ID, the session kept (account-service rulings §9). */
async function expectErrorPage() {
  const page = await screen.findByTestId('error-page');
  expect(screen.getByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument();
  expect(screen.getByTestId('message-text')).toHaveTextContent(UNEXPECTED);
  expect(screen.getByTestId('error-reference')).toHaveTextContent(/Reference [0-9a-f-]{36}\./);
  expect(screen.getByRole('button', { name: /^Account menu/ })).toBeInTheDocument();
  await waitFor(() => expect(screen.getByRole('button', { name: 'Try again' })).toHaveFocus());
  return page;
}

describe('an unexpected failure ends the request with the error page', () => {
  it('@ERR-ACVW-004 account view: the lookup fails, Try again starts the page afresh', async () => {
    const app = await renderApp('/accounts/view', { signedOnAs: 'USER0001', settings: { faults: ['unexpected'] } });
    await screen.findByRole('heading', { name: 'Account view' });
    await app.user.type(screen.getByTestId('acct-id'), '00000000001{Enter}');
    await expectErrorPage();
    app.settings.faults = [];
    await app.user.click(screen.getByRole('button', { name: 'Try again' }));
    await screen.findByRole('heading', { name: 'Account view' });
    expect(screen.getByTestId('acct-id')).toHaveValue('');
    const me = await app.server.handle(new Request(`${window.location.origin}/api/auth/me`));
    expect(me.status).toBe(200);
  });

  it('@ERR-CRDV-002 card view: the read by card ID fails; Back leaves for the card list', async () => {
    const app = await renderApp('/cards/49', { signedOnAs: 'USER0001', settings: { faults: ['unexpected'] } });
    await expectErrorPage();
    expect(screen.queryByTestId('card-panel')).toBeNull();
    await app.user.click(screen.getByRole('button', { name: /^Back/ }));
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/cards'));
  });

  it('@ERR-ACUP-006 account update: the save fails; the main menu stays reachable', async () => {
    const app = await renderApp('/accounts/edit', { signedOnAs: 'USER0001' });
    await screen.findByRole('heading', { name: 'Update account' });
    await app.user.type(screen.getByTestId('acct-id'), '00000000001{Enter}');
    await screen.findByTestId('creditLimit');
    await app.user.clear(screen.getByTestId('creditLimit'));
    await app.user.type(screen.getByTestId('creditLimit'), '2500.00');
    await app.user.click(screen.getByRole('button', { name: 'Check changes' }));
    const save = await screen.findByRole('button', { name: /^Save/ });
    app.settings.faults = ['unexpected'];
    await app.user.click(save);
    await expectErrorPage();
    await app.user.click(screen.getByRole('button', { name: /^Back/ }));
    await screen.findByRole('heading', { name: 'Main menu' });
  });
});
