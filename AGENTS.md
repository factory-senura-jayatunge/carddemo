# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository. CardDemo is an AWS sample mainframe credit card application: COBOL batch and CICS programs, VSAM files, JCL, BMS maps and CSD definitions, plus optional DB2, IMS and MQ modules. On a workstation only the batch programs that need no CICS, DB2, IMS or MQ can be compiled, run and tested, with GnuCOBOL. Everything else is source that is checked by the linter and cross-referenced in [docs/inventory.md](docs/inventory.md), but not executed.

## Setup

Requirements, all checked by `make check-toolchain`:

- GnuCOBOL 3.2 (`cobc`) built with Berkeley DB, for INDEXED (KSDS) files
- Python 3.11 or newer, standard library only (`tools/carddemo.py` and the tests read TOML with `tomllib`)
- GNU make

If you don't want to install them, use the dev container in `.devcontainer/`. It builds a pinned Debian 13 (trixie) image with the packages in `tools/apt-packages.txt` (`gnucobol3=3.2-3`, python3, make, git), and CI runs the COBOL steps in the same image. To change defaults such as the business date, the data directory or the test timeout, copy `.env.example` to `.env` (git-ignored); the Makefile reads it.

Enable the pre-commit lint hook once per clone:

```sh
git config core.hooksPath .githooks
```

## Commands

| Command | What it does |
|:--|:--|
| `make help` | Lists every make target. |
| `make check-toolchain` | Verifies cobc 3.2 with INDEXED support and Python 3.11+. |
| `make build` | Compiles the batch programs, shims, PARM driver and KSDS utilities into `build/release`. |
| `make build-trace` | The same with `-ftrace` into `build/trace` (the tests use it). |
| `make list` | Which COBOL sources are built and which are excluded, with the reason. |
| `make jobs` | The runnable jobs and their DD mappings. |
| `make run-batch PROGRAM=POSTTRAN` | Runs one job (or program) on fresh sample data in `build/run/<JOB>`. |
| `make test` | Unit and integration tests; a full run fails below the paragraph-coverage minimum. |
| `make test-unit`, `make test-integration` | One suite, without the coverage minimum. |
| `make test-integration TEST_ARGS="-k posttran"` | Only the tests whose id contains the text. Filter with `test-unit` or `test-integration`, which never apply the coverage minimum. |
| `make coverage` | All tests, then points to `build/test-results/coverage.json`. |
| `make lint` | `scripts/lint.sh` (lint rules plus the cobc syntax check), then the linter's own tests. |
| `make docs-check` | Fails when `docs/inventory.md` is stale. |
| `make agents-md-check` | Checks the commands and paths in AGENTS.md and the skills. |
| `make check` | lint, docs-check, agents-md-check, build and test, with a summary. This is the single gate. |
| `make package VERSION=v1.0.0` | Archives `build/release` into `dist/` with `SHA256SUMS`. |
| `make changelog` | Regenerates `CHANGELOG.md` from the git history. |
| `make clean` | Removes `build/` and `dist/`. |

The tools behind the targets can be called directly:

```sh
python3 tools/carddemo.py run POSTTRAN       # same as make run-batch, without the build step
python3 tests/run_tests.py --suite unit --coverage-min 0
scripts/lint.sh --no-baseline                # every finding, including baselined ones
python3 scripts/cobol_lint.py rules          # every lint rule with its severity and thresholds
python3 scripts/cobol_lint.py report         # findings per rule, complexity, tech-debt markers
python3 -m unittest discover -s scripts/tests
python3 scripts/gen_inventory.py             # regenerate docs/inventory.md
python3 scripts/gen_inventory.py --check     # what make docs-check runs
python3 scripts/check_agents_md.py           # check the commands and paths in this file
```

`scripts/lint.sh` and `scripts/cobol_lint.py check` exit 0 when there are no new findings, 1 when there are new findings, and 2 for a configuration error or a missing cobc.

## Definition of done

A change is done when `make check` ends with `make check: OK`. It runs every step even after one fails and prints a summary. The full test run inside it fails below 85% paragraph coverage (`min_percent` in `tests/test-config.toml`). In addition:

- Behaviour changes to a batch program come with a test in `tests/` (see the `add-batch-test` skill).
- No new lint findings. Fix the cause; never add findings to `cobol-lint-baseline.json` to get a change through. The baseline may only shrink (see the `fix-lint-finding` skill).
- If you add, remove or rename a program, copybook, mapset, CSD definition or JCL job, run `python3 scripts/gen_inventory.py` and commit `docs/inventory.md` with the change.
- If you change a command, target, script or path that this file mentions, update this file; `python3 scripts/check_agents_md.py` must pass.
- Every COBOL source under `app/` is listed in `tools/batch-programs.toml`, either as a `[[program]]` or in an `[[exclude]]` with the reason. `make list` and `tests/unit/test_manifest.py` enforce it.

## Repository map

| Path | Contents |
|:--|:--|
| `app/cbl/` | Base application COBOL programs: `CO*` online CICS, `CB*` batch, `CS*` callable utilities. |
| `app/cpy/`, `app/cpy-bms/` | Copybooks: record layouts and shared code; BMS symbolic maps. |
| `app/bms/`, `app/csd/` | BMS mapsets; CICS resource definitions (`CARDDEMO.CSD`). |
| `app/jcl/`, `app/proc/`, `app/ctl/` | Batch and utility JCL, cataloged procedures, control cards. |
| `app/asm/`, `app/maclib/` | Assembler routines (COBDATFT, MVSWAIT) and their macros. |
| `app/data/ASCII/`, `app/data/EBCDIC/` | Sample data: ASCII for local runs, EBCDIC for upload to the mainframe. |
| `app/scheduler/` | CA7 and Control-M schedule definitions. |
| `app/app-authorization-ims-db2-mq/` | Optional authorization module (CICS, IMS, DB2, MQ). |
| `app/app-transaction-type-db2/` | Optional transaction type maintenance module (CICS, DB2). |
| `app/app-vsam-mq/` | Optional account and date inquiry over MQ (CICS, MQ). |
| `samples/jcl/`, `samples/proc/` | Sample compile JCL and procedures for the mainframe. |
| `samples/m2/` | AWS Mainframe Modernization runtime archives. |
| `tools/` | Local build and runner (`carddemo.py`), the build manifest (`batch-programs.toml`), LE/assembler shims (`shims/`), the PARM driver (`drivers/`), and the check, CI, changelog and packaging helpers. |
| `tests/` | Test runner (`run_tests.py`), helpers (`support.py`), the independent model of expected results (`oracle.py`), paragraph coverage (`paracov.py`), `unit/` (including COBOL drivers in `unit/cobol/`) and `integration/`. |
| `scripts/` | `cobol_lint.py` and `lint.sh` with their tests in `scripts/tests/`, `gen_inventory.py`, `check_agents_md.py`. The older `remote_*.sh`, `run_*.sh` and `upld_module.sh` drive a real mainframe through an FTP tunnel and do not run locally; `local_compile.sh`, `git-addSrcVersionInfo.sh`, `pad.awk` and `markers/` are legacy helpers that the local build does not use. |
| `docs/` | [Inventory](docs/inventory.md), [linting](docs/linting.md), [runbooks](docs/runbooks/README.md), [security](docs/security/secrets.md). |
| `.github/workflows/` | `ci.yml` (jobs lint, docs-check, build-and-test), `release.yml` (`v*` tags), `label-sync.yml`, `agents-md.yml`. |
| `.factory/skills/` | Step-by-step procedures for common tasks (listed below). |
| `modernization/` | The modernized stack, in progress: local infrastructure (Docker Compose) and its own make targets. See [modernization/README.md](modernization/README.md). |

Generated files, never edited by hand: `docs/inventory.md` (`python3 scripts/gen_inventory.py`), `CHANGELOG.md` (`make changelog`), `cobol-lint-baseline.json` (`python3 scripts/cobol_lint.py baseline`), and everything under `build/` and `dist/`.

## What runs locally, and what does not

- **Built and run:** `make build` compiles the batch programs that `make list` shows as built, and every one of them runs except CBSTM03A. It compiles them with the `cobc_flags` in `tools/batch-programs.toml`: `-std=ibm -fsign=EBCDIC -ftab-width=4`. `-fsign=EBCDIC` is required because the ASCII sample data keeps mainframe zoned-decimal overpunch signs.
- **Built, not run:** CBSTM03A (CREASTMT) reads z/OS control blocks from address 0, which do not exist outside z/OS. Its subroutine CBSTM03B is tested directly.
- **Not built:** the sources that `make list` shows as excluded, with the reason: the online CICS programs in `app/cbl` and every program in the three optional modules. GnuCOBOL has no CICS translator and no DB2 or IMS precompiler, and there is no CICS region, DB2, IMS or MQ locally. The lint syntax check skips the programs that contain `EXEC CICS`, `EXEC SQL` or `EXEC DLI` and reports them as skipped, never as passed.
- **Replaced by shims:** the LE services CEE3ABD and CEEDAYS and the assembler routines COBDATFT and MVSWAIT are COBOL stand-ins in `tools/shims/`. CBACT04C takes a JCL `PARM`, so it runs through the PARMRUN driver.
- **Emulated JCL:** JCL is not executed. `tools/carddemo.py` defines each runnable job with the DD names from its JCL (`make jobs`) and loads the sample data from `app/data/ASCII` into a scratch directory. TRANVALD (CBTRN01C) has no JCL in the repository; its DDs come from the program's `SELECT` clauses. TRANREPT and CBEXPORT run POSTTRAN first, and CBIMPORT runs CBEXPORT first.
- **Business date:** the programs see 2022-07-06 (`CARDDEMO_BUSINESS_DATE`), the sample data's posting day. The tests' expectations assume it.

## Conventions

- **Fixed-format COBOL.** Columns 1-6 sequence number, column 7 indicator, columns 8-72 code, 73-80 ignored by the compiler. New and changed lines use LF line endings, no tabs, and nothing past column 72. `python3 scripts/cobol_lint.py fix FILE...` makes these mechanical fixes; run it only on files you are changing anyway, never on whole directories.
- **Naming and module boundaries** are in [CONTRIBUTING.md](CONTRIBUTING.md#naming-conventions) and enforced by the linter: 1-8 character member names that match `PROGRAM-ID`, `CO` for CICS programs and `CB` for batch, copybook prefixes, and no dependency from the base application on an optional module or between modules. Every rule is described in [docs/linting.md](docs/linting.md).
- **Copybooks are shared.** A record layout in `app/cpy/` is used by many programs; check its "Used by programs" row in [docs/inventory.md](docs/inventory.md) before you change it.
- **Tests** follow the naming in `tests/test-config.toml` (`test_*.py`, classes ending in `Test`, methods starting with `test_`). Integration tests compare program output with values that `tests/oracle.py` computes independently from the copybook layouts, not with output captured from the program. Tests run in parallel in random order; the seed is printed and `CARDDEMO_TEST_SEED` repeats a run.
- **Commits.** Imperative subject line, a body that says why, one logical change per commit. Commits made by an AI agent carry a `Co-authored-by:` trailer, as in the existing history. Pull requests use `.github/pull_request_template.md`; `.github/CODEOWNERS` lists the reviewers.

## Security

- The sign-on users in the README (`ADMIN001`/`USER0001`, password `PASSWORD`) are public demo seed data. Never add real credentials, hosts or keys to source, JCL, data or docs. `app/jcl/FTPJCL.JCL` reads its FTP login from a NETRC data set. See [docs/security/secrets.md](docs/security/secrets.md).
- The bundled data is synthetic, but the code handles card numbers, SSNs and other PII. Do not `DISPLAY` sensitive fields: the `sensitive-display` lint rule is an error. See [docs/security/data-handling.md](docs/security/data-handling.md).
- `.env` is git-ignored; only `.env.example` is committed, with defaults and no secrets.
- Report vulnerabilities through the [AWS vulnerability reporting page](https://aws.amazon.com/security/vulnerability-reporting/), not in a public issue.

## Operations

The [runbooks](docs/runbooks/README.md) cover restarting the batch cycle, closing and opening the CICS files, the daily rejects file and the authorization purge on the mainframe.

## Skills

Project skills in `.factory/skills/`:

- `run-batch-job`: build, run one batch job on fresh sample data and read its counts, return code, outputs and rejects.
- `add-batch-test`: add an integration test for a batch program and keep paragraph coverage above the minimum.
- `fix-lint-finding`: find, fix and verify a lint finding, and keep the baseline policy.
