# CardDemo target architecture (proposal)

Status: **approved** (decision D10), with the user's answers to the decision catalog (`docs/modernization/decisions/README.md`) applied. The build starts with the Bill Pay slice, whose build contract is [billpay-slice.md](billpay-slice.md).

This folder describes how CardDemo moves from CICS, VSAM, Db2, IMS, MQ and JCL to React, Spring Boot, SQL Server, RabbitMQ, Spring Batch and Airflow. It starts with Bill Pay and converts the rest one bounded context at a time.

## Inputs used

The proposal is derived **only** from these inputs. No COBOL, copybook, BMS, JCL, CSD or scheduler source was read.

- The natural-language specification in `docs/modernization/spec/` on branch `modernization` at commit `72c47cc`: program specs, job, procedure, CSD and schedule specs (`jobs/`, including `jobs/README.md` with the batch-cycle options), `entities.md`, `components.md`, `interactions.md`, `areas/`, `evidence/` and `checks/README.md`. The area G job specs in it were merged from `nl/G` at `d0e7f1a`, after their review.
- `modernization/README.md`, `modernization/Makefile` and `modernization/docker-compose.yml`.

[decisions.md](decisions.md) also names the matching entries (D-01 to D-75) of the workstream's user decision catalog, which is itself derived from the spec, so the two lists can be answered together.

## Documents

| Document | What it answers |
| :-- | :-- |
| [WALKTHROUGH.md](WALKTHROUGH.md) | The proposal in two pages, for the review meeting. |
| [overview.md](overview.md) | Principles, bounded contexts, context diagram, services, the Bill Pay first slice, phasing. |
| [mapping.md](mapping.md) | Every program and job spec (100) and where it goes: service, endpoint, route, batch step, DAG task, queue, or "dropped" with the reason. |
| [data-model.md](data-model.md) | SQL Server schemas and tables, keys, how VSAM keys, alternate indexes, Db2 and IMS map, numeric and text types, FILLER and layout quirks, sample-data migration and reconciliation. |
| [api-security.md](api-security.md) | REST API sketch per service, error model, users and roles, authentication, the no-sign-on defect (INV-006) under each choice. |
| [web.md](web.md) | React routes per screen, navigation, menu mapping, how 3270 keys and messages carry over. |
| [batch.md](batch.md) | Spring Batch jobs per JCL job and step, Airflow DAGs, the batch-cycle option, restart, idempotency, files versus tables, report reproduction. |
| [messaging.md](messaging.md) | RabbitMQ exchanges and queues, message contracts for authorization and account and date inquiry, reply and correlation, errors. |
| [repo-build-ci.md](repo-build-ci.md) | Layout under `modernization/`, build, the test strategy (differential, rule-ID, Testcontainers, Playwright), traceability, CI. |
| [make-targets.md](make-targets.md) | Each `make -C modernization` target in one line: what it does and when it passes. |
| [decisions.md](decisions.md) | Every decision this proposal needs, with the recommended default. |
| [switches.md](switches.md) | The authoritative list of fidelity switches under the targeted policy, with the reason for each and the items that are fixed-only. |
| [batch-execution-contract.md](batch-execution-contract.md) | The approved contract between Airflow and `batch-service`: cycles, job launch and poll, run identity, execution leases, fencing and recovery, operator rules, the batch window. |
| [d33-matching-brief.md](d33-matching-brief.md) | Decided rule for releasing pending authorizations "when matched" (D-33): conditions, schema, grants, batch steps and tests. |
| [billpay-slice.md](billpay-slice.md) | The build contract for the first slice (sign-on, main menu, Bill Pay): modules, Flyway ranges, tables, endpoints, tokens, switches, scenarios in both modes, and when each make target is done. |

## Terms

- **Legacy mode** and **fixed mode**: for each spec item marked defect-decide, legacy mode is the COBOL behaviour and fixed mode the corrected one. Under the targeted policy only the items in [switches.md](switches.md) have both, selected by one configuration switch per item; every other decided item is built in its decided mode only. See [overview.md](overview.md#fidelity).
- **Cycle**: one run of the daily batch (posting, optional interest, statements), identified by a `cycle_id` and a business date.
- **Batch window**: the time during which the batch owns the core tables; it replaces closing and opening the CICS files.
