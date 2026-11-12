import type { Page } from '@playwright/test';
import {
  callApi,
  callsDuring,
  expect,
  expectAccessible,
  expectHeader,
  expectMessage,
  expectNoMessage,
  signOn,
  signOnAsUser,
  test,
  trackApi,
  turnOnFunctionKeys,
} from './fixtures';

/**
 * TRNL and TRNV of the transaction-service contract (COTRN00C, COTRN01C) and the
 * ADMIN unmask of the card number. Mock-only: the rows are the 300 of
 * dailytran.txt in the mock, which a stack's posted master need not match.
 */

const rowId = (page: Page, n: number) => page.getByTestId(`row-id-${n}`);
const pageNumber = (page: Page) => page.getByTestId('page-number');
const search = (page: Page) => page.getByTestId('tran-search');

async function openList(page: Page) {
  await signOnAsUser(page);
  await page.getByTestId('option-06').click();
  await expect(page.getByRole('heading', { name: 'Transactions', level: 1 })).toBeVisible();
  await expect(rowId(page, 1)).toHaveText('0000000000683580');
}

test.describe('transactions @mock-only', () => {
  test.beforeEach(async ({ mock }) => mock({ latency: 0 }));

  test('@FLOW-TRNL-001 @FLOW-TRNV-001 without a session the pages and the API refuse', async ({ page }) => {
    await page.goto('/transactions');
    await expect(page).toHaveURL(/\/sign-on$/);
    await page.goto('/transactions/0000000000683580');
    await expect(page).toHaveURL(/\/sign-on$/);
    expect((await callApi(page, 'GET', '/api/transactions')).status).toBe(401);
  });

  test('@BR-TRNL-001 @BR-TRNL-002 @BR-TRNL-006 @NUM-TRNL-001 @NUM-TRNL-002 menu option 6: ten rows in ID order for a USER', async ({ page }) => {
    await openList(page);
    await expectHeader(page, 'CT00 · COTRN00C');
    await expect(page.getByTestId(/^row-id-\d+$/)).toHaveCount(10);
    await expect(page.getByTestId('row-date-1')).toHaveText('06/10/22');
    await expect(page.getByTestId('row-desc-2')).toHaveText('Return item at Nitzsche, N');
    await expect(page.getByTestId('row-amount-2')).toHaveText('-00000919.00');
    await expect(pageNumber(page)).toHaveText('1');
    await expectNoMessage(page);
    await expect(search(page)).toBeFocused();
    await expectAccessible(page);
  });

  test('@FLOW-TRNL-005 @FLOW-TRNL-006 @VAL-TRNL-004 @BR-TRNL-004 Next and Previous, the top notice, and a reload keeps the place', async ({ page }) => {
    await openList(page);
    const calls = trackApi(page);
    const atTop = await callsDuring(page, calls, async () => {
      await page.getByTestId('previous').click();
      await expectMessage(page, 'You are already at the top of the page...', 'info');
    });
    expect(atTop).toEqual([]);
    await page.getByTestId('next').click();
    await expect(rowId(page, 1)).toHaveText('0000000025430891');
    await expect(pageNumber(page)).toHaveText('2');
    await expect(page).toHaveURL(/\/transactions\?first=0000000025430891&page=2$/);
    await page.reload();
    await expect(rowId(page, 1)).toHaveText('0000000025430891');
    await expect(pageNumber(page)).toHaveText('2');
    await page.getByTestId('previous').click();
    await expect(rowId(page, 1)).toHaveText('0000000000683580');
    await expectMessage(page, 'You have reached the top of the page...', 'info');
  });

  test('@BR-TRNL-005 @VAL-TRNL-005 the last page has the bottom notice and Next stays there', async ({ page }) => {
    await signOnAsUser(page);
    await page.goto('/transactions?first=0000000957864065&page=30');
    await expect(rowId(page, 10)).toHaveText('0000000996722787');
    await expect(pageNumber(page)).toHaveText('30');
    await expectMessage(page, 'You have reached the bottom of the page...', 'info');
    await page.getByTestId('next').click();
    await expectMessage(page, 'You are already at the bottom of the page...', 'info');
  });

  test('@VAL-TRNL-001 @DEF-TRNL-006 the search: short IDs padded, trailing spaces allowed, no length cap, non-digits refused', async ({ page }) => {
    await openList(page);
    await search(page).fill('1774260');
    await search(page).press('Enter');
    await expect(rowId(page, 1)).toHaveText('0000000001774260');
    await search(page).fill('0000000001774260  ');
    await search(page).press('Enter');
    await expect(rowId(page, 1)).toHaveText('0000000001774260');
    await expect(search(page)).toHaveValue('0000000001774260  ');
    for (const bad of ['12a', '00000000017742600']) {
      await search(page).fill(bad);
      await search(page).press('Enter');
      await expectMessage(page, 'Tran ID must be Numeric ...', 'error');
      await expect(search(page)).toHaveAttribute('aria-invalid', 'true');
    }
    await expectAccessible(page);
  });

  test('@BR-TRNL-003 @VAL-TRNL-002 @FLOW-TRNL-010 @BR-TRNV-003 with the function keys on, S opens the row; another code is refused', async ({ page }) => {
    await openList(page);
    await turnOnFunctionKeys(page);
    await page.getByTestId('sel-2').fill('x');
    await page.getByTestId('sel-3').fill('s');
    await page.getByTestId('sel-3').press('Enter');
    await expectMessage(page, 'Invalid selection. Valid value is S', 'error');
    await expect(page.getByTestId('sel-2')).toHaveAttribute('aria-invalid', 'true');
    await expectAccessible(page);
    await page.getByTestId('sel-2').fill('');
    await page.getByTestId('sel-3').press('Enter');
    await expect(page).toHaveURL(/\/transactions\/0000000006292564$/);
    await expect(page.getByTestId('tran-panel')).toBeVisible();
    await expectHeader(page, 'CT01 · COTRN01C');
  });

  test('@BR-TRNV-002 @FLOW-TRNV-004 @DEF-TRNV-004 View shows the masked card to a USER; Back returns to page 1', async ({ page }) => {
    await openList(page);
    await page.getByTestId('next').click();
    await expect(pageNumber(page)).toHaveText('2');
    await page.getByTestId('view-10').click();
    await expect(page).toHaveURL(/\/transactions\/0000000054727064$/);
    await page.getByTestId('back').click();
    await expect(rowId(page, 1)).toHaveText('0000000000683580');
    await expect(pageNumber(page)).toHaveText('1');
    await page.getByTestId('view-1').click();
    await expect(page.getByTestId('tran-desc')).toHaveText('Purchase at Abshire-Lowe');
    await expect(page.getByTestId('tran-amount')).toHaveText('+00000504.77');
    await expect(page.getByTestId('tran-card')).toHaveText('************7065');
    await expect(page.getByTestId('tran-card-toggle')).toHaveCount(0);
    await expectAccessible(page);
    expect((await callApi(page, 'POST', '/api/transactions/0000000000683580/unmask', { body: { fields: ['cardNum'] } })).status).toBe(403);
  });

  test('@FLOW-TRNV-002 @VAL-TRNV-001 @VAL-TRNV-002 @FLOW-TRNV-005 @FLOW-TRNV-006 menu option 7: required, not found, Clear and the list', async ({ page }) => {
    await signOnAsUser(page);
    await page.getByTestId('option-07').click();
    await expect(page).toHaveURL(/\/transactions\/lookup$/);
    await expect(page.getByTestId('tran-panel-empty')).toBeVisible();
    await page.getByTestId('submit').click();
    await expectMessage(page, 'Tran ID can NOT be empty...', 'error');
    await page.getByTestId('tran-id-input').fill('12345');
    await page.getByTestId('tran-id-input').press('Enter');
    await expectMessage(page, 'Transaction ID NOT found...', 'error');
    await expectAccessible(page);
    await page.getByTestId('tran-id-input').fill('683580');
    await page.getByTestId('tran-id-input').press('Enter');
    await expect(page.getByTestId('tran-id')).toHaveText('0000000000683580');
    await page.getByTestId('clear').click();
    await expect(page.getByTestId('tran-id-input')).toHaveValue('');
    await expect(page.getByTestId('tran-panel-empty')).toBeVisible();
    await page.getByTestId('browse').click();
    await expect(page).toHaveURL(/\/transactions(\?.*)?$/);
  });

  test('@DEF-TRNV-004 an ADMIN shows the card number through the audited unmask and hides it again', async ({ page }) => {
    await signOn(page, 'ADMIN001');
    await page.getByTestId('option-07').click();
    await page.getByTestId('lookup-transaction-list').click();
    await page.getByTestId('view-1').click();
    await expect(page.getByTestId('tran-card')).toHaveText('************7065');
    await page.getByRole('button', { name: 'Show card number' }).click();
    await expect(page.getByTestId('tran-card')).toHaveText('4859452612877065');
    await expectAccessible(page);
    await page.getByRole('button', { name: 'Hide card number' }).click();
    await expect(page.getByTestId('tran-card')).toHaveText('************7065');
    await page.getByTestId('back').click();
    await expect(page).toHaveURL(/\/transactions(\?.*)?$/);
  });

  test('@ERR-TRNL-001 @ERR-TRNV-001 failed reads show the lookup messages', async ({ page, mock }) => {
    await mock({ latency: 0, faults: ['tran-read'] });
    await signOnAsUser(page);
    await page.goto('/transactions');
    await expectMessage(page, 'Unable to lookup transaction...', 'error');
    await page.goto('/transactions/lookup');
    await page.getByTestId('tran-id-input').fill('683580');
    await page.getByTestId('tran-id-input').press('Enter');
    await expectMessage(page, 'Unable to lookup Transaction...', 'error');
  });
});
