# Repository layout, build, tests and CI

## Layout

Everything new lives under `modernization/`, next to the infrastructure that is already there. Nothing under `app/`, `tools/` or `tests/` changes; the differential tests call the existing GnuCOBOL build through `tools/carddemo.py`.

```text
modernization/
  pom.xml                 parent POM: Spring Boot 4.1.x, Java 21, enforcer [21,22)
  mvnw, .mvn/             Maven wrapper (pinned Maven version)
  platform/
    fidelity/             FidelitySwitches, @SpecItem test annotation
    ledger-core/          transaction ID allocation, transaction insert, balance updates, batch-window guard
    legacy-format/        zoned and packed codecs, legacy number and timestamp edits, fixed-layout record export
    validation/           field validators and the rule table that generates their TypeScript mirror
    web-support/          resource-server security, problem details, message catalogue
    test-support/         Testcontainers fixtures (SQL Server, RabbitMQ), sample-data loader for tests
  services/
    identity-service/  billpay-service/  account-service/  transaction-service/
    reference-service/ authorization-service/  inquiry-service/
  batch/
    batch-service/        every Spring Batch job, the internal job and batch-window API
  db/
    migrations/           Flyway: V###__<schema>_<change>.sql, R__grants.sql, R__views.sql
    db-migrate/           small Spring Boot app that runs Flyway (make db-reset), so no service migrates
  web/                    Vite + React + TypeScript app; src/api is generated from the OpenAPI documents
    e2e/                  Playwright tests
  airflow/
    dags/                 one file per DAG, orchestration only
    carddemo_airflow/     the shared SpringBatchJobOperator and its deferrable trigger
    tests/                pytest DAG tests
  verify/
    differential/         harness: run GnuCOBOL and Spring Batch on the same input, export, compare
    trace/                traceability matrix generator
    data/                 load reconciliation (make verify-data)
  ledger/
    fidelity.toml         chosen value per fidelity switch, approver, date
    deviations.toml       accepted deviations (C1 sign-on, architecture-dependent fixes)
    scope.toml            which spec units are converted, so trace knows what must be covered
  infra/, scripts/, docker-compose.yml, Makefile, README.md   (existing)
  var/                    git-ignored: inbound files, reports, statements, exports
```

Rules that keep the boundaries, checked by ArchUnit tests in `test-backend`:

- A service depends on `platform/*` only, never on another service. Services talk to each other only through REST or RabbitMQ, and today no online service calls another one.
- Only `ledger-core` writes `core.transaction` and adjusts balances; services and jobs call it.
- Only `batch-service` imports Spring Batch. DAG files import only Airflow and `carddemo_airflow` (`test-airflow`).
- SQL access uses Spring's `JdbcClient` with explicit SQL, not JPA: the lock hints (`UPDLOCK`), keyset paging and fixed column lists are part of the behaviour and stay visible.

## Build

| Part | Tool | Notes |
| :-- | :-- | :-- |
| Java | Maven multi-module, `./mvnw -T 1C verify` | One reactor for platform, services, batch, db-migrate. Spring Boot 4.1.x BOM, Spring Batch, Spring AMQP, Spring Security resource server, springdoc-openapi, Flyway with the SQL Server module, Testcontainers, ArchUnit. Each service builds an executable jar; container images (Buildpacks) only for a deployment. |
| Web | npm (`package-lock.json`), Node 24 via `engines` | `tsc --noEmit`, ESLint, Vitest, `vite build`. The API client is generated from the OpenAPI documents that the Maven build writes to `target/openapi/`, so a contract change breaks the web build instead of the browser. |
| Airflow | the host venv (`AIRFLOW_VENV`, Airflow 3.3) plus `apache-airflow-providers-postgres` | DAGs are plain files; `carddemo_airflow` is installed into the venv in editable mode by `make setup`. |
| Database | Flyway through `db-migrate` | Migrations are forward-only; `make db-reset` drops and recreates the database locally. |

Local runs: `make up` starts the JVMs from their jars with the heap caps of [overview.md](overview.md#deployables), Airflow (API server, scheduler, DAG processor, triggerer) from the venv, and the Vite dev server. Logs go to `var/log/<process>.log`; PIDs to `var/run/`.

## Test strategy

| Layer | What it proves | Tooling | Make target |
| :-- | :-- | :-- | :-- |
| Unit | One rule: a validator, an edit order, a number edit, an interest formula | JUnit 5, Vitest | `test-backend`, `web-build` |
| Service integration | An endpoint against a real SQL Server: data changes, messages, cursor field, transaction boundaries, locks | Spring Boot tests + Testcontainers (SQL Server 2025 image of the compose file) | `test-backend`, `test-billpay` |
| Contract | OpenAPI documents match the generated client; message bodies match the golden files from the spec examples | springdoc, golden files, Testcontainers RabbitMQ | `test-backend` |
| Differential | A Spring Batch job gives the same records and report lines as the GnuCOBOL program on the same input | `verify/differential` + `docs/modernization/spec/checks/` | `verify-batch`, `verify-harness` |
| Data | The load equals the source files | `verify/data` | `verify-data` |
| Cycle | The DAGs run the jobs in the agreed order through a real Airflow | Airflow API + the differential exporter | `verify-cycle` |
| DAG structure | DAGs import, tasks and parameters are as specified, no business logic | pytest, `DagBag` | `test-airflow` |
| End to end | Each screen's equivalence scenarios through the browser, keyboard only, accessibility | Playwright, axe-core | `test-e2e` |

**Equivalence scenarios first.** Every program spec ends with an equivalence contract and, for Bill Pay, scenarios S1 to S12. Each scenario becomes one service integration test and, for screens, one Playwright test, named after the scenario and tagged with its item IDs. A converted unit is not done until all of its scenarios pass in legacy mode.

**Both fidelity modes.** A test for a switched item is a parameterised test over `legacy` and `fixed`. Legacy mode must match the spec; fixed mode must match the fix the item describes and nothing else changes.

**Differential harness.** For each batch job: build the GnuCOBOL programs (`make build` at the repository root), run the job on the sample data and on the stress data of `docs/modernization/spec/checks/`, load the same input into a Testcontainers SQL Server, run the Spring Batch job with `businessDate=2022-07-06`, export the tables with `platform/legacy-format` into the legacy fixed layouts and compare with the existing `compare.py`, masking only wall-clock timestamps. `verify-harness` proves the harness can fail: it feeds it seeded differences (one byte, one missing record, two records swapped, one sign) and expects each to be reported.

## Traceability

- **Tagging.** Java tests carry `@SpecItem({"BR-BILL-001", "SE-BILL-002"})` (class or method). Vitest and Playwright titles carry `@BR-BILL-001` tags. pytest tests carry `@pytest.mark.spec("INV-052")`. The differential harness tags each job comparison with the job's equivalence-contract items.
- **Matrix.** `make trace` reads the item IDs from the spec (the `#### <ID> · <title>` headings, the fidelity tag of each item, and the Q tables), the test reports (JUnit XML with the tags as properties, Playwright JSON, pytest JUnit XML), `ledger/fidelity.toml`, `ledger/deviations.toml` and `ledger/scope.toml`. It writes `build/trace/matrix.md` and `matrix.json`: for each item, its unit, its fidelity class, the tests that cite it and their result, and its ledger entry.
- **Gate.** For the units in `scope.toml`: every must-preserve item has at least one passing test; every defect-decide item has passing tests in the mode the ledger chose (both modes while undecided) or a deviation entry; every architecture-dependent item has a deviation entry; no test cites an ID the spec does not define. Incidental items need no test.

## CI (GitHub Actions)

A new workflow, `.github/workflows/modernization.yml`, runs on pull requests and pushes that touch `modernization/**` or `docs/modernization/spec/**`. The existing `ci.yml` (lint, docs-check, build-and-test) stays as it is.

| Job | Runs | Needs |
| :-- | :-- | :-- |
| `spec` | `make -C modernization verify-spec` in the repository's dev-container image (GnuCOBOL 3.2) | - |
| `backend` | `make -C modernization test-backend` on `ubuntu-latest` with Docker (Testcontainers) | - |
| `billpay` | `make -C modernization test-billpay` | - |
| `web` | `make -C modernization web-build` | `backend` (OpenAPI documents) |
| `airflow` | `make -C modernization test-airflow` | - |
| `batch` | `make -C modernization verify-harness verify-batch` in the dev-container image plus Docker | `spec` |
| `data` | `make -C modernization db-reset load verify-data` | - |
| `e2e` | `make -C modernization up test-e2e` (Chromium on pull requests; Firefox and WebKit nightly) | `web` |
| `cycle` | `make -C modernization up verify-cycle` (nightly and on the `batch` label) | `batch` |
| `trace` | `make -C modernization verify-ledger trace`; uploads `build/trace/` as an artifact and fails on a gap | every job above that ran |

Caches: Maven repository, npm cache, the Airflow venv keyed by its constraints file, Docker images by digest. Secrets: none; CI runs `make setup`, which generates throwaway passwords.

## Memory budget

The machine has 24 GiB. Numbers are what the processes may use at most, not what they use idle.

| Part | Budget | How it is held there |
| :-- | --: | :-- |
| Colima VM (SQL Server, RabbitMQ, Postgres, plus one Testcontainers SQL Server and RabbitMQ while a heavy target runs) | 8 GiB | VM size from `modernization/README.md`; SQL Server limited with `MSSQL_MEMORY_LIMIT_MB=2048` in compose and in the Testcontainers fixture (proposed addition to `docker-compose.yml`) |
| Eight JVMs | 4.5 GiB | heap caps in [overview.md](overview.md#deployables) (3.1 GiB together), `-XX:MaxMetaspaceSize=128m`, `-Xss512k`, virtual threads |
| Airflow (four processes) | 1.5 GiB | LocalExecutor, `parallelism=4`, deferrable waits |
| Vite dev server and one Playwright browser | 1 GiB | one worker locally |
| **Peak** | **15 GiB** | typical with the stack up and no test running: about 10 GiB |

Heavy targets (`test-backend`, `test-billpay`, `verify-batch`, `verify-data`, `verify-cycle`, `test-e2e`) run one at a time; each starts by checking that no other heavy target holds `var/run/heavy.lock`.
