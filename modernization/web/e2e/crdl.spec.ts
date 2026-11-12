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

/**
 * CRDL scenarios of the account-service contract (COCRDLIC, section 7), with the card view
 * and card update pages they open. Start data: the 50 sample cards, signed on as USER0001.
 */

const INFO = 'TYPE S FOR DETAIL, U TO UPDATE ANY RECORD';
const acctFilter = (page: Page) => page.getByTestId('acct-filter');
const cardFilter = (page: Page) => page.getByTestId('card-filter');
const info = (page: Page) => page.getByTestId('info-line');
const rowAcct = (page: Page, n: number) => page.getByTestId(`row-acct-${n}`);
const rowCard = (page: Page, n: number) => page.getByTestId(`row-card-${n}`);

async function openList(page: Page) {
  await signOnAsUser(page);
  await page.getByTestId('option-03').click();
  await expect(page.getByRole('heading', { name: 'Credit cards' })).toBeVisible();
  await expect(rowAcct(page, 1)).toHaveText('00000000050');
}

/** Page 1 of all cards with empty filters (S1). */
async function expectFirstPage(page: Page) {
  await expect(page.getByTestId('page-number')).toHaveText('1');
  await expect(rowAcct(page, 1)).toHaveText('00000000050');
  await expect(rowCard(page, 1)).toContainText('************5740');
  await expect(rowAcct(page, 7)).toHaveText('00000000037');
  await expect(rowCard(page, 7)).toContainText('************8931');
  await expect(acctFilter(page)).toHaveValue('');
  await expect(cardFilter(page)).toHaveValue('');
  await expect(info(page)).toHaveText(INFO);
}

async function expectSessionUnchanged(page: Page) {
  const me = await callApi(page, 'GET', '/api/auth/me');
  expect(me.status).toBe(200);
  expect(me.body).toMatchObject({ userId: 'USER0001', role: 'USER' });
}

test.beforeEach(async ({ mock }) => mock({ latency: 0 }));

test('@FLOW-CRDL-001 @DEF-CRDL-003 CRDL-S20: without a session the list and its API refuse', async ({ page }) => {
  await page.goto('/cards');
  await expect(page).toHaveURL(/\/sign-on$/);
  await expectNoMessage(page);
  const list = await callApi(page, 'POST', '/api/cards/search', { body: {} });
  expect(list.status).toBe(401);
  expect(list.body?.code).toBe('UNAUTHENTICATED');
});

test('@FLOW-CRDL-003 CRDL-S1: option 3 shows page 1 of all cards with focus on the account filter', async ({ page }) => {
  await openList(page);
  await expectFirstPage(page);
  await expectNoMessage(page);
  await expect(acctFilter(page)).toBeFocused();
  await expectHeader(page, 'CCLI · COCRDLIC');
  await expectAccessible(page);
});

test('@FLOW-CRDL-009 @DEF-CRDL-004 CRDL-S13: View opens the card by its ID, never its number', async ({ page }) => {
  await openList(page);
  await page.getByTestId('view-1').click();
  await expect(page.getByTestId('card-panel')).toBeVisible();
  await expect(page).toHaveURL(/\/cards\/[^/]+$/);
  expect(page.url()).not.toContain('0500024453765740');
  await expect(page.getByTestId('card-acct')).toHaveText('00000000050');
  await expectHeader(page, 'CCDL · COCRDSLC');
  await expectSessionUnchanged(page);
  await expectAccessible(page);
});

test('@FLOW-CRDL-010 CRDL-S14: Update opens the card update page for that row', async ({ page }) => {
  await openList(page);
  await page.getByTestId('update-2').click();
  await expect(page.getByRole('heading', { name: 'Update credit card' })).toBeVisible();
  await expect(page).toHaveURL(/\/cards\/[^/]+\/edit$/);
  expect(page.url()).not.toContain('0683586198171516');
  await expect(page.getByTestId('acct-id')).toHaveValue('00000000027');
  await expectAccessible(page);
});

test('@FLOW-CRDL-008 @DEF-CRDL-005 CRDL-S21: Back from card view shows page 1 of all cards, empty filters, the session unchanged', async ({ page }) => {
  await openList(page);
  await acctFilter(page).fill('00000000027');
  await acctFilter(page).press('Enter');
  await expect(rowAcct(page, 1)).toHaveText('00000000027');
  await expect(rowAcct(page, 2)).toHaveCount(0);
  await page.getByTestId('view-1').click();
  await expect(page.getByTestId('card-panel')).toBeVisible();
  await page.getByRole('button', { name: 'Back to card list' }).click();
  await expectFirstPage(page);
  await expect(acctFilter(page)).toBeFocused();
  await expectSessionUnchanged(page);
});

test('@FLOW-CRDL-008 CRDL-S21: Back from card update shows page 1 of all cards, empty filters, the session unchanged', async ({ page }) => {
  await openList(page);
  await page.getByTestId('next').click();
  await expect(page.getByTestId('page-number')).toHaveText('2');
  await page.getByTestId('update-1').click();
  await expect(page.getByRole('heading', { name: 'Update credit card' })).toBeVisible();
  await page.getByRole('button', { name: 'Back to card list' }).click();
  await expectFirstPage(page);
  await expectSessionUnchanged(page);
});

test('@BR-CRDL-007 @SE-CRDL-003 @FLOW-CRDL-009: a different valid filter typed with a selection is not applied; the selected row opens', async ({ page }) => {
  await openList(page);
  await turnOnFunctionKeys(page);
  await acctFilter(page).fill('00000000001');
  await page.getByTestId('sel-2').fill('S');
  await page.getByTestId('sel-2').press('Enter');
  await expect(page.getByTestId('card-panel')).toBeVisible();
  await expect(page.getByTestId('card-acct')).toHaveText('00000000027');
  await expect(page.getByTestId('card-num-shown')).toContainText('1516');
  expect(page.url()).not.toContain('0683586198171516');
});

test('@BR-CRDL-007 @FLOW-CRDL-010: a different valid card filter typed with U opens the selected row for update', async ({ page }) => {
  await openList(page);
  await turnOnFunctionKeys(page);
  await cardFilter(page).fill('9680294154603697');
  await page.getByTestId('sel-3').fill('u');
  await page.getByTestId('sel-3').press('Enter');
  await expect(page.getByRole('heading', { name: 'Update credit card' })).toBeVisible();
  await expect(page.getByTestId('acct-id')).toHaveValue('00000000002');
});

test('@BR-CRDL-007 @VAL-CRDL-003: an invalid filter typed with a selection is checked; nothing opens and no call is made', async ({ page }) => {
  await openList(page);
  await turnOnFunctionKeys(page);
  const calls = trackApi(page);
  const made = await callsDuring(page, calls, async () => {
    await acctFilter(page).fill('12345');
    await page.getByTestId('sel-2').fill('S');
    await page.getByTestId('sel-2').press('Enter');
    await expectMessage(page, 'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER', 'error');
  });
  expect(made).toEqual([]);
  await expect(acctFilter(page)).toBeFocused();
  await expect(page.getByRole('heading', { name: 'Credit cards' })).toBeVisible();
});

test('@ERR-CRDV-002 an unexpected failure opening a card shows the error page; Back returns to the list @mock-only', async ({ page }) => {
  await openList(page);
  await setFaults(page, ['unexpected']);
  await page.getByTestId('view-1').click();
  await expect(page.getByRole('heading', { name: 'Something went wrong' })).toBeVisible();
  await expectMessage(page, 'An unexpected error occurred. Please try again later ...', 'error');
  await expect(page.getByTestId('error-reference')).toContainText('Reference');
  await expectAccessible(page);
  await setFaults(page, []);
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Credit cards' })).toBeVisible();
  await expectSessionUnchanged(page);
});
