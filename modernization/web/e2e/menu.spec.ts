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

test('@FLOW-MENU-001 @DEF-MENU-003 @DEF-MENU-005 MENU-S1: without a session / goes to sign-on', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/sign-on$/);
  await expectNoMessage(page);
});

test('@BR-MENU-001 @FLOW-MENU-002 @FLOW-MENU-007 MENU-S2: USER0001 sees the eleven options in order, an empty option and no message', async ({ page }) => {
  await signOnAsUser(page);
  const tiles = page.getByRole('list', { name: 'Main menu options' }).getByRole('button');
  await expect(tiles).toHaveCount(11);
  for (const [index, name] of MAIN.entries()) {
    await expect(tiles.nth(index)).toContainText(`${String(index + 1).padStart(2, '0')}. ${name}`);
  }
  await expect(optionField(page)).toBeFocused();
  await expect(optionField(page)).toHaveValue('');
  await expectNoMessage(page);
  await expectHeader(page, 'CM00 · COMEN01C');
  await expectAccessible(page);
});

test('@BR-MENU-002 @FLOW-MENU-003 MENU-S3: option 10 and Enter open Bill Pay', async ({ page }) => {
  await signOnAsUser(page);
  await typeOption(page, '10');
  await expect(page).toHaveURL(/\/bill-pay$/);
  await expect(page.getByRole('heading', { name: 'Bill payment' })).toBeVisible();
});

test('@BR-MENU-002 @FLOW-MENU-003 MENU-S4: option 8 opens Transaction Add and option 9 Transaction Reports', async ({ page }) => {
  await signOnAsUser(page);
  await typeOption(page, '8');
  await expect(page).toHaveURL(/\/transactions\/new$/);
  await expect(page.getByRole('heading', { name: 'Add a transaction' })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
  await page.getByTestId('option-09').click();
  await expect(page).toHaveURL(/\/reports\/transactions$/);
  await expect(page.getByRole('heading', { name: 'Transaction report' })).toBeVisible();
});

test('@VAL-MENU-001 @NUM-MENU-001 @DEF-MENU-001 MENU-S5: blank, 00, 12 and 99 are not valid options and go nowhere', async ({ page }) => {
  await signOnAsUser(page);
  for (const value of ['', '00', '12', '99']) {
    await typeOption(page, value);
    await expectMessage(page, 'Please enter a valid option number...', 'error');
    await expect(page).toHaveURL(/\/$/);
  }
});

test('@BR-MENU-004 MENU-S6: option 11 without the authorization module is not installed, in red', async ({ page }) => {
  await signOnAsUser(page);
  await typeOption(page, '11');
  await expectMessage(page, 'This option Pending Authorization View is not installed...', 'error');
  await expect(page.getByTestId('option-11')).toContainText('Not installed');
});

test('@FLOW-MENU-004 MENU-S8: Back ends the session and shows an empty sign-on page', async ({ page }) => {
  await signOnAsUser(page);
  await page.getByRole('button', { name: /^Sign out/ }).click();
  await expect(page).toHaveURL(/\/sign-on$/);
  await expectNoMessage(page);
  expect((await callApi(page, 'GET', '/api/auth/me')).status).toBe(401);
  await page.goto('/');
  await expect(page).toHaveURL(/\/sign-on$/);
});

test('@VAL-MENU-002 @FLOW-MENU-005 MENU-S9: F7 with the function keys on is an invalid key; F3 signs off', async ({ page }) => {
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

test('@BR-SGN-004 @BR-MENU-003 MENU-S10: ADMIN001 who opens / goes to the admin menu', async ({ page }) => {
  await signOn(page, 'ADMIN001');
  await expect(page).toHaveURL(/\/admin$/);
  await page.goto('/');
  await expect(page).toHaveURL(/\/admin$/);
  await expect(page.getByRole('heading', { name: 'Admin menu' })).toBeVisible();
});

test('@BR-MENU-003 MENU-S11: USER0001 who opens /admin goes to the main menu', async ({ page }) => {
  await signOnAsUser(page);
  await page.goto('/admin');
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Admin menu' })).toHaveCount(0);
  const me = await callApi(page, 'GET', '/api/auth/me');
  expect(me.body).toMatchObject({ userId: 'USER0001', role: 'USER' });
});

test('@DEF-SGN-012 MENU-S12: /admin without a session goes to sign-on', async ({ page }) => {
  await page.goto('/admin');
  await expect(page).toHaveURL(/\/sign-on$/);
  await expect(page.getByRole('heading', { name: 'Admin menu' })).toHaveCount(0);
  await expectNoMessage(page);
});

test('@D-70 @D-09 admin menu: options 1 to 4 open the user pages, 5 and 6 are hidden, 7 is the read-only lookup', async ({ page }) => {
  await signOn(page, 'ADMIN001');
  await expect(page.getByRole('heading', { name: 'Admin menu' })).toBeVisible();
  const tiles = page.getByRole('list', { name: 'Admin menu options' }).getByRole('button');
  await expect(tiles).toHaveCount(5);
  await expect(tiles.nth(4)).toContainText('07. Account and Card View');
  for (const value of ['5', '6']) {
    await typeOption(page, value);
    await expectMessage(page, 'Please enter a valid option number...', 'error');
  }
  await expectHeader(page, 'CA00 · COADM01C');
  await expectAccessible(page);
  await page.goto('/bill-pay');
  await expect(page).toHaveURL(/\/admin$/);
  await typeOption(page, '7');
  await expect(page).toHaveURL(/\/admin\/lookup$/);
  await page.goto('/admin');
  await page.getByTestId('option-01').click();
  await expect(page).toHaveURL(/\/admin\/users$/);
  await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible();
});

test('@D-09 admin lookup: links to the read-only pages, and Back from them returns here', async ({ page }) => {
  await signOn(page, 'ADMIN001');
  await page.getByTestId('option-07').click();
  await expect(page.getByRole('heading', { name: 'Account and card view' })).toBeVisible();
  await expectHeader(page, 'CA00 · COADM01C');
  await expectAccessible(page);
  await page.getByTestId('lookup-transaction').click();
  await expect(page).toHaveURL(/\/transactions\/lookup$/);
  await page.getByTestId('back').click();
  await expect(page).toHaveURL(/\/admin\/lookup$/);
  await page.getByTestId('lookup-account').click();
  await expect(page.getByRole('heading', { name: 'Account view' })).toBeVisible();
  await page.getByRole('button', { name: /^Back/ }).click();
  await expect(page).toHaveURL(/\/admin\/lookup$/);
  await page.getByRole('button', { name: /^Back/ }).click();
  await expect(page).toHaveURL(/\/admin$/);
});
