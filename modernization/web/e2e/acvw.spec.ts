import type { Page } from '@playwright/test';
import {
  callApi,
  callsDuring,
  expect,
  expectAccessible,
  expectHeader,
  expectMessage,
  expectNoMessage,
  setFaults,
  signOnAsUser,
  test,
  trackApi,
} from './fixtures';

/** ACVW scenarios of the account-service contract (COACTVWC, section 5). */

const acct = (page: Page) => page.getByTestId('acct-id');
const info = (page: Page) => page.getByTestId('info-line');

async function openView(page: Page) {
  await signOnAsUser(page);
  await page.getByTestId('option-01').click();
  await expect(page.getByRole('heading', { name: 'Account view' })).toBeVisible();
}

async function view(page: Page, acctId: string) {
  await acct(page).fill(acctId);
  await acct(page).press('Enter');
}

test.beforeEach(async ({ mock }) => mock({ latency: 0 }));

test('@DEF-ACVW-009 ACVW-S1: without a session the page and its API refuse', async ({ page }) => {
  await page.goto('/accounts/view');
  await expect(page).toHaveURL(/\/sign-on$/);
  await expectNoMessage(page);
  const read = await callApi(page, 'GET', '/api/accounts/00000000001');
  expect(read.status).toBe(401);
  expect(read.body?.code).toBe('UNAUTHENTICATED');
});

test('@DEF-ACVW-009 ACVW-S17: view and unmask without a session get 401', async ({ page }) => {
  await page.goto('/sign-on');
  await expect(page.getByRole('heading', { name: 'Sign on' })).toBeVisible();
  const read = await callApi(page, 'GET', '/api/accounts/00000000001');
  expect(read.status).toBe(401);
  expect(read.body?.code).toBe('UNAUTHENTICATED');
  const unmask = await callApi(page, 'POST', '/api/accounts/00000000001/unmask', { body: { fields: ['ssn'] } });
  expect(unmask.status).toBe(401);
  expect(unmask.body?.code).toBe('UNAUTHENTICATED');
});

test('ACVW-S2: option 1 opens an empty search with focus on the account number', async ({ page }) => {
  await openView(page);
  await expect(acct(page)).toHaveValue('');
  await expect(acct(page)).toBeFocused();
  await expect(info(page)).toHaveText('Enter or update id of account to display');
  await expectNoMessage(page);
  await expectHeader(page, 'CAVW · COACTVWC');
  await expectAccessible(page);
});

test('ACVW-S3: Enter with no account number is refused with no call', async ({ page }) => {
  await openView(page);
  const calls = trackApi(page);
  const made = await callsDuring(page, calls, async () => {
    await acct(page).press('Enter');
    await expectMessage(page, 'No input received', 'error');
  });
  expect(made).toEqual([]);
  await expect(acct(page)).toHaveAttribute('aria-invalid', 'true');
  await expect(acct(page)).toBeFocused();
});

test('@D-09 ACVW-S6: the account, its customer and card, with PII masked', async ({ page }) => {
  await openView(page);
  await view(page, '00000000001');
  await expect(page.getByTestId('customer-panel')).toBeVisible();
  await expect(page.getByTestId('acct-balance')).toContainText('194.00');
  await expect(page.getByTestId('acct-open-date')).toHaveText('2014-11-20');
  await expect(page.getByTestId('cust-id')).toHaveText('000000001');
  await expect(page.getByTestId('cust-city')).toHaveText('Altenwerthshire');
  await expect(page.getByTestId('cust-zip')).toHaveText('12546');
  await expect(page.getByTestId('cust-phone1')).toHaveText('(908)119-8310');
  await expect(page.getByTestId('card-num')).toContainText('************3697');
  await expect(page.getByTestId('customer-panel')).not.toContainText('020-97-3888');
  await expect(page.getByRole('button', { name: /^Show/ })).toHaveCount(0);
  await expectNoMessage(page);
  await expectAccessible(page);
});

test('ACVW-S8 ACVW-S16: an unknown account keeps the typed ID in red and drops the earlier customer', async ({ page }) => {
  await openView(page);
  await view(page, '00000000001');
  await expect(page.getByTestId('customer-panel')).toBeVisible();
  await view(page, '99999999999');
  await expectMessage(page, 'Account 99999999999 not found.', 'error');
  await expect(acct(page)).toHaveValue('99999999999');
  await expect(acct(page)).toHaveAttribute('aria-invalid', 'true');
  await expect(page.getByTestId('customer-panel')).toHaveCount(0);
  await expect(page.getByTestId('cust-id')).toHaveCount(0);
  await expect(page.getByTestId('card-num')).toHaveCount(0);
});

test('@DEF-ACVW-011 ACVW-S13: Back returns to the main menu with the role unchanged', async ({ page }) => {
  await openView(page);
  await view(page, '00000000001');
  await expect(page.getByTestId('customer-panel')).toBeVisible();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
  const me = await callApi(page, 'GET', '/api/auth/me');
  expect(me.body).toMatchObject({ userId: 'USER0001', role: 'USER' });
});

test('@ERR-ACVW-004 an unexpected failure shows the error page and keeps the session @mock-only', async ({ page }) => {
  await openView(page);
  await setFaults(page, ['unexpected']);
  await view(page, '00000000001');
  await expect(page.getByRole('heading', { name: 'Something went wrong' })).toBeVisible();
  await expectMessage(page, 'An unexpected error occurred. Please try again later ...', 'error');
  await expect(page.getByTestId('error-reference')).toContainText('Reference');
  await expect(page.getByRole('button', { name: 'Try again' })).toBeFocused();
  await expectAccessible(page);
  await setFaults(page, []);
  await page.getByRole('button', { name: 'Try again' }).click();
  await expect(acct(page)).toHaveValue('');
  await expect(acct(page)).toBeFocused();
  const me = await callApi(page, 'GET', '/api/auth/me');
  expect(me.status).toBe(200);
});
