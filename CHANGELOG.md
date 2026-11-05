# Changelog

Notable changes, newest first. This file is generated from the git history
by `python3 tools/changelog.py` (`make changelog`): change commit messages,
not this file. History up to 59cc6c2, the upstream AWS CardDemo
import, is not listed.

## [Unreleased]

### Added

- Add make agents-md-check and run it in make check (7f7d5b8)
- Add AGENTS.md with verified commands, map and definition of done (cd967fd)
- Add project skills for batch runs, batch tests and lint fixes (dadef90)
- Add lint baseline and pre-commit hook (0e11729)
- Add runbooks for the batch cycle, CICS files, rejects and auth purge (7c00a03)
- Add COBOL/JCL linter checkpoint with config and lint.sh entry point (b6aa1c3)
- Add Dependabot config with a 7-day cooldown (5c16ac0)
- Add issue forms, PR template, CODEOWNERS and label taxonomy (90a84dc)

### Changed

- Pass output streams to ci_summary and check instead of redirecting stdout (07dbbbf)
- Test check_agents_md.py against fixture trees (c674854)
- Let check_agents_md.py check a repository other than its own (36c1e7b)
- Link AGENTS.md from the README (b686ccc)
- Check the commands and paths in AGENTS.md and the skills (02c922a)
- Test that CBIMPORT routes an unknown record type to ERROUT (441ed0d)
- Enforce the coverage minimum only on full test runs (2157ef8)
- Prune baseline entries fixed by the batch program changes (5063cf7)
- Compile the cobc syntax check with -fsign=EBCDIC like the build (8a28dd5)
- Lint the tools/ shims and drivers; exclude the COBOL templates (d2f25b9)
- Link the generated inventory from the README (22e4b71)
- Generate docs/inventory.md from the sources (282e9cc)
- Read FTPJCL credentials from a NETRC data set (757e1af)
- Analyse COBOL structure with tabs expanded to the configured tab width (e085368)
- Link security docs and runbooks from the README (593d9e3)
- Extend .gitignore for env files, build output and tooling caches (eda6a4e)

### Fixed

- batch: CBTRN01C (TRANVALD) stops after the last daily record (aa42bdb)
- batch: CBACT01C (READACCT) reissue date padding and cycle debit (3816bcd)
- batch: CBCUS01C (READCUST) prints each customer once (836107f)
- batch: CBACT03C (READXREF) prints each xref record once (edfd1a7)
- batch: CBIMPORT writes blank FILLER like the source files (70fe3e8)
- batch: CBTRN02C overlimit check and blank FILLER (37cea4e)
- batch: CBTRN03C totals the last account once and prints it (c5bb790)
- batch: CBACT04C updates the last account and blanks its records (268af56)
- batch: CSUTLDTC reports the date it was given (8122736)
- jcl: add the CARDOUT DD to CBIMPORT.jcl (07f21e6)
- Fix linter false positives, add duplicate-paragraph rule and rule tests (395c462)
- batch: put the export file RECORD KEY inside its record (6076eff)

### Documentation

- Document why the unit-test COBOL drivers are not linted (bcfcc3c)
- generate CHANGELOG.md (0329cd1)
- README section for the local build, run and test (73d0d98)
- Document NETRC setup for FTPJCL in the secrets guide (55111da)
- Document linting: README section, docs/linting.md, naming conventions (d1fd2a8)
- Document secrets handling and PII data handling (9d71b5e)

### Build and CI

- CI and release workflows (9896ae0)
- release packaging and changelog generator (e28a1e1)
- dev container, package list and .env.example (7ff7499)
- make test, coverage, lint, docs-check and check targets (30cccc5)
- runner support for the test suite (4ffb463)
- add Makefile with build and run-batch targets (6b02cd2)
- add local GnuCOBOL build and batch runner (09314ec)

### Tests

- model WS-TEMP-BAL storage in the posting oracle (e6aa111)
- unit and integration suite with paragraph coverage (389b2d9)
