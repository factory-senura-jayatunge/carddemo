import type { Page } from '@playwright/test';
import { callApi, expect, expectAccessible, expectHeader, expectMessage, setFaults, signOn, signOnAsUser, test, turnOnFunctionKeys } from './fixtures';

/** AUDT and AUFR scenarios of the authorization-service contract (COPAUS1C, COPAUS2C). */

const tran = (authId: number) => `TRN${String(authId).padStart(12, '0')}`;
const toggle = (page: Page) => page.getByTestId('fraud-toggle');
const fraudShown = (page: Page) => page.getByTestId('detail-fraud-display');

async function openDetail(page: Page, authId: number) {
  await signOnAsUser(page);
  await page.goto(`/authorizations/${authId}`);
  await expect(page.getByTestId('detail-tran')).toHaveText(tran(authId));
}

test.beforeEach(async ({ mock }) => mock({ latency: 0, authorizationInstalled: true }));

test('@FLOW-AUDT-002 A10: every field, the reason text and the masked card @mock-only', async ({ page }) => {
  await openDetail(page, 1012);
  await expectHeader(page, 'CPVD · COPAUS1C');
  await expect(page.getByTestId('detail-reason')).toHaveText('0000-APPROVED');
  await expect(page.getByTestId('detail-approved-amount')).toHaveText('$50.00');
  await expect(page.getByTestId('detail-card')).toHaveText(/^\*{12}\d{4}$/);
  await expect(fraudShown(page)).toHaveText('-');
  await expect(toggle(page)).toHaveText(/Report fraud/);
  await expect(toggle(page)).toBeFocused();
  await expectAccessible(page);
});

test('@BR-AUDT-006 @VAL-AUDT-003 Next opens the next older one; at the oldest the web says so @mock-only', async ({ page }) => {
  await openDetail(page, 1002);
  await page.getByTestId('next').click();
  await expect(page).toHaveURL(/\/authorizations\/1001$/);
  await expect(page.getByTestId('detail-matched-tran')).toHaveText('0000000000000301');
  await page.getByTestId('next').click();
  await expectMessage(page, 'Already at the last Authorization...', 'error');
  await expect(page.getByTestId('detail-tran')).toHaveText(tran(1001));
});

test('@BR-AUDT-007 @BR-AUDT-008 A14: Report fraud, then Remove the report, with F5 too @mock-only', async ({ page }) => {
  await openDetail(page, 1012);
  await toggle(page).click();
  await expectMessage(page, 'AUTH MARKED FRAUD...', 'info');
  await expect(fraudShown(page)).toHaveText(/^F-\d{2}\/\d{2}\/\d{2}$/);
  await expect(toggle(page)).toHaveText(/Remove fraud report/);
  await expectAccessible(page);
  await turnOnFunctionKeys(page);
  await page.keyboard.press('F5');
  await expectMessage(page, 'AUTH FRAUD REMOVED...', 'info');
  await expect(fraudShown(page)).toHaveText(/^R-/);
  await expect(toggle(page)).toHaveText(/Report fraud/);
});

test('@D-17 A15: a change since the view shows the stored detail to decide again @mock-only', async ({ page }) => {
  await openDetail(page, 1012);
  const stored = await callApi(page, 'GET', '/api/authorizations/1012');
  const other = await callApi(page, 'POST', '/api/authorizations/1012/fraud', {
    body: { action: 'F' },
    headers: { 'If-Match': `"${String(stored.body?.version)}"` },
  });
  expect(other.status).toBe(200);
  await expect(toggle(page)).toHaveText(/Report fraud/);
  await toggle(page).click();
  await expectMessage(page, 'Record changed by some one else. Please review', 'error');
  await expect(fraudShown(page)).toHaveText(/^F-/);
  await expect(toggle(page)).toHaveText(/Remove fraud report/);
});

test('@DEF-AUDT-003 @ERR-AUDT-004 A17: a failed fraud write keeps the stored status @mock-only', async ({ page }) => {
  await openDetail(page, 1012);
  await setFaults(page, ['fraud-update']);
  await toggle(page).click();
  await expectMessage(page, ' System error while FRAUD Tagging, ROLLBACK', 'error');
  await expect(fraudShown(page)).toHaveText('-');
});

test('@DEF-AUDT-004 A18: an unknown authorization shows its text, no detail, and focus on Back', async ({ page }) => {
  await signOnAsUser(page);
  await page.goto('/authorizations/77');
  await expectMessage(page, 'Authorization not found...', 'error');
  await expect(page.getByTestId('auth-detail')).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Back/ })).toBeFocused();
  await expectAccessible(page);
});

test('@DEF-AUSM-007 @DEF-AUDT-006 C1: an ADMIN who opens an authorization goes to the admin menu', async ({ page }) => {
  await signOn(page, 'ADMIN001');
  await expect(page).toHaveURL(/\/admin$/);
  await page.goto('/authorizations/1012');
  await expect(page).toHaveURL(/\/admin$/);
  const read = await callApi(page, 'GET', '/api/authorizations/1012');
  expect(read.status).toBe(403);
});
