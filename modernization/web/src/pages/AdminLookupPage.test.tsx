import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderApp } from '@/test/renderApp';

const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;

describe('Admin lookup (admin option 7, D-09)', () => {
  it('links to account view, card list, card view, transaction list and transaction view', async () => {
    await renderApp('/admin/lookup', { signedOnAs: 'ADMIN001' });
    await screen.findByRole('heading', { name: 'Account and card view' });
    const list = screen.getByRole('list', { name: 'Read-only views' });
    expect(list.querySelectorAll('li')).toHaveLength(5);
    expect(screen.getByTestId('lookup-account')).toHaveAttribute('href', '/accounts/view');
    expect(screen.getByTestId('lookup-card-list')).toHaveAttribute('href', '/cards');
    expect(screen.getByTestId('lookup-card')).toHaveAttribute('href', '/cards/lookup');
    expect(screen.getByTestId('lookup-transaction-list')).toHaveAttribute('href', '/transactions');
    expect(screen.getByTestId('lookup-transaction')).toHaveAttribute('href', '/transactions/lookup');
    expect(messageText()).toBeNull();
  });

  it('@FLOW-TRNV-004 transaction view opened from the lookup goes Back to it', async () => {
    const { user, router } = await renderApp('/admin/lookup', { signedOnAs: 'ADMIN001' });
    await user.click(await screen.findByTestId('lookup-transaction'));
    await screen.findByRole('heading', { name: 'Transaction', level: 1 });
    await user.click(screen.getByTestId('back'));
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/lookup'));
  });

  it('@FLOW-ACVW-005 account view opened from the lookup goes Back to it, with the ADMIN role kept', async () => {
    const { user, router } = await renderApp('/admin/lookup', { signedOnAs: 'ADMIN001' });
    await user.click(await screen.findByTestId('lookup-account'));
    await screen.findByRole('heading', { name: 'Account view' });
    await user.click(screen.getByRole('button', { name: /^Back/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/lookup'));
  });

  it('an ADMIN on a reader page with no route state goes Back to the lookup', async () => {
    const { user, router } = await renderApp('/cards/lookup', { signedOnAs: 'ADMIN001' });
    await screen.findByRole('heading', { name: /card/i, level: 1 });
    await user.click(screen.getByRole('button', { name: /^Back/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/lookup'));
  });

  it('Back returns to the admin menu', async () => {
    const { user, router } = await renderApp('/admin/lookup', { signedOnAs: 'ADMIN001' });
    await user.click(await screen.findByRole('button', { name: /^Back/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin'));
  });

  it('a USER is sent to the main menu', async () => {
    const { router } = await renderApp('/admin/lookup', { signedOnAs: 'USER0001' });
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
  });
});
