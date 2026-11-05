# Local build, run and test entry points for the CardDemo batch programs.
# Requires GnuCOBOL 3.2 (cobc) and Python 3.11+. See README "Local build".

# Optional local overrides (copy .env.example to .env).
-include .env
export

PYTHON ?= python3
CARDDEMO := $(PYTHON) tools/carddemo.py
PROGRAM ?= CBTRN02C

.DEFAULT_GOAL := help
.PHONY: help check-toolchain build build-trace run-batch jobs list clean

help: ## Show the available targets
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  %-16s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

check-toolchain: ## Verify cobc 3.2 with INDEXED support and Python 3.11+
	@$(CARDDEMO) check-toolchain

build: ## Compile the batch programs, shims and helpers into build/release
	@$(CARDDEMO) build

build-trace: ## Compile with -ftraceall into build/trace (used by the tests)
	@$(CARDDEMO) build --variant trace

run-batch: build ## Run one batch job on fresh sample data: make run-batch PROGRAM=CBTRN02C
	@$(CARDDEMO) run --no-build $(PROGRAM)

jobs: ## List runnable batch jobs and their DD mappings
	@$(CARDDEMO) jobs

list: ## List which COBOL sources are built or excluded, and why
	@$(CARDDEMO) list

clean: ## Remove build output
	rm -rf build
