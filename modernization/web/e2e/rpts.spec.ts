import type { Page } from '@playwright/test';
import { callApi, expect, expectAccessible, expectHeader, expectMessage, expectNoMessage, signOnAsUser, test, turnOnFunctionKeys } from './fixtures';

/**
 * RPTS of the transaction-service contract (CORPT00C). Mock-only: the mock's run moves one step
 * per status read and writes a short stand-in for TRANREPT.
 */

const radio = (page: Page, name: string) => page.getByRole('radio', { name: new RegExp(`^${name}`) });
const runMessage = (page: Page) => page.locator('#report-run-message').getByTestId('message-text');

async function openReports(page: Page) {
  await signOnAsUser(page);
  await page.getByTestId('option-09').click();
  await expect(page.getByRole('heading', { name: 'Transaction report' })).toBeVisible();
}

async function requestMonthly(page: Page) {
  await radio(page, 'Monthly').check();
  await page.getByTestId('submit').click();
  await expectMessage(page, 'Please confirm to print the Monthly report...', 'error');
  await expect(page.getByTestId('report-range')).toHaveText(/^It covers the transactions processed from \d{4}-\d{2}-01 to \d{4}-\d{2}-\d{2}\.$/);
  await radio(page, 'Yes').check();
  await page.getByTestId('submit').click();
  await expectMessage(page, 'Monthly report submitted for printing ...', 'success');
}

test.describe('transaction reports @mock-only', () => {
  test.beforeEach(async ({ mock }) => mock({ latency: 0 }));

  test('@FLOW-RPTS-001 without a session the page and the API refuse', async ({ page }) => {
    await page.goto('/reports/transactions');
    await expect(page).toHaveURL(/\/sign-on$/);
    expect((await callApi(page, 'POST', '/api/reports/transactions', { body: { type: 'MONTHLY', confirm: 'Y' } })).status).toBe(401);
  });

  test('@FLOW-RPTS-002 @BR-RPTS-002 @BR-RPTS-005 @BR-RPTS-008 menu option 9: Monthly, confirm, and the run until its file downloads', async ({ page }) => {
    await openReports(page);
    await expectHeader(page, 'CR00 · CORPT00C');
    await expect(radio(page, 'Monthly')).toBeFocused();
    await expect(radio(page, 'Monthly')).not.toBeChecked();
    await expectNoMessage(page);
    await expectAccessible(page);
    await requestMonthly(page);
    await expect(radio(page, 'Monthly')).not.toBeChecked();
    await expect(page.getByTestId('run-status')).toHaveText(/Requested|Running/);
    // The status is read every 5 seconds; the mock's run is ready at the second read.
    await expect(page.getByTestId('run-status')).toHaveText('Ready', { timeout: 20_000 });
    await expectAccessible(page);
    const download = page.waitForEvent('download');
    await page.getByTestId('download').click();
    expect((await download).suggestedFilename()).toBe('TRANREPT-1.txt');
  });

  test('@BR-RPTS-004 @VAL-RPTS-002 @FLOW-RPTS-008 Custom checks each part; No clears everything with no message', async ({ page }) => {
    await openReports(page);
    await radio(page, 'Custom').check();
    await page.getByTestId('startMonth').fill('7');
    await page.getByTestId('startDay').fill('1');
    await page.getByTestId('startYear').fill('2022');
    await page.getByTestId('submit').click();
    await expectMessage(page, 'End Date - Month can NOT be empty...', 'error');
    await expect(page.getByTestId('endMonth')).toBeFocused();
    await expectAccessible(page);
    await page.getByTestId('endMonth').fill('07');
    await page.getByTestId('endDay').fill('31');
    await page.getByTestId('endYear').fill('2022');
    await page.getByTestId('submit').click();
    await expect(page.getByTestId('report-range')).toHaveText('It covers the transactions processed from 2022-07-01 to 2022-07-31.');
    await radio(page, 'No').check();
    await page.getByTestId('submit').click();
    await expect(radio(page, 'Custom')).not.toBeChecked();
    await expect(page.getByTestId('custom-dates')).toHaveCount(0);
    await expectNoMessage(page);
    await expect(radio(page, 'Monthly')).toBeFocused();
    await expect(page.getByTestId('run-empty')).toBeVisible();
  });

  test('a stalled run says so, with no download', async ({ page, mock }) => {
    await mock({ latency: 0, faults: ['report-stall'] });
    await openReports(page);
    await requestMonthly(page);
    await expect(page.getByTestId('run-status')).toHaveText('Stalled', { timeout: 20_000 });
    await expect(runMessage(page)).toHaveText('The report run has stopped responding. Contact operations...');
    await expect(page.getByTestId('download')).toHaveCount(0);
  });

  test('@VAL-RPTS-006 @FLOW-RPTS-005 @FLOW-RPTS-004 F4 is an invalid key with the cursor on Monthly; F3 goes back', async ({ page }) => {
    await openReports(page);
    await turnOnFunctionKeys(page);
    await radio(page, 'Yearly').check();
    await page.keyboard.press('F4');
    await expectMessage(page, 'Invalid key pressed. Please see below...', 'error');
    await expect(radio(page, 'Yearly')).toBeChecked();
    await expect(radio(page, 'Monthly')).toBeFocused();
    await page.keyboard.press('F3');
    await expect(page).toHaveURL(/\/$/);
  });
});
