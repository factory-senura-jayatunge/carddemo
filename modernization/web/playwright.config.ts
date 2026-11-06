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
// Tests tagged with the other mode (@fixed, @legacy), or that need a mock-only fixture, are skipped.
const mode = target === 'stack' && process.env.CARDDEMO_E2E_MODE === 'legacy' ? 'legacy' : 'fixed';
const skip = [mode === 'fixed' ? /@legacy\b/ : /@fixed\b/, target === 'stack' ? /@mock-only/ : null].filter((r): r is RegExp => r !== null);
const port = Number(process.env.CARDDEMO_WEB_PORT ?? 25173);
const baseURL = process.env.CARDDEMO_WEB_URL ?? `http://127.0.0.1:${port}`;

// Firefox and WebKit exist only when asked for, by --project or E2E_BROWSERS, so that a plain
// `playwright test` (make test-billpay, npm run e2e) stays on Chromium and needs no other browser.
// Workers load this file again without the command line, so the main process hands the
// answer down in CARDDEMO_E2E_PROJECTS.
const asked = new Set(`${process.env.E2E_BROWSERS ?? ''} ${process.env.CARDDEMO_E2E_PROJECTS ?? ''}`.split(/[\s,]+/));
const named: string[] = [];
process.argv.forEach((arg, i) => {
  const value = arg.startsWith('--project=') ? arg.slice('--project='.length) : arg === '--project' ? process.argv[i + 1] : undefined;
  value?.split(',').forEach((name) => named.push(name));
});
named.forEach((name) => asked.add(name));
process.env.CARDDEMO_E2E_PROJECTS = [...asked].filter(Boolean).join(' ');
const desktop = { viewport: { width: 1280, height: 860 } };
const others = [
  { name: 'firefox', use: { ...devices['Desktop Firefox'], ...desktop }, testIgnore: /screenshots\.spec\.ts/ },
  { name: 'webkit', use: { ...devices['Desktop Safari'], ...desktop }, testIgnore: /screenshots\.spec\.ts/ },
].filter((project) => asked.has(project.name));
// The projects this run covers name its report, so that a run of another browser (one that fails
// to launch, say) never replaces or joins the Chromium results. A Chromium-only run keeps the plain
// name, which scripts/test_billpay.py checks.
const covered = named.length > 0 ? [...new Set(named)] : ['chromium', ...others.map((project) => project.name)];
const reportSuffix = covered.every((name) => name === 'chromium') ? '' : `-${covered.join('+')}`;

export default defineConfig({
  testDir: 'e2e',
  // Playwright empties its output folder on every run; a subfolder keeps Vitest's report beside it.
  outputDir: 'test-results/e2e',
  fullyParallel: target === 'mock',
  workers: target === 'mock' ? 4 : 1,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  timeout: 30_000,
  expect: { timeout: 5_000 },
  grepInvert: skip,
  // The JSON report is what make trace reads (@<item ID> and @fixed/@legacy tags). One file per
  // target and mode (plus the projects, when not just Chromium), outside the html folder and
  // test-results, which each run empties.
  reporter: [
    ['list'],
    ['json', { outputFile: `playwright-report/e2e-${target}-${mode}${reportSuffix}.json` }],
    ['html', { open: 'never', outputFolder: 'playwright-report/html' }],
  ],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    locale: 'en-US',
    timezoneId: 'America/Chicago',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'], ...desktop }, testIgnore: /screenshots\.spec\.ts/ },
    { name: 'screenshots', use: { ...devices['Desktop Chrome'], ...desktop }, testMatch: /screenshots\.spec\.ts/ },
    ...others,
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
