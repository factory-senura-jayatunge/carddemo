# Walkthrough: CardDemo target architecture

This is the proposal in two pages, for the review meeting. The details are in the other documents of this folder; [decisions.md](decisions.md) is the list to answer. It was written from the natural-language spec only, without reading the COBOL.

## What we build

CardDemo becomes a React web app in front of seven small Spring Boot services, one Spring Batch application and Airflow DAGs, on one SQL Server database and RabbitMQ. Everything runs on a laptop with `make -C modernization up`.

| Legacy | Target |
| :-- | :-- |
| 3270 screens, CICS transactions | React routes, one REST service per business area (identity, Bill Pay, accounts and cards, transactions, reference data, authorizations, inquiries) |
| VSAM files, Db2 tables, IMS database | One SQL Server database, one schema per area, the core tables shared under per-service grants |
| Batch COBOL programs | Spring Batch jobs in `batch-service` |
| JCL, CA-7, Control-M | Airflow DAGs that only order, schedule and retry the jobs |
| IBM MQ | RabbitMQ quorum queues |
| IDCAMS, GDGs, CICS file close and open, FTP, fixed waits | Not rebuilt: Flyway, cycle-tagged rows, a batch-window row, or dropped |

All 100 specs are placed in [mapping.md](mapping.md): 46 programs and 54 jobs. 34 of them are dropped, each with a reason: diagnostic listings that print PII, IMS unloads, demo jobs, and plumbing that SQL Server or Airflow make unnecessary.

## Bill Pay first

Bill Pay is one screen and three tables, and it touches the shared ledger, so it proves the shared pieces early: core tables, transaction ID allocation, sign-on, the batch-window guard, the fidelity switches, traceability and CI. The payment runs in one database transaction, so the legacy "balance zeroed but no payment record" defect cannot happen. `make billpay` brings up just that slice. The other menu options show the legacy "coming soon" message until their area is converted.

Order after that: data migration, batch and the daily cycle, the online account, card, transaction and user screens, then the optional modules.

## Fidelity: the COBOL behaviour by default

- Every observable behaviour in the spec is reproduced: data changes, message texts, the order of field checks, report and statement layouts.
- Each defect the spec marks *defect-decide* keeps its COBOL behaviour by default and gets a switch (`carddemo.fidelity.<item ID>: legacy | fixed`). Choices are recorded in a ledger file, and `make verify-ledger` checks the code against it.
- About two dozen defects cannot be kept, because the mechanism behind them is gone (state shared between MQ messages in one CICS task, files without rollback). They are listed in [decisions.md](decisions.md#architecture-dependent-fidelity-items) for an explicit "accept the fix".
- Proof: batch jobs are compared byte for byte with the GnuCOBOL build on the sample and stress data (`make verify-batch`). Every test names the spec items it proves, and `make trace` fails CI when a converted unit has an untested item.

## Security: the one place we depart by default

Today most screens work without signing on, and any user can open any account. The spec says this must be fixed, not kept (INV-006). Default **C1**: sign-on for everything, the two legacy roles, and staff can still work on any account, so the data model needs nothing new. Recommended for production: **C2**, where a user sees only linked accounts and PII is masked. C2 needs a user-to-account link that no legacy file holds. Passwords are hashed in either case.

## The batch cycle: option F

Seven different cycle orders exist in the repository (README, runbook, two scripts, CA-7, Control-M and the order area G derived). None of the scheduler versions runs repeatedly as written. **We recommend option F, the order the data dependencies give**, which the decision-catalog agent also recommends (catalog D-37):

`open window → archive master → ingest daily file → post → interest (if due) → statements → retention → close window → authorization purge`

Our version of F differs from the JCL form of F in three ways, all because of the database:

1. **No TRANBKP copy and no COMBTRAN.** All transactions stay in one table; the "current master" is a flag. Nothing selects "the newest generation", so the report-loses-the-day hazard disappears.
2. **The purge runs after the cycle and never blocks posting.** It touches only the authorization tables. CA-7 runs it before posting, the runbook after; the catalog agrees with "after" (D-39).
3. **Statements only after posting and interest.** CA-7 runs them in parallel with posting.

Recommended settings, all in agreement with the catalog: interest at month end (D-24), statements once per period (D-38), full history kept with a retention policy (D-40), stop and alert on failure with no blind retry (D-57), reruns of a completed cycle refused (D-23).

**One difference from the catalog:** the catalog recommends no batch window at all (D-18). We recommend keeping writes blocked during the cycle for the first release, with reads still working. Posting's over-limit check depends on running totals, so online writes during posting would make results depend on timing, and the comparison with the COBOL needs stable input. The window can go once balances can only change through posting (catalog D-08, D-27, D-28).

## What we need from you

1. Approve the architecture decisions D-ARCH-01 to 16 as a set, or name the ones to change.
2. Choose the security model: C1 now and C2 later, or C2 from the start (needs the account-link data).
3. Confirm option F with the recommended settings (D-BAT-01 to 04) and the batch-window mode (D-BAT-05).
4. Accept the architecture-dependent fixes.
5. Answer the Q-items that change the architecture, starting with Q-BILL-002 (does a payment update cycle totals?) and Q-TRNA-001 (are online transactions posted?). Both change Bill Pay and posting.

Everything else, about 800 spec items, can be answered later through the decision catalog. Until then the build keeps the COBOL behaviour.

## How you will know it works

Every phase turns a set of `make -C modernization` targets from "not implemented yet" to passing ([make-targets.md](make-targets.md)). Bill Pay is done when `test-billpay` passes all twelve spec scenarios and `trace` shows every Bill Pay rule covered. Batch is done when `verify-batch` matches the COBOL byte for byte and `verify-cycle` runs the whole cycle through Airflow twice without double charging.
