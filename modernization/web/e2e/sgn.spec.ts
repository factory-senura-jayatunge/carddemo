import type { Page } from '@playwright/test';
import {
  callApi,
  expect,
  expectAccessible,
  expectHeader,
  expectMessage,
  expectNoMessage,
  mode,
  PASSWORD,
  signOn,
  target,
  test,
  turnOnFunctionKeys,
  whenAnswered,
} from './fixtures';

/** SGN scenarios of the slice contract (COSGN00C section 15). */

const userId = (page: Page) => page.getByLabel('User ID');
const password = (page: Page) => page.getByLabel('Password', { exact: true });
const NEUTRAL = 'Wrong User ID or Password. Try again ...';
const SESSION_ENDED = 'Your session has ended. Please sign on again ...';
/** Enter in Password, once sign-on has answered it; every failed attempt shows the same message. */
const submitPassword = (page: Page) => whenAnswered(page, 'POST /api/auth/sign-on', () => password(page).press('Enter'));

test.beforeEach(async ({ mock }) => mock({ latency: 0 }));

test('@FLOW-SGN-001 @FLOW-SGN-007 SGN-S1: the app opens on sign-on with focus on User ID', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/sign-on$/);
  await expect(userId(page)).toBeFocused();
  await expectNoMessage(page);
  await expectHeader(page, 'CC00 · COSGN00C');
  await expectAccessible(page);
});

test('@VAL-SGN-001 SGN-S2: both fields blank', async ({ page }) => {
  await page.goto('/sign-on');
  await page.getByRole('button', { name: 'Sign on', exact: true }).click();
  await expectMessage(page, 'Please enter User ID ...', 'error');
  await expect(userId(page)).toBeFocused();
  await expect(userId(page)).toHaveAttribute('aria-invalid', 'true');
  await expectAccessible(page);
});

test('@VAL-SGN-002 SGN-S3: password blank', async ({ page }) => {
  await page.goto('/sign-on');
  await userId(page).fill('ADMIN001');
  await userId(page).press('Enter');
  await expectMessage(page, 'Please enter Password ...', 'error');
  await expect(password(page)).toBeFocused();
});

test('@BR-SGN-004 @BR-SGN-005 SGN-S4: an admin lands on the admin menu, signed on as an admin', async ({ page }) => {
  await signOn(page, 'ADMIN001');
  await expect(page).toHaveURL(/\/admin$/);
  await expect(page.getByRole('heading', { name: 'Admin menu' })).toBeVisible();
  const me = await callApi(page, 'GET', '/api/auth/me');
  expect(me.status).toBe(200);
  expect(me.body).toMatchObject({ userId: 'ADMIN001', userType: 'A', role: 'ADMIN', home: '/admin' });
});

test(`@DEF-SGN-011 SGN-S5 @${mode}: a lower-case user ID and password`, async ({ page }) => {
  await signOn(page, 'user0001', PASSWORD.toLowerCase());
  if (mode === 'fixed') {
    await expectMessage(page, NEUTRAL, 'error');
    await expect(password(page)).toBeFocused();
  } else {
    await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
  }
});

test('@BR-SGN-001 @NUM-SGN-002 SGN-S5b: user IDs are not case-sensitive', async ({ page }) => {
  await signOn(page, 'user0001');
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
  const me = await callApi(page, 'GET', '/api/auth/me');
  expect(me.body).toMatchObject({ userId: 'USER0001', userType: 'U', role: 'USER', home: '/' });
});

test(`@BR-SGN-002 @VAL-SGN-003 @DEF-SGN-003 SGN-S6 @${mode}: an unknown user`, async ({ page }) => {
  await signOn(page, 'NOUSER01');
  if (mode === 'fixed') {
    await expectMessage(page, NEUTRAL, 'error');
    await expect(password(page)).toBeFocused();
  } else {
    await expectMessage(page, 'User not found. Try again ...', 'error');
    await expect(userId(page)).toBeFocused();
  }
});

test(`@VAL-SGN-004 @DEF-SGN-003 SGN-S7 @${mode}: a wrong password`, async ({ page }) => {
  await signOn(page, 'ADMIN001', 'WRONGPWD');
  await expectMessage(page, mode === 'fixed' ? NEUTRAL : 'Wrong Password. Try again ...', 'error');
  await expect(password(page)).toBeFocused();
  await expectAccessible(page);
});

test('@FLOW-SGN-003 SGN-S8: Exit shows the thank-you page; Back on a menu ends the session', async ({ page }) => {
  await page.goto('/sign-on');
  await page.getByRole('button', { name: /^Exit/ }).click();
  await expect(page).toHaveURL(/\/signed-off$/);
  await expect(page.getByTestId('message-text')).toHaveText('Thank you for using CardDemo application...');
  await expectAccessible(page);
  await page.getByRole('link', { name: 'Sign on again' }).click();
  await expect(page).toHaveURL(/\/sign-on$/);

  await signOn(page, 'USER0001');
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
  await page.getByRole('button', { name: /^Sign out/ }).click();
  await expect(page).toHaveURL(/\/sign-on$/);
  await expectNoMessage(page);
  await expect(userId(page)).toHaveValue('');
  expect((await callApi(page, 'GET', '/api/auth/me')).status).toBe(401);
});

test('@VAL-SGN-005 @FLOW-SGN-004 @DEF-SGN-010 SGN-S9: F5 with the function keys on is an invalid key and keeps the input; F3 exits', async ({ page }) => {
  await page.goto('/sign-on');
  await turnOnFunctionKeys(page);
  await userId(page).fill('USER0001');
  await userId(page).press('F5');
  await expectMessage(page, 'Invalid key pressed. Please see below...', 'error');
  await expect(userId(page)).toHaveValue('USER0001');
  await userId(page).press('F3');
  await expect(page).toHaveURL(/\/signed-off$/);
});

test('SGN-S10: sign-on works while the batch window is open @mock-only', async ({ page, mock }) => {
  await mock({ latency: 0, batchWindow: true });
  await signOn(page, 'USER0001');
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
});

test('@DEF-SGN-011 SGN-S11 @fixed: a mixed-case password signs on as typed @mock-only', async ({ page }) => {
  await signOn(page, 'TEST0001', 'Secret12');
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
});

test('@DEF-SGN-002 SGN-S12 @fixed: five wrong passwords lock the user for 15 minutes @mock-only', async ({ page }) => {
  await page.clock.install();
  await page.goto('/sign-on');
  await userId(page).fill('USER0003');
  for (let attempt = 1; attempt <= 5; attempt += 1) {
    await password(page).fill(`WRONG00${attempt}`);
    await submitPassword(page);
    await expectMessage(page, NEUTRAL, 'error');
  }
  await password(page).fill(PASSWORD);
  await submitPassword(page);
  await expectMessage(page, NEUTRAL, 'error');
  await page.clock.fastForward('16:00');
  await password(page).fill(PASSWORD);
  await password(page).press('Enter');
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
});

test('@DEF-SGN-005 SGN-S13 @fixed: an unknown user type is refused @mock-only', async ({ page }) => {
  await signOn(page, 'TYPEX001');
  await expectMessage(page, 'User not authorized. Contact your administrator ...', 'error');
  await expect(userId(page)).toBeFocused();
});

test('@D-SEC-07 SGN-S14 @fixed: a forced password change checks each rule and lands on home @mock-only', async ({ page }) => {
  await signOn(page, 'CHANGE01');
  await expect(page).toHaveURL(/\/sign-on\/change-password$/);
  await expect(page.getByRole('heading', { name: 'Change your password' })).toBeVisible();
  await expect(page.getByTestId('message')).toHaveAttribute('data-tone', 'info');
  const newPassword = page.getByLabel('New password', { exact: true });
  const repeat = page.getByLabel('Repeat new password', { exact: true });
  await expect(newPassword).toBeFocused();
  await expectAccessible(page);

  const change = page.getByRole('button', { name: 'Change password' });
  const cases: [string, string, string][] = [
    ['', '', 'Please enter new Password ...'],
    ['short', 'short', 'New Password must be 8 to 64 characters ...'],
    [PASSWORD, PASSWORD, 'New Password must differ from the current one ...'],
    ['change01', 'change01', 'New Password must not be the User ID ...'],
    ['Better pass 1', 'Better pass 2', 'New Password and confirmation do not match ...'],
  ];
  for (const [next, again, text] of cases) {
    await newPassword.fill(next);
    await repeat.fill(again);
    await change.click();
    await expectMessage(page, text, 'error');
  }
  await expectAccessible(page);
  await repeat.fill('Better pass 1');
  await change.click();
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
});

test('@D-05 SGN-S15: a session idle for 16 minutes cannot be refreshed, and the next action shows the note @mock-only', async ({ page, mock }) => {
  // The page's own idle sign-out is pushed back, so the server's timeout is what ends the session.
  await mock({ latency: 0, idle: { timeoutMs: 60 * 60_000, warnBeforeMs: 120_000 } });
  await page.clock.install();
  await signOn(page, 'USER0001');
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
  await page.clock.fastForward('16:00');
  const refresh = await callApi(page, 'POST', '/api/auth/refresh');
  expect(refresh.status).toBe(401);
  expect(refresh.body?.code).toBe('SESSION_EXPIRED');
  await page.getByTestId('option-10').click();
  await page.getByLabel('Account ID').fill('00000000001');
  await page.getByTestId('submit').click();
  await expect(page).toHaveURL(/\/sign-on$/);
  await expectMessage(page, SESSION_ENDED, 'info');
});

test('@D-05 SGN-S20: 15 idle minutes sign the user off after a warning, with the session-ended note', async ({ page }) => {
  await page.clock.install();
  await signOn(page, 'USER0001');
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
  await page.clock.fastForward('13:01');
  const dialog = page.getByRole('dialog', { name: 'Are you still there?' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Stay signed on', exact: true })).toBeFocused();
  // Let the opening transition end, so axe measures the final colours.
  await page.clock.runFor(1000);
  await expect(page.locator('.mantine-Modal-content')).toHaveCSS('opacity', '1');
  await expect(page.locator('.mantine-Modal-overlay')).toHaveCSS('opacity', '1');
  await expectAccessible(page);
  await page.clock.fastForward('02:00');
  await expect(page).toHaveURL(/\/sign-on$/);
  await expectMessage(page, SESSION_ENDED, 'info');
  await expectAccessible(page);
  expect((await callApi(page, 'GET', '/api/auth/me')).status).toBe(401);
});

test('@DEF-SGN-002 SGN-S19 @fixed: failed attempts over the per-address limit get 429, even with the right password @mock-only', async ({ page }) => {
  // On the stack 30 failures would limit 127.0.0.1 for a minute and fail the tests after it;
  // identity-service's own tests cover S19 there with a low limit.
  await page.clock.install();
  await page.goto('/sign-on');
  await userId(page).fill('USER0003');
  // identity-service's default: 30 failed attempts per client address per minute.
  for (let attempt = 1; attempt <= 30; attempt += 1) {
    await password(page).fill(`WRONG${String(attempt).padStart(3, '0')}`);
    await submitPassword(page);
    await expectMessage(page, NEUTRAL, 'error');
  }
  await password(page).fill(PASSWORD);
  await submitPassword(page);
  await expectMessage(page, 'Too many sign-on attempts. Try again later ...', 'error');
  await expect(userId(page)).toBeFocused();
  await expect(page.getByTestId('retry-at')).toHaveText(/^Try again after \d{2}:\d{2}\.$/);
  await expectAccessible(page);
  // The limit is per address, not per user.
  await userId(page).fill('USER0001');
  await submitPassword(page);
  await expectMessage(page, 'Too many sign-on attempts. Try again later ...', 'error');
  // A minute later the limit is lifted; the five failures have locked USER0003 meanwhile.
  await page.clock.fastForward('01:01');
  await userId(page).fill('USER0003');
  await submitPassword(page);
  await expectMessage(page, NEUTRAL, 'error');
  await expect(page.getByTestId('retry-at')).toHaveCount(0);
  await userId(page).fill('USER0001');
  await password(page).press('Enter');
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
});

test('@DEF-SGN-002 SGN-S19 @legacy: there is no limit on failed attempts', async ({ page }) => {
  await page.goto('/sign-on');
  await userId(page).fill('USER0003');
  for (let attempt = 1; attempt <= 31; attempt += 1) {
    await password(page).fill(`WRONG${String(attempt).padStart(3, '0')}`);
    await submitPassword(page);
    await expectMessage(page, 'Wrong Password. Try again ...', 'error');
  }
  await password(page).fill(PASSWORD);
  await password(page).press('Enter');
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
});

test('SGN-S21: a password change whose user ID is not the signed-on user is refused and counts nothing', async ({ page }) => {
  await signOn(page, 'USER0001');
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
  const answer = await callApi(page, 'POST', '/api/auth/password', {
    body: { userId: 'admin001', currentPassword: PASSWORD, newPassword: 'Another pass 21' },
  });
  expect(answer.status).toBe(400);
  expect(answer.body).toMatchObject({ code: 'USER_ID_MISMATCH', field: 'userId', message: 'User ID does not match the signed-on user ...' });
  const me = await callApi(page, 'GET', '/api/auth/me');
  expect(me.body?.userId).toBe('USER0001');
  // ADMIN001's password is unchanged.
  await callApi(page, 'POST', '/api/auth/sign-off');
  await signOn(page, 'ADMIN001');
  await expect(page.getByRole('heading', { name: 'Admin menu' })).toBeVisible();
});
test('SGN-S16: after Sign out the old session cannot be refreshed', async ({ page, context }) => {
  await signOn(page, 'USER0001');
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
  const before = await context.cookies();
  await page.getByRole('button', { name: /^Account menu/ }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/signed-off$/);
  await expect(page.getByTestId('message-text')).toHaveText('Thank you for using CardDemo application...');
  if (target === 'stack') await context.addCookies(before.filter((c) => c.name === 'cd_at' || c.name === 'cd_rt'));
  expect((await callApi(page, 'POST', '/api/auth/refresh')).status).toBe(401);
  await page.goto('/');
  await expect(page).toHaveURL(/\/sign-on$/);
});
