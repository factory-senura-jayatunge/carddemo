import type { Locator, Page } from '@playwright/test';
import {
  callApi,
  callsDuring,
  expect,
  expectAccessible,
  expectHeader,
  expectMessage,
  expectNoMessage,
  expectReadOnly,
  setFaults,
  signOn,
  signOnAsUser,
  test,
  trackApi,
  turnOnFunctionKeys,
  whenAnswered,
} from './fixtures';

/**
 * ACUP scenarios of the account-service contract (COACTUPC, section 6).
 * Tests that save, or need a mock fault or the batch window, are mock-only:
 * a stack run must keep account 00000000001 as loaded.
 */

const acct = (page: Page) => page.getByTestId('acct-id');
const field = (page: Page, name: string) => page.getByTestId(name);
const info = (page: Page) => page.getByTestId('info-line');
const check = (page: Page) => page.getByRole('button', { name: 'Check changes' });
const save = (page: Page) => page.getByRole('button', { name: /^Save/ });

async function openUpdate(page: Page) {
  await signOnAsUser(page);
  await page.getByTestId('option-02').click();
  await expect(page.getByRole('heading', { name: 'Update account' })).toBeVisible();
}

async function openAccount(page: Page, acctId = '00000000001') {
  await openUpdate(page);
  await acct(page).fill(acctId);
  await acct(page).press('Enter');
  await expect(field(page, 'activeStatus')).toBeVisible();
}

/** WCAG 2.2 SC 2.4.11: the focused field is clear of the sticky header and the sticky action bar. */
async function expectFocusInView(page: Page, target: Locator) {
  await expect(target).toBeFocused();
  const box = await target.boundingBox();
  const header = await page.getByRole('banner').boundingBox();
  const bar = await page.locator('[data-stage]').boundingBox();
  expect(box && header && bar).toBeTruthy();
  if (!box || !header || !bar) return;
  expect(box.y).toBeGreaterThanOrEqual(header.y + header.height);
  expect(box.y + box.height).toBeLessThanOrEqual(bar.y);
}

async function toConfirm(page: Page, edits: Record<string, string>) {
  for (const [name, value] of Object.entries(edits)) await field(page, name).fill(value);
  await check(page).click();
  await expect(info(page)).toHaveText('Changes validated.Press F5 to save');
}

test.beforeEach(async ({ mock }) => mock({ latency: 0 }));

test('@FLOW-ACUP-006 @FLOW-ACUP-012 ACUP-S2: option 2 opens an empty search with focus on the account number', async ({ page }) => {
  await openUpdate(page);
  await expect(acct(page)).toBeFocused();
  await expect(acct(page)).toHaveValue('');
  await expect(info(page)).toHaveText('Enter or update id of account to update');
  await expectNoMessage(page);
  await expectHeader(page, 'CAUP · COACTUPC');
  await expectAccessible(page);
});

test('ACUP-S3 ACUP-S21: a blank or short account number is refused with no call', async ({ page }) => {
  await openUpdate(page);
  const calls = trackApi(page);
  const made = await callsDuring(page, calls, async () => {
    await acct(page).press('Enter');
    await expectMessage(page, 'Account number not provided', 'error');
    await expect(page.getByTestId('acct-id-marker')).toBeVisible();
    await acct(page).fill('123');
    await acct(page).press('Enter');
    await expectMessage(page, 'Account Number if supplied must be a 11 digit Non-Zero Number', 'error');
  });
  expect(made.filter((c) => c.includes('/api/accounts'))).toEqual([]);
  await expect(acct(page)).toHaveAttribute('aria-invalid', 'true');
  await expectAccessible(page);
});

test('ACUP-S5: an unknown account stays in Search with the field red', async ({ page }) => {
  await openUpdate(page);
  await acct(page).fill('00000000077');
  await acct(page).press('Enter');
  await expectMessage(page, 'Account 00000000077 not found.', 'error');
  await expect(acct(page)).toHaveAttribute('aria-invalid', 'true');
  await expectReadOnly(acct(page), false);
  await expect(field(page, 'activeStatus')).toHaveCount(0);
});

test('@D-09 @D-08 ACUP-S4: the details with masked write-only values and read-only balances', async ({ page }) => {
  await openAccount(page);
  await expect(field(page, 'activeStatus')).toHaveValue('Y');
  await expect(field(page, 'creditLimit')).toHaveValue('2020.00');
  await expect(field(page, 'cashCreditLimit')).toHaveValue('1020.00');
  await expect(page.getByTestId('currentBalance')).toContainText('194.00');
  await expect(page.getByTestId('ssn-masked')).toHaveText('***-**-3888');
  await expect(page.getByTestId('dob-masked')).toHaveText('****-06-08');
  await expect(page.getByTestId('govtIssuedId-masked')).toHaveText('****************8437');
  await expect(page.getByTestId('eftAccountId-masked')).toHaveText('******1756');
  await expect(page.getByRole('button', { name: /^Show/ })).toHaveCount(0);
  await expect(field(page, 'ficoScore')).toHaveValue('274');
  await expect(field(page, 'city')).toHaveValue('Altenwerthshire');
  await expect(page.getByTestId('countryCd')).toHaveText('USA');
  await expectReadOnly(acct(page), true);
  await expect(info(page)).toHaveText('Update account details presented above.');
  await expect(field(page, 'activeStatus')).toBeFocused();
  await expectAccessible(page);
});

test('ACUP-S6: Enter without changes says so and calls nothing', async ({ page }) => {
  await openAccount(page);
  const calls = trackApi(page);
  const made = await callsDuring(page, calls, async () => {
    await field(page, 'activeStatus').press('Enter');
    await expectMessage(page, 'No change detected with respect to values fetched.', 'error');
  });
  expect(made).toEqual([]);
  await expect(field(page, 'activeStatus')).toBeFocused();
});

test('@D-49 ACUP-S7 ACUP-S9: one change goes to Confirm; Enter there makes no call', async ({ page }) => {
  await openAccount(page);
  await toConfirm(page, { creditLimit: '2500.00' });
  await expectReadOnly(field(page, 'creditLimit'), true);
  await expect(save(page)).toBeFocused();
  const calls = trackApi(page);
  const made = await callsDuring(page, calls, async () => {
    await acct(page).press('Enter');
  });
  expect(made).toEqual([]);
  await expect(info(page)).toHaveText('Changes validated.Press F5 to save');
  await expectAccessible(page);
});

test('ACUP-S23: a ZIP that does not match the state is refused in the page; focus State', async ({ page }) => {
  await openAccount(page);
  await field(page, 'zip5').fill('12547');
  await check(page).click();
  await expectMessage(page, 'Invalid zip code for state', 'error');
  await expect(field(page, 'stateCd')).toHaveAttribute('aria-invalid', 'true');
  await expect(field(page, 'zip5')).toHaveAttribute('aria-invalid', 'true');
  await expectFocusInView(page, field(page, 'stateCd'));
  await expectAccessible(page);
});

test('ACUP-S23 on a phone: the focused State field is not hidden by the action bar', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openAccount(page);
  await field(page, 'zip5').fill('12547');
  await check(page).click();
  await expectMessage(page, 'Invalid zip code for state', 'error');
  await expectFocusInView(page, field(page, 'stateCd'));
});

test('@D-09 ACUP-S28: Change opens the SSN; Keep current restores the mask', async ({ page }) => {
  await openAccount(page);
  await page.getByRole('button', { name: 'Change SSN' }).click();
  await expect(field(page, 'ssn1')).toBeFocused();
  await field(page, 'ssn1').fill('987');
  await field(page, 'ssn2').fill('65');
  await field(page, 'ssn3').fill('4321');
  await check(page).click();
  await expectMessage(page, 'SSN: First 3 chars: should not be 000, 666, or between 900 and 999', 'error');
  await page.getByRole('button', { name: 'Keep the current SSN' }).click();
  await expect(page.getByTestId('ssn-masked')).toHaveText('***-**-3888');
  await expect(page.getByRole('button', { name: 'Change SSN' })).toBeFocused();
});

test('@DEF-ACUP-010 ACUP-S17: with shortcuts on, F5 outside Confirm is an invalid key', async ({ page }) => {
  await openAccount(page);
  await turnOnFunctionKeys(page);
  await field(page, 'ficoScore').fill('700');
  const calls = trackApi(page);
  const made = await callsDuring(page, calls, async () => {
    await field(page, 'ficoScore').press('F5');
    await expectMessage(page, 'Invalid key pressed. Please see below...', 'error');
  });
  expect(made).toEqual([]);
  await expect(field(page, 'ficoScore')).toHaveValue('700');
});

test('@FLOW-ACUP-005 @DEF-ACUP-009 ACUP-S18: Back returns to the main menu', async ({ page }) => {
  await openAccount(page);
  await field(page, 'ficoScore').fill('700');
  await page.getByRole('button', { name: /^Back/ }).click();
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
});

test('ACUP-S29: an ADMIN never reaches the page', async ({ page }) => {
  await signOn(page, 'ADMIN001');
  await expect(page.getByRole('heading', { name: 'Admin menu' })).toBeVisible();
  await page.goto('/accounts/edit');
  await expect(page).toHaveURL(/\/admin$/);
});

/** The update form's version, read through the API as the page reads it. */
async function storedVersion(page: Page) {
  const form = await callApi(page, 'GET', '/api/accounts/00000000001/update-form');
  expect(form.status).toBe(200);
  return String(form.body?.version);
}

const saveDirect = (page: Page, version: string, changes: Record<string, string>) =>
  callApi(page, 'PUT', '/api/accounts/00000000001', { body: { changes }, headers: { 'If-Match': `"${version}"` } });

test('@D-08 @DEF-ACUP-013 ACUP-S31: a save that names a field the screen does not edit is refused and writes nothing', async ({ page }) => {
  await signOnAsUser(page);
  const version = await storedVersion(page);
  for (const name of ['currentBalance', 'currCycDebit', 'acctId', 'custId', 'countryCd', 'cvv']) {
    const answer = await saveDirect(page, version, { [name]: '1' });
    expect(answer.status, name).toBe(400);
    expect(answer.body?.code, name).toBe('FIELD_NOT_EDITABLE');
  }
  const balance = await saveDirect(page, version, { currentBalance: '0.00' });
  expect(balance.body?.message).toBe('Current Balance cannot be changed on this screen.');
  expect(balance.body?.field).toBe('currentBalance');
  const customer = await saveDirect(page, version, { custId: '1' });
  expect(customer.body?.message).toBe('Customer id cannot be changed on this screen.');
  // A name outside the screen gets one fixed text and is never echoed back.
  for (const name of ['cvv', 'cvvCd', 'ssn', '<b>123-45-6789</b>', 'x'.repeat(10_000)]) {
    const answer = await saveDirect(page, version, { [name]: '1' });
    expect(answer.status, name.slice(0, 20)).toBe(400);
    expect(answer.body).toMatchObject({ code: 'FIELD_NOT_EDITABLE', field: 'changes', message: 'This field cannot be changed on this screen.' });
    expect(JSON.stringify(answer.body), name.slice(0, 20)).not.toContain(name);
  }
  expect(await storedVersion(page)).toBe(version);
});

test('@D-49 @DEF-ACUP-013: a save with a changed field that fails its check, sent without validate, is refused and writes nothing', async ({ page }) => {
  await signOnAsUser(page);
  const version = await storedVersion(page);
  const answer = await saveDirect(page, version, { ficoScore: '999' });
  expect(answer.status).toBe(400);
  expect(answer.body?.field).toBe('ficoScore');
  expect(answer.body?.code).not.toBe('FIELD_NOT_EDITABLE');
  expect(await storedVersion(page)).toBe(version);
  const form = await callApi(page, 'GET', '/api/accounts/00000000001/update-form');
  expect(form.body?.ficoScore).toBe('274');
});

test.describe('saves @mock-only', () => {
  test('ACUP-S8 ACUP-S10 ACUP-S11: three changes are saved; Enter then gives an empty search', async ({ page }) => {
    await openAccount(page);
    await toConfirm(page, { ficoScore: '700', phone2Area: '908', zip5: '27601' });
    await whenAnswered(page, 'PUT /api/accounts/00000000001', () => save(page).click());
    await expect(info(page)).toHaveText('Changes committed to database');
    await expect(field(page, 'ficoScore')).toHaveValue('700');
    await expect(field(page, 'zip5')).toHaveValue('27601');
    await expectAccessible(page);
    await page.getByRole('button', { name: 'Update another account' }).press('Enter');
    await expect(acct(page)).toHaveValue('');
    await expect(info(page)).toHaveText('Enter or update id of account to update');
  });

  test('@BR-ACUP-004 ACUP-S12: Cancel in Confirm reads the account again and drops the edits', async ({ page }) => {
    await openAccount(page);
    await toConfirm(page, { ficoScore: '700' });
    await turnOnFunctionKeys(page);
    await save(page).press('F12');
    await expect(field(page, 'ficoScore')).toHaveValue('274');
    await expect(info(page)).toHaveText('Update account details presented above.');
  });

  test('@D-17 @DEF-ACUP-006 ACUP-S13: a payment since the fetch gives Record changed; the edit stays', async ({ page }) => {
    await openAccount(page);
    await toConfirm(page, { creditLimit: '2500.00' });
    // Another tab pays the balance, which moves the account row's version.
    const paid = await page.evaluate(async () => {
      const xsrf = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.slice(11) ?? '');
      const r = await fetch('/api/billpay/accounts/00000000001/payments', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrf, 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ confirm: 'Y' }),
      });
      return r.status;
    });
    expect(paid).toBe(201);
    await save(page).click();
    await expectMessage(page, 'Record changed by some one else. Please review', 'error');
    await expect(field(page, 'creditLimit')).toHaveValue('2500.00');
    await expect(page.getByTestId('currentBalance-moved')).toBeVisible();
    await expectAccessible(page);
    await check(page).click();
    await save(page).click();
    await expect(info(page)).toHaveText('Changes committed to database');
  });

  test('ACUP-S14: an account lock failure is a failed save', async ({ page }) => {
    await openAccount(page);
    await toConfirm(page, { creditLimit: '2500.00' });
    await setFaults(page, ['account-lock']);
    await save(page).click();
    await expectMessage(page, 'Could not lock account record for update', 'error');
    await expect(info(page)).toHaveText('Changes unsuccessful. Please try again');
  });

  test('ACUP-S32: the batch window refuses the save with the account-update text', async ({ page, mock }) => {
    await mock({ latency: 0, batchWindow: true });
    await openAccount(page);
    await toConfirm(page, { creditLimit: '2500.00' });
    await save(page).click();
    await expectMessage(page, 'Account update is not available during the batch cycle. Try again later...', 'error');
    await expect(info(page)).toHaveText('Changes unsuccessful. Please try again');
    await expect(page.getByText(/^Try again after/)).toBeVisible();
  });

  test('@D-61 ACUP-S48: an account with no card shows and saves the account fields only', async ({ page }) => {
    await openAccount(page, '00000000098');
    await expect(page.getByTestId('no-customer')).toBeVisible();
    await expect(field(page, 'ficoScore')).toHaveCount(0);
    await toConfirm(page, { creditLimit: '2500.00' });
    await save(page).click();
    await expect(info(page)).toHaveText('Changes committed to database');
  });

  test('@ERR-ACUP-006 an unexpected failure on save shows the error page and keeps the session', async ({ page }) => {
    await openAccount(page);
    await toConfirm(page, { creditLimit: '2500.00' });
    await setFaults(page, ['unexpected']);
    await save(page).click();
    await expect(page.getByRole('heading', { name: 'Something went wrong' })).toBeVisible();
    await expectMessage(page, 'An unexpected error occurred. Please try again later ...', 'error');
    await expect(page.getByTestId('error-reference')).toContainText('Reference');
    await expect(page.getByRole('button', { name: 'Try again' })).toBeFocused();
    await expectAccessible(page);
    await setFaults(page, []);
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(acct(page)).toHaveValue('');
    await expect(acct(page)).toBeFocused();
  });
});
