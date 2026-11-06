import type { Page } from '@playwright/test';
import { callApi, expect, expectAccessible, expectHeader, expectMessage, expectNoMessage, signOn, signOnAsUser, test, turnOnFunctionKeys } from './fixtures';

/** MENU scenarios of the slice contract (COMEN01C section 15), and the COADM01C stub. */

const optionField = (page: Page) => page.getByLabel('Option number');

async function typeOption(page: Page, value: string) {
  await optionField(page).fill(value);
  await optionField(page).press('Enter');
}

const MAIN = [
  'Account View',
  'Account Update',
  'Credit Card List',
  'Credit Card View',
  'Credit Card Update',
  'Transaction List',
  'Transaction View',
  'Transaction Add',
  'Transaction Reports',
  'Bill Payment',
  'Pending Authorization View',
];

test.beforeEach(async ({ mock }) => mock({ latency: 0 }));

test('[DEF-MENU-003] [DEF-SGN-012] MENU-S1: without a session / goes to sign-on', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/sign-on$/);
  await expectNoMessage(page);
});

test('[BR-MENU-001] [FLOW-MENU-007] MENU-S2: USER0001 sees the eleven options in order', async ({ page }) => {
  await signOnAsUser(page);
  const tiles = page.getByRole('list', { name: 'Main menu options' }).getByRole('button');
  await expect(tiles).toHaveCount(11);
  for (const [index, name] of MAIN.entries()) {
    await expect(tiles.nth(index)).toContainText(`${String(index + 1).padStart(2, '0')}. ${name}`);
  }
  await expect(optionField(page)).toBeFocused();
  await expectHeader(page, 'CM00 · COMEN01C');
  await expectAccessible(page);
});

test('[BR-MENU-002] MENU-S3: option 10 opens Bill Pay', async ({ page }) => {
  await signOnAsUser(page);
  await typeOption(page, '10');
  await expect(page).toHaveURL(/\/bill-pay$/);
  await expect(page.getByRole('heading', { name: 'Bill payment' })).toBeVisible();
});

test('[BR-MENU-005] MENU-S4: option 5 is coming soon, in green', async ({ page }) => {
  await signOnAsUser(page);
  await typeOption(page, '5');
  await expectMessage(page, 'This option Credit Card Update is coming soon ...', 'success');
  await page.getByTestId('option-07').click();
  await expectMessage(page, 'This option Transaction View is coming soon ...', 'success');
  await expectAccessible(page);
});

test('[VAL-MENU-001] [NUM-MENU-001] MENU-S5: blank, 00, 12 and 99 are not valid options', async ({ page }) => {
  await signOnAsUser(page);
  for (const value of ['', '00', '12', '99']) {
    await typeOption(page, value);
    await expectMessage(page, 'Please enter a valid option number...', 'error');
    await expect(page).toHaveURL(/\/$/);
  }
});

test('[BR-MENU-004] MENU-S6: option 11 without the authorization module is not installed, in red', async ({ page }) => {
  await signOnAsUser(page);
  await typeOption(page, '11');
  await expectMessage(page, 'This option Pending Authorization View is not installed...', 'error');
  await expect(page.getByTestId('option-11')).toContainText('Not installed');
});

test('[FLOW-MENU-004] MENU-S8: Back ends the session and shows an empty sign-on page', async ({ page }) => {
  await signOnAsUser(page);
  await page.getByRole('button', { name: /^Sign out/ }).click();
  await expect(page).toHaveURL(/\/sign-on$/);
  await expectNoMessage(page);
  expect((await callApi(page, 'GET', '/api/auth/me')).status).toBe(401);
  await page.goto('/');
  await expect(page).toHaveURL(/\/sign-on$/);
});

test('[VAL-MENU-002] [FLOW-MENU-005] MENU-S9: F7 with the function keys on is an invalid key; F3 signs off', async ({ page }) => {
  await signOnAsUser(page);
  await turnOnFunctionKeys(page);
  await optionField(page).fill('3');
  await optionField(page).press('F7');
  await expectMessage(page, 'Invalid key pressed. Please see below...', 'error');
  await expect(optionField(page)).toHaveValue('3');
  await optionField(page).press('F3');
  await expect(page).toHaveURL(/\/sign-on$/);
  await expectNoMessage(page);
});

test('[BR-SGN-004] MENU-S10: ADMIN001 who opens / goes to the admin menu', async ({ page }) => {
  await signOn(page, 'ADMIN001');
  await expect(page).toHaveURL(/\/admin$/);
  await page.goto('/');
  await expect(page).toHaveURL(/\/admin$/);
});

test('[D-70] admin menu stub: options 1 to 4 are coming soon, 5 and 6 are hidden', async ({ page }) => {
  await signOn(page, 'ADMIN001');
  await expect(page.getByRole('heading', { name: 'Admin menu' })).toBeVisible();
  const tiles = page.getByRole('list', { name: 'Admin menu options' }).getByRole('button');
  await expect(tiles).toHaveCount(4);
  await page.getByTestId('option-01').click();
  await expectMessage(page, 'This option User List (Security) is coming soon ...', 'success');
  for (const value of ['5', '6']) {
    await typeOption(page, value);
    await expectMessage(page, 'Please enter a valid option number...', 'error');
  }
  await expectHeader(page, 'CA00 · COADM01C');
  await expectAccessible(page);
  await page.goto('/bill-pay');
  await expect(page).toHaveURL(/\/admin$/);
});
