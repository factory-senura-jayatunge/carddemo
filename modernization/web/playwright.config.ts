import { defineConfig, devices } from '@playwright/test';

/**
 * Two targets:
 *   mock (default): starts `vite --mode mock`, the in-browser stand-in for the services.
 *   stack: CARDDEMO_E2E_TARGET=stack runs against a running slice (make up), at
 *   CARDDEMO_WEB_URL (default http://127.0.0.1:25173). Tests that need a mock-only
 *   fixture (batch window, zero balance, scenario users) are tagged @mock-only
 *   and skipped there. CARDDEMO_E2E_MODE=legacy runs the legacy expectations.
 */
const target = process.env.CARDDEMO_E2E_TARGET === 'stack' ? 'stack' : 'mock';
// The mock answers in fixed mode; a stack in the mode CARDDEMO_E2E_MODE names (fixed by default).
// Tests whose title names the other mode, or that need a mock-only fixture, are skipped.
const mode = target === 'stack' && process.env.CARDDEMO_E2E_MODE === 'legacy' ? 'legacy' : 'fixed';
const skip = [mode === 'fixed' ? / legacy\b/ : / fixed\b/, target === 'stack' ? /@mock-only/ : null].filter((r): r is RegExp => r !== null);
const port = Number(process.env.CARDDEMO_WEB_PORT ?? 25173);
const baseURL = process.env.CARDDEMO_WEB_URL ?? `http://127.0.0.1:${port}`;

export default defineConfig({
  testDir: 'e2e',
  fullyParallel: target === 'mock',
  workers: target === 'mock' ? 4 : 1,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  timeout: 30_000,
  expect: { timeout: 5_000 },
  grepInvert: skip,
  reporter: [['list'], ['junit', { outputFile: 'test-results/e2e-junit.xml' }], ['html', { open: 'never' }]],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    locale: 'en-US',
    timezoneId: 'America/Chicago',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 860 } }, testIgnore: /screenshots\.spec\.ts/ },
    { name: 'screenshots', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 860 } }, testMatch: /screenshots\.spec\.ts/ },
  ],
  webServer:
    target === 'mock'
      ? {
          command: `npx vite --mode mock --host 127.0.0.1 --port ${port} --strictPort`,
          url: baseURL,
          reuseExistingServer: !process.env.CI,
          timeout: 60_000,
        }
      : undefined,
});
