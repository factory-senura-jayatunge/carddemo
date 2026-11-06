import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { expect, message, signOn, test } from './fixtures';

/**
 * Screens for review, on mock data. CARDDEMO_SCREENSHOT_DIR changes where they
 * go. Mock-only: they pay account 00000000001, which a stack run must keep.
 */
const dir = process.env.CARDDEMO_SCREENSHOT_DIR ?? join(import.meta.dirname, '..', 'test-results', 'screens');
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
    await page.getByTestId('option-05').click();
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
    await signOn(page, 'USER0001');
    await page.goto('/bill-pay');
    await page.getByLabel('Account ID').fill('00000000002');
    await page.getByRole('radio', { name: 'Yes' }).check();
    await page.getByRole('button', { name: 'Pay the full balance' }).click();
    await expect(message(page)).toHaveText('Bill payment is not available while the batch cycle runs. Try again later...');
    await page.screenshot({ path: shot('07-bill-pay-batch-window') });
  });

  test('narrow screen', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signOn(page, 'USER0001');
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
});
