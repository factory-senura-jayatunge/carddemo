import type { Page } from '@playwright/test';
import { callsDuring, expect, expectAccessible, expectHeader, expectMessage, expectNoMessage, signOn, test, trackApi, turnOnFunctionKeys } from './fixtures';

/**
 * User administration (COUSR00C to COUSR03C) on the mock's thirteen users, signed on as
 * ADMIN001. Mock-only: the pages add, change and delete users, which a stack run must keep.
 */

const rowId = (page: Page, n: number) => page.getByTestId(`row-id-${n}`);
const userKey = (id: string) => Buffer.from(id, 'latin1').toString('base64url');

async function signOut(page: Page) {
  await page.getByRole('button', { name: /^Account menu/ }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { name: 'You are signed out' })).toBeVisible();
}

async function openList(page: Page) {
  await signOn(page, 'ADMIN001');
  await page.getByTestId('option-01').click();
  await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible();
  await expect(rowId(page, 10)).toHaveText('USER0002');
}

test.describe('user administration @mock-only', () => {
  test.beforeEach(async ({ mock }) => mock({ latency: 0 }));

  test('@BR-USRL-001 @BR-USRL-006 @VAL-USRL-003 the list pages through every user; Previous on page 1 asks nothing', async ({ page }) => {
    const calls = trackApi(page);
    await openList(page);
    await expectHeader(page, 'CU00 · COUSR00C');
    await expect(rowId(page, 1)).toHaveText('ADMIN001');
    await expect(page.getByTestId('user-row-1')).toContainText('You');
    await expect(page.getByTestId('page-number')).toHaveText('1');
    await expectAccessible(page);
    expect(await callsDuring(page, calls, () => page.getByTestId('previous').click())).toEqual([]);
    await page.getByTestId('next').click();
    await expect(rowId(page, 1)).toHaveText('USER0003');
    await expect(rowId(page, 3)).toHaveText('USER0005');
    await expect(page.getByTestId('page-number')).toHaveText('2');
    expect(await callsDuring(page, calls, () => page.getByTestId('next').click())).toEqual([]);
    await page.getByTestId('previous').click();
    await expect(rowId(page, 1)).toHaveText('ADMIN001');
  });

  test('@VAL-USRL-002 F8 and F7 page with the function keys on; F3 goes back to the admin menu', async ({ page }) => {
    await openList(page);
    await turnOnFunctionKeys(page);
    await page.getByTestId('user-search').press('F8');
    await expect(rowId(page, 1)).toHaveText('USER0003');
    await page.getByTestId('user-search').press('F7');
    await expect(rowId(page, 1)).toHaveText('ADMIN001');
    await page.getByTestId('user-search').press('F3');
    await expect(page).toHaveURL(/\/admin$/);
  });

  test('@Q-USRL-001 a search lists the user IDs that start with it', async ({ page }) => {
    await openList(page);
    await page.getByTestId('user-search').fill('admin00');
    await page.getByTestId('user-search').press('Enter');
    await expect(rowId(page, 5)).toHaveText('ADMIN005');
    await expect(rowId(page, 6)).toHaveCount(0);
  });

  test('@VAL-USRA-001 @BR-USRA-002 add: the first missing field is named, then the user is added and can sign on', async ({ page }) => {
    await openList(page);
    await page.getByTestId('add-user').click();
    await expect(page.getByRole('heading', { name: 'Add user' })).toBeVisible();
    await expectHeader(page, 'CU01 · COUSR01C');
    await expect(page.getByTestId('firstName')).toBeFocused();
    await page.getByTestId('submit').click();
    await expectMessage(page, 'First Name can NOT be empty...', 'error');
    await expect(page.getByTestId('firstName')).toHaveAttribute('aria-invalid', 'true');
    await expectAccessible(page);
    await page.getByTestId('firstName').fill('Grace');
    await page.getByTestId('lastName').fill('Hopper');
    await page.getByTestId('userId').fill('hopper01');
    await page.getByTestId('password').fill('Compiler59');
    await page.getByRole('radio', { name: 'User' }).check();
    await page.getByTestId('submit').click();
    await expectMessage(page, 'User HOPPER01 has been added ...', 'success');
    await expect(page.getByTestId('userId')).toHaveValue('');
    await expect(page.getByTestId('firstName')).toBeFocused();
    await signOut(page);
    await signOn(page, 'HOPPER01', 'Compiler59');
    await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
  });

  test('@D-BAT-05 add in the batch window: refused with its retry time, and the form keeps what was typed', async ({ page, mock }) => {
    await mock({ latency: 0, batchWindow: true });
    await signOn(page, 'ADMIN001');
    await page.getByTestId('option-02').click();
    await page.getByTestId('firstName').fill('Grace');
    await page.getByTestId('lastName').fill('Hopper');
    await page.getByTestId('userId').fill('HOPPER01');
    await page.getByTestId('password').fill('Compiler59');
    await page.getByRole('radio', { name: 'Administrator' }).check();
    await page.getByTestId('submit').click();
    await expectMessage(page, 'User changes are not available during the batch cycle. Try again later...', 'error');
    await expect(page.getByTestId('message')).toContainText(/Try again after \d/);
    await expect(page.getByTestId('userId')).toHaveValue('HOPPER01');
  });

  test('@BR-USRL-003 @BR-USRU-003 @D-68 update from the list: Back asks before losing edits, Save stores them', async ({ page }) => {
    await openList(page);
    await page.getByTestId('update-9').click();
    await expect(page.getByTestId('locked-id')).toHaveText('USER0001');
    await expectHeader(page, 'CU02 · COUSR02C');
    await expectNoMessage(page);
    await expectAccessible(page);
    await page.getByTestId('lastName').fill('THOMSON');
    await page.getByRole('button', { name: /^Back/ }).click();
    await expect(page.getByRole('dialog', { name: 'Leave without saving?' })).toBeVisible();
    await page.getByTestId('stay').click();
    await expect(page.getByTestId('lastName')).toHaveValue('THOMSON');
    await page.getByTestId('save').click();
    await expectMessage(page, 'User USER0001 has been updated ...', 'success');
    await page.getByRole('button', { name: /^Back/ }).click();
    await expect(page).toHaveURL(/\/admin\/users$/);
    await expect(page.getByTestId('row-name-9')).toHaveText('LAWRENCE THOMSON');
  });

  test('@BR-USRU-004 @BR-USRU-005 update: F5 with nothing changed asks for a change', async ({ page }) => {
    await signOn(page, 'ADMIN001');
    await expect(page.getByRole('heading', { name: 'Admin menu' })).toBeVisible();
    await page.goto(`/admin/users/${userKey('USER0002')}/edit`);
    await turnOnFunctionKeys(page);
    await expect(page.getByTestId('firstName')).toHaveValue('AJITH');
    await page.getByTestId('firstName').press('F5');
    await expectMessage(page, 'Please modify to update ...', 'error');
  });

  test('@VAL-USRU-003 update: an unknown user shows the refusal and a way back', async ({ page }) => {
    await signOn(page, 'ADMIN001');
    await expect(page.getByRole('heading', { name: 'Admin menu' })).toBeVisible();
    await page.goto(`/admin/users/${userKey('NOBODY01')}/edit`);
    await expect(page.getByTestId('missing-user')).toBeVisible();
    await expectMessage(page, 'User ID NOT found...', 'error');
    await expectAccessible(page);
    await page.getByTestId('missing-back').click();
    await expect(page).toHaveURL(/\/admin\/users$/);
  });

  test('@BR-USRL-004 @BR-USRD-003 @BR-USRD-004 delete from the list: the confirmation names the user, the list shows the result', async ({ page }) => {
    await openList(page);
    await page.getByTestId('next').click();
    await page.getByTestId('delete-3').click();
    await expect(page.getByTestId('delete-id')).toHaveText('USER0005');
    await expect(page.getByTestId('delete-id')).toBeFocused();
    await expect(page.getByTestId('confirm-bar')).toContainText('Delete USER0005 (LEE TING)?');
    await expectHeader(page, 'CU03 · COUSR03C');
    await expectAccessible(page);
    await page.getByTestId('confirm-delete').click();
    await expect(page).toHaveURL(/\/admin\/users$/);
    await expectMessage(page, 'User USER0005 has been deleted ...', 'success');
    await signOut(page);
    await signOn(page, 'USER0005');
    await expectMessage(page, 'Wrong User ID or Password. Try again ...', 'error');
  });

  test('@VAL-USRD-003 @Q-USRD-002 delete: yourself is refused; F12 cancels', async ({ page }) => {
    await openList(page);
    await turnOnFunctionKeys(page);
    await page.getByTestId('delete-1').click();
    await page.getByTestId('confirm-delete').click();
    await expectMessage(page, 'You cannot delete your own User ID...', 'error');
    await page.getByTestId('delete-id').press('F12');
    await expect(page).toHaveURL(/\/admin\/users$/);
  });

  test('@BR-USRL-001 the list on a narrow screen', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openList(page);
    await expect(page.getByTestId('update-1')).toBeVisible();
    await expectAccessible(page);
  });
});
