# CardDemo modernization: local stack

This directory holds the modernized CardDemo stack: Spring Boot services, Spring Batch jobs, Airflow DAGs and a React front end on SQL Server and RabbitMQ. It is being built in phases. Right now it contains the local infrastructure, the operator entry points and the lifecycle of the Bill Pay slice. The targets whose work has not landed exist already but fail with the phase that delivers them, so a missing piece never looks like a pass.

Run every command from the repository root with `make -C modernization <target>`, or from this directory with `make <target>`.

## Prerequisites

`make setup` checks all of them and prints a hint for anything missing.

| Tool | Version | macOS (tested: Apple Silicon) | Linux / CI |
|:--|:--|:--|:--|
| JDK | 21 (`.java-version`) | `brew install openjdk@21` (no linking needed) | `openjdk-21-jdk-headless`, or `actions/setup-java` with `java-version: 21` |
| Node.js and npm | 24 (`.nvmrc`, `.node-version`) | `brew install node@24` (keg-only, used by its full path) | `nvm install 24`, or `actions/setup-node` with `node-version-file: modernization/.nvmrc` |
| Docker engine and Compose v2 | Compose 2 or newer | `brew install colima docker docker-compose`, then `colima start --vm-type vz --vz-rosetta --cpus 4 --memory 8 --disk 60` once (later just `colima start`) | Docker Engine with the compose plugin |
| amd64 emulation | | Colima with `--vz-rosetta`: SQL Server ships amd64 images only | Not needed on x86_64 |
| Airflow venv | Airflow 3.x | See below | See below |
| Python | 3 | Comes with the Xcode command line tools | `python3` |

The Airflow virtual environment lives outside the repository, by default in `~/.venvs/carddemo-airflow` (set `AIRFLOW_VENV` to use another one). The tested setup is Airflow 3.3.2 on Python 3.14. Create the venv once; `make setup` (`scripts/airflow_venv.sh`) then installs `airflow/requirements.txt` with the official constraints file kept next to it (`airflow/constraints-3.3.2-py3.14.txt`) and the `carddemo_airflow` package in editable mode. Requirements that are already satisfied need no network.

```sh
python3.14 -m venv ~/.venvs/carddemo-airflow
make -C modernization setup
```

A venv on another Python fails `setup` until a `constraints-3.3.2-py<version>.txt` for that Python is added (from the `constraints-3.3.2` branch of `github.com/apache/airflow`).

## Quick start

```sh
make -C modernization setup   # toolchain check, creates .env and the JWT signing key with generated secrets
make -C modernization up      # starts SQL Server, RabbitMQ and Postgres, waits until all are healthy
make -C modernization status  # containers and health
make -C modernization down    # stops and removes the containers; data volumes are kept
```

## Lifecycle

These are the targets in the order you use them. Every target ends with one line, `<target>: OK` or `<target>: FAILED (<reason>)`, and exits non-zero on failure (`make` itself always exits 2 when a recipe fails, so read the line, not the number). Targets marked "not yet" print `<target>: FAILED (not implemented yet: arrives with <phase>)`; `make -s stubs` lists them, which is how CI skips their jobs instead of passing them.

| Order | Target | What it does | State |
|:--|:--|:--|:--|
| 1 | `setup` | Checks JDK 21, Node 24, Docker/Colima (with amd64 emulation on arm64) and the Airflow venv. Creates `.env` from `.env.example`, generates every empty password and the JWT signing key, and validates the compose file. Running it again keeps existing values and only adds keys that are new in the example. | Done |
| 2 | `db-reset` | Builds `db/db-migrate`, starts the containers if needed, drops and recreates the application database `CARDDEMO_DB_NAME` (it warns first), runs every Flyway migration, creates the service logins and checks their grants (`--reset --check-logins`). | Done |
| 3 | `load` | Builds `batch/batch-service` and runs its `dataLoadJob` once (a one-shot JVM, profile `demo`) in its own batch window, on `app/data/ASCII` (`CARDDEMO_LOAD_SOURCE_DIR`), and fails unless the job completed: the core and reference tables of `CARDDEMO_DB_NAME`, and the lookup lists of the account edits. A reload after payments exist fails on a foreign key; run `db-reset` first. `verify-data` comes after `up`, because identity-service seeds the sign-on users when it starts. | Done |
| 4 | `up` | Starts the infrastructure, then every converted service from its jar (Spring profile `demo`) and the Vite dev server, and waits for their health checks (`UP_TIMEOUT`, default 300 s). `ONLY=identity-service,web` starts only those components. | Done; services join as they are converted |
| 5 | `verify-data` | Reconciles the loaded tables and the sign-on users with the sample files (details under Verification targets). It needs identity-service to have started once on this database (`up`, or `up ONLY=identity-service`); otherwise the `iam.app_user` check fails and the target prints that hint. | Done |
| 6 | `smoke` | Read-only check of the running slice in under 60 s: health of every running service, sign-on as `USER0001` with the seed password, `me`, `features`, a Bill Pay preview for account `00000000001` (needs `load`), the JWKS, the web app when it runs, and sign-off. It counts the rows of every table in `CARDDEMO_DB_NAME` before and after; only `iam.session` and `iam.audit_event` may change. | Done |
| 7 | `billpay` | Builds identity-service and billpay-service, runs `db-reset` and `load` when `CARDDEMO_DB_NAME` has no accounts, starts the slice (`up` for identity-service, billpay-service and web), then makes one scripted payment through the API (`scripts/billpay_e2e.py`): sign-on, preview, pay with confirm `Y`, and checks the stored transaction and the zero balance. The slice stays up at `http://127.0.0.1:25173/bill-pay`. | Done; needs `load` |
| 8 | `down` | Stops the web app, the services and the containers; volumes are kept. | Done |

Other targets:

| Target | What it does |
|:--|:--|
| `help` | Lists every target. |
| `toolchain` | Shows the `JAVA_HOME`, `node`, `java`, `DOCKER_HOST` and shared paths that recipes use. |
| `build` | Builds the jars of this checkout with the Maven wrapper, tests skipped. |
| `db-migrate` | Like `db-reset`, without dropping the database: applies new migrations and checks the logins. `NEW_INSTANCE=1` (only after a restore, see the [runbook](../docs/runbooks/modernized-database-restore.md)) also gives the database a new instance ID and so a new output root; any other value fails. |
| `status` | Containers and host processes with their health; fails when one is unhealthy or a recorded process is gone. |
| `logs` | Follows the container logs; `SERVICE=mssql`, `rabbitmq` or `airflow-db` limits it to one service. Process logs are in `$CARDDEMO_VAR_DIR/log/<name>.log`. |
| `stop` | Stops the web app and the services but leaves the shared containers running, so other checkouts and test runs keep their SQL Server. |
| `infra-up`, `infra-down` | The infrastructure parts of `up` and `down`. |
| `rabbitmq-users` | Creates the RabbitMQ virtual host and service users when they are missing and sets their permissions (see Infrastructure). `infra-up` and `up` do the same; this target also fails when a service password is missing from `.env`. |
| `infra-wipe CONFIRM=1` | Removes this project's containers and volumes (all local data, for every checkout). It touches nothing outside the `carddemo-modern` compose project. |
| `cycle-rerun CYCLE=<id>` | The deliberate rerun of one daily cycle. It checks that `CYCLE` is a cycle ID first, a whole number from 1 to 2147483647 without a leading zero (`batch.cycle.cycle_id` is an `INT`), and otherwise fails with exit status 2 before anything runs; then it builds `batch/batch-service` and runs its exec jar once, like `load` but without the `demo` profile, with `--carddemo.batch.command=cycle-rerun --carddemo.batch.rerun.cycle-id=<id>`, and fails unless the JVM exits 0. batch-service puts the cycle back to `OPEN` with the next `attempt`, so the next `carddemo_daily_cycle` run of that date runs it as fresh job instances. |
| `stubs` | Lists the targets that are not implemented yet. |

### Host processes

`up` runs each converted component on 127.0.0.1 (ports and heap caps from the architecture's deployables table):

| Component | Port | Started when | Command |
|:--|--:|:--|:--|
| `identity-service` | 28081 | `services/identity-service/pom.xml` exists | `java -Xmx256m -XX:MaxMetaspaceSize=128m -Xss512k -jar target/identity-service-*-exec.jar --server.port=28081 --server.address=127.0.0.1 --spring.profiles.active=demo`; it reads its signing key from `CARDDEMO_JWT_KEY_FILE` |
| `billpay-service` | 28082 | `services/billpay-service/pom.xml` exists | the same pattern, 256 MiB |
| `account-service`, `transaction-service`, `reference-service`, `authorization-service`, `inquiry-service` | 28083 to 28087 | their module exists | the same pattern (384, 384, 256, 384, 256 MiB) |
| `web` (Vite) | 25173 | `web/package.json` exists | `npm run dev` in `web/` |
| `batch-service` | 28090 (`CARDDEMO_BATCH_PORT`) | `batch/batch-service/pom.xml` exists; started before Airflow | `java -Xmx1g -XX:MaxMetaspaceSize=128m -Xss512k -jar target/batch-service-*-exec.jar --spring.profiles.active=server --server.port=28090 --server.address=127.0.0.1`: the internal API that Airflow calls, `/internal/*` with the bearer `CARDDEMO_BATCH_INTERNAL_TOKEN`, `/actuator/health` without it. It starts a job only when one is launched, and a launch answers `202` at once. `up` gives it absolute `CARDDEMO_LOAD_SOURCE_DIR` and `CARDDEMO_PAUTH_SOURCE_FILE` (the sample files of this checkout) and `CARDDEMO_VAR_DIR` (it reads the inbound files under `$CARDDEMO_VAR_DIR/inbound/` and writes under its database's output root, `$CARDDEMO_VAR_DIR/out/`), and gives Airflow `CARDDEMO_BATCH_URL` on the same port. `load` still runs `dataLoadJob` in its own one-shot JVM |
| `airflow-api-server` | 28080 | `airflow/dags/` exists | `airflow api-server` from `AIRFLOW_VENV`: UI, REST API and the task execution API; its health endpoint `/api/v2/monitor/health` reports for all four Airflow processes |
| `airflow-scheduler` | | the same | `airflow scheduler --skip-serve-logs` (LocalExecutor, parallelism 4) |
| `airflow-dag-processor` | | the same | `airflow dag-processor` (one parsing process) |
| `airflow-triggerer` | | the same | `airflow triggerer --skip-serve-logs`: the deferred waits for batch-service job executions |

`up ONLY=airflow` starts only the four Airflow processes. Before they start, `up` runs `airflow db migrate` and `airflow pools import airflow/pools.json` (log: `$CARDDEMO_VAR_DIR/log/airflow-prepare.log`). Before it starts anything, `up` prepares what is missing, so a fresh clone needs no manual build: one Maven run (`package`, tests skipped; log `$CARDDEMO_VAR_DIR/log/up-build.log`) builds every service jar that is missing or older than its module, the reactor poms or `platform/`, and `npm ci` (log `up-npm-ci.log`) runs when `web/node_modules` is missing or older than `package-lock.json`. A component that already runs is left as it is, with a warning when its files are stale. A component whose preparation fails does not start, and `up` fails with the reason; one whose module does not exist yet is skipped. Health is `GET /actuator/health` for the services and `GET /` for Vite. Every process gets the values of the shared `.env` in its environment (the environment wins), and `CARDDEMO_LEDGER_DIR` set to this checkout's `ledger/`. PIDs are in `$CARDDEMO_VAR_DIR/run/<name>.pid`, logs in `$CARDDEMO_VAR_DIR/log/<name>.log`; both are machine-wide, so a second `up` from another worktree reports the running processes instead of starting copies. `CARDDEMO_SPRING_PROFILE` overrides `demo`.

### Protecting the shared containers

The containers are shared by every checkout, so the targets that would interrupt them say so:

- `up` and `infra-up` first do a compose dry run. If it would recreate (restart) a running container, for example after a change to `docker-compose.yml` or `.env`, they stop with `would RECREATE (restart) running shared container(s): ...` and change nothing. `RECREATE=1` lets them go ahead, with a warning.
- `down` and `infra-down` print which shared containers they stop, and refuse while a heavy target holds the heavy lock; `FORCE=1` overrides.
- `infra-wipe` needs `CONFIRM=1`.
## Airflow

Airflow 3 orchestrates the batch jobs; it holds no business rule. The DAGs in `airflow/dags/` import only Airflow and the `carddemo_airflow` package (`airflow/carddemo_airflow/`), whose operators call batch-service's internal API (`/internal/*` on `127.0.0.1:28090`, bearer `CARDDEMO_BATCH_INTERNAL_TOKEN`): register the cycle, open and close the global batch window, launch a job and wait for it in the triggerer, as the batch-service execution contract (`docs/modernization/architecture/batch-execution-contract.md`) defines. File paths never travel in a request; batch-service resolves them, and names output folders by IDs it generates.

| Setting | Value |
|:--|:--|
| `AIRFLOW_HOME` | `$CARDDEMO_VAR_DIR/airflow` (shared by every checkout); task logs in `$CARDDEMO_VAR_DIR/log/airflow/` |
| Metadata | the `airflow-db` Postgres container (`AIRFLOW_DB_*`) |
| Executor | LocalExecutor; DAGs are paused when they are created (except `carddemo_transaction_report`, below), and none catches up |
| Sign-on | `http://127.0.0.1:28080`, user `AIRFLOW_ADMIN_USER` with `AIRFLOW_ADMIN_PASSWORD` from `.env` (simple auth manager, through `carddemo_airflow.auth`) |
| Report account | `CARDDEMO_AIRFLOW_REPORT_USER` (default `transaction-service`) with `CARDDEMO_AIRFLOW_REPORT_PASSWORD`: may only start `carddemo_transaction_report` runs (below) |
| Pool | `carddemo_batch`, 1 slot, deferred waits included: one data-changing job at a time |

| DAG | Schedule | Tasks |
|:--|:--|:--|
| `carddemo_daily_cycle` | `@daily` | `register_cycle`, `open_batch_window`, [`purge_pending_authorizations_before_posting`], `archive_master`, `ingest_daily_file`, `post_daily_transactions`, `calculate_interest` (when the cycle charges interest), `generate_statements`, `apply_retention`, `close_batch_window`, [`purge_pending_authorizations`]. Params `businessDate`, `interest` (`always`, `month_end`, `never`), `statements`, `purge_position`. |
| `carddemo_data_load` | manual | `open_batch_window`, `load` (`dataLoadJob`), `close_batch_window`. The reconciliation is not a job: run `verify-data` afterwards. |
| `carddemo_reference_refresh` | Saturdays 06:00 | `open_batch_window`, transaction types (only when the inbound file was delivered), disclosure groups, `close_batch_window` |
| `carddemo_transaction_report` | manual (requested online) | the report of the `batch.report_run` row in conf `runId` |
| `carddemo_export_masters` | manual | the export; the task's XCom `outputs` (and its log) give the `exportId` |
| `carddemo_import_migration` | manual | `open_batch_window`, the import of the completed export in param `exportId`, `close_batch_window` |
| `carddemo_category_balance_listing` | manual | the listing |

transaction-service starts the report through the Airflow REST API. It signs on with `POST $CARDDEMO_AIRFLOW_URL/auth/token` `{"username", "password"}` (the report account; the answer's `access_token` lasts `[api_auth] jwt_expiration_time`, 24 hours by default, so it signs on again after a `401`), then sends `POST /api/v2/dags/carddemo_transaction_report/dagRuns` with `Authorization: Bearer <token>` and `{"dag_run_id": "tranrept-<runId>", "logical_date": null, "conf": {"runId", "startDate", "endDate", "requestedBy"}}`. A `409` means the run already exists. The simple auth manager has no per-DAG permissions, so `carddemo_airflow.auth.CarddemoAuthManager` refuses this account every call that needs a permission, reads included (`403`): other DAGs, reads of DAGs and runs, pause, delete, connections, variables, pools, config. What needs no permission stays open to it, as to anyone signed on: `GET /api/v2/monitor/health` and `/api/v2/version` (also without a token), the API docs (`/openapi.json`, `/docs`, `/redoc`), the login and logout pages, and its own UI metadata (`/ui/auth/me`: its ID and name; `/ui/auth/menus`: no items; `/ui/config`: the UI settings). A backfill of the report DAG is a `POST` on its runs too, so it passes the auth manager; Airflow then refuses it (`422`) because the DAG has no schedule. The admin keeps the simple auth manager's rules. The restriction follows the configured name, and Airflow accepts a token until it expires without looking the name up again: after `CARDDEMO_AIRFLOW_REPORT_USER` is renamed, a token issued to the old name keeps its viewer role, unrestricted, for up to 24 hours. `up` warns when the name changed since the last start; rotate `AIRFLOW_API_JWT_SECRET` and restart Airflow to end those tokens. `up` writes both accounts to `$CARDDEMO_VAR_DIR/airflow/passwords.json` (mode 600); without `CARDDEMO_AIRFLOW_REPORT_PASSWORD` it starts Airflow with the admin only and warns. `carddemo_transaction_report` is the one DAG that is not paused when it is created: it only reads, and a triggered run of a paused DAG would wait. A metadata database that registered it paused before keeps it paused; unpause it once in the UI.

batch-service writes every output under the output root of its database, `$CARDDEMO_VAR_DIR/out/<database component>/<instance ID>/` ([batch execution contract, section 4.10](../docs/modernization/architecture/batch-execution-contract.md)), one folder per kind and run ID, such as `reports/tranrept/<runId>/TRANREPT.txt`. db-migrate creates the root, and a restored database needs a new one ([runbook](../docs/runbooks/modernized-database-restore.md)). transaction-service reads the reports there and only reads; the services run on the host, so this is a path setting, not a mount. `up` gives every process `CARDDEMO_AIRFLOW_URL` (default `http://127.0.0.1:28080`, unless the environment or `.env` sets it) and the resolved `CARDDEMO_VAR_DIR`. The old settings `CARDDEMO_REPORTS_DIR`, `CARDDEMO_TRANREPT_DIR`, `CARDDEMO_INBOUND_DIR` and `CARDDEMO_OUTPUT_DIR`, and the environment forms of the removed properties (`CARDDEMO_BATCH_FILES_OUTPUTDIR`, `CARDDEMO_BATCH_FILES_INBOUNDDIR`, `CARDDEMO_TRANSACTION_REPORT_ROOT`), are refused by name: `setup` and `up` fail on them, no process that `up` starts gets them, and the services and db-migrate stop with `OUTPUT_ROOT_LEGACY_SETTING`.

There is one global batch window. The daily cycle holds it by its `cycleId`, the other DAGs that change data by `<dag_id>/<run_id>`, and a job that needs the window runs only inside its own holder's window. While another holder has the window open, `open_batch_window` waits (deferred, `Retry-After`) until its `execution_timeout` (2 hours) and then fails. The daily cycle's `close_batch_window` sends `complete: true` and so completes the cycle; the other DAGs' close sends no `complete`, since their window has no cycle. A close that meets a launch of the same holder (`503 LAUNCH_IN_PROGRESS`), and a launch that meets its holder's close (`503 WINDOW_CLOSE_IN_PROGRESS`), wait the same way (`Retry-After`, else 30 s). A completed cycle's window is never opened again (`409 CYCLE_COMPLETED`), and closing a window that was never opened (`404 WINDOW_NOT_FOUND`) fails; neither is retried.

Every task retries twice. A retry is there for a lost execution (its lease expired for TTL plus grace; the retry's launch recovers and restarts it) and for transient errors (batch-service unreachable, a `5xx`). A job that ends `FAILED` is not retried: it stops the run and leaves the batch window open, so the online services keep refusing writes until an operator has recovered; clearing the task then restarts the job. Clearing a task whose job completed does not run it again. To reopen online writes after a failure, `make batch-window-close CYCLE=<id>` closes a cycle's window with `complete: false`, so the cycle stays `OPEN` and the next DAG run resumes it; `make batch-window-close RUN_KEY=<dag_id>/<run_id>` closes another DAG's window. Give exactly one of the two. It needs a running batch-service (`CARDDEMO_BATCH_PORT`, default 28090) and prints batch-service's answer: the closed window, or the code and message, with the running executions on `409 EXECUTIONS_RUNNING` and the wait on `503 LAUNCH_IN_PROGRESS`; every answer but `200` exits non-zero. It never completes a cycle. `make cycle-rerun CYCLE=<id>` is the deliberate rerun of a cycle (Other targets).

To run the cycle for the sample day, unpause `carddemo_daily_cycle` in the UI and trigger it with the parameters `businessDate` 2022-07-06 and `interest` as wanted. Unpausing a DAG without catch-up at once creates a scheduled run for the latest interval as well; with `max_active_runs=1` the two run one after the other.

On a laptop, keep the machine awake during a run (`caffeinate -i`, or on mains power): while macOS sleeps, every Airflow process and batch-service stop, so a task looks "running" for as long as the sleep lasts and carries on after the wake.

## Verification targets

A target whose work has not landed fails with the phase that delivers it. A target whose script or module is missing in the checkout (for example `web/` before the front end is merged) fails with `no modernization/<path> in this checkout`. "Heavy" means it runs Maven verify, Vitest or Playwright, or needs the running stack or Testcontainers; it takes the heavy lock (below), so two never run at the same time on one machine. Run these commands through the make targets, or through `scripts/heavy.py <name> <command>`, rather than directly.

| Target | Checks | Heavy | Arrives with |
|:--|:--|:--|:--|
| `verify-spec` | Natural-language spec format and paragraph coverage (`check_spec.py --require-all`, which also fails when an in-scope program or job has no spec), then every spec-only reimplementation in `docs/modernization/spec/checks/` against the GnuCOBOL build, on sample and stress data. Builds the batch programs first (needs GnuCOBOL 3.2, see the root `AGENTS.md`). | No | Done (Phase 1, NL spec) |
| `verify-ledger` | Ledger evidence and links (`verify/trace/verify_ledger.py`, Python 3.11 or newer) | No | Done |
| `test-backend` | `./mvnw -B verify`: unit, integration, contract and ArchUnit tests, against the per-agent test database or Testcontainers | Yes | Done |
| `test-billpay` | `scripts/test_billpay.py`: Maven verify of ledger-core and billpay-service, the Bill Pay Vitest file, Playwright `e2e/bill.spec.ts` on the mocked API (Vite on 25174), then the same spec against a slice on its own database (`scripts/e2e_stack.py`, see `test-e2e`), then the trace gate for BILL on the reports this run wrote only: it records the run's start and the report files already on disk in `build/test-billpay/manifest.json` before the first step, and `trace_matrix.py --unit BILL --manifest` writes the gate to `build/test-billpay/trace/` (`make trace` is unchanged). The Vitest report (`BillPayPage.test.tsx`, `format.test.ts`) goes to `build/test-results/billpay/`; Playwright's stay in `web/playwright-report/e2e-<target>-<mode>.json`. The demo database is not touched, and what ran before is started again at the end. | Yes | Done; needs `load` |
| `test-batch-live` | `scripts/batch_e2e.py`: a real batch-service server (profile `server`) on its own database `CARDDEMO_BATCH_E2E_DB_NAME` (default `carddemo_batch_e2e`; it must be `carddemo_batch_e2e` or `carddemo_batch_e2e_<suffix>`, never the demo, test or browser e2e database) with its own logins, on a free loopback port (never 28090, so the server of `up` keeps running). It runs `db-reset` on that database, builds batch-service and drives the internal API: anonymous health, 401 without the token, cycle registration (201, 200, 409 `CYCLE_PARAMETERS_DIFFER`, 400), the cycle's window (open, 503 `WINDOW_BUSY` with `Retry-After`, 400 without `complete`, close with `complete: false`, 404 `WINDOW_NOT_FOUND`), `dataLoadJob` in a run-key window (409 `BATCH_WINDOW_REQUIRED`, 202, the 409 attach, 409 `EXECUTIONS_RUNNING` on the close, 409 `JOB_INSTANCE_COMPLETE`), a failed instance (400 on a changed `businessDate`, 202 restart), a cycle completed by its close (409 `CYCLE_COMPLETED`), and a probe of every contract job (400 registered, 404 `JOB_NOT_FOUND` a gap). It prints the jobs that are not built and the parts it does not drive (the full cycle, cycle-rerun, the cycle's files, the lease takeover). `SIGSTOP=1` (Linux only, as in CI) also pauses the server's JVM for less than lease TTL + grace during the load and checks that nothing is recovered. Its lock and pid file are in `$CARDDEMO_VAR_DIR/run/batch-e2e/`. `db-reset` and the server get a `CARDDEMO_VAR_DIR` of the run's own (`RUN_VAR_DIR`, see the shared-paths section), so the shared `out/` gets no new entry. It always stops its own server, removes its folder (`KEEP_VAR=1` keeps it), and keeps the database for a look. | Yes | Done (the jobs batch-service registers) |
| `verify-batch` | `verify/differential/verify_batch.py`: for every required job (POSTTRAN, INTCALC, TRANREPT, CREASTMT, CBEXPORT, CBIMPORT), the spec check's GnuCOBOL run on the sample and the stress data against the Spring Batch job on the same input, exported by `verify/differential`'s exec jar (export v2: `--load-execution-id` of the case's own load, no `--fidelity`) and compared through the check's own `compare.py`. Every switch in `BatchSwitches` is set explicitly in each run: all legacy (byte for byte; only the wall-clock timestamps that the checks mask are not compared, and of the sysout only the START and END lines, the counts and the return code), each switch fixed on its own, and all fixed. A fixed item may change only what its rule derives from the GnuCOBOL outputs and the input; for DEF-ACUP-002 (architect ruling v4) that is a second GnuCOBOL INTCALC run in which only the records an account layout manifest declares `ACUP_UPDATE` take their group from positions 103-112, while every other record is kept as read and the exported positions 103-122 must equal the input's. Each generated or prepared account file gets its own manifest, with the hash taken after the file is written and the INTCALC stress overrides read from `gen_stress.py`. The manifest goes to the load as `carddemo.data-load.account-layout-manifest`; the POSTTRAN sample relies on the built-in entry for the ASCII sample. Before any cycle starts, the load report (`--load-report` of the execution the load named, schemaVersion 1, a completed `dataLoadJob`) must name the bytes the case prepared, their line count, the planned layout source and manifest, and the manifest's `migrated` count (0 in legacy mode). The export's `counts.json` (schemaVersion 2, every key in its order) must name the cycle, attempt and load the driver ran, repeat the load report's values, and agree with its files and the input; fixed DEF-INTC-010 IDs must start at the interest sequence's start that the driver read as the run's own batch login, never a constant. Each case has a private `CARDDEMO_VAR_DIR` (mode 700) with the inbound daily file and the output root that db-reset made; no legacy output or inbound setting reaches a command. A required job whose Spring Batch side is not built fails with what is missing, so the target fails until every job exists. `ONLY=POSTTRAN,INTCALC` runs a subset and ends `verify-batch: PARTIAL (...)` with exit status 3 even when everything matched, never `OK`. The exported files hold full card numbers; they stay in the run's temporary directory (mode 700) and are not uploaded. | Yes | Harness done; fails until batch-service's driver and the TRANREPT, CREASTMT, CBEXPORT and CBIMPORT jobs exist (`verify_batch.py --plan` lists what each job lacks; CI skips the `batch` job until then) |
| `verify-batch-spec` | `python3 -m modernization.verify.adapter.spec_cli` from the repository root: the spec validators' live cases (`verify/spec_jobs/runner.py`: PRTCATBL, TRANBKP, PURGE, DISCGRP and the data oracle's DATALOAD cases), each in legacy and in fixed mode (every switch in `BatchSwitches` set explicitly), on the same private database and lock as `verify-batch` (`CARDDEMO_VERIFY_BATCH_DB_NAME`). The transport (`verify/adapter/transport.py`) runs each case as `db-reset`, the checked one-shot sample load, snapshot isolation and a fixture login made for the run (random password, never written down, dropped at the end; one that exists already is refused, never reused, its grants must be exactly `db_datareader` and `db_datawriter`, a random SID chosen before its `CREATE LOGIN`, and an ownership record `$CARDDEMO_VAR_DIR/run/verify-batch/<database>.fixture-login.json` with its name, SID and guarded drop batch, written before the `CREATE` and removed after the drop; the drop removes only a login with that name and that SID, so a login of the same name that is not the run's is left alone and fails the clean-up), then batch-service on a free loopback port; reads and writes go through sqlcmd in the mssql container as the database's own service logins with the SQL on stdin, the snapshot digests through fixed `sa` templates that return digests only, after the snapshot-digest conformance queries passed. No SQL result, input value or exception text is printed. It fails, never skips, while the load report is not merged or when a jar, `.env`, the switch list or the lock is missing; a fixture login that existed before the run or could not be dropped, an ownership record left by an earlier run, and a held lock that was lost before its release, also fail the run. A run whose drop failed prints the record's path and leaves it. Every later run then refuses (`FIXTURE_RECORD_LEFT`) when its first case provisions the fixture login: after that case's `db-reset` of the private database and its sample load, before any login is created, so nothing outside the private database has changed. The record is written to disk and flushed before the `CREATE LOGIN`, and every rewrite replaces it whole, so it always holds the login's name and SID. What can be left behind, all of it failing closed: a run killed while it writes the record (SIGKILL, power loss) can leave a hidden `.<record name>.<hex>.tmp` file beside it, holding the same name, SID and drop batch (no password); no run reads or reuses it, and the operator deletes it after the record is cleared, or at once when there is no record. An error from the `CREATE LOGIN` call other than the transport's own leaves the record with no outcome, and the next run refuses with `FIXTURE_RECORD_LEFT`. Removing the record, after a drop or on `FIXTURE_LOGIN_EXISTS`, is not followed by a sync of its folder, so a crash right after it can bring the record back; the next run then refuses with `FIXTURE_RECORD_LEFT`, and the record's drop batch prints `ABSENT` (after a drop) or `FOREIGN` (while the other login of that name exists), handled as above. To clear it the operator runs the record's `drop` batch as `sa` (sqlcmd in the mssql container, the batch on stdin; it prints exactly one word: `ABSENT`, `DROPPED`, `OWNED` or `FOREIGN`) and deletes the record after `ABSENT` or `DROPPED`. `OWNED` means the run's login is still there: run the batch again, and find out why before deleting anything if it stays. `FOREIGN` means a login of that name with another SID: it is not the run's, no run drops it, and the run prints `kept: foreign login`. The operator finds out whose login it is and either keeps it or has its owner remove it, then deletes the record; while a login of that name exists every run fails with `FIXTURE_LOGIN_EXISTS` at the same point, after the first case's `db-reset`. SQL text reaches sqlcmd only as tabs, line feeds and printable ASCII; a Latin-1 value goes in as `sqltext.latin1_literal`, its UTF-16 in hex. `ONLY=PURGE,DISCGRP` ends `verify-batch-spec: PARTIAL` with exit status 3 even when everything passed, never `OK`. | Yes | Transport done and tested offline only (the `batch-harness` CI job runs its unit tests); not run by CI. Fails until the load report and export v2 are merged, then needs its first live run |
| `verify-harness` | `verify_batch.py --self-test`: builds the batch programs into its own temporary directory (never the checkout's `build/`, so it can run beside `make check`), runs POSTTRAN and INTCALC on the sample data with GnuCOBOL, then seeds differences into copies of the outputs (a byte, a sign, a missing or swapped record, a byte next to a mask, wrong interest IDs or another sequence start, exporter counts that disagree with the files or name another attempt, another load or another schema version, an ADDR-ZIP or GROUP-ID byte in fixed DEF-ACUP-002, a load report with a wrong `migrated` count) and fails unless the comparison reports each one and accepts the allowed ones (masked timestamps, a record dump in sysout, the fixed-mode IDs, the exporter layout of identical outputs). A seeded exporter count is reported only when the error names that field. It also runs INTCALC's stress data and checks the fixed DEF-ACUP-002 expectation against the ruling's figures: 128 interest records (legacy 124), 44 migrated, and only accounts 4, 8, 9 and 10 changed; on the sample, 50 migrated and nothing changed. No database. | No | Done |
| `verify-data` | Builds `verify/data` and compares each loaded core and reference table with its file in `app/data/ASCII` (row count, keys, control totals, byte-exact re-export) and the lookup tables with the validators' lists, then the relations and the `DUSRSECJ` users against `iam.app_user`. One line per check; it reads as the admin login and prints field names, file lines and public reference codes only, never values or account, customer or card keys. Run it after `db-reset load up`. | No (one JVM) | Done |
| `identity-bcrypt-check` | `scripts/identity_bcrypt_check.py` through `scripts/with_env.py`: runs identity-service's `checks/bcrypt-outside-seed.sql` read-only against `CARDDEMO_DB_NAME` in the running `mssql` container and fails when an active user outside the named demo seed users, without a forced password change, still has a `{bcrypt}` hash (sign-on replaces one only after its first successful check). It prints user IDs only, never hashes, and does not start the containers. `CARDDEMO_DB_NAME=<database>` in the environment checks another database of the same SQL Server instead of the one in `.env`, for example the identity-service test database `<CARDDEMO_TEST_MSSQL_DB>_identity_service`. Run it before bcrypt verification is removed. | No | Done; needs identity-service's check files |
| `verify-migrations` | `scripts/verify_migrations.py`: Flyway applies migrations out of order (each owner numbers within its own range), so it migrates the scratch database `<CARDDEMO_DB_NAME>_upgrade` with the base commit's db-migrate (`BASE`, default the merge base with `origin/modernization`, or `HEAD^` on that commit itself; built in a temporary worktree under `build/migrate-check/`), then with this checkout's, and `<CARDDEMO_DB_NAME>_fresh` from empty. Both must pass `--check-logins`, and both must end in the same schema: columns (with their masks), defaults, keys, indexes, checks, foreign keys, views and routines, sequences, principals, permissions, role members, the database's collation and row versioning, row counts and the applied migrations (version and checksum, not their order). The scratch databases use the logins of `CARDDEMO_DB_NAME` and are dropped at the end (`KEEP=1` keeps them). Each db-migrate run gets a `CARDDEMO_VAR_DIR` of its own, removed when it ends (`KEEP_VAR=1` keeps it), so the scratch databases' output roots never land in the shared folder. A `BASE` that is not a commit in the clone fails; a base without db-migrate runs the fresh half only, unless `REQUIRE_BASE=1` (as in CI) makes it fail. | No (two db-migrate builds) | Done |
| `verify-cycle` | The full batch cycle through Airflow | Yes | Phase 3, Airflow |
| `test-airflow` | pytest in the Airflow venv, without a running Airflow (`airflow/tests/`): DagBag import, the expected DAGs, task order, trigger rules, parameters, pool, retries and window placement, the DAG import rule, the operators and the trigger against a stub batch-service, then whole DAG runs with `dag.test()` (SQLite metadata, real XCom) for cycles with and without interest, statements, purge position, a failed job, the data load, a window held by another holder, the export's `exportId` into the import, and the report run; also the CI artifact layout that the ledger job's `make trace` reads (`tests/test_ci_artifacts.py`). JUnit in `build/test-results/airflow/`. | No | Done |
| `web-build` | Exports the message catalogue (`MessageCatalogTest` writes `platform/web-support/target/messages.json`, passed as `CARDDEMO_MESSAGES_JSON` so the catalogue check runs instead of skipping), then `npm ci` and `npm run verify` in `web/`: API client, tsc, ESLint, Vitest, vite build, bundle budget | Yes (Maven test, Vitest) | Done |
| `test-e2e` | `scripts/e2e_stack.py`: Playwright (`CARDDEMO_E2E_TARGET=stack`) against identity-service, billpay-service and web from this checkout, started on their own database `CARDDEMO_E2E_DB_NAME` (default `carddemo_e2e`; it must be `carddemo_e2e` or `carddemo_e2e_<suffix>`, and never the demo or test database). For each browser (`E2E_BROWSERS`, default Chromium) it stops the services, runs `db-reset load` on that database and starts the slice on it, so every run begins on the start data. `db-reset load` get a `CARDDEMO_VAR_DIR` of the run's own (`RUN_VAR_DIR`), removed at the end (`KEEP_VAR=1` keeps it), so the e2e database's output roots never land in the shared folder; the slice itself keeps the shared one. `E2E_SPEC=e2e/bill.spec.ts` runs one spec; `E2E_RELOAD=0` keeps the data the last run left; it is only for a diagnostic run of one spec on one browser (checking a spec's start-data precondition), and the full target refuses it. The demo database `CARDDEMO_DB_NAME` is never reset. While it runs it holds the machine-wide lock `~/.cache/carddemo/slice.lock` (`CARDDEMO_SLICE_LOCK` overrides), so `up`, `stop` and `down` fail in every worktree and clone that has this `stack.py`; older copies and processes started by hand do not know the lock, so a run on a shared machine needs an agreed window; afterwards, also after a failure or a signal, it stops what it started and starts again the slice components that ran before, each from its own checkout and on its own database. It fails before changing anything when a slice port is held by a process `stack.py` did not start. | Yes | Done |
| `trace` | Rule-to-test traceability matrix and its gate (`verify/trace/trace_matrix.py`, Python 3.11 or newer) | No | Done |

To implement a target, replace its stub: remove it from `STUBS` in the `Makefile`, delete its `PHASE` line and give it a real recipe. Keep the `## ` help comment.

## Infrastructure

`docker-compose.yml` defines the compose project `carddemo-modern`. Images are pinned by tag and digest. Ports bind to 127.0.0.1 only, and the host ports are set in `.env`. The defaults avoid the standard ports, so the stack can run next to other local SQL Server, RabbitMQ or Postgres containers.

| Service | Image | Host endpoint (default) | Credentials in `.env` | Volume |
|:--|:--|:--|:--|:--|
| `mssql` | SQL Server 2025 CU10 (17.0.5035.5), Developer edition, `linux/amd64` | `localhost:21433`; JDBC `jdbc:sqlserver://localhost:21433;encrypt=true;trustServerCertificate=true` | `sa` / `MSSQL_SA_PASSWORD` | `carddemo-modern_mssql-data` |
| `rabbitmq` | RabbitMQ 4.4.0 with management | AMQP `localhost:25672`, management UI `http://localhost:35672` | `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS` (admin, virtual host `/`); `CARDDEMO_RABBITMQ_AUTHORIZATION_USER` / `CARDDEMO_RABBITMQ_AUTHORIZATION_PASSWORD` (virtual host `CARDDEMO_RABBITMQ_VHOST`) | `carddemo-modern_rabbitmq-data` |
| `airflow-db` | PostgreSQL 17.11, for Airflow metadata only | `localhost:25432`, database `airflow` | `AIRFLOW_DB_USER` / `AIRFLOW_DB_PASSWORD` | `carddemo-modern_airflow-db-data` |

Application data lives in SQL Server. Airflow can't keep its own metadata there, so it gets a separate Postgres.

SQL Server limits its own memory to `MSSQL_MEMORY_LIMIT_MB` (2048 MiB by default, inside the 4 GiB container limit), so the 8 GiB Colima VM keeps room for a Testcontainers SQL Server and RabbitMQ during heavy tests. Changing the value recreates the container on the next `make up`; the data volume is kept.

Every service has a health check, and `make up` waits for all of them (`UP_TIMEOUT`, default 300 seconds). On the tested Mac, with the images already pulled, `make up` takes about 8 seconds, both on fresh volumes and on existing ones. The first pull downloads about 3 GB, 2.4 GB of it for SQL Server.

RabbitMQ is configured in `infra/rabbitmq/20-carddemo.conf` with `default_queue_type = quorum`. An AMQP client that declares a durable queue without `x-queue-type` gets a quorum queue. RabbitMQ 4.4 rejects transient non-exclusive queues, so declare queues durable. `rabbitmqadmin declare queue` is the exception: it sends `--type classic` unless you pass `--type quorum`.

The services use the virtual host `carddemo` (`CARDDEMO_RABBITMQ_VHOST`), one RabbitMQ user per service. After the containers are healthy, `infra-up` and `up` create the virtual host and the user `authorization_svc` (`CARDDEMO_RABBITMQ_AUTHORIZATION_USER`) through the management API with the admin login, never through a proxy. It is create-only: an existing virtual host or user is kept, and an existing user keeps its password even when `.env` holds another one. `setup` generates `CARDDEMO_RABBITMQ_AUTHORIZATION_PASSWORD` when it is missing; until then `up` skips the user with a warning. The user's permissions on the virtual host are set to its own names only, and corrected when they differ (`scripts/rabbitmq_users.py`). Creating the virtual host through the API also gives the admin user full permissions on it, as RabbitMQ does for the user that creates a virtual host; the admin needs them to look at the queues. authorization-service declares its own topology at start (messaging.md), so it needs:

| Permission | Names | Why |
|:--|:--|:--|
| configure | `carddemo.pauth`, `carddemo.pauth.request`, `carddemo.pauth.reply`, `carddemo.pauth.request.dlq`, `carddemo.dlx` | `exchange.declare` and `queue.declare` of its five names |
| write | `amq.default` (the default exchange), `carddemo.pauth`, `carddemo.dlx`, `carddemo.pauth.request`, `carddemo.pauth.reply`, `carddemo.pauth.request.dlq` | replies to `reply_to` or to `carddemo.pauth`; the two queues with a dead-letter exchange need write on `carddemo.dlx`; each `queue.bind` needs write on its queue |
| read | `carddemo.pauth.request`, `carddemo.pauth.reply`, `carddemo.pauth`, `carddemo.dlx` | consuming the requests; a queue declared with a dead-letter exchange needs read on itself; each `queue.bind` needs read on its exchange |

Nothing else: other exchanges and queues, `amq.direct`, consuming or purging the DLQ are refused (`ACCESS_REFUSED`). Configure on its own names also allows deleting them; the service never does.

Useful commands (the passwords stay inside the containers; the SA password is read from the compose secret file):

```sh
docker compose --project-directory modernization exec mssql sh -c \
  'SQLCMDPASSWORD="$(cat /run/secrets/mssql_sa_password)" /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C'
docker compose --project-directory modernization exec -u rabbitmq rabbitmq rabbitmqctl list_queues name type
docker compose --project-directory modernization exec airflow-db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Run RabbitMQ CLI commands as the `rabbitmq` user (`exec -u rabbitmq`). As root they can create a root-owned Erlang cookie, and then the server can't start.

## Secrets

`.env` is git-ignored, by `modernization/.gitignore` and by the root `.gitignore`. `make setup` creates it with mode 600. Only `.env.example` is committed, and its password values are empty. Compose refuses to start a service whose password is not set. The SQL Server SA password is fixed when the volume is first created. If you change it in `.env` later, run `make infra-wipe CONFIRM=1` and then `make up`.

The SA password is not in the `mssql` service's environment, where `docker inspect` and every process in the container (`ps e`) would show it. `make setup` and every `make up` (`stack.py sa-secret`, then `scripts/init_env.py --sa-secret`) copy `MSSQL_SA_PASSWORD` from `.env` to `$CARDDEMO_VAR_DIR/secrets/mssql-sa-password` (`CARDDEMO_MSSQL_SA_PASSWORD_FILE`), and compose mounts it as the secret `/run/secrets/mssql_sa_password`. They write it only when it is asked for or needed: when `CARDDEMO_MSSQL_SA_PASSWORD_FILE` is set in the environment or on the command line, when the running `mssql` container mounts the secret (one field of `docker inspect`, the mount destinations), or when there is no `mssql` container and the compose file gives the secret to the one `up` creates. A container created before the secret existed has its password in `master` and needs no file; then nothing is written and a `note sa-secret` line says why. The file is mode 644 so that SQL Server's user (uid 10001) can read the bind mount on a Linux host; its directory is mode 700. `infra/mssql/entrypoint.sh` passes the password to SQL Server only on the start that creates `master`, on an empty volume; later starts get none. The health check, `smoke`, `billpay-e2e` and `verify-migrations` read the file inside the container (the scripts fall back to `MSSQL_SA_PASSWORD` in a container created before the secret existed). The file is rewritten in place, so a running container sees a changed value at once. Do not run `docker inspect`, `env` or `ps e` against the containers; see [docs/security/secrets.md](../docs/security/secrets.md#local-modernized-stack).

`make setup` also generates:

- `CARDDEMO_SEED_USER_PASSWORD` in `.env`: the password of the 10 seed users (`ADMIN001`, `USER0001`, ...) that identity-service creates in the `demo` profile. It replaces the public `PASSWORD` of the mainframe demo data. It is 8 upper-case letters and digits, so it fits the legacy 8-character field, survives the legacy upper-casing and meets the fixed-mode minimum of 8.
- `var/secrets/identity-jwt.pem`: the RSA 2048 private key (PKCS#8 PEM, mode 600, directory mode 700) that identity-service signs its RS256 access tokens with. The other services get the public key from its JWKS endpoint. The Makefile exports the path as `CARDDEMO_JWT_KEY_FILE`. An existing key is kept; delete it and rerun `make setup` to rotate it, which invalidates every issued token.
- `AIRFLOW_API_JWT_SECRET` in `.env`: the HS512 key of Airflow's API tokens, 64 letters and digits. A kept value is never changed by `make setup` (a value shorter than 64 bytes gets a warning). To rotate it, for example after renaming the report account, stop every Airflow process, run `python3 scripts/init_env.py --rotate AIRFLOW_API_JWT_SECRET "$CARDDEMO_ENV_FILE" --backup-dir <private directory>`, then start Airflow again: everyone signed in to Airflow signs in again, and transaction-service gets a `401` and signs on again. It changes only that line and keeps every other byte, copies the previous file to the backup directory (mode 700, the copy mode 600) first, replaces `.env` only if it did not change meanwhile, and puts the previous file back unless exactly that line differs. All Airflow processes must start with the same key.

None is ever printed. `var/` is git-ignored.

## One stack per machine, shared by every worktree

There is one `carddemo-modern` compose project per machine, and its volumes were created with the secrets of one `.env`. So every checkout of the repository, including linked git worktrees, uses the files of the main checkout (the one that owns `.git`):

| Variable (exported to recipes; `CARDDEMO_MSSQL_SA_PASSWORD_FILE` only when set) | Default |
|:--|:--|
| `CARDDEMO_ENV_FILE` | `<main checkout>/modernization/.env` |
| `CARDDEMO_INFRA_DIR` | `<main checkout>/modernization` (its `docker-compose.yml`) |
| `CARDDEMO_VAR_DIR` | The environment's value, else `.env`'s (`setup` writes the default there when it is empty), else `<main checkout>/modernization/var`: logs, PIDs, locks, keys, inbound files and the output roots. It must be absolute; `setup`, the Makefile's scripts, `stack.py` and every JVM refuse a relative value (`OUTPUT_ROOT_INVALID`). |
| `CARDDEMO_JWT_KEY_FILE` | `$CARDDEMO_VAR_DIR/secrets/identity-jwt.pem` |
| `CARDDEMO_MSSQL_SA_PASSWORD_FILE` | `$CARDDEMO_VAR_DIR/secrets/mssql-sa-password` |

`.env` also holds `CARDDEMO_DB_NAME` (default `carddemo`), the SQL login password of each service (`CARDDEMO_DB_<SERVICE>_PASSWORD` for identity, billpay, account, transaction, reference, authorization, inquiry and batch; db-migrate creates the logins) and `MSSQL_MEMORY_LIMIT_MB` (2048). `make setup` adds keys that are new in `.env.example` to an existing `.env` and generates their passwords; it never changes a value that is already there.

Setting a variable in the environment overrides it. `make setup` in a worktree completes the main checkout's `.env` and key; it never creates a second set. A `modernization/.env` inside a worktree is ignored, and `make setup` warns about it. `make toolchain` shows the paths in use.

Only the demo stack writes into the shared `CARDDEMO_VAR_DIR`. The harnesses (`test-batch-live`, `test-e2e`, `test-billpay`'s stack step, `verify-migrations`) create a folder of their own with `mkdtemp` (mode 700) for the JVMs that create output roots, and remove it when they end; `KEEP_VAR=1` keeps it and prints its path. They give it to `db-reset`, `db-migrate` and `load` as `RUN_VAR_DIR=<absolute path>`, which reaches only that target's JVM: the target's other steps, `stack.py`, compose and Airflow keep the shared folder (its `run/`, `log/` and `secrets/`). `stack.py` refuses every command, with `OUTPUT_ROOT_PRIVATE`, when its `CARDDEMO_VAR_DIR` is such a folder.

## Running a command with the stack's settings

`scripts/with_env.py` runs one command with every value of the shared `.env` in its environment, without printing any of them. A variable that is already set in the environment (even empty) wins over `.env`. Make recipes that need secrets (`db-reset`, the services in `up`, the test targets) run through it, and so can you:

```sh
modernization/scripts/with_env.py ./mvnw -pl services/identity-service verify
```

## Test database

Backend tests (`platform/test-support`) read these variables:

| Variable | Meaning | Default |
|:--|:--|:--|
| `CARDDEMO_TEST_MSSQL_URL` | JDBC URL of a running SQL Server, without `databaseName`. Empty or unset: the tests start a Testcontainers SQL Server (the image of `docker-compose.yml`, `MSSQL_MEMORY_LIMIT_MB=2048`). | empty in `.env.example`, so CI uses Testcontainers |
| `CARDDEMO_TEST_MSSQL_USER` | Login for the tests | `sa` |
| `CARDDEMO_TEST_MSSQL_PASSWORD` | Its password | `MSSQL_SA_PASSWORD` when the user is `sa` (filled in by `with_env.py`) |
| `CARDDEMO_TEST_MSSQL_DB` | Name of the test database; test-support creates it when it is missing | `carddemo_test` |

Policy on a workstation: the tests use the compose SQL Server (`make up`), not a container of their own, and every agent or checkout uses its own database, so parallel test runs never see each other's data and the VM never holds more than one SQL Server for tests. The shared `.env` on the main development machine sets `CARDDEMO_TEST_MSSQL_URL=jdbc:sqlserver://127.0.0.1:21433;encrypt=true;trustServerCertificate=true`. Each agent sets its database in its own environment:

| Owner | `CARDDEMO_TEST_MSSQL_DB` |
|:--|:--|
| backend (platform, services, batch) | `carddemo_test_backend` |
| identity-service | `carddemo_test_identity` |
| data migration | `carddemo_test_data` |

```sh
CARDDEMO_TEST_MSSQL_DB=carddemo_test_backend modernization/scripts/with_env.py ./mvnw verify
```

Never point the tests at `CARDDEMO_DB_NAME` (`carddemo`, the database of `make up` and `load`). CI leaves the URL empty and uses Testcontainers.

Heavy targets (`test-backend`, `test-billpay`, `test-batch-live`, `web-build`, `verify-batch`, `verify-batch-spec`, `verify-cycle`, `test-e2e`) take the machine-wide lock `$CARDDEMO_VAR_DIR/run/heavy.lock` for their whole run. A second heavy target fails at once and names the holder; `HEAVY_WAIT=1` makes it wait instead. The lock is released when the process ends, also when it crashes.

## CI

`.github/workflows/modernization.yml` runs on pushes to `main` and `modernization` and on pull requests that touch `modernization/`, `docs/modernization/spec/` or the workflow, plus nightly. Each job runs `make setup` (throwaway secrets, no repository secrets) and then the same make targets as a developer:

| Job | Targets | Needs |
|:--|:--|:--|
| `spec` | `verify-spec` in the dev container image (GnuCOBOL 3.2) | |
| `backend` | `test-backend` (Testcontainers) | |
| `billpay` | `test-billpay` (skipped until `load` lands, because its stack step needs data) | |
| `web` | `web-build` | `backend` (runs when it was skipped, not when it failed) |
| `airflow` | `test-airflow`, in a venv cached on the hash of `airflow/requirements.txt`, the constraints file and `pyproject.toml` | |
| `batch-harness` | `verify-harness`, the unit tests of `verify/differential/` and those of the validator adapter's database-free parts (`verify/adapter/`) in the dev container image; runs on every run, also when `spec` failed | `spec` |
| `batch` | `verify-batch`, only when `verify_batch.py --plan` is READY (see below) | `spec` |
| `batch-live` | `test-batch-live SIGSTOP=1` (the lease pause runs on the Linux runner) | |
| `data` | `db-reset load up verify-data ONLY=identity-service` (identity-service seeds the users that `verify-data` checks), then `verify-migrations` from the previous head: the pull request's base, or the commit before the push (full history checkout) | |
| `e2e` | `test-e2e` (its own database, reloaded per browser; Chromium, nightly also Firefox and WebKit through `E2E_BROWSERS`) | `web` |
| `cycle` | `db-reset load up verify-cycle` (nightly, on demand, or on pull requests labelled `batch`) | `batch` |
| `ledger` | the unit tests of `verify/trace/` and of `scripts/` (`scripts/tests/`), then `verify-ledger trace`, with the other jobs' test results in `build/ci-results/` | every job above that ran |

The `plan` job reads `make -s stubs`: a job whose targets are still stubs is skipped, and the run summary says why. A stub never shows as a pass. `verify-batch` is no stub, but it fails until every required job has its Spring Batch side, so `plan` also runs `verify_batch.py --plan`: `batch` runs only when every required job has a producer and every job of `tools/carddemo.py` is in the catalogue; otherwise the summary row says `skipped: not ready:` with the jobs, and a notice names them. The plan never says OK; run it locally with `python3 verify/differential/verify_batch.py --plan` (exit status 0 READY, 4 SKIPPED). Jobs set `CARDDEMO_SETUP_SKIP` (any of `java node docker airflow`) so `make setup` doesn't fail on a tool the job doesn't use. Every job uploads its JUnit results (and process logs) as `test-results-<job>`; `backend` and `billpay` also upload `target/spec-items/`, the Java tests' item citations, which `make trace` needs. The jobs that upload Playwright's output (`billpay`, `web`, `e2e`) leave out its trace archives and HARs (`*.zip`, `*.har` under `web/`), which record request headers, the sign-on token included, and its HTML report (`web/playwright-report/html/`), which records each filled value, the seed password included, in a step title. The JSON and JUnit reports that `make trace` reads stay.

## Toolchain pinning

Nothing is installed or switched globally. `scripts/toolchain.sh` finds the tools, and the `Makefile` puts them first on `PATH` for its recipes:

- **JDK 21:** the first match from `CARDDEMO_JAVA_HOME` (used as given), `JAVA_HOME` if it is a 21, `JAVA_HOME_21_X64`/`JAVA_HOME_21_ARM64` (set by `actions/setup-java`), `/usr/libexec/java_home -v 21`, Homebrew `openjdk@21`, `/usr/lib/jvm/*21*`, SDKMAN, then `java` on `PATH`. The Makefile exports `JAVA_HOME`.
- **Node 24:** the first match from `CARDDEMO_NODE_BIN` (used as given), `node` on `PATH` if it is a 24, Homebrew `node@24`, nvm, fnm, Volta, `/usr/local/bin`, then `/usr/bin`.
- **Docker for Testcontainers:** when the active Docker context is Colima and `DOCKER_HOST` is unset, the Makefile exports `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

The environment wins over `.env`, and `.env` wins over detection. In `.env` you can set `CARDDEMO_JAVA_HOME`, `CARDDEMO_NODE_BIN` and `AIRFLOW_VENV`. Run `make toolchain` to see what was picked. The builds that come later should pin the versions as well: the Maven enforcer should allow only `[21,22)`, and `engines` in `package.json` should require Node 24.

## Troubleshooting

- **`<path>/.env is missing; run make setup (in the main checkout) first`:** run `make setup`; from a worktree it creates the main checkout's `.env`.
- **Port already in use:** change the `*_HOST_PORT` values in `.env`.
- **SQL Server never gets healthy on Apple Silicon:** check `make toolchain` and `colima status`. The Colima VM needs Rosetta (`--vz-rosetta`) and at least 4 GiB; 8 GiB is the tested size.
- **`docker engine not reachable`:** run `colima start` on macOS, or start the Docker daemon on Linux.
