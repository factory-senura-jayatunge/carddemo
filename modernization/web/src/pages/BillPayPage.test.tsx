import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderApp } from '@/test/renderApp';

const acct = () => screen.getByLabelText('Account ID');
const messageText = () => screen.queryByTestId('message-text')?.textContent ?? null;
const submit = () => screen.getByTestId('submit');

async function openBillPay(options: Parameters<typeof renderApp>[1] = {}) {
  const app = await renderApp('/bill-pay', { signedOnAs: 'USER0001', ...options });
  await screen.findByRole('heading', { name: 'Bill payment' });
  return app;
}

describe('Bill Pay page (COBIL00C)', () => {
  it('[FLOW-BILL-002] BILL-S2: opens empty with focus on Account ID', async () => {
    await openBillPay();
    expect(acct()).toHaveValue('');
    await waitFor(() => expect(acct()).toHaveFocus());
    expect(messageText()).toBeNull();
  });

  it('[VAL-BILL-001] [FLOW-BILL-003] BILL-S3: Enter with a blank account ID shows the message and calls nothing', async () => {
    const { user, apiCalls } = await openBillPay();
    await user.type(acct(), '{Enter}');
    expect(messageText()).toBe('Acct ID can NOT be empty...');
    expect(acct()).toHaveFocus();
    expect(apiCalls('/api/billpay')).toHaveLength(0);
  });

  it('[VAL-BILL-003] BILL-S4: an unknown account', async () => {
    const { user } = await openBillPay();
    await user.type(acct(), '99999999999{Enter}');
    await waitFor(() => expect(messageText()).toBe('Account ID NOT found...'));
    expect(acct()).toHaveFocus();
    expect(screen.queryByTestId('balance-panel')).toBeNull();
  });

  it('[VAL-BILL-005] [NUM-BILL-001] [DEF-BILL-014] BILL-S5 fixed: shows the balance, asks to confirm and locks the account ID', async () => {
    const { user } = await openBillPay();
    await user.type(acct(), '00000000001{Enter}');
    await waitFor(() => expect(messageText()).toBe('Confirm to make a bill payment...'));
    expect(screen.getByTestId('balance-display')).toHaveTextContent('+0000000194.00');
    expect(screen.getByTestId('balance-amount')).toHaveTextContent('$194.00');
    expect(screen.getByTestId('message')).toHaveAttribute('data-tone', 'error');
    expect(acct()).toHaveAttribute('readonly');
    expect(screen.getByRole('radio', { name: 'Yes' })).toHaveFocus();
  });

  it('[DEF-BILL-014] BILL-S5 legacy: the account ID stays editable', async () => {
    const { user } = await openBillPay({ settings: { uiSwitches: { 'DEF-BILL-014': 'legacy' } } });
    await user.type(acct(), '00000000001{Enter}');
    await waitFor(() => expect(messageText()).toBe('Confirm to make a bill payment...'));
    expect(acct()).not.toHaveAttribute('readonly');
  });

  it('[VAL-BILL-004] BILL-S6: zero and negative balances show the balance and nothing to pay', async () => {
    const { user } = await openBillPay();
    await user.type(acct(), '00000000098{Enter}');
    await waitFor(() => expect(messageText()).toBe('You have nothing to pay...'));
    expect(screen.getByTestId('balance-display')).toHaveTextContent('+0000000000.00');
    expect(acct()).not.toHaveAttribute('readonly');
    await user.clear(acct());
    await user.type(acct(), '00000000099{Enter}');
    await waitFor(() => expect(screen.getByTestId('balance-display')).toHaveTextContent('-0000000025.50'));
    await user.click(screen.getByRole('radio', { name: 'Yes' }));
    await user.click(submit());
    await waitFor(() => expect(messageText()).toBe('You have nothing to pay...'));
    expect(screen.getByTestId('balance-display')).toHaveTextContent('-0000000025.50');
  });

  it('[VAL-BILL-002] BILL-S7: No clears the fields and the message without a call', async () => {
    const { user, apiCalls } = await openBillPay();
    await user.type(acct(), '00000000001{Enter}');
    await waitFor(() => expect(messageText()).toBe('Confirm to make a bill payment...'));
    const before = apiCalls('/api/billpay').length;
    await user.click(screen.getByRole('radio', { name: 'No' }));
    await user.click(submit());
    expect(acct()).toHaveValue('');
    expect(acct()).not.toHaveAttribute('readonly');
    expect(messageText()).toBeNull();
    expect(screen.queryByTestId('balance-panel')).toBeNull();
    expect(screen.getByRole('radio', { name: 'No' })).not.toBeChecked();
    expect(acct()).toHaveFocus();
    expect(apiCalls('/api/billpay')).toHaveLength(before);
  });

  it('[BR-BILL-001] [VAL-BILL-002] BILL-S9: Yes pays with a fresh Idempotency-Key, clears the form and unlocks', async () => {
    const { user, apiCalls } = await openBillPay();
    await user.type(acct(), '00000000001{Enter}');
    await waitFor(() => expect(acct()).toHaveAttribute('readonly'));
    await user.keyboard('y');
    expect(screen.getByRole('radio', { name: 'Yes' })).toBeChecked();
    expect(submit()).toHaveTextContent('Pay $194.00');
    await user.keyboard('{Enter}');
    await waitFor(() => expect(messageText()).toBe('Payment successful.  Your Transaction ID is 0000000000000001.'));
    expect(screen.getByTestId('message')).toHaveAttribute('data-tone', 'success');
    expect(acct()).toHaveValue('');
    expect(acct()).not.toHaveAttribute('readonly');
    expect(acct()).toHaveFocus();
    const [payment] = apiCalls('/api/billpay/accounts/00000000001/payments');
    expect(payment?.method).toBe('POST');
    expect(payment?.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(payment?.headers.get('X-XSRF-TOKEN')).toBeTruthy();
    expect(await payment?.json()).toEqual({ confirm: 'Y' });
  });

  it('[BR-BILL-007] BILL-S16: Yes on the empty form pays without a preview, each Enter with its own key', async () => {
    const { user, apiCalls } = await openBillPay();
    await user.type(acct(), '00000000002');
    await user.click(screen.getByRole('radio', { name: 'Yes' }));
    await user.click(submit());
    await waitFor(() => expect(messageText()).toMatch(/^Payment successful\. {2}Your Transaction ID is 0{15}1\.$/));
    expect(apiCalls('/api/billpay/accounts/00000000002')).toHaveLength(1);
    await user.type(acct(), '00000000002');
    await user.click(screen.getByRole('radio', { name: 'Yes' }));
    await user.click(submit());
    await waitFor(() => expect(messageText()).toBe('You have nothing to pay...'));
    const keys = apiCalls('/api/billpay/accounts/00000000002/payments').map((r) => r.headers.get('Idempotency-Key'));
    expect(keys).toHaveLength(2);
    expect(keys[0]).not.toBe(keys[1]);
  });

  it('BILL-S13: during a batch window the payment shows a notice with the retry time', async () => {
    const { user } = await openBillPay({ settings: { batchWindowOpen: true } });
    await user.type(acct(), '00000000001{Enter}');
    await waitFor(() => expect(messageText()).toBe('Confirm to make a bill payment...'));
    await user.click(screen.getByRole('radio', { name: 'Yes' }));
    await user.click(submit());
    await waitFor(() =>
      expect(messageText()).toBe('Bill payment is not available while the batch cycle runs. Try again later...'),
    );
    const notice = screen.getByTestId('message');
    expect(notice).toHaveAttribute('data-tone', 'notice');
    expect(within(notice).getByText(/^Try again after \d\d:\d\d\.$/)).toBeInTheDocument();
    expect(within(notice).getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('[FLOW-BILL-005] BILL-S11: Clear empties the form and unlocks the account ID', async () => {
    const { user } = await openBillPay();
    await user.type(acct(), '00000000001{Enter}');
    await waitFor(() => expect(acct()).toHaveAttribute('readonly'));
    await user.click(screen.getByRole('button', { name: /^Clear/ }));
    expect(acct()).toHaveValue('');
    expect(acct()).not.toHaveAttribute('readonly');
    expect(messageText()).toBeNull();
  });

  it('[FLOW-BILL-004] BILL-S10: Back returns to the main menu', async () => {
    const { user, router } = await openBillPay();
    await user.click(screen.getByRole('button', { name: /^Back to menu/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
  });

  it('[VAL-BILL-006] [FLOW-BILL-006] BILL-S12: with the function keys on, F3 and F4 act and other keys are invalid', async () => {
    const { user, router } = await openBillPay({ functionKeys: true });
    await user.type(acct(), '00000000001');
    fireEvent.keyDown(window, { key: 'F5' });
    expect(messageText()).toBe('Invalid key pressed. Please see below...');
    expect(acct()).toHaveValue('00000000001');
    fireEvent.keyDown(window, { key: 'F4' });
    expect(acct()).toHaveValue('');
    expect(messageText()).toBeNull();
    fireEvent.keyDown(window, { key: 'F3' });
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
  });

  it('[VAL-BILL-006] with the function keys off, function keys do nothing', async () => {
    const { user } = await openBillPay();
    await user.type(acct(), '00000000001');
    const event = new KeyboardEvent('keydown', { key: 'F5', cancelable: true });
    window.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
    expect(messageText()).toBeNull();
    expect(acct()).toHaveValue('00000000001');
  });

  it('[FLOW-BILL-001] BILL-S1: without a session it goes to an empty sign-on page', async () => {
    const { router } = await renderApp('/bill-pay');
    await waitFor(() => expect(router.state.location.pathname).toBe('/sign-on'));
    expect(messageText()).toBeNull();
  });

  it('[FLOW-BILL-009] shows the legacy titles, screen IDs, date and time in the header', async () => {
    await openBillPay();
    expect(screen.getByTestId('header-titles')).toHaveTextContent('AWS Mainframe ModernizationCardDemo');
    expect(screen.getByTestId('screen-id')).toHaveTextContent('CB00 · COBIL00C');
    expect(screen.getByTestId('header-date').textContent).toMatch(/^\d\d\/\d\d\/\d\d$/);
    expect(screen.getByTestId('header-time').textContent).toMatch(/^\d\d:\d\d:\d\d$/);
  });
});
