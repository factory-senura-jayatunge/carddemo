import type { Page } from '@playwright/test';
import {
  callApi,
  callsDuring,
  expect,
  expectAccessible,
  expectHeader,
  expectMessage,
  expectNoMessage,
  expectReadOnly,
  mode,
  signOnAsUser,
  test,
  trackApi,
  turnOnFunctionKeys,
} from './fixtures';

/**
 * BILL scenarios of the slice contract (COBIL00C section 15). Tests that pay
 * come last, so on a stack (one worker, in order) the earlier ones still see
 * account 00000000001 at 194.00.
 */

const PAID = /^Payment successful\. {2}Your Transaction ID is \d+\.$/;

const acct = (page: Page) => page.getByLabel('Account ID');
const yes = (page: Page) => page.getByRole('radio', { name: 'Yes' });
const no = (page: Page) => page.getByRole('radio', { name: 'No' });
const submit = (page: Page) => page.getByTestId('submit');
const display = (page: Page) => page.getByTestId('balance-display');

async function openBillPay(page: Page) {
  await signOnAsUser(page);
  await page.getByTestId('option-10').click();
  await expect(page.getByRole('heading', { name: 'Bill payment' })).toBeVisible();
}

async function preview(page: Page, acctId: string) {
  await acct(page).fill(acctId);
  await submit(page).click();
}

test.beforeEach(async ({ mock }) => mock({ latency: 0 }));

test('[FLOW-BILL-001] BILL-S1: without a session the page and both APIs refuse', async ({ page }) => {
  await page.goto('/bill-pay');
  await expect(page).toHaveURL(/\/sign-on$/);
  await expectNoMessage(page);
  const read = await callApi(page, 'GET', '/api/billpay/accounts/00000000001');
  expect(read.status).toBe(401);
  expect(read.body?.code).toBe('UNAUTHENTICATED');
  const pay = await callApi(page, 'POST', '/api/billpay/accounts/00000000001/payments', {
    body: { confirm: 'Y' },
    headers: { 'Idempotency-Key': crypto.randomUUID() },
  });
  expect(pay.status).toBe(401);
  expect(pay.body?.code).toBe('UNAUTHENTICATED');
});

test('[FLOW-BILL-002] [FLOW-BILL-009] BILL-S2: menu option 10 opens an empty form with focus on Account ID', async ({ page }) => {
  await openBillPay(page);
  await expect(page).toHaveURL(/\/bill-pay$/);
  await expect(acct(page)).toBeFocused();
  await expect(acct(page)).toHaveValue('');
  await expect(yes(page)).not.toBeChecked();
  await expect(no(page)).not.toBeChecked();
  await expectNoMessage(page);
  await expectHeader(page, 'CB00 · COBIL00C');
  await expectAccessible(page);
});

test('[VAL-BILL-001] [FLOW-BILL-003] BILL-S3: Enter with a blank account ID makes no call', async ({ page }) => {
  await openBillPay(page);
  const calls = trackApi(page);
  const made = await callsDuring(page, calls, () => acct(page).press('Enter'));
  await expectMessage(page, 'Acct ID can NOT be empty...', 'error');
  await expect(acct(page)).toBeFocused();
  await expect(acct(page)).toHaveAttribute('aria-invalid', 'true');
  expect(made).toEqual([]);
  await expectAccessible(page);
  for (const path of ['/api/billpay/accounts/%20', '/api/billpay/accounts/%20/payments']) {
    const answer = path.endsWith('payments')
      ? await callApi(page, 'POST', path, { body: { confirm: 'Y' }, headers: { 'Idempotency-Key': crypto.randomUUID() } })
      : await callApi(page, 'GET', path);
    expect(answer.status, path).toBe(400);
    expect(answer.body?.code, path).toBe('ACCT_ID_REQUIRED');
  }
});

test('[VAL-BILL-003] BILL-S4: an unknown account', async ({ page }) => {
  await openBillPay(page);
  await preview(page, '99999999999');
  await expectMessage(page, 'Account ID NOT found...', 'error');
  await expect(acct(page)).toBeFocused();
  await expect(page.getByTestId('balance-panel')).toHaveCount(0);
});

test(`[VAL-BILL-005] [NUM-BILL-001] [DEF-BILL-014] BILL-S5 ${mode}: the balance and the confirm prompt`, async ({ page }) => {
  await openBillPay(page);
  await preview(page, '00000000001');
  await expect(display(page)).toHaveText('+0000000194.00');
  await expect(page.getByTestId('balance-amount')).toHaveText('$194.00');
  await expectMessage(page, 'Confirm to make a bill payment...', 'error');
  await expect(yes(page)).toBeFocused();
  await expectReadOnly(acct(page), mode === 'fixed');
  await expect(submit(page)).toHaveText('Check again');
  await expectAccessible(page);
  // Previewing again changes nothing.
  await submit(page).click();
  await expect(display(page)).toHaveText('+0000000194.00');
});

test('[VAL-BILL-004] BILL-S6: zero and negative balances have nothing to pay @mock-only', async ({ page }) => {
  await openBillPay(page);
  for (const [acctId, shown] of [
    ['00000000098', '+0000000000.00'],
    ['00000000099', '-0000000025.50'],
  ] as const) {
    await page.getByRole('button', { name: /^Clear/ }).click();
    await preview(page, acctId);
    await expect(display(page)).toHaveText(shown);
    await expectMessage(page, 'You have nothing to pay...', 'error');
    await expect(acct(page)).toBeFocused();
    await expectReadOnly(acct(page), false);
    await yes(page).check();
    await submit(page).click();
    await expectMessage(page, 'You have nothing to pay...', 'error');
    await expect(display(page)).toHaveText(shown);
    const answer = await callApi(page, 'POST', `/api/billpay/accounts/${acctId}/payments`, {
      body: { confirm: 'Y' },
      headers: { 'Idempotency-Key': crypto.randomUUID() },
    });
    expect(answer.status).toBe(422);
    expect(answer.body?.code).toBe('NOTHING_TO_PAY');
  }
});

test('[VAL-BILL-002] BILL-S7: No clears the fields and the message without a call', async ({ page }) => {
  await openBillPay(page);
  await preview(page, '00000000001');
  await expect(display(page)).toBeVisible();
  const calls = trackApi(page);
  await no(page).check();
  await expect(submit(page)).toHaveText('Start over');
  const made = await callsDuring(page, calls, () => submit(page).click());
  expect(made).toEqual([]);
  await expect(acct(page)).toHaveValue('');
  await expect(acct(page)).toBeFocused();
  await expectReadOnly(acct(page), false);
  await expect(yes(page)).not.toBeChecked();
  await expect(no(page)).not.toBeChecked();
  await expect(page.getByTestId('balance-panel')).toHaveCount(0);
  await expectNoMessage(page);
});

test('[VAL-BILL-002] BILL-S8: a confirm value other than Y or N is refused by the API', async ({ page }) => {
  // The Yes/No choice cannot produce X, so the page never sends it; the
  // service still refuses it, and nothing is paid.
  await openBillPay(page);
  const answer = await callApi(page, 'POST', '/api/billpay/accounts/00000000001/payments', {
    body: { confirm: 'X' },
    headers: { 'Idempotency-Key': crypto.randomUUID() },
  });
  expect(answer.status).toBe(400);
  expect(answer.body?.code).toBe('INVALID_CONFIRM');
  expect(answer.body?.message).toBe('Invalid value. Valid values are (Y/N)...');
  await preview(page, '00000000001');
  await expect(display(page)).toHaveText('+0000000194.00');
});

test('[FLOW-BILL-004] BILL-S10: Back returns to the main menu, by button and by F3', async ({ page }) => {
  await openBillPay(page);
  await page.getByRole('button', { name: /^Back to menu/ }).click();
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
  await page.getByTestId('option-10').click();
  await turnOnFunctionKeys(page);
  await acct(page).fill('00000000001');
  await acct(page).press('F3');
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
});

test('[FLOW-BILL-005] BILL-S11: Clear empties the form and unlocks the account ID, by button and by F4', async ({ page }) => {
  await openBillPay(page);
  for (const clear of [() => page.getByRole('button', { name: /^Clear/ }).click(), () => page.keyboard.press('F4')]) {
    await preview(page, '00000000001');
    await expect(display(page)).toBeVisible();
    await expectReadOnly(acct(page), mode === 'fixed');
    await turnOnFunctionKeys(page);
    await clear();
    await expect(acct(page)).toHaveValue('');
    await expect(acct(page)).toBeFocused();
    await expectReadOnly(acct(page), false);
    await expect(page.getByTestId('balance-panel')).toHaveCount(0);
    await expectNoMessage(page);
  }
});

test('[VAL-BILL-006] [FLOW-BILL-006] BILL-S12: F5 with the function keys on is an invalid key and keeps the input', async ({ page }) => {
  await openBillPay(page);
  await turnOnFunctionKeys(page);
  await acct(page).fill('00000000001');
  await acct(page).press('F5');
  await expectMessage(page, 'Invalid key pressed. Please see below...', 'error');
  await expect(acct(page)).toHaveValue('00000000001');
  await expect(page).toHaveURL(/\/bill-pay$/);
});

test('[VAL-BILL-006] with the function keys off, F3 and F5 do nothing on the page', async ({ page }) => {
  await openBillPay(page);
  await acct(page).fill('00000000001');
  await acct(page).press('F3');
  await expect(page).toHaveURL(/\/bill-pay$/);
  await expectNoMessage(page);
});

test('BILL-S13: an open batch window allows the preview and refuses the payment @mock-only', async ({ page, mock }) => {
  await mock({ latency: 0, batchWindow: true });
  await openBillPay(page);
  await preview(page, '00000000001');
  await expect(display(page)).toHaveText('+0000000194.00');
  await yes(page).check();
  await page.getByRole('button', { name: 'Pay $194.00' }).click();
  await expectMessage(page, 'Bill payment is not available while the batch cycle runs. Try again later...', 'notice');
  await expect(page.getByText(/^Try again after \d{2}:\d{2}\.$/)).toBeVisible();
  await expectAccessible(page);
  await page.getByRole('button', { name: 'Clear' }).click();
  await preview(page, '00000000001');
  await expect(display(page)).toHaveText('+0000000194.00');
});

test('[BR-BILL-007] BILL-S16: Yes on the empty form pays without a preview', async ({ page }) => {
  await openBillPay(page);
  const calls = trackApi(page);
  await acct(page).fill('00000000002');
  await yes(page).check();
  await expect(submit(page)).toHaveText('Pay the full balance');
  const made = await callsDuring(page, calls, async () => {
    await submit(page).click();
    await expectMessage(page, PAID, 'success');
  });
  expect(made).toEqual(['POST /api/billpay/accounts/00000000002/payments']);
  await expect(acct(page)).toHaveValue('');
});

test('[BR-BILL-001] [BR-BILL-003] BILL-S9: Yes pays the full balance, clears the form and unlocks', async ({ page }) => {
  await openBillPay(page);
  await preview(page, '00000000001');
  await expect(display(page)).toHaveText('+0000000194.00');
  await yes(page).check();
  await page.getByRole('button', { name: 'Pay $194.00' }).click();
  await expectMessage(page, PAID, 'success');
  await expect(page.getByText('$194.00 paid on account 00000000001.')).toBeVisible();
  await expect(acct(page)).toHaveValue('');
  await expect(acct(page)).toBeFocused();
  await expectReadOnly(acct(page), false);
  await expect(yes(page)).not.toBeChecked();
  await expect(page.getByTestId('balance-panel')).toHaveCount(0);
  await expectAccessible(page);
  // The account is paid off: curr_bal is now 0.00.
  await preview(page, '00000000001');
  await expect(display(page)).toHaveText('+0000000000.00');
  await expectMessage(page, 'You have nothing to pay...', 'error');
});
