# CardDemo modernization: local stack

This directory holds the modernized CardDemo stack: Spring Boot services, Spring Batch jobs, Airflow DAGs and a React front end on SQL Server and RabbitMQ. It is being built in phases. Right now it contains the local infrastructure and the operator entry points. The other targets exist already but fail with the phase that delivers them, so a missing piece never looks like a pass.

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

The Airflow virtual environment lives outside the repository, by default in `~/.venvs/carddemo-airflow` (set `AIRFLOW_VENV` to use another one). The tested setup is Airflow 3.3.2 on Python 3.14 with the official constraints:

```sh
python3.14 -m venv ~/.venvs/carddemo-airflow
~/.venvs/carddemo-airflow/bin/pip install "apache-airflow==3.3.2" \
  --constraint https://raw.githubusercontent.com/apache/airflow/constraints-3.3.2/constraints-3.14.txt
```

The Airflow work in Phase 3 adds the Postgres driver and the DAGs.

## Quick start

```sh
make -C modernization setup   # toolchain check, creates .env and the JWT signing key with generated secrets
make -C modernization up      # starts SQL Server, RabbitMQ and Postgres, waits until all are healthy
make -C modernization status  # containers and health
make -C modernization down    # stops and removes the containers; data volumes are kept
```

## Lifecycle

These are the targets in the order you use them. Targets marked "not yet" exit with status 2 and print `not implemented yet: arrives with <phase>`.

| Order | Target | What it does | State |
|:--|:--|:--|:--|
| 1 | `setup` | Checks JDK 21, Node 24, Docker/Colima (with amd64 emulation on arm64) and the Airflow venv. Creates `.env` from `.env.example`, generates every empty password, and validates the compose file. Running it again keeps existing values and only adds keys that are new in the example. | Done |
| 2 | `db-reset` | Drops and recreates the application schema (Flyway). | Not yet (Phase 3, Bill Pay pilot) |
| 3 | `load` | Loads the sample data into SQL Server. | Not yet (Phase 3, data migration) |
| 4 | `up` | Starts the infrastructure and waits for the health checks; the application services join in Phase 3. | Infrastructure done |
| 5 | `smoke` | Quick health check of the running application. | Not yet (Phase 3) |
| 6 | `billpay` | Runs the Bill Pay slice end to end. | Not yet (Phase 3, Bill Pay pilot) |
| 7 | `down` | Stops and removes the containers; volumes are kept. | Infrastructure done |

Other targets:

| Target | What it does |
|:--|:--|
| `help` | Lists every target. |
| `toolchain` | Shows the `JAVA_HOME`, `node`, `java` and `DOCKER_HOST` that recipes use. |
| `status` | `docker compose ps` for this project. |
| `logs` | Follows the logs; `SERVICE=mssql`, `rabbitmq` or `airflow-db` limits it to one service. |
| `infra-up`, `infra-down` | The infrastructure parts of `up` and `down`. |
| `infra-wipe CONFIRM=1` | Removes this project's containers and volumes (all local data). It touches nothing outside the `carddemo-modern` compose project. |

## Verification targets

Only `verify-spec` is implemented so far; each of the others fails with the phase that delivers it. "Heavy" means it needs the running stack or Testcontainers, takes minutes, and should not run in parallel with other heavy targets on a laptop.

| Target | Checks | Heavy | Arrives with |
|:--|:--|:--|:--|
| `verify-spec` | Natural-language spec format and paragraph coverage (`check_spec.py --require-all`, which also fails when an in-scope program or job has no spec), then every spec-only reimplementation in `docs/modernization/spec/checks/` against the GnuCOBOL build, on sample and stress data. Builds the batch programs first (needs GnuCOBOL 3.2, see the root `AGENTS.md`). | No | Done (Phase 1, NL spec) |
| `verify-ledger` | Ledger evidence and links | No | Phase 4, validation |
| `test-backend` | Spring Boot service tests | Yes (Testcontainers) | Phase 3 |
| `test-billpay` | Bill Pay service, API and UI tests | Yes | Phase 3, Bill Pay pilot |
| `verify-batch` | Spring Batch against GnuCOBOL, field by field | Yes | Phase 3, batch |
| `verify-harness` | Self-test of the differential harness | No | Phase 3, batch |
| `verify-data` | Record counts and checksums after `load` | Yes | Phase 3, data migration |
| `verify-cycle` | The full batch cycle through Airflow | Yes | Phase 3, Airflow |
| `test-airflow` | DAG integrity and task order | No | Phase 3, Airflow |
| `web-build` | React and Vite production build | No | Phase 3, front end |
| `test-e2e` | Playwright end-to-end tests | Yes | Phase 3, front end |
| `trace` | Rule-to-test traceability matrix | No | Phase 4, validation |

To implement a target, replace its stub: remove it from `STUBS` in the `Makefile`, delete its `PHASE` line and give it a real recipe. Keep the `## ` help comment.

## Infrastructure

`docker-compose.yml` defines the compose project `carddemo-modern`. Images are pinned by tag and digest. Ports bind to 127.0.0.1 only, and the host ports are set in `.env`. The defaults avoid the standard ports, so the stack can run next to other local SQL Server, RabbitMQ or Postgres containers.

| Service | Image | Host endpoint (default) | Credentials in `.env` | Volume |
|:--|:--|:--|:--|:--|
| `mssql` | SQL Server 2025 CU10 (17.0.5035.5), Developer edition, `linux/amd64` | `localhost:21433`; JDBC `jdbc:sqlserver://localhost:21433;encrypt=true;trustServerCertificate=true` | `sa` / `MSSQL_SA_PASSWORD` | `carddemo-modern_mssql-data` |
| `rabbitmq` | RabbitMQ 4.4.0 with management | AMQP `localhost:25672`, management UI `http://localhost:35672` | `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS` | `carddemo-modern_rabbitmq-data` |
| `airflow-db` | PostgreSQL 17.11, for Airflow metadata only | `localhost:25432`, database `airflow` | `AIRFLOW_DB_USER` / `AIRFLOW_DB_PASSWORD` | `carddemo-modern_airflow-db-data` |

Application data lives in SQL Server. Airflow can't keep its own metadata there, so it gets a separate Postgres.

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

- `CARDDEMO_SEED_USER_PASSWORD` in `.env`: the password of the 10 seed users (`ADMIN001`, `USER0001`, ...) that identity-service creates. It replaces the public `PASSWORD` of the mainframe demo data.
- `var/secrets/identity-jwt.pem`: the RSA 3072 private key (PKCS#8 PEM, mode 600, directory mode 700) that identity-service signs its RS256 access tokens with. The other services get the public key from its JWKS endpoint. The Makefile exports the path as `CARDDEMO_JWT_KEY_FILE`. An existing key is kept; delete it and rerun `make setup` to rotate it, which invalidates every issued token.

Neither is ever printed. `var/` is git-ignored.

## One stack per machine, shared by every worktree

There is one `carddemo-modern` compose project per machine, and its volumes were created with the secrets of one `.env`. So every checkout of the repository, including linked git worktrees, uses the files of the main checkout (the one that owns `.git`):

| Variable (exported to recipes) | Default |
|:--|:--|
| `CARDDEMO_ENV_FILE` | `<main checkout>/modernization/.env` |
| `CARDDEMO_INFRA_DIR` | `<main checkout>/modernization` (its `docker-compose.yml`) |
| `CARDDEMO_VAR_DIR` | `<main checkout>/modernization/var` (logs, PIDs, locks, keys, batch files) |
| `CARDDEMO_JWT_KEY_FILE` | `$CARDDEMO_VAR_DIR/secrets/identity-jwt.pem` |

Setting a variable in the environment overrides it. `make setup` in a worktree completes the main checkout's `.env` and key; it never creates a second set. A `modernization/.env` inside a worktree is ignored, and `make setup` warns about it. `make toolchain` shows the paths in use.

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
