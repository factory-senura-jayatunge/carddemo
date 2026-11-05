# Changelog

Notable changes, newest first. This file is generated from the git history
by `python3 tools/changelog.py` (`make changelog`): change commit messages,
not this file. History up to 59cc6c2, the upstream AWS CardDemo
import, is not listed.

## [Unreleased]

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
- batch: put the export file RECORD KEY inside its record (6076eff)

### Documentation

- README section for the local build, run and test (73d0d98)

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
