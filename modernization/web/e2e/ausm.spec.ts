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
  turnOnFunctionKeys,
} from './fixtures';

/** AUSM scenarios of the authorization-service contract (COPAUS0C). */

const acct = (page: Page) => page.getByTestId('acct-id');
const rows = (page: Page) => page.getByTestId(/^row-tran-\d$/);
const tran = (authId: number) => `TRN${String(authId).padStart(12, '0')}`;

async function openSummary(page: Page) {
  await signOnAsUser(page);
  await page.getByLabel('Option number').fill('11');
  await page.getByLabel('Option number').press('Enter');
  await expect(page).toHaveURL(/\/authorizations$/);
  await expect(page.getByRole('heading', { name: 'Pending authorizations' })).toBeVisible();
}

async function lookUp(page: Page, acctId: string) {
  await acct(page).fill(acctId);
  await acct(page).press('Enter');
}

test.describe('with the authorization module', () => {
  test.beforeEach(async ({ mock }) => mock({ latency: 0, authorizationInstalled: true }));

  test('@DEF-AUSM-012 @DEF-AUDT-009 without a session the page and its API refuse', async ({ page }) => {
    await page.goto('/authorizations');
    await expect(page).toHaveURL(/\/sign-on$/);
    await expectNoMessage(page);
    for (const path of ['/api/accounts/00000000001/authorizations/summary', '/api/accounts/00000000001/authorizations', '/api/authorizations/1012']) {
      const read = await callApi(page, 'GET', path);
      expect(read.status, path).toBe(401);
      expect(read.body?.code).toBe('UNAUTHENTICATED');
    }
  });

  test('@BR-MENU-004 @FLOW-AUSM-001 option 11 opens an empty search with focus on the account number', async ({ page }) => {
    await openSummary(page);
    await expect(acct(page)).toHaveValue('');
    await expect(acct(page)).toBeFocused();
    await expect(page.getByTestId('auth-table')).toHaveCount(0);
    await expectNoMessage(page);
    await expectHeader(page, 'CPVS · COPAUS0C');
    await expectAccessible(page);
  });

  test('@BR-AUSM-001 @BR-AUSM-002 @BR-AUSM-003 A1: totals and the five newest authorizations @mock-only', async ({ page }) => {
    await openSummary(page);
    await lookUp(page, '00000000001');
    await expect(rows(page)).toHaveText([1012, 1011, 1010, 1009, 1008].map(tran));
    await expect(page.getByTestId('auth-name')).toHaveText('Immanuel M Kessler');
    await expect(page.getByTestId('auth-approved-count')).toHaveText('8');
    await expect(page.getByTestId('auth-declined-count')).toHaveText('4');
    await expect(page.getByTestId('auth-credit-limit')).toHaveText('$2,020.00');
    await expect(page.getByTestId('auth-row-1')).toContainText('Approved');
    await expect(page.getByTestId('auth-row-2')).toContainText('Declined');
    await expectNoMessage(page);
    await expectAccessible(page);
  });

  test('@VAL-AUSM-005 A7: Previous and Next page the list; at either end the web says so without a call @mock-only', async ({ page }) => {
    await openSummary(page);
    await lookUp(page, '00000000001');
    await expect(rows(page)).toHaveCount(5);
    const calls = trackApi(page);
    const atTop = await callsDuring(page, calls, async () => {
      await page.getByTestId('previous').click();
      await expectMessage(page, 'You are already at the top of the page...', 'error');
    });
    expect(atTop).toEqual([]);
    await page.getByTestId('next').click();
    await expect(rows(page)).toHaveText([1007, 1006, 1005, 1004, 1003].map(tran));
    await expectNoMessage(page);
    await page.getByTestId('next').click();
    await expect(rows(page)).toHaveText([1002, 1001].map(tran));
    const atBottom = await callsDuring(page, calls, async () => {
      await page.getByTestId('next').click();
      await expectMessage(page, 'You are already at the bottom of the page...', 'error');
    });
    expect(atBottom).toEqual([]);
    await expectAccessible(page);
    await page.getByTestId('previous').click();
    await expect(rows(page).first()).toHaveText(tran(1007));
  });

  test('F7 and F8 page with the function keys on; F3 returns to the menu @mock-only', async ({ page }) => {
    await openSummary(page);
    await turnOnFunctionKeys(page);
    await lookUp(page, '00000000001');
    await expect(rows(page)).toHaveCount(5);
    await page.keyboard.press('F8');
    await expect(rows(page).first()).toHaveText(tran(1007));
    await page.keyboard.press('F7');
    await expect(rows(page).first()).toHaveText(tran(1012));
    await page.keyboard.press('F3');
    await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
  });

  test('@VAL-AUSM-001 @VAL-AUSM-002 A3 A4: required, numeric and not found mark the field', async ({ page }) => {
    await openSummary(page);
    await acct(page).press('Enter');
    await expectMessage(page, 'Please enter Acct Id...', 'error');
    await expect(acct(page)).toHaveAttribute('aria-invalid', 'true');
    await lookUp(page, '0000000000A');
    await expectMessage(page, 'Acct Id must be Numeric ...', 'error');
    await lookUp(page, '99999999999');
    await expectMessage(page, 'Account:99999999999 not found in ACCT file.', 'error');
    await expect(acct(page)).toHaveAttribute('aria-invalid', 'true');
    await expect(acct(page)).toBeFocused();
    await expect(page.getByTestId('auth-table')).toHaveCount(0);
    await expectAccessible(page);
  });

  test('@DEF-AUSM-004 @DEF-AUSM-001 @ERR-AUSM-003 A6: name and address cuts, a missing customer keeps the totals @mock-only', async ({ page }) => {
    await openSummary(page);
    await lookUp(page, '00000000097');
    await expect(page.getByTestId('auth-name')).toBeVisible();
    // toHaveText collapses runs of spaces, and the name keeps its own.
    expect(await page.getByTestId('auth-name').textContent()).toBe('Maximiliana   Vandenbergh');
    await expect(page.getByTestId('auth-address1')).toHaveText('12,Unit 4');
    await expect(page.getByTestId('auth-credit-balance')).toHaveText('$1,234,567,890.12');
    await expectAccessible(page);
    await lookUp(page, '00000000099');
    await expectMessage(page, 'Customer:000000099 not found in CUST file.', 'error');
    await expect(page.getByTestId('auth-customer-empty')).toBeVisible();
    await expect(rows(page)).toHaveCount(2);
    await expect(acct(page)).not.toHaveAttribute('aria-invalid', 'true');
  });

  test('@ERR-AUSM-004 A23: a failed summary read shows its text and no list @mock-only', async ({ page }) => {
    await openSummary(page);
    await setFaults(page, ['auth-summary-read']);
    await lookUp(page, '00000000001');
    await expectMessage(page, ' System error while reading AUTH Summary', 'error');
    await expect(rows(page)).toHaveCount(0);
  });

  test('@BR-AUSM-007 @BR-AUDT-005 View opens the detail; Back returns to the same account @mock-only', async ({ page }) => {
    await openSummary(page);
    await lookUp(page, '00000000002');
    await expect(rows(page)).toHaveCount(5);
    await page.getByTestId('view-3').click();
    await expect(page).toHaveURL(/\/authorizations\/2003$/);
    await expect(page.getByTestId('detail-tran')).toHaveText(tran(2003));
    await page.getByRole('button', { name: /^Back/ }).click();
    await expect(page).toHaveURL(/\/authorizations$/);
    await expect(acct(page)).toHaveValue('00000000002');
    await expect(rows(page)).toHaveCount(5);
  });
  test('a narrow screen lists the authorizations as cards that open with the keyboard @mock-only', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openSummary(page);
    await lookUp(page, '00000000001');
    await expect(page.getByTestId('auth-cards').getByRole('listitem')).toHaveCount(5);
    await expect(page.getByTestId('auth-table')).toHaveCount(0);
    await expect(page.getByTestId('auth-row-2')).toContainText('Declined');
    await expectAccessible(page);
    const link = page.getByRole('link', { name: `View authorization ${tran(1011)}` });
    await link.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/authorizations\/1011$/);
    await expect(page.getByTestId('detail-reason')).toHaveText('9999-ERROR');
    await expectAccessible(page);
  });
});

test.describe('without the authorization module', () => {
  test.beforeEach(async ({ mock }) => mock({ latency: 0 }));

  test('@BR-MENU-004 the pages do not exist and option 11 is not installed', async ({ page }) => {
    await signOnAsUser(page);
    await expect(page.getByTestId('option-11')).toContainText('Not installed');
    for (const path of ['/authorizations', '/authorizations/1012']) {
      await page.goto(path);
      await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
      await expect(page.getByRole('heading', { name: /authorization/i })).toHaveCount(0);
    }
    const read = await callApi(page, 'GET', '/api/accounts/00000000001/authorizations/summary');
    expect(read.status).toBe(404);
  });
});
