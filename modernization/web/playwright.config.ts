import { defineConfig, devices } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

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

/** chmod -N on dir and the folders below it; symbolic links are neither followed nor changed. */
function removeAcls(dir: string) {
  execFileSync('/bin/chmod', ['-N', dir], { stdio: 'ignore' });
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) removeAcls(join(dir, entry.name));
  }
}

/**
 * On macOS 27, Playwright's Firefox can exit at launch with "Could not find profile folder". Reported
 * cause: besides the profile Playwright passes, Gecko looks in ~/Library/Application Support/Firefox,
 * which macOS can protect (https://github.com/microsoft/playwright/issues/42768, closed for 1.65; 1.65.0
 * still fails on macOS 27.2, https://github.com/n9d0g/fcc/pull/477). CFFIXED_USER_HOME points that
 * lookup at an empty private folder that this process creates and removes, so the user's own Firefox
 * folder is never read or changed. Linux and Windows get nothing. Try without it on each upgrade.
 */
function firefoxLaunchEnv(): Record<string, string> | undefined {
  if (process.platform !== 'darwin') return undefined;
  const home = mkdtempSync(join(tmpdir(), 'carddemo-pw-firefox-home-'));
  process.on('exit', () => {
    // macOS gives the Library and Downloads folders Firefox creates there a "deny delete" ACL.
    // chmod -R would change the targets of symbolic links, so the folders are walked one by one;
    // rmSync removes links, not their targets. A folder left behind must not change the exit code.
    try {
      removeAcls(home);
      rmSync(home, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
    } catch (error) {
      const reason = (error as NodeJS.ErrnoException).code ?? 'chmod failed';
      console.warn(`playwright.config: Firefox's scratch home ${home} was left behind (${reason})`);
    }
  });
  const env = Object.fromEntries(Object.entries(process.env).filter((entry): entry is [string, string] => entry[1] !== undefined));
  return { ...env, CFFIXED_USER_HOME: home };
}
const firefoxEnv = asked.has('firefox') ? firefoxLaunchEnv() : undefined;

const others = [
  {
    name: 'firefox',
    use: { ...devices['Desktop Firefox'], ...desktop, ...(firefoxEnv ? { launchOptions: { env: firefoxEnv } } : {}) },
    testIgnore: /screenshots\.spec\.ts/,
  },
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
