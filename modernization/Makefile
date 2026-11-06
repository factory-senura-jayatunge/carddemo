# Operator entry points for the modernized CardDemo stack.
#
#   make setup    check the toolchain, create .env (secrets) from .env.example
#   make up       start the stack; make down stops it (data volumes are kept)
#   make help     every target, with what is implemented so far
#
# JDK 21 and Node 24 are found by scripts/toolchain.sh and put first on PATH
# for every recipe; nothing global is changed. See README.md.

HERE := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))

# One compose project serves every checkout on the machine, so every checkout
# must use the same secrets and the same compose file. Both default to the
# main checkout (the worktree that owns .git), also from linked worktrees;
# CARDDEMO_ENV_FILE and CARDDEMO_INFRA_DIR override them.
GIT_COMMON_DIR := $(shell git -C '$(HERE)' rev-parse --path-format=absolute --git-common-dir 2>/dev/null)
MAIN_CHECKOUT := $(if $(GIT_COMMON_DIR),$(patsubst %/,%,$(dir $(GIT_COMMON_DIR))))
MAIN_MODERNIZATION := $(if $(wildcard $(MAIN_CHECKOUT)/modernization/docker-compose.yml),$(MAIN_CHECKOUT)/modernization,$(HERE))
CARDDEMO_ENV_FILE ?= $(MAIN_MODERNIZATION)/.env
CARDDEMO_INFRA_DIR ?= $(MAIN_MODERNIZATION)
# Runtime files (logs, PIDs, locks, keys, batch files) are machine-wide too.
CARDDEMO_VAR_DIR ?= $(MAIN_MODERNIZATION)/var
CARDDEMO_JWT_KEY_FILE ?= $(CARDDEMO_VAR_DIR)/secrets/identity-jwt.pem
export CARDDEMO_ENV_FILE CARDDEMO_INFRA_DIR CARDDEMO_VAR_DIR CARDDEMO_JWT_KEY_FILE
ENV_FILE := $(CARDDEMO_ENV_FILE)
TOOLCHAIN := $(HERE)/scripts/toolchain.sh
PYTHON ?= python3
UP_TIMEOUT ?= 300

# Toolchain overrides: the environment wins, then .env, then detection. Only
# these keys are read from .env, so secrets never become make variables.
env_value = $(shell sed -n 's/^$(1)=//p' '$(ENV_FILE)' 2>/dev/null | tail -n 1)
CARDDEMO_JAVA_HOME ?= $(call env_value,CARDDEMO_JAVA_HOME)
CARDDEMO_NODE_BIN ?= $(call env_value,CARDDEMO_NODE_BIN)
AIRFLOW_VENV ?= $(or $(call env_value,AIRFLOW_VENV),$(HOME)/.venvs/carddemo-airflow)
export CARDDEMO_JAVA_HOME CARDDEMO_NODE_BIN AIRFLOW_VENV

JDK21_HOME := $(shell '$(TOOLCHAIN)' java-home 2>/dev/null)
NODE24_BIN := $(shell '$(TOOLCHAIN)' node-bin 2>/dev/null)
TOOL_PATH :=
ifneq ($(JDK21_HOME),)
export JAVA_HOME := $(JDK21_HOME)
TOOL_PATH := $(JDK21_HOME)/bin:
endif
ifneq ($(NODE24_BIN),)
TOOL_PATH := $(TOOL_PATH)$(NODE24_BIN):
endif
export PATH := $(TOOL_PATH)$(PATH)

# Testcontainers can't follow Docker CLI contexts; point it at Colima's
# socket when Colima is the active context.
ifeq ($(origin DOCKER_HOST),undefined)
ifeq ($(shell docker context show 2>/dev/null),colima)
COLIMA_SOCKET := $(HOME)/.colima/default/docker.sock
ifneq ($(wildcard $(COLIMA_SOCKET)),)
export DOCKER_HOST := unix://$(COLIMA_SOCKET)
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE ?= /var/run/docker.sock
endif
endif
endif

COMPOSE = docker compose --project-directory '$(CARDDEMO_INFRA_DIR)' -f '$(CARDDEMO_INFRA_DIR)/docker-compose.yml' --env-file '$(ENV_FILE)'

STACK = $(PYTHON) '$(HERE)/scripts/stack.py'
# Runs a command with the shared .env in its environment (never printed).
WITH_ENV = $(PYTHON) '$(HERE)/scripts/with_env.py'
# Heavy targets run their command through $(HEAVY) <target> <command>.
HEAVY = $(PYTHON) '$(HERE)/scripts/heavy.py'
JAVA = $(if $(JAVA_HOME),$(JAVA_HOME)/bin/java,java)
DB_NAME = $(or $(CARDDEMO_DB_NAME),$(call env_value,CARDDEMO_DB_NAME),carddemo)
# verify/trace needs tomllib.
PY311 = $(PYTHON) -c 'import sys; sys.exit(sys.version_info < (3, 11))' || { echo '$@: FAILED ($(PYTHON) is older than 3.11; set PYTHON=python3.11 or newer)' >&2; exit 1; }
# $(call need,<path>,<what builds it>) fails the target when a file is missing.
need = [ -e '$(HERE)/$(1)' ] || { echo '$@: FAILED (no modernization/$(1) in this checkout; $(2))' >&2; exit 1; }

.DEFAULT_GOAL := help

# Not implemented yet. Each target fails with exit status 2 and the phase
# that delivers it; replace the stub with the real recipe (and drop it from
# STUBS) when that work lands. Every target ends with "<target>: OK" or
# "<target>: FAILED (<reason>)".
STUBS := load billpay test-billpay verify-batch verify-data verify-harness \
	verify-cycle test-airflow
.PHONY: help stubs setup toolchain build up down status logs infra-up infra-down \
	infra-wipe require-env verify-spec db-reset db-migrate smoke verify-ledger \
	test-backend web-build test-e2e trace $(STUBS)

help: ## List the targets
	@awk 'BEGIN {FS = ":[^#]*## "} /^[a-zA-Z0-9_-]+:[^=]*## / {printf "  %-15s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

stubs: ## List the targets that are not implemented yet (CI skips their jobs)
	@echo $(STUBS)

setup: ## Check JDK 21, Node 24, Docker/Colima and the Airflow venv; create .env and the JWT key with generated secrets
	@rc=0; \
	'$(TOOLCHAIN)' check || rc=1; \
	$(PYTHON) '$(HERE)/scripts/init_env.py' '$(HERE)/.env.example' '$(ENV_FILE)' || rc=1; \
	'$(HERE)/scripts/init_jwt_key.sh' '$(CARDDEMO_JWT_KEY_FILE)' || rc=1; \
	if [ '$(ENV_FILE)' != '$(HERE)/.env' ] && [ -f '$(HERE)/.env' ] && [ ! '$(HERE)/.env' -ef '$(ENV_FILE)' ]; then \
		printf 'warn  %-16s %s\n' .env '$(HERE)/.env is ignored; this checkout uses $(ENV_FILE)'; fi; \
	if $(COMPOSE) config --quiet; then \
		printf 'ok    %-16s %s\n' compose 'docker-compose.yml is valid with .env'; \
	else rc=1; fi; \
	if [ $$rc -eq 0 ]; then echo 'setup: OK'; else echo 'setup: FAILED (see the lines above)' >&2; fi; \
	exit $$rc

toolchain: ## Show the JDK, Node and Docker endpoint that the recipes use
	@echo "JAVA_HOME=$${JAVA_HOME:-<JDK $$(cat '$(HERE)/.java-version') not found>}"
	@echo "node: $$(command -v node) $$(node -v 2>/dev/null)"
	@echo "java: $$(command -v java) $$(java -version 2>&1 | head -n 1)"
	@echo "DOCKER_HOST=$${DOCKER_HOST:-<docker CLI default>}"
	@echo "AIRFLOW_VENV=$(AIRFLOW_VENV)"
	@echo "CARDDEMO_ENV_FILE=$(ENV_FILE)"
	@echo "CARDDEMO_INFRA_DIR=$(CARDDEMO_INFRA_DIR)"
	@echo "CARDDEMO_VAR_DIR=$(CARDDEMO_VAR_DIR)"
	@echo "CARDDEMO_JWT_KEY_FILE=$(CARDDEMO_JWT_KEY_FILE)"

require-env:
	@[ -f '$(ENV_FILE)' ] || { echo '$(ENV_FILE) is missing; run make setup (in the main checkout) first' >&2; exit 1; }

build: ## Build the service and batch jars of this checkout (Maven, tests skipped)
	@if [ ! -x '$(HERE)/mvnw' ]; then echo 'build: FAILED (not implemented yet: no Maven wrapper; arrives with Phase 3, the backend services)' >&2; exit 2; fi; \
	cd '$(HERE)' && ./mvnw -B -q -T 1C -DskipTests package \
	&& echo 'build: OK' || { echo 'build: FAILED (Maven, see above)' >&2; exit 1; }

up: require-env ## Start the infrastructure, then every converted service and the web app, and wait until healthy
	@$(STACK) up

down: require-env ## Stop the web app, the services and the containers (for every checkout); data volumes are kept
	@$(STACK) down

status: require-env ## Containers and host processes of the stack, with their health
	@$(STACK) status

infra-up: require-env ## Start SQL Server, RabbitMQ and the Airflow Postgres and wait until healthy (RECREATE=1 to allow restarts)
	@$(STACK) infra-up

infra-down: require-env ## Stop and remove the infrastructure containers for every checkout; volumes are kept
	@$(STACK) infra-down

infra-wipe: require-env ## Remove this project's containers AND volumes (CONFIRM=1); other projects are untouched
	@[ "$(CONFIRM)" = 1 ] || { echo 'infra-wipe: FAILED (deletes all data of the shared carddemo-modern project for every checkout; rerun with CONFIRM=1)' >&2; exit 1; }
	@echo 'WARN  infra-wipe removes the shared containers and volumes for every checkout'
	@$(COMPOSE) down --volumes --remove-orphans && echo 'infra-wipe: OK' || { echo 'infra-wipe: FAILED (docker compose down)' >&2; exit 1; }

logs: require-env ## Follow the infrastructure logs (SERVICE=mssql|rabbitmq|airflow-db for one); app logs are in $CARDDEMO_VAR_DIR/log
	$(COMPOSE) logs --follow --tail 100 $(SERVICE)

db-reset: require-env ## Recreate the application database (CARDDEMO_DB_NAME) and run every migration, then check the logins' grants
	@$(call need,mvnw,arrives with the backend)
	@$(STACK) ensure-infra $@
	@echo 'WARN  db-reset drops and recreates database $(DB_NAME) on the shared SQL Server'
	@cd '$(HERE)' && ./mvnw -q -B -pl db/db-migrate -am package -DskipTests || { echo '$@: FAILED (building db-migrate)' >&2; exit 1; }
	@cd '$(HERE)' && $(WITH_ENV) '$(JAVA)' -jar "$$(ls db/db-migrate/target/db-migrate-*-exec.jar | tail -n 1)" --reset --check-logins \
	&& echo '$@: OK' || { echo '$@: FAILED (db-migrate, see above)' >&2; exit 1; }
db-migrate: require-env ## Apply new migrations to CARDDEMO_DB_NAME without dropping it, then check the logins' grants
	@$(call need,mvnw,arrives with the backend)
	@$(STACK) ensure-infra $@
	@cd '$(HERE)' && ./mvnw -q -B -pl db/db-migrate -am package -DskipTests || { echo '$@: FAILED (building db-migrate)' >&2; exit 1; }
	@cd '$(HERE)' && $(WITH_ENV) '$(JAVA)' -jar "$$(ls db/db-migrate/target/db-migrate-*-exec.jar | tail -n 1)" --check-logins \
	&& echo '$@: OK' || { echo '$@: FAILED (db-migrate, see above)' >&2; exit 1; }
load: ## Load the sample data into SQL Server [not implemented yet]
load: PHASE = Phase 3, the data migration (dataLoadJob)
smoke: require-env ## Read-only check of the running slice: health, sign-on, me, features, a Bill Pay preview, JWKS, no row changes
	@$(WITH_ENV) $(PYTHON) '$(HERE)/scripts/smoke.py'
billpay: ## Run the Bill Pay slice end to end [not implemented yet]
billpay: PHASE = Phase 3, the Bill Pay pilot (needs load)
verify-spec: ## Check the NL spec (format, paragraph coverage) and run its GnuCOBOL equivalence checks
	@$(PYTHON) '$(HERE)/../docs/modernization/spec/tools/verify_spec.py' --require-all
verify-ledger: ## Check the ledger against the code and the spec (switches, decisions, deviations)
	@$(PY311)
	@$(call need,verify/trace/verify_ledger.py,arrives with the ledger)
	@$(PYTHON) '$(HERE)/verify/trace/verify_ledger.py'
test-backend: require-env ## ./mvnw verify: unit, integration (Testcontainers or the per-agent test database), contract, ArchUnit (heavy)
	@$(call need,mvnw,arrives with the backend)
	@cd '$(HERE)' && $(WITH_ENV) $(HEAVY) $@ ./mvnw -B verify \
	&& echo '$@: OK' || { echo '$@: FAILED (Maven, see above)' >&2; exit 1; }
test-billpay: ## Bill Pay service, API and UI tests [not implemented yet]
test-billpay: PHASE = Phase 3, the Bill Pay pilot (service, API and UI tests)
verify-batch: ## GnuCOBOL vs Spring Batch differential tests (heavy) [not implemented yet]
verify-batch: PHASE = Phase 3, the batch jobs (differential tests against GnuCOBOL)
verify-data: ## Reconcile record counts and checksums after load (heavy) [not implemented yet]
verify-data: PHASE = Phase 3, the data migration (reconciliation)
verify-harness: ## Self-test of the differential test harness [not implemented yet]
verify-harness: PHASE = Phase 3, the batch jobs (differential harness)
verify-cycle: ## Full batch cycle through Airflow and Spring Batch (heavy) [not implemented yet]
verify-cycle: PHASE = Phase 3, Airflow (end-to-end batch cycle run)
test-airflow: ## Airflow DAG integrity and ordering tests [not implemented yet]
test-airflow: PHASE = Phase 3, Airflow (DAG tests)
web-build: ## npm ci, API client from the snapshots, tsc, ESLint, Vitest, vite build, bundle budget
	@$(call need,web/package.json,arrives with the web app)
	@cd '$(HERE)/web' && npm ci && npm run verify \
	&& echo '$@: OK' || { echo '$@: FAILED (see above)' >&2; exit 1; }
test-e2e: require-env ## Playwright against the running stack (make up first); E2E_BROWSERS="chromium firefox webkit" (heavy)
	@$(call need,web/playwright.config.ts,arrives with the web app)
	@cd '$(HERE)/web' && browsers='$(or $(E2E_BROWSERS),chromium)' \
	&& npx playwright install $$browsers \
	&& $(HEAVY) $@ env CARDDEMO_E2E_TARGET=stack npx playwright test $$(for b in $$browsers; do printf -- '--project=%s ' $$b; done) \
	&& echo '$@: OK' || { echo '$@: FAILED (see above)' >&2; exit 1; }
trace: ## Rule-to-test traceability matrix and its gate (build/trace/matrix.md)
	@$(PY311)
	@$(call need,verify/trace/trace_matrix.py,arrives with the ledger)
	@$(PYTHON) '$(HERE)/verify/trace/trace_matrix.py'

$(STUBS):
	@printf '%s: FAILED (not implemented yet: arrives with %s)\n' '$@' '$(PHASE)' >&2
	@exit 2
