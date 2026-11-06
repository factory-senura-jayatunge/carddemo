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
WITH_ENV = $(PYTHON) '$(HERE)/scripts/with_env.py'
# Heavy targets run their command through $(HEAVY) <target> -- <command>.
HEAVY = $(PYTHON) '$(HERE)/scripts/heavy.py'

.DEFAULT_GOAL := help

# Not implemented yet. Each target fails with exit status 2 and the phase
# that delivers it; replace the stub with the real recipe (and drop it from
# STUBS) when that work lands. Every target ends with "<target>: OK" or
# "<target>: FAILED (<reason>)".
STUBS := db-reset load smoke billpay verify-ledger test-backend \
	test-billpay verify-batch verify-data verify-harness verify-cycle \
	test-airflow web-build test-e2e trace

.PHONY: help stubs setup toolchain build up down status logs infra-up infra-down \
	infra-wipe require-env verify-spec $(STUBS)

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

db-reset: ## Drop and recreate the application database schema [not implemented yet]
db-reset: PHASE = Phase 3, the Bill Pay pilot (Flyway migrations on SQL Server)
load: ## Load the sample data into SQL Server [not implemented yet]
load: PHASE = Phase 3, the data migration (sample files into SQL Server)
smoke: ## Quick health check of the running application stack [not implemented yet]
smoke: PHASE = Phase 3, the Bill Pay pilot (end-to-end smoke check)
billpay: ## Run the Bill Pay slice end to end [not implemented yet]
billpay: PHASE = Phase 3, the Bill Pay pilot
verify-spec: ## Check the NL spec (format, paragraph coverage) and run its GnuCOBOL equivalence checks
	@$(PYTHON) '$(HERE)/../docs/modernization/spec/tools/verify_spec.py' --require-all
verify-ledger: ## Check the ledger evidence and its links [not implemented yet]
verify-ledger: PHASE = Phase 4, validation (ledger evidence checks)
test-backend: ## Spring Boot service tests (heavy: Testcontainers) [not implemented yet]
test-backend: PHASE = Phase 3, the backend services (Spring Boot tests)
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
web-build: ## Build the React front end [not implemented yet]
web-build: PHASE = Phase 3, the front end (React and Vite build)
test-e2e: ## Playwright end-to-end tests (heavy) [not implemented yet]
test-e2e: PHASE = Phase 3, the front end (Playwright tests)
trace: ## Rule-to-test traceability matrix [not implemented yet]
trace: PHASE = Phase 4, validation (traceability matrix)

$(STUBS):
	@printf '%s: FAILED (not implemented yet: arrives with %s)\n' '$@' '$(PHASE)' >&2
	@exit 2
