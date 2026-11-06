# CardDemo web app

The React front end of the modernized CardDemo (work package P4). It replaces the 3270 screens with web pages and keeps their rules, messages and order of checks. The routes, messages and API it uses are fixed by the Bill Pay slice contract.

| Route | Role | Legacy screen |
| :-- | :-- | :-- |
| `/sign-on` | public | CC00 · COSGN00C |
| `/sign-on/change-password` | public | forced password change (fixed mode) |
| `/signed-off` | public | the COSGN00C thank-you text |
| `/` | USER | CM00 · COMEN01C |
| `/admin` | ADMIN | CA00 · COADM01C (stub: options 1 to 4 coming soon) |
| `/bill-pay` | USER | CB00 · COBIL00C |

Stack: Vite, React 19, TypeScript, Mantine, TanStack Query, React Router, openapi-fetch. Node 24 (`engines`), exact versions in `package-lock.json`.

## Commands

```sh
npm ci
npm run verify       # gen:api, typecheck, lint, Vitest, build, bundle budget
npm run dev          # http://127.0.0.1:25173, /api proxied to the services (identity 28081, billpay 28082)
npm run dev:mock     # the same with the in-browser contract mock, no services needed
npx playwright install chromium
npx playwright test  # BILL, SGN and MENU scenarios plus axe checks, on the mock
CARDDEMO_E2E_TARGET=stack CARDDEMO_ENV_FILE=../.env npx playwright test --project=chromium   # against a running slice
npm run screenshots  # review screens into test-results/screens (CARDDEMO_SCREENSHOT_DIR to change)
```

On a stack, `CARDDEMO_E2E_MODE=legacy` runs the legacy-mode expectations instead of the fixed ones; tests tagged `@mock-only` (batch window, zero balances, scenario users, screenshots) are skipped there.

## Generated inputs

- `src/api/generated/` comes from the OpenAPI snapshots in `openapi/` (`npm run gen:api`, also run by `verify`). `CARDDEMO_OPENAPI_DIR` points it at another folder.
- `src/messages.ts` is the message catalogue. `npm run gen:messages` writes it from `platform/web-support/target/messages.json`; `node scripts/gen-messages.mjs --check` fails when it is stale.

## Mock mode

`vite --mode mock` installs `src/mocks/` in the browser. It follows the contract in fixed mode. Settings, in `sessionStorage`: `carddemo.mock.latency` (ms, default 250), `carddemo.mock.batchWindow=open`, `carddemo.mock.switches` (JSON of UI switches), `carddemo.mock.authorization=installed`, `carddemo.mock.idle` (JSON idle timings). Demo users: `ADMIN001`, `ADMIN002`, `USER0001` to `USER0003` with `PASSWORD`; `TEST0001`/`Secret12`, `TYPEX001` (type X) and `CHANGE01` (must change password) for the SGN scenarios. Accounts: the 50 sample accounts, plus `00000000098` (0.00) and `00000000099` (-25.50).

## Tests

Test names carry the scenario (`BILL-S5`) and the spec items they cover (`[DEF-BILL-014]`), for the trace matrix. Vitest writes `test-results/vitest-junit.xml`, Playwright `test-results/e2e-junit.xml`.
