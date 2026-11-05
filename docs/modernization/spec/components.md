# CardDemo component map

System-level map of programs, entry points, data stores, CRUD and cross-component invariants. It starts with the slice around the two pilot units, `COBIL00C` (bill payment) and `CBTRN02C` (POSTTRAN), and grows as the area specs are merged (proposals arrive in `areas/`). Sources: `docs/inventory.md`, `app/csd/CARDDEMO.CSD`, `app/jcl/`, `app/scheduler/CardDemo.ca7`, `docs/runbooks/batch-cycle-restart.md`, and the two program specs. Components marked *(not yet decomposed)* are named from the inventory only; their behaviour will be specified when their own specs are written.

## Component map

| Component | Kind | Entry point | Module | Role in this slice | Spec |
| :-- | :-- | :-- | :-- | :-- | :-- |
| `COSGN00C` | online | `CC00` | core | Sign-on; target when bill payment starts without a commarea | *(not yet decomposed)* |
| `COMEN01C` | online | `CM00` | core | Main menu for regular users; option 10 transfers to bill payment | *(not yet decomposed)* |
| `COBIL00C` | online | `CB00`, map `COBIL00`/`COBIL0A` | core | Bill payment | [programs/COBIL00C.md](programs/COBIL00C.md) |
| `COTRN02C` | online | `CT02` | core | Transaction add; also creates `TRAN-ID` = highest + 1 | *(not yet decomposed)* |
| `COTRN00C`, `COTRN01C` | online | `CT00`, `CT01` | core | Transaction list and view; read `TRANSACT` | *(not yet decomposed)* |
| `COACTUPC`, `COACTVWC` | online | `CAUP`, `CAVW` | core | Account update and view; use `ACCTDAT` | *(not yet decomposed)* |
| `CBTRN02C` | batch | job `POSTTRAN` step `STEP15` | core | Daily posting | [programs/CBTRN02C.md](programs/CBTRN02C.md) |
| `CBACT04C` | batch | job `INTCALC` | core | Interest from `TCATBALF`, updates `ACCTDATA` | *(not yet decomposed)* |
| `CBTRN03C` | batch | job `TRANREPT` | core | Transaction report | *(not yet decomposed)* |
| CLOSEFIL, OPENFIL | JCL (CEMT) | batch cycle steps 1 and 9 | core | Close and reopen the CICS files around the batch window | - |
| TRANBKP, COMBTRAN, TRANIDX | JCL (IDCAMS, SORT) | batch cycle steps 5, 6, 8 | core | Back up, rebuild and re-index `TRANSACT` | - |
| `CEE3ABD` | LE service (shim locally) | CALL | runtime | Abend for batch programs | - |

## Data stores

| Store | Entity | Physical | Online name | Batch DD | Key |
| :-- | :-- | :-- | :-- | :-- | :-- |
| Accounts | ENT-ACCOUNT | `ACCTDATA.VSAM.KSDS` | `ACCTDAT` | `ACCTFILE` | `ACCT-ID` |
| Card cross-reference | ENT-CARD-XREF | `CARDXREF.VSAM.KSDS` + AIX on account | `CCXREF`, `CXACAIX` | `XREFFILE` | card; AIX account (non-unique) |
| Transactions | ENT-TRANSACTION | `TRANSACT.VSAM.KSDS` + AIX on `TRAN-PROC-TS` | `TRANSACT` | `TRANFILE` | `TRAN-ID` |
| Category balances | ENT-TCAT-BAL | `TCATBALF.VSAM.KSDS` | - | `TCATBALF` | account + type + category |
| Daily transactions | ENT-DAILY-TRAN | `DALYTRAN.PS` | - | `DALYTRAN` | - |
| Daily rejects | ENT-DAILY-REJECT | `DALYREJS(+1)` GDG | - | `DALYREJS` | - |

## CRUD matrix (pilot units, from their specs)

R = read, RU = read for update, C = create, U = update, B = browse.

| Program | Accounts | Card xref | Transactions | Category balances | Daily transactions | Daily rejects |
| :-- | :-- | :-- | :-- | :-- | :-- | :-- |
| `COBIL00C` | RU, U (`ACCT-CURR-BAL`) | R (by account, AIX) | B (highest ID), C | - | - | - |
| `CBTRN02C` | R, U (`ACCT-CURR-BAL`, cycle credit/debit) | R (by card) | C (file opened for output) | R, C, U | R | C |

The interaction diagrams are in [interactions.md](interactions.md).

## Cross-component invariants found so far

| ID | Invariant | Components | Evidence |
| :-- | :-- | :-- | :-- |
| INV-001 | `TRAN-ID` is unique in `TRANSACT`. Online writers compute "highest + 1"; POSTTRAN takes IDs from the daily file; INTCALC builds IDs from a date PARM and a counter. Nothing coordinates them; a collision makes online writes fail and POSTTRAN abend. | COBIL00C, COTRN02C, CBTRN02C, CBACT04C | `COBIL00C.md` BR-BILL-004, DEF-BILL-003; `CBTRN02C.md` ERR-POST-004; `docs/runbooks/batch-cycle-restart.md:37-39` |
| INV-002 | Online and batch never run on the same files at the same time: CLOSEFIL closes `ACCTDAT`, `CCXREF`, `CXACAIX`, `TRANSACT` before POSTTRAN. | CICS programs, batch cycle | `docs/runbooks/batch-cycle-restart.md:13` |
| INV-003 | `TRANSACT` is empty when POSTTRAN starts (opened for output). | TRANBKP, CBTRN02C | `CBTRN02C.md` §3 |
| INV-004 | Posting keeps three stores in step for each transaction: category balance, account, transaction master. Bill payment updates only the account balance and the transaction master. | CBTRN02C, COBIL00C | `CBTRN02C.md` §9, `COBIL00C.md` DEF-BILL-010 |
| INV-005 | Amount sign: POSTTRAN treats positive amounts as increasing the balance; bill payment stores a positive amount for a payment that decreases it. | CBTRN02C, COBIL00C | `COBIL00C.md` DEF-BILL-010 |

## Candidate seams for the architecture step (observations, not decisions)

- **Payments** (bill payment) is a self-contained online use case with one screen, three data stores and no outbound calls other than navigation; it is the natural first slice for selective conversion. Its coupling points are INV-001, INV-002 and INV-004 (`COBIL00C.md` "Module extraction notes").
- **Posting** (POSTTRAN) is a classic read-validate-write chunk job: daily file in, three stores updated, rejects out. It maps onto a Spring Batch step with an item reader, a validating processor and composite writers; the transaction boundary is open question Q-POST-004.
- The **batch window** (INV-002) is an operational coupling that the target can remove only if account updates from payments and from posting are made safe to run concurrently.
