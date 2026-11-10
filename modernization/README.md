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
| 3 | `load` | Builds `batch/batch-service` and runs its `dataLoadJob` once (a one-shot JVM, profile `demo`) on `app/data/ASCII` (`CARDDEMO_LOAD_SOURCE_DIR`): the core and reference tables of `CARDDEMO_DB_NAME`, and the lookup lists of the account edits. A reload after payments exist fails on a foreign key; run `db-reset` first. `verify-data` comes after `up`, because identity-service seeds the sign-on users when it starts. | Done |
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
| `db-migrate` | Like `db-reset`, without dropping the database: applies new migrations and checks the logins. |
| `status` | Containers and host processes with their health; fails when one is unhealthy or a recorded process is gone. |
| `logs` | Follows the container logs; `SERVICE=mssql`, `rabbitmq` or `airflow-db` limits it to one service. Process logs are in `$CARDDEMO_VAR_DIR/log/<name>.log`. |
| `stop` | Stops the web app and the services but leaves the shared containers running, so other checkouts and test runs keep their SQL Server. |
| `infra-up`, `infra-down` | The infrastructure parts of `up` and `down`. |
| `infra-wipe CONFIRM=1` | Removes this project's containers and volumes (all local data, for every checkout). It touches nothing outside the `carddemo-modern` compose project. |
| `stubs` | Lists the targets that are not implemented yet. |

### Host processes

`up` runs each converted component on 127.0.0.1 (ports and heap caps from the architecture's deployables table):

| Component | Port | Started when | Command |
|:--|--:|:--|:--|
| `identity-service` | 28081 | `services/identity-service/pom.xml` exists | `java -Xmx256m -XX:MaxMetaspaceSize=128m -Xss512k -jar target/identity-service-*-exec.jar --server.port=28081 --server.address=127.0.0.1 --spring.profiles.active=demo`; it reads its signing key from `CARDDEMO_JWT_KEY_FILE` |
| `billpay-service` | 28082 | `services/billpay-service/pom.xml` exists | the same pattern, 256 MiB |
| `account-service`, `transaction-service`, `reference-service`, `authorization-service`, `inquiry-service` | 28083 to 28087 | their module exists | the same pattern (384, 384, 256, 384, 256 MiB) |
| `web` (Vite) | 25173 | `web/package.json` exists | `npm run dev` in `web/` |
| `batch-service` | 28090 | not started by `up`; `load` runs `dataLoadJob` one-shot (server mode comes with the cycle jobs) | |
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

Airflow 3 orchestrates the batch jobs; it holds no business rule. The DAGs in `airflow/dags/` import only Airflow and the `carddemo_airflow` package (`airflow/carddemo_airflow/`), whose operators call batch-service's internal API (`/internal/*` on `127.0.0.1:28090`, bearer `CARDDEMO_BATCH_INTERNAL_TOKEN`): register the cycle, open and close the global batch window, launch a job and wait for it in the triggerer. File paths never travel in a request; batch-service resolves them.

| Setting | Value |
|:--|:--|
| `AIRFLOW_HOME` | `$CARDDEMO_VAR_DIR/airflow` (shared by every checkout); task logs in `$CARDDEMO_VAR_DIR/log/airflow/` |
| Metadata | the `airflow-db` Postgres container (`AIRFLOW_DB_*`) |
| Executor | LocalExecutor; DAGs are paused when they are created, and none catches up |
| Sign-on | `http://127.0.0.1:28080`, user `AIRFLOW_ADMIN_USER` with `AIRFLOW_ADMIN_PASSWORD` from `.env` (simple auth manager) |
| Pool | `carddemo_batch`, 1 slot, deferred waits included: one data-changing job at a time |

| DAG | Schedule | Tasks |
|:--|:--|:--|
| `carddemo_daily_cycle` | `@daily` | `register_cycle`, `open_batch_window`, [`purge_pending_authorizations_before_posting`], `archive_master`, `ingest_daily_file`, `post_daily_transactions`, `calculate_interest` (when the cycle charges interest), `generate_statements`, `apply_retention`, `close_batch_window`, [`purge_pending_authorizations`]. Params `businessDate`, `interest` (`always`, `month_end`, `never`), `statements`, `purge_position`. |
| `carddemo_data_load` | manual | `open_batch_window`, `load` (`dataLoadJob`), `close_batch_window`. The reconciliation is not a job: run `verify-data` afterwards. |
| `carddemo_reference_refresh` | Saturdays 06:00 | transaction types (only when the inbound file was delivered), disclosure groups |
| `carddemo_transaction_report` | manual (requested online) | the report for a date range |
| `carddemo_export_masters`, `carddemo_import_migration`, `carddemo_category_balance_listing` | manual | one job each |

A failed task stops the run and leaves the batch window open, so the online services keep refusing writes until an operator has recovered. Retries are off for the tasks that change data. The recovery targets `batch-window-close` and `cycle-rerun CYCLE=<id>` (architecture, make targets) arrive with batch-service's server mode.

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
| `verify-batch` | Spring Batch against GnuCOBOL, field by field | Yes | Phase 3, batch |
| `verify-harness` | Self-test of the differential harness | No | Phase 3, batch |
| `verify-data` | Builds `verify/data` and compares each loaded core and reference table with its file in `app/data/ASCII` (row count, keys, control totals, byte-exact re-export) and the lookup tables with the validators' lists, then the relations and the `DUSRSECJ` users against `iam.app_user`. One line per check; it reads as the admin login and prints keys and field names only, never values. Run it after `db-reset load up`. | No (one JVM) | Done |
| `verify-migrations` | `scripts/verify_migrations.py`: Flyway applies migrations out of order (each owner numbers within its own range), so it migrates the scratch database `<CARDDEMO_DB_NAME>_upgrade` with the base commit's db-migrate (`BASE`, default the merge base with `origin/modernization`, or `HEAD^` on that commit itself; built in a temporary worktree under `build/migrate-check/`), then with this checkout's, and `<CARDDEMO_DB_NAME>_fresh` from empty. Both must pass `--check-logins`, and both must end in the same schema: columns, defaults, keys, indexes, checks, foreign keys, views and routines, sequences, principals, permissions, role members, row counts and the applied migrations (version and checksum, not their order). The scratch databases use the logins of `CARDDEMO_DB_NAME` and are dropped at the end (`KEEP=1` keeps them). A `BASE` that is not a commit in the clone fails; a base without db-migrate runs the fresh half only, unless `REQUIRE_BASE=1` (as in CI) makes it fail. | No (two db-migrate builds) | Done |
| `verify-cycle` | The full batch cycle through Airflow | Yes | Phase 3, Airflow |
| `test-airflow` | pytest in the Airflow venv, without a running Airflow (`airflow/tests/`): DagBag import, the expected DAGs, task order, trigger rules, parameters, pool and retries, the DAG import rule, the operators and the trigger against a stub batch-service, then whole DAG runs with `dag.test()` (SQLite metadata, real XCom) for cycles with and without interest, statements, purge position, a failed job and the data load; also the CI artifact layout that the ledger job's `make trace` reads (`tests/test_ci_artifacts.py`). JUnit in `build/test-results/airflow/`. | No | Done |
| `web-build` | Exports the message catalogue (`MessageCatalogTest` writes `platform/web-support/target/messages.json`, passed as `CARDDEMO_MESSAGES_JSON` so the catalogue check runs instead of skipping), then `npm ci` and `npm run verify` in `web/`: API client, tsc, ESLint, Vitest, vite build, bundle budget | Yes (Maven test, Vitest) | Done |
| `test-e2e` | `scripts/e2e_stack.py`: Playwright (`CARDDEMO_E2E_TARGET=stack`) against identity-service, billpay-service and web from this checkout, started on their own database `CARDDEMO_E2E_DB_NAME` (default `carddemo_e2e`; it must be `carddemo_e2e` or `carddemo_e2e_<suffix>`, and never the demo or test database). For each browser (`E2E_BROWSERS`, default Chromium) it stops the services, runs `db-reset load` on that database and starts the slice on it, so every run begins on the start data. `E2E_SPEC=e2e/bill.spec.ts` runs one spec; `E2E_RELOAD=0` keeps the data the last run left; it is only for a diagnostic run of one spec on one browser (checking a spec's start-data precondition), and the full target refuses it. The demo database `CARDDEMO_DB_NAME` is never reset. While it runs it holds the machine-wide lock `~/.cache/carddemo/slice.lock` (`CARDDEMO_SLICE_LOCK` overrides), so `up`, `stop` and `down` fail in every worktree and clone that has this `stack.py`; older copies and processes started by hand do not know the lock, so a run on a shared machine needs an agreed window; afterwards, also after a failure or a signal, it stops what it started and starts again the slice components that ran before, each from its own checkout and on its own database. It fails before changing anything when a slice port is held by a process `stack.py` did not start. | Yes | Done |
| `trace` | Rule-to-test traceability matrix and its gate (`verify/trace/trace_matrix.py`, Python 3.11 or newer) | No | Done |

To implement a target, replace its stub: remove it from `STUBS` in the `Makefile`, delete its `PHASE` line and give it a real recipe. Keep the `## ` help comment.

## Infrastructure

`docker-compose.yml` defines the compose project `carddemo-modern`. Images are pinned by tag and digest. Ports bind to 127.0.0.1 only, and the host ports are set in `.env`. The defaults avoid the standard ports, so the stack can run next to other local SQL Server, RabbitMQ or Postgres containers.

| Service | Image | Host endpoint (default) | Credentials in `.env` | Volume |
|:--|:--|:--|:--|:--|
| `mssql` | SQL Server 2025 CU10 (17.0.5035.5), Developer edition, `linux/amd64` | `localhost:21433`; JDBC `jdbc:sqlserver://localhost:21433;encrypt=true;trustServerCertificate=true` | `sa` / `MSSQL_SA_PASSWORD` | `carddemo-modern_mssql-data` |
| `rabbitmq` | RabbitMQ 4.4.0 with management | AMQP `localhost:25672`, management UI `http://localhost:35672` | `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS` | `carddemo-modern_rabbitmq-data` |
| `airflow-db` | PostgreSQL 17.11, for Airflow metadata only | `localhost:25432`, database `airflow` | `AIRFLOW_DB_USER` / `AIRFLOW_DB_PASSWORD` | `carddemo-modern_airflow-db-data` |

Application data lives in SQL Server. Airflow can't keep its own metadata there, so it gets a separate Postgres.

SQL Server limits its own memory to `MSSQL_MEMORY_LIMIT_MB` (2048 MiB by default, inside the 4 GiB container limit), so the 8 GiB Colima VM keeps room for a Testcontainers SQL Server and RabbitMQ during heavy tests. Changing the value recreates the container on the next `make up`; the data volume is kept.

Every service has a health check, and `make up` waits for all of them (`UP_TIMEOUT`, default 300 seconds). On the tested Mac, with the images already pulled, `make up` takes about 8 seconds, both on fresh volumes and on existing ones. The first pull downloads about 3 GB, 2.4 GB of it for SQL Server.

RabbitMQ is configured in `infra/rabbitmq/20-carddemo.conf` with `default_queue_type = quorum`. An AMQP client that declares a durable queue without `x-queue-type` gets a quorum queue. RabbitMQ 4.4 rejects transient non-exclusive queues, so declare queues durable. `rabbitmqadmin declare queue` is the exception: it sends `--type classic` unless you pass `--type quorum`.

Useful commands (the password stays in the container's environment):

```sh
docker compose --project-directory modernization exec mssql sh -c \
  'SQLCMDPASSWORD="$MSSQL_SA_PASSWORD" /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C'
docker compose --project-directory modernization exec -u rabbitmq rabbitmq rabbitmqctl list_queues name type
docker compose --project-directory modernization exec airflow-db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Run RabbitMQ CLI commands as the `rabbitmq` user (`exec -u rabbitmq`). As root they can create a root-owned Erlang cookie, and then the server can't start.

## Secrets

`.env` is git-ignored, by `modernization/.gitignore` and by the root `.gitignore`. `make setup` creates it with mode 600. Only `.env.example` is committed, and its password values are empty. Compose refuses to start a service whose password is not set. The SQL Server SA password is fixed when the volume is first created. If you change it in `.env` later, run `make infra-wipe CONFIRM=1` and then `make up`.

`make setup` also generates:

- `CARDDEMO_SEED_USER_PASSWORD` in `.env`: the password of the 10 seed users (`ADMIN001`, `USER0001`, ...) that identity-service creates in the `demo` profile. It replaces the public `PASSWORD` of the mainframe demo data. It is 8 upper-case letters and digits, so it fits the legacy 8-character field, survives the legacy upper-casing and meets the fixed-mode minimum of 8.
- `var/secrets/identity-jwt.pem`: the RSA 2048 private key (PKCS#8 PEM, mode 600, directory mode 700) that identity-service signs its RS256 access tokens with. The other services get the public key from its JWKS endpoint. The Makefile exports the path as `CARDDEMO_JWT_KEY_FILE`. An existing key is kept; delete it and rerun `make setup` to rotate it, which invalidates every issued token.

Neither is ever printed. `var/` is git-ignored.

## One stack per machine, shared by every worktree

There is one `carddemo-modern` compose project per machine, and its volumes were created with the secrets of one `.env`. So every checkout of the repository, including linked git worktrees, uses the files of the main checkout (the one that owns `.git`):

| Variable (exported to recipes) | Default |
|:--|:--|
| `CARDDEMO_ENV_FILE` | `<main checkout>/modernization/.env` |
| `CARDDEMO_INFRA_DIR` | `<main checkout>/modernization` (its `docker-compose.yml`) |
| `CARDDEMO_VAR_DIR` | `<main checkout>/modernization/var` (logs, PIDs, locks, keys, batch files) |
| `CARDDEMO_JWT_KEY_FILE` | `$CARDDEMO_VAR_DIR/secrets/identity-jwt.pem` |

`.env` also holds `CARDDEMO_DB_NAME` (default `carddemo`), the SQL login password of each service (`CARDDEMO_DB_<SERVICE>_PASSWORD` for identity, billpay, account, transaction, reference, authorization, inquiry and batch; db-migrate creates the logins) and `MSSQL_MEMORY_LIMIT_MB` (2048). `make setup` adds keys that are new in `.env.example` to an existing `.env` and generates their passwords; it never changes a value that is already there.

Setting a variable in the environment overrides it. `make setup` in a worktree completes the main checkout's `.env` and key; it never creates a second set. A `modernization/.env` inside a worktree is ignored, and `make setup` warns about it. `make toolchain` shows the paths in use.

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

Heavy targets (`test-backend`, `test-billpay`, `web-build`, `verify-batch`, `verify-cycle`, `test-e2e`) take the machine-wide lock `$CARDDEMO_VAR_DIR/run/heavy.lock` for their whole run. A second heavy target fails at once and names the holder; `HEAVY_WAIT=1` makes it wait instead. The lock is released when the process ends, also when it crashes.

## CI

`.github/workflows/modernization.yml` runs on pushes to `main` and `modernization` and on pull requests that touch `modernization/`, `docs/modernization/spec/` or the workflow, plus nightly. Each job runs `make setup` (throwaway secrets, no repository secrets) and then the same make targets as a developer:

| Job | Targets | Needs |
|:--|:--|:--|
| `spec` | `verify-spec` in the dev container image (GnuCOBOL 3.2) | |
| `backend` | `test-backend` (Testcontainers) | |
| `billpay` | `test-billpay` (skipped until `load` lands, because its stack step needs data) | |
| `web` | `web-build` | `backend` (runs when it was skipped, not when it failed) |
| `airflow` | `test-airflow`, in a venv cached on the hash of `airflow/requirements.txt`, the constraints file and `pyproject.toml` | |
| `batch` | `verify-harness verify-batch` | `spec` |
| `data` | `db-reset load up verify-data ONLY=identity-service` (identity-service seeds the users that `verify-data` checks), then `verify-migrations` from the previous head: the pull request's base, or the commit before the push (full history checkout) | |
| `e2e` | `test-e2e` (its own database, reloaded per browser; Chromium, nightly also Firefox and WebKit through `E2E_BROWSERS`) | `web` |
| `cycle` | `db-reset load up verify-cycle` (nightly, on demand, or on pull requests labelled `batch`) | `batch` |
| `ledger` | the unit tests of `verify/trace/` and of `scripts/` (`scripts/tests/`), then `verify-ledger trace`, with the other jobs' test results in `build/ci-results/` | every job above that ran |

The `plan` job reads `make -s stubs`: a job whose targets are still stubs is skipped, and the run summary says why. A stub never shows as a pass. Jobs set `CARDDEMO_SETUP_SKIP` (any of `java node docker airflow`) so `make setup` doesn't fail on a tool the job doesn't use. Every job uploads its JUnit results (and process logs) as `test-results-<job>`; `backend` and `billpay` also upload `target/spec-items/`, the Java tests' item citations, which `make trace` needs. The jobs that upload Playwright's output (`billpay`, `web`, `e2e`) leave out its trace archives and HARs (`*.zip`, `*.har` under `web/`): they record request headers, the sign-on token included. The JSON and JUnit reports that `make trace` reads stay; the HTML report opens without its trace links.

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
