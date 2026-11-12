import type { Page } from '@playwright/test';
import { callApi, expect, expectAccessible, expectHeader, expectMessage, expectNoMessage, signOn, signOnAsUser, test, turnOnFunctionKeys } from './fixtures';

/**
 * TRNA of the transaction-service contract (COTRN02C). Mock-only: each add leaves a waiting
 * transaction, and account 00000000096 with two cards exists only in the mock.
 */

const FORM = {
  acctId: '50',
  typeCd: '1',
  catCd: '1',
  source: 'POS TERM',
  description: 'Coffee',
  amount: '-00000012.50',
  origDate: '2022-07-06',
  merchantId: '123',
  merchantName: 'Cafe',
  merchantCity: 'Seattle',
  merchantZip: '98101',
};

async function openAdd(page: Page) {
  await signOnAsUser(page);
  await page.getByTestId('option-08').click();
  await expect(page.getByRole('heading', { name: 'Add a transaction' })).toBeVisible();
}

async function fill(page: Page, values: Partial<typeof FORM> & { cardNum?: string }) {
  for (const [name, value] of Object.entries(values)) await page.getByTestId(name).fill(value);
}

test.describe('transaction add @mock-only', () => {
  test.beforeEach(async ({ mock }) => mock({ latency: 0 }));

  test('@FLOW-TRNA-001 without a session the page and the API refuse', async ({ page }) => {
    await page.goto('/transactions/new');
    await expect(page).toHaveURL(/\/sign-on$/);
    expect((await callApi(page, 'GET', '/api/transactions/latest')).status).toBe(401);
  });

  test('@FLOW-TRNA-002 @BR-TRNA-001 @BR-TRNA-002 @BR-TRNA-005 menu option 8: review, confirm Yes and the new ID with the posting hint', async ({ page }) => {
    await openAdd(page);
    await expectHeader(page, 'CT02 · COTRN02C');
    await expect(page.getByTestId('acctId')).toBeFocused();
    await expectNoMessage(page);
    await expectAccessible(page);
    await fill(page, FORM);
    await page.getByTestId('submit').click();
    await expectMessage(page, 'Confirm to add this transaction...', 'error');
    await expect(page.getByTestId('review-acct')).toHaveText('00000000050');
    await expect(page.getByTestId('review-card')).toHaveText(/^\*{12}5740$/);
    await expect(page.getByTestId('review-amount')).toHaveText('-00000012.50');
    await expect(page.getByRole('radio', { name: 'Yes' })).toBeFocused();
    await expectAccessible(page);
    await page.keyboard.press('y');
    await page.keyboard.press('Enter');
    await expectMessage(page, /^Transaction added successfully\. {2}Your Tran ID is 9\d{15}\.$/, 'success');
    await expect(page.getByTestId('add-hint')).toHaveText('The transaction will be posted in the next batch cycle.');
    await expect(page.getByTestId('acctId')).toHaveValue('');
    await expect(page.getByTestId('acctId')).toBeFocused();
    await expectAccessible(page);
  });

  test('@DEF-TRNA-004 an account with two cards lists them masked; the typed card is used', async ({ page }) => {
    await openAdd(page);
    await fill(page, { ...FORM, acctId: '96' });
    await page.getByTestId('submit').click();
    await expectMessage(page, 'Account has more than one card. Enter the Card Number...', 'error');
    await expect(page.getByTestId('card-choice').getByRole('listitem')).toHaveText(['************0961', '************0962']);
    await expect(page.getByTestId('cardNum')).toBeFocused();
    await expectAccessible(page);
    await page.getByTestId('cardNum').fill('9999000000000961');
    await page.getByTestId('submit').click();
    await expect(page.getByTestId('review-card')).toHaveText('************0961');
  });

  test('@VAL-TRNA-006 nothing is capped on the page: 33 emoji in the description reach the service', async ({ page }) => {
    await openAdd(page);
    await expect(page.getByTestId('description')).not.toHaveAttribute('maxlength');
    await fill(page, { ...FORM, description: '\u{1F600}'.repeat(33) });
    await page.getByTestId('submit').click();
    await expectMessage(page, 'Description contains a character that is not supported.', 'error');
    await expect(page.getByTestId('description')).toBeFocused();
    await expect(page.getByTestId('description')).toHaveValue('\u{1F600}'.repeat(33));
  });

  test('@BR-TRNA-006 @FLOW-TRNA-006 @FLOW-TRNA-005 @VAL-TRNA-013 F5 copies the last transaction, F4 clears, F7 is invalid', async ({ page }) => {
    await openAdd(page);
    await turnOnFunctionKeys(page);
    await page.getByTestId('acctId').fill('50');
    await page.keyboard.press('F5');
    await expectMessage(page, 'Confirm to add this transaction...');
    await expect(page.getByTestId('description')).toHaveValue('Purchase at Kilback LLC');
    await expect(page.getByTestId('amount')).toHaveValue('+00000603.22');
    await page.keyboard.press('F7');
    await expectMessage(page, 'Invalid key pressed. Please see below...', 'error');
    await expect(page.getByTestId('description')).toHaveValue('Purchase at Kilback LLC');
    await page.keyboard.press('F4');
    await expect(page.getByTestId('description')).toHaveValue('');
    await expectNoMessage(page);
    await page.keyboard.press('F3');
    await expect(page).toHaveURL(/\/$/);
  });

  test('@D-02 an ADMIN is sent to the admin menu and the API refuses', async ({ page }) => {
    await signOn(page, 'ADMIN001');
    await expect(page.getByRole('heading', { name: 'Admin menu' })).toBeVisible();
    await page.goto('/transactions/new');
    await expect(page).toHaveURL(/\/admin$/);
    expect((await callApi(page, 'POST', '/api/transactions/validate', { body: FORM })).status).toBe(403);
  });
});
