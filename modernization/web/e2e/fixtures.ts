import AxeBuilder from '@axe-core/playwright';
import { expect, test as base, type Locator, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';

/**
 * mock (default): `vite --mode mock`, which answers the API in the browser in
 * fixed mode. stack: a running slice; CARDDEMO_E2E_MODE (fixed or legacy)
 * says which mode its fidelity switches are in.
 */
export const target = process.env.CARDDEMO_E2E_TARGET === 'stack' ? 'stack' : 'mock';
export const mode: 'fixed' | 'legacy' = target === 'stack' && process.env.CARDDEMO_E2E_MODE === 'legacy' ? 'legacy' : 'fixed';

/** The demo seed password: PASSWORD in mock mode, CARDDEMO_SEED_USER_PASSWORD on a stack. */
function seedPassword(): string {
  if (target === 'mock') return 'PASSWORD';
  if (process.env.CARDDEMO_SEED_USER_PASSWORD) return process.env.CARDDEMO_SEED_USER_PASSWORD;
  const envFile = process.env.CARDDEMO_ENV_FILE;
  if (envFile) {
    const line = readFileSync(envFile, 'utf8')
      .split('\n')
      .find((l) => l.startsWith('CARDDEMO_SEED_USER_PASSWORD='));
    const value = line?.slice('CARDDEMO_SEED_USER_PASSWORD='.length).trim();
    if (value) return value;
  }
  throw new Error('Set CARDDEMO_SEED_USER_PASSWORD or CARDDEMO_ENV_FILE for the stack target');
}

export const PASSWORD = seedPassword();

export interface MockOptions {
  batchWindow?: boolean;
  switches?: Record<string, 'legacy' | 'fixed'>;
  authorizationInstalled?: boolean;
  latency?: number;
  idle?: { timeoutMs: number; warnBeforeMs: number };
}

export const test = base.extend<{ mock: (options: MockOptions) => Promise<void> }>({
  mock: async ({ page }, use) => {
    await use(async (options) => {
      await page.addInitScript((o: MockOptions) => {
        sessionStorage.setItem('carddemo.mock.latency', String(o.latency ?? 0));
        if (o.batchWindow) sessionStorage.setItem('carddemo.mock.batchWindow', 'open');
        if (o.switches) sessionStorage.setItem('carddemo.mock.switches', JSON.stringify(o.switches));
        if (o.authorizationInstalled) sessionStorage.setItem('carddemo.mock.authorization', 'installed');
        if (o.idle) sessionStorage.setItem('carddemo.mock.idle', JSON.stringify(o.idle));
      }, options);
    });
  },
});

export { expect };

export const message = (page: Page) => page.getByTestId('message-text');
export const messageBox = (page: Page) => page.getByTestId('message');

/** The message line shows exactly this text, in this tone (error is red, success green, info neutral). */
export async function expectMessage(page: Page, text: string | RegExp, tone?: 'error' | 'success' | 'info' | 'notice') {
  await expect(message(page)).toHaveText(text);
  if (tone) await expect(messageBox(page)).toHaveAttribute('data-tone', tone);
}

export async function expectNoMessage(page: Page) {
  await expect(messageBox(page)).toHaveCount(0);
}

export async function signOn(page: Page, userId: string, password = PASSWORD) {
  await page.goto('/sign-on');
  await page.getByLabel('User ID').fill(userId);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign on', exact: true }).click();
}

export async function signOnAsUser(page: Page) {
  await signOn(page, 'USER0001');
  await expect(page.getByRole('heading', { name: 'Main menu' })).toBeVisible();
}

export async function turnOnFunctionKeys(page: Page) {
  const toggle = page.getByRole('switch', { name: 'F-keys' });
  if (!(await toggle.isChecked())) await toggle.check({ force: true });
  await expect(toggle).toBeChecked();
}

export interface ApiAnswer {
  status: number;
  body: Record<string, unknown> | null;
}

/**
 * Calls the API from the page, with the page's cookies and the CSRF header,
 * so it reaches the same service (or the in-browser mock) as the app.
 */
export async function callApi(
  page: Page,
  method: 'GET' | 'POST',
  path: string,
  options: { body?: unknown; headers?: Record<string, string> } = {},
): Promise<ApiAnswer> {
  return page.evaluate(
    async ({ method, path, body, headers }) => {
      const xsrf = document.cookie
        .split('; ')
        .find((c) => c.startsWith('XSRF-TOKEN='))
        ?.slice('XSRF-TOKEN='.length);
      const response = await fetch(path, {
        method,
        credentials: 'same-origin',
        headers: {
          Accept: 'application/json',
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
          ...(method !== 'GET' && xsrf ? { 'X-XSRF-TOKEN': decodeURIComponent(xsrf) } : {}),
          ...headers,
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      let json: Record<string, unknown> | null = null;
      try {
        json = (await response.json()) as Record<string, unknown>;
      } catch {
        json = null;
      }
      return { status: response.status, body: json };
    },
    { method, path, body: options.body, headers: options.headers ?? {} },
  );
}

/**
 * Records the API calls the page makes, as "METHOD /path". In mock mode the
 * calls never reach the network, so they are read from the mock's own log.
 */
export function trackApi(page: Page): () => Promise<string[]> {
  const seen: string[] = [];
  if (target === 'stack') {
    page.on('request', (request) => {
      const url = new URL(request.url());
      if (url.pathname.startsWith('/api/')) seen.push(`${request.method()} ${url.pathname}`);
    });
  }
  return async () =>
    target === 'mock'
      ? page.evaluate(() => [...((window as unknown as { carddemoMock: { calls: string[] } }).carddemoMock.calls)])
      : [...seen];
}

/** The calls made while `action` ran. */
export async function callsDuring(page: Page, calls: () => Promise<string[]>, action: () => Promise<void>): Promise<string[]> {
  const before = (await calls()).length;
  await action();
  // A call the action started would be on its way by now.
  await page.waitForTimeout(150);
  return (await calls()).slice(before);
}

export async function expectReadOnly(locator: Locator, readOnly: boolean) {
  if (readOnly) await expect(locator).toHaveAttribute('readonly', '');
  else await expect(locator).not.toHaveAttribute('readonly');
}

/** No serious or critical axe finding against WCAG 2.2 AA. */
export async function expectAccessible(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  const blocking = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  expect(
    blocking.map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => `${n.target.join(' ')}: ${n.failureSummary?.split('\n').slice(1).join(' ').trim() ?? ''}`).join('; ')})`),
  ).toEqual([]);
}

/** The header every page shows (FLOW-BILL-009, FLOW-SGN-007, FLOW-MENU-007). */
export async function expectHeader(page: Page, screenId: string) {
  await expect(page.getByTestId('header-titles')).toContainText('AWS Mainframe Modernization');
  await expect(page.getByTestId('header-titles')).toContainText('CardDemo');
  await expect(page.getByTestId('screen-id')).toContainText(screenId);
  await expect(page.getByTestId('header-date')).toHaveText(/^\d{2}\/\d{2}\/\d{2}$/);
  await expect(page.getByTestId('header-time')).toHaveText(/^\d{2}:\d{2}:\d{2}$/);
}
