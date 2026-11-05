# Local build, run and test entry points for the CardDemo batch programs.
# Requires GnuCOBOL 3.2 (cobc) and Python 3.11+. See README "Local build".

# Optional local overrides (copy .env.example to .env).
-include .env
export

PYTHON ?= python3
CARDDEMO := $(PYTHON) tools/carddemo.py
PROGRAM ?= CBTRN02C
TEST_ARGS ?=
LINT_ARGS ?=

# Provided by the lint and docs work; until those are merged the targets
# below report the tool as not present instead of silently passing.
LINT_TOOL := scripts/lint.sh
LINT_TESTS := scripts/tests
INVENTORY_TOOL := scripts/gen_inventory.py

.DEFAULT_GOAL := help
.PHONY: help check-toolchain build build-trace run-batch jobs list test coverage test-unit \
	test-integration lint docs-check check clean

help: ## Show the available targets
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  %-16s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

check-toolchain: ## Verify cobc 3.2 with INDEXED support and Python 3.11+
	@$(CARDDEMO) check-toolchain

build: ## Compile the batch programs, shims and helpers into build/release
	@$(CARDDEMO) build

build-trace: ## Compile with -ftrace into build/trace (used by the tests)
	@$(CARDDEMO) build --variant trace

run-batch: build ## Run one batch job on fresh sample data: make run-batch PROGRAM=CBTRN02C
	@$(CARDDEMO) run --no-build $(PROGRAM)

jobs: ## List runnable batch jobs and their DD mappings
	@$(CARDDEMO) jobs

list: ## List which COBOL sources are built or excluded, and why
	@$(CARDDEMO) list

test: ## Run all tests with the paragraph-coverage minimum: make test TEST_ARGS="-k posttran"
	@$(PYTHON) tests/run_tests.py $(TEST_ARGS)

coverage: ## Run all tests and write the paragraph coverage report (build/test-results/coverage.json)
	@$(PYTHON) tests/run_tests.py $(TEST_ARGS)
	@echo "coverage report: build/test-results/coverage.json"

test-unit: ## Run only the unit tests (no coverage minimum)
	@$(PYTHON) tests/run_tests.py --suite unit --coverage-min 0 $(TEST_ARGS)

test-integration: ## Run only the integration tests (no coverage minimum)
	@$(PYTHON) tests/run_tests.py --suite integration --coverage-min 0 $(TEST_ARGS)

lint: ## COBOL/JCL lint + cobc syntax check, then the linter's own tests
	@if [ ! -f $(LINT_TOOL) ] || [ ! -d $(LINT_TESTS) ]; then \
		echo "NOT PRESENT: lint tool not present ($(LINT_TOOL), $(LINT_TESTS)); it arrives with the lint branch merge"; \
		exit 1; \
	fi
	@sh $(LINT_TOOL) $(LINT_ARGS)
	@$(PYTHON) -m unittest discover -s $(LINT_TESTS)

docs-check: ## Fail if docs/inventory.md is stale (python3 scripts/gen_inventory.py --check)
	@if [ ! -f $(INVENTORY_TOOL) ]; then \
		echo "NOT PRESENT: docs inventory tool not present ($(INVENTORY_TOOL)); it arrives with the docs branch merge"; \
		exit 1; \
	fi
	@$(PYTHON) $(INVENTORY_TOOL) --check

check: ## lint + docs-check + build + test; prints a summary and fails if any step failed or did not run
	@$(PYTHON) tools/check.py lint docs-check build test

clean: ## Remove build output
	rm -rf build
