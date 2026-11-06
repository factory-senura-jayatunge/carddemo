# Make targets

Every target runs as `make -C modernization <target>`. "Passes" means exit status 0; every target prints one summary line, `<target>: OK` or `<target>: FAILED (<reason>)`. The targets that exist today keep their behaviour; the stubs are replaced when their phase lands ([overview.md](overview.md#phasing)).

## Lifecycle

| Target | What it does | Passes when |
| :-- | :-- | :-- |
| `setup` | Checks JDK 21, Node 24, Docker/Colima and the Airflow venv, creates `.env` with generated secrets, validates the compose file; later also runs `npm ci`, installs `carddemo_airflow` into the venv and warms the Maven cache. | every check prints `ok` and it ends with `setup: OK` (exists today). |
| `db-reset` | Starts SQL Server if needed, drops and recreates the `carddemo` database, runs every Flyway migration through `db-migrate` (schemas, tables, logins, grants, views, reference seed). | Flyway reports every migration applied, `flyway validate` is clean, and each service login can connect and is refused a table outside its grants. |
| `load` | Runs `dataLoadJob` on `app/data/ASCII` (and the authorization sample unload when the module is enabled); seeds the users with the password from `.env`. | every step ends `COMPLETED` with the expected counts in [data-model.md](data-model.md#sample-data-migration) (50 accounts, customers, cards, cross-references and category balances; 7 types, 18 categories, 51 disclosure groups, 10 users). |
| `up` | Starts the infrastructure (as today), then the converted services, `batch-service`, Airflow and the web app on 127.0.0.1, and waits for their health checks. | every container and process reports healthy within `UP_TIMEOUT`, and `make status` lists them. |
| `smoke` | Read-only check of the running stack: health endpoints, sign-on as a seed user, `GET /api/auth/me`, a Bill Pay balance preview, the DAG list without import errors, the RabbitMQ queues of the enabled modules. | every check passes in under 60 seconds and no table row count changes. |
| `billpay` | Starts only the Bill Pay slice (SQL Server, `identity-service`, `billpay-service`, web), runs `db-reset` and `load` if the database is empty, runs one scripted sign-on, preview and payment, then leaves the slice running at `http://127.0.0.1:25173/bill-pay`. | the slice is healthy and the scripted payment returns `Payment successful.  Your Transaction ID is ...` with the account balance at zero afterwards. |
| `down` | Stops the web app, Airflow, the JVMs and the containers; data volumes are kept (as today for the containers). | no `carddemo-modern` container and no process recorded in `var/run/` is left running. |

## Verification

| Target | What it does | Passes when |
| :-- | :-- | :-- |
| `verify-spec` | Checks the NL spec format and coverage, builds the GnuCOBOL batch programs and runs every spec-only reimplementation in `docs/modernization/spec/checks/` (exists today). | `check_spec.py` reports no finding and every reimplementation matches the GnuCOBOL output on sample and stress data. |
| `verify-ledger` | Compares `ledger/*.toml` with the code and the spec. | every switch read through `FidelitySwitches` has a ledger entry, every entry names a defect-decide item the spec defines, every `fixed` value and every deviation has an approver and a date, and every architecture-dependent item has a deviation entry. |
| `test-backend` | `./mvnw verify`: unit, service integration (Testcontainers), contract and ArchUnit tests for the platform libraries, every converted service and `batch-service`, in both fidelity modes. | zero failing or erroring tests; JUnit reports are written to `build/test-results/backend/`. |
| `test-billpay` | The Bill Pay subset: `billpay-service` and `ledger-core` tests, scenarios S1 to S12 against the API, the screen's Vitest tests and its Playwright tests. | all of them pass, and every BR, VAL, FLOW, SE, ERR and NUM item of unit BILL has a passing test. |
| `verify-batch` | The differential harness: each converted batch job against its GnuCOBOL program on sample and stress data ([batch.md](batch.md#differential-verification)). | in legacy mode every exported record and report line is byte-identical; in fixed mode the only differences are in the records the switched item describes. |
| `verify-data` | Reconciles SQL Server with the source files after `load` ([data-model.md](data-model.md#reconciliation)). | counts, key sets, control totals, byte-for-byte re-export and the cross-table checks report zero mismatches for every table. |
| `verify-harness` | Self-test of the differential harness with seeded differences. | identical inputs compare equal, every seeded difference (byte, missing record, order, sign) is reported, and only wall-clock timestamps are masked. |
| `verify-cycle` | On a fresh `load`, triggers `carddemo_daily_cycle` through the Airflow API for business date 2022-07-06, then a second trigger of the same date and a run for the next date. | the first run succeeds with the end state equal to the GnuCOBOL F-order run of the harness, the repeated trigger is refused without changing data, the window is closed at the end, and the next-date run succeeds. |
| `test-airflow` | pytest on the DAGs without a running Airflow: `DagBag` import, task order, parameters, pool and `max_active_runs`, the import rule, the operator against a stub `batch-service`. | zero DAG import errors and every test passes. |
| `web-build` | `npm ci`, client generation from the OpenAPI documents, `tsc --noEmit`, ESLint, Vitest, `vite build`. | no type or lint error, every Vitest test passes, and `web/dist/` is written within the bundle-size budget in `package.json`. |
| `test-e2e` | Playwright against the stack from `make up` on fresh data: each converted screen's equivalence scenarios, keyboard-only paths and axe accessibility checks. | every test passes in Chromium (CI nightly adds Firefox and WebKit) with no serious or critical axe finding. |
| `trace` | Builds the traceability matrix from the spec, the test reports and the ledger ([repo-build-ci.md](repo-build-ci.md#traceability)). | no gap for the units in `ledger/scope.toml` and no test cites an unknown item ID. |

## Proposed additions

These targets are not in today's `Makefile`; the batch design needs them.

| Target | What it does | Passes when |
| :-- | :-- | :-- |
| `batch-window-close` | Closes a batch window left open after a failed cycle, once the operator has recovered (replaces running OPENFIL by hand). | the `ops.batch_window` row is closed and the online services accept writes again. |
| `cycle-rerun CYCLE=<id>` | Restores the account and category-balance snapshots taken before the cycle's posting step and reruns the cycle as a new job instance (replaces the runbook restore). | the snapshot restore and the rerun both succeed and the cycle's end state equals the first successful run of that input. |
