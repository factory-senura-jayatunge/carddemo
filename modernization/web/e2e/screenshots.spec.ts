import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { expect, message, signOn, signOnAsUser, test } from './fixtures';

/**
 * Screens for review, on mock data. CARDDEMO_SCREENSHOT_DIR changes where they
 * go. Mock-only: they pay account 00000000001, which a stack run must keep.
 */
const dir = process.env.CARDDEMO_SCREENSHOT_DIR ?? join(import.meta.dirname, '..', 'e2e-output', 'screens');
mkdirSync(dir, { recursive: true });
const shot = (name: string) => join(dir, `${name}.png`);

test.describe('screens @mock-only', () => {
  test.beforeEach(async ({ mock }) => mock({ latency: 0 }));

  test('sign-on', async ({ page }) => {
    await page.goto('/sign-on');
    await expect(page.getByRole('heading', { name: 'Sign on' })).toBeVisible();
    await page.screenshot({ path: shot('01-sign-on') });
    await page.getByRole('button', { name: 'Sign on', exact: true }).click();
    await expect(message(page)).toHaveText('Please enter User ID ...');
    await page.screenshot({ path: shot('02-sign-on-error') });
  });

  test('main menu and bill pay', async ({ page }) => {
    await signOn(page, 'USER0001');
    await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
    await page.screenshot({ path: shot('03-main-menu') });
    await page.getByTestId('option-10').click();
    await expect(page.getByRole('heading', { name: 'Bill payment' })).toBeVisible();
    await page.screenshot({ path: shot('04-bill-pay-empty') });
    await page.getByLabel('Account ID').fill('00000000001');
    await page.getByRole('button', { name: 'Check balance' }).click();
    await expect(page.getByTestId('balance-display')).toHaveText('+0000000194.00');
    await page.screenshot({ path: shot('05-bill-pay-confirm') });
    await page.getByRole('radio', { name: 'Yes' }).check();
    await page.getByRole('button', { name: 'Pay $194.00' }).click();
    await expect(message(page)).toHaveText(/^Payment successful\. {2}Your Transaction ID is \d+\.$/);
    await page.screenshot({ path: shot('06-bill-pay-paid') });
  });

  test('bill pay during the batch window', async ({ page, mock }) => {
    await mock({ latency: 0, batchWindow: true });
    await signOnAsUser(page);
    await page.goto('/bill-pay');
    await page.getByLabel('Account ID').fill('00000000002');
    await page.getByRole('radio', { name: 'Yes' }).check();
    await page.getByRole('button', { name: 'Pay the full balance' }).click();
    await expect(message(page)).toHaveText('Bill payment is not available while the batch cycle runs. Try again later...');
    await page.screenshot({ path: shot('07-bill-pay-batch-window') });
  });

  test('narrow screen', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signOnAsUser(page);
    await page.goto('/bill-pay');
    await page.getByLabel('Account ID').fill('00000000001');
    await page.getByRole('button', { name: 'Check balance' }).click();
    await expect(page.getByTestId('balance-display')).toBeVisible();
    await page.screenshot({ path: shot('08-bill-pay-mobile'), fullPage: true });
  });

  test('forced password change and admin menu', async ({ page }) => {
    await signOn(page, 'CHANGE01');
    await expect(page.getByRole('heading', { name: 'Change your password' })).toBeVisible();
    await page.getByLabel('New password', { exact: true }).fill('Better pass 1');
    await page.getByLabel('Repeat new password', { exact: true }).fill('Better pass 2');
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(message(page)).toHaveText('New Password and confirmation do not match ...');
    await page.screenshot({ path: shot('09-change-password') });
    await page.goto('/sign-on');
    await signOn(page, 'ADMIN001');
    await expect(page.getByRole('heading', { name: 'Admin menu' })).toBeVisible();
    await page.getByTestId('option-02').click();
    await page.screenshot({ path: shot('10-admin-menu') });
  });

  test('account view and update', async ({ page }) => {
    await signOn(page, 'USER0001');
    await page.getByTestId('option-01').click();
    await expect(page.getByRole('heading', { name: 'Account view' })).toBeVisible();
    await page.getByTestId('acct-id').fill('00000000001');
    await page.getByTestId('acct-id').press('Enter');
    await expect(page.getByTestId('customer-panel')).toBeVisible();
    await page.screenshot({ path: shot('11-account-view'), fullPage: true });
    await page.goto('/');
    await page.getByTestId('option-02').click();
    await expect(page.getByRole('heading', { name: 'Update account' })).toBeVisible();
    await page.getByTestId('acct-id').fill('00000000001');
    await page.getByTestId('acct-id').press('Enter');
    await expect(page.getByTestId('activeStatus')).toBeVisible();
    await page.screenshot({ path: shot('12-account-update'), fullPage: true });
    await page.getByTestId('creditLimit').fill('2500.00');
    await page.getByTestId('zip5').fill('12547');
    await page.getByRole('button', { name: 'Check changes' }).click();
    await expect(message(page)).toBeVisible();
    await page.screenshot({ path: shot('13-account-update-error') });
  });

  test('cards', async ({ page }) => {
    await signOn(page, 'USER0001');
    await page.getByTestId('option-03').click();
    await expect(page.getByRole('heading', { name: 'Credit cards' })).toBeVisible();
    await expect(page.getByTestId('card-row-1')).toBeVisible();
    await page.screenshot({ path: shot('14-card-list'), fullPage: true });
    await page.getByTestId('view-1').click();
    await expect(page.getByTestId('card-panel')).toBeVisible();
    await page.screenshot({ path: shot('15-card-view'), fullPage: true });
    await page.goto('/');
    await page.getByTestId('option-03').click();
    await page.getByTestId('update-1').click();
    await expect(page.getByRole('heading', { name: 'Update credit card' })).toBeVisible();
    await expect(page.getByLabel('Name on card')).toBeVisible();
    await page.screenshot({ path: shot('16-card-update'), fullPage: true });
  });

  test('account view on a narrow screen', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signOnAsUser(page);
    await page.goto('/accounts/view');
    await page.getByTestId('acct-id').fill('00000000001');
    await page.getByTestId('acct-id').press('Enter');
    await expect(page.getByTestId('customer-panel')).toBeVisible();
    await page.screenshot({ path: shot('17-account-view-mobile'), fullPage: true });
  });

  test('pending authorizations', async ({ page, mock }) => {
    await mock({ latency: 0, authorizationInstalled: true });
    await signOnAsUser(page);
    await page.goto('/authorizations');
    await page.getByTestId('acct-id').fill('00000000001');
    await page.getByTestId('acct-id').press('Enter');
    await expect(page.getByTestId('row-tran-5')).toBeVisible();
    await page.screenshot({ path: shot('18-authorizations'), fullPage: true });
    await page.getByTestId('view-2').click();
    await expect(page.getByTestId('detail-reason')).toHaveText('9999-ERROR');
    await page.screenshot({ path: shot('19-authorization-detail'), fullPage: true });
    await page.getByTestId('fraud-toggle').click();
    await expect(message(page)).toHaveText('AUTH MARKED FRAUD...');
    await page.screenshot({ path: shot('20-authorization-fraud'), fullPage: true });
  });

  test('pending authorizations on a narrow screen', async ({ page, mock }) => {
    await mock({ latency: 0, authorizationInstalled: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await signOnAsUser(page);
    await page.goto('/authorizations');
    await page.getByTestId('acct-id').fill('00000000097');
    await page.getByTestId('acct-id').press('Enter');
    await expect(page.getByTestId('row-tran-1')).toBeVisible();
    await page.screenshot({ path: shot('21-authorizations-mobile'), fullPage: true });
  });

  test('user administration', async ({ page }) => {
    await signOn(page, 'ADMIN001');
    await page.getByTestId('option-01').click();
    await expect(page.getByTestId('row-id-10')).toHaveText('USER0002');
    await page.screenshot({ path: shot('22-user-list'), fullPage: true });
    await page.getByTestId('add-user').click();
    await page.getByTestId('firstName').fill('Grace');
    await page.getByTestId('submit').click();
    await expect(message(page)).toHaveText('Last Name can NOT be empty...');
    await page.screenshot({ path: shot('23-user-add-error'), fullPage: true });
    await page.getByTestId('clear').click();
    await page.goto('/admin/users');
    await page.getByTestId('update-9').click();
    await expect(page.getByTestId('locked-id')).toHaveText('USER0001');
    await page.getByTestId('password-toggle').click();
    await page.screenshot({ path: shot('24-user-update'), fullPage: true });
    await page.goto('/admin/users');
    await page.getByTestId('delete-10').click();
    await expect(page.getByTestId('confirm-bar')).toBeVisible();
    await page.screenshot({ path: shot('25-user-delete'), fullPage: true });
  });

  test('user list on a narrow screen', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signOn(page, 'ADMIN001');
    await page.getByTestId('option-01').click();
    await expect(page.getByTestId('row-id-10')).toHaveText('USER0002');
    await page.screenshot({ path: shot('26-user-list-mobile'), fullPage: true });
  });

  test('transactions', async ({ page }) => {
    await signOn(page, 'ADMIN001');
    await page.getByTestId('option-07').click();
    await page.getByTestId('lookup-transaction-list').click();
    await expect(page.getByTestId('row-id-10')).toBeVisible();
    await page.screenshot({ path: shot('27-transaction-list'), fullPage: true });
    await page.getByTestId('view-1').click();
    await page.getByRole('button', { name: 'Show card number' }).click();
    await expect(page.getByTestId('tran-card')).toHaveText('4859452612877065');
    await page.screenshot({ path: shot('28-transaction-view'), fullPage: true });
  });

  test('transaction list on a narrow screen', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signOnAsUser(page);
    await page.goto('/transactions');
    await expect(page.getByTestId('row-id-10')).toBeVisible();
    await page.screenshot({ path: shot('29-transaction-list-mobile'), fullPage: true });
  });

  test('transaction add', async ({ page }) => {
    await signOn(page, 'USER0001');
    await page.getByTestId('option-08').click();
    await expect(page.getByRole('heading', { name: 'Add a transaction' })).toBeVisible();
    await page.getByTestId('acctId').fill('50');
    await page.getByRole('button', { name: 'Copy last transaction' }).click();
    await expect(message(page)).toHaveText('Confirm to add this transaction...');
    await page.screenshot({ path: shot('30-transaction-add-review'), fullPage: true });
    await page.getByRole('radio', { name: 'Yes' }).check();
    await page.getByRole('button', { name: 'Add transaction' }).click();
    await expect(message(page)).toHaveText(/^Transaction added successfully\./);
    await page.screenshot({ path: shot('31-transaction-added'), fullPage: true });
  });

  test('transaction add on a narrow screen', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signOnAsUser(page);
    await page.goto('/transactions/new');
    await page.getByTestId('acctId').fill('96');
    await page.getByRole('button', { name: 'Review' }).click();
    await expect(page.getByTestId('card-choice')).toBeVisible();
    await page.screenshot({ path: shot('32-transaction-add-mobile'), fullPage: true });
  });

  test('transaction report', async ({ page }) => {
    await signOn(page, 'USER0001');
    await page.getByTestId('option-09').click();
    await expect(page.getByRole('heading', { name: 'Transaction report' })).toBeVisible();
    await page.getByRole('radio', { name: /^Custom/ }).check();
    await page.getByTestId('startMonth').fill('06');
    await page.getByTestId('startDay').fill('01');
    await page.getByTestId('startYear').fill('2022');
    await page.getByTestId('endMonth').fill('06');
    await page.getByTestId('endDay').fill('30');
    await page.getByTestId('endYear').fill('2022');
    await page.getByRole('button', { name: 'Continue' }).click();
    await expect(message(page)).toHaveText('Please confirm to print the Custom report...');
    await page.screenshot({ path: shot('33-report-confirm'), fullPage: true });
    await page.getByRole('radio', { name: 'Yes' }).check();
    await page.getByRole('button', { name: 'Print report' }).click();
    await expect(page.getByTestId('run-status')).toHaveText('Ready', { timeout: 20_000 });
    await page.screenshot({ path: shot('34-report-ready'), fullPage: true });
  });
});
