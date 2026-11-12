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

Not built yet: the transaction-service contract lets an `ADMIN` open a report run by link, `/reports/transactions/<runId>`, with no run list. The app has no such route; `/reports/transactions` is for a `USER` only, and an `ADMIN` cannot see a run in the web app.

Stack: Vite, React 19, TypeScript, Mantine, TanStack Query, React Router, openapi-fetch. Node 24 (`engines`), exact versions in `package-lock.json`.

## Commands

```sh
npm ci
npm run verify       # gen:api, typecheck, lint, Vitest, build, bundle budget
npm run dev          # http://127.0.0.1:25173, /api proxied to the services (identity 28081, billpay 28082)
npm run dev:mock     # the same with the in-browser contract mock, no services needed
npm run build && npm run preview   # the built app on 25173, /api proxied like dev
npx playwright install chromium
npx playwright test  # BILL, SGN and MENU scenarios plus axe checks, on the mock
CARDDEMO_E2E_TARGET=stack CARDDEMO_ENV_FILE=../.env npx playwright test --project=chromium   # against a running slice
npx playwright test --project=firefox --project=webkit   # other browsers, only when named (or E2E_BROWSERS); on macOS Firefox gets a scratch home (playwright.config.ts)
npm run screenshots  # review screens into e2e-output/screens (CARDDEMO_SCREENSHOT_DIR to change)
```

`dev` and `preview` proxy `/api` to the standard service ports (identity 28081, billpay 28082, account 28083, transaction 28084, reference 28085, authorization 28086). For a stack on other ports, set `CARDDEMO_IDENTITY_PORT`, `CARDDEMO_BILLPAY_PORT`, `CARDDEMO_ACCOUNT_PORT`, `CARDDEMO_TRANSACTION_PORT`, `CARDDEMO_REFERENCE_PORT` or `CARDDEMO_AUTHORIZATION_PORT`; `npx vite preview --port 25194 --strictPort` serves the build on another port.

On a stack, `CARDDEMO_E2E_MODE=legacy` runs the legacy-mode expectations instead of the fixed ones; tests tagged `@mock-only` (batch window, zero balances, scenario users, screenshots) are skipped there.

The BILL scenarios on a stack need the contract's start data: account 00000000001 at 194.00 and 00000000002 at 158.00, as `make db-reset load` leaves them. They pay both, and so does `make billpay` for 00000000001, so every browser's run needs a freshly loaded database. `e2e/bill.spec.ts` checks the two balances with read-only previews first and stops with the reason when they differ; it never resets anything.

## Generated inputs

- `src/api/generated/` comes from the OpenAPI snapshots in `openapi/` (`npm run gen:api`, also run by `verify`). `CARDDEMO_OPENAPI_DIR` points it at another folder.
- `src/messages/` is the message catalogue, split into one table per group (`scripts/message-groups.mjs`: a key's group comes from the area of its spec item, a named decision, its prefix, or the `CORE_KEYS` list; a key none of these places fails the generator, so nothing reaches the entry by default). `index.ts` holds only types and `all.ts` the merged table for the mocks and tests (ESLint keeps it and `src/mocks` out of the app, by any import path). `src/lib/messages.ts` resolves the entry groups, core and bill; a lazy page passes the tables it imports to `catalogue()`, so a feature's texts load with its page. `npm run gen:messages` writes the folder from `platform/web-support/target/messages.json`; `node scripts/gen-messages.mjs --check` fails when a file is stale, missing or not generated.

## Bundle budget

`npm run budget` (`scripts/check-bundle.mjs`, part of `verify`) checks the production build against `bundleBudget` in `package.json`, in gzip KiB:

- `initialJsGzipKiB` 190: the entry chunk and the chunks it imports statically, which the first page load downloads.
- `lazyRouteJsGzipKiB` 8: the own chunk of each page that `src/router.tsx` loads with `import()`. The largest route was 5.9 KiB (the account update page) when the cap was set. The cap is per own chunk: chunks shared between routes and the first-use size are printed and bounded only by the total.
- `totalJsGzipKiB` 280: every JS chunk. It was set from the measured 261.2 KiB, plus the planned reference-service list and update pages and the ADMIN report-run route (about 8 KiB), plus 10 KiB of headroom.
- `totalCssGzipKiB` 40.

It also fails:

- when a page the router loads with `import()` has no chunk of its own (it is also imported statically), or a dynamic page chunk is not in the router;
- when a key of a message group that the app imports (`src/messages/<group>`, outside the mocks and tests) is in no chunk or in two, or when a chunk holds a message text as a `text:` value without as many keys with that text in the table shape the checker reads, so a changed table shape fails instead of passing. A message text anywhere else fails too, unless the validation rules also hold it: the rule table and the engine's default messages repeat some catalogue texts under their own properties. Every group must have at least one text that is not a rule text, so a key-less copy of a whole table is still seen. The guarantee is for whole tables only: a partial key-less copy (code/text tuples) made only of texts the validation rules also hold may pass;
- when a message group other than core or bill is in an initial chunk;
- when a key of a group no app module imports is in the build. Such a group (the reference group before its pages exist) is reported as not shipped.

It prints every route with its first-use size, every chunk as initial, route or shared, and the chunk each message group landed in. The key lists come from `src/messages/`, so `verify` runs `check:messages` before `budget`.

## Mock mode

`vite --mode mock` installs `src/mocks/` in the browser. It follows the contract in fixed mode. Settings, in `sessionStorage`: `carddemo.mock.latency` (ms, default 250), `carddemo.mock.batchWindow=open`, `carddemo.mock.switches` (JSON of UI switches), `carddemo.mock.authorization=installed`, `carddemo.mock.idle` (JSON idle timings). Demo users: `ADMIN001`, `ADMIN002`, `USER0001` to `USER0003` with `PASSWORD`; `TEST0001`/`Secret12`, `TYPEX001` (type X) and `CHANGE01` (must change password) for the SGN scenarios. Accounts: the 50 sample accounts, plus `00000000098` (0.00) and `00000000099` (-25.50), and, for the transaction add only, `00000000096` with two cards.

## Tests

Test names carry the scenario (`BILL-S5`), the spec items they cover as tags (`@DEF-BILL-014`) and, where the result depends on the fidelity mode, `@fixed` or `@legacy`. `make trace` reads them from `test-results/vitest-junit.xml` (Vitest) and `playwright-report/e2e-<target>-<mode>.json` (Playwright on Chromium; one file per target and mode, so a fixed and a legacy run on the stack both count). Runs of other projects get their own file, such as `e2e-mock-fixed-firefox+webkit.json`, so a browser that fails to launch never touches the Chromium results. The Playwright HTML report is in `playwright-report/html`.
