## Description

<!-- What does this change do and why? Link the issue it addresses, for example "Fixes #64". -->

## Module

- [ ] Base application (CICS online, VSAM, batch)
- [ ] Optional: Transaction Type Management with Db2
- [ ] Optional: Pending Authorizations with IMS, Db2 and MQ
- [ ] Optional: Account Extractions with MQ and VSAM
- [ ] Data export/import, scheduler, sample data
- [ ] Scripts, build, CI or documentation only

## Affected members

<!-- List every member you changed. Delete rows that do not apply. -->

| Kind | Members |
| :--- | :------ |
| Programs (`cbl`) | |
| Copybooks (`cpy`, `cpy-bms`) | |
| BMS maps (`bms`) | |
| JCL / PROC / control cards | |
| CSD definitions | |
| Db2 DDL/DCL, IMS DBD/PSB | |
| Sample data | |

Copybook or record layout changes: list every program that copies the changed copybook and confirm record lengths still match the dataset definitions in the README and JCL.

## Testing done

- [ ] Lint passes on the changed sources
- [ ] Build passes (local GnuCOBOL build, or compile on a mainframe or rehosting runtime)
- [ ] Automated tests pass
- [ ] Manually ran the affected transactions or jobs (describe below)

<!-- Environment used, jobs submitted, return codes, screens exercised. -->

## Data handling

- [ ] No real customer data, card numbers, SSNs, passwords or other secrets added (see `docs/security/data-handling.md` and `docs/security/secrets.md`)
- [ ] No new DISPLAY, log or report output of sensitive fields

## Context

<!-- Anything reviewers should know: trade-offs, follow-ups, related issues, line endings or code page notes. -->
