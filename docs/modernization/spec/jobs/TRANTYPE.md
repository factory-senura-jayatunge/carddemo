---
id: TRANTYPE
title: Transaction type reload (delete, define and load the TRANTYPE KSDS)
kind: job
module: core
unit_code: JTTYP
source_rev: 8a8a80a
sources:
  - {path: app/jcl/TRANTYPE.jcl, sha256: 7a10aa43bd67, role: job}
  - {path: app/cpy/CVTRA03Y.cpy, sha256: fb15dbc4a692, role: copybook}
  - {path: app/catlg/LISTCAT.txt, sha256: d207911955e1, role: catalog, lines: "284, 487, 3727-3828, 3885"}
  - {path: README.md, sha256: 952958322286, role: runbook, lines: "144, 164, 221-223, 364"}
  - {path: scripts/run_full_batch.sh, sha256: 5380076d14e7, role: script, lines: "37-38"}
  - {path: scripts/remote_refresh.sh, sha256: 65be63e4c382, role: script, lines: "40-41"}
  - {path: app/scheduler/CardDemo.ca7, sha256: 61d204864a80, role: schedule, lines: "159-189"}
entry_points: [{type: manual, job: TRANTYPE}, {type: script, file: scripts/run_full_batch.sh, lines: "37-38"}, {type: script, file: scripts/remote_refresh.sh, lines: "40-41"}, {type: scheduler, file: app/scheduler/CardDemo.ca7, lines: "159-162"}]
programs: [IDCAMS]
data_stores:
  - {dd: TRANTYPE, dataset: AWS.M2.CARDDEMO.TRANTYPE.PS, entity: ENT-TRAN-TYPE, copybook: CVTRA03Y, access: [read-sequential]}
  - {dd: TTYPVSAM, dataset: AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS, entity: ENT-TRAN-TYPE, copybook: CVTRA03Y, access: [delete, define, load]}
invoked_by: [operator, scripts/run_full_batch.sh, scripts/remote_refresh.sh, CA-7 trigger from CLOSEFIL]
spec_status: reviewed
---

# TRANTYPE: Transaction type reload

## 1. Summary

TRANTYPE throws away the transaction type reference file (ENT-TRAN-TYPE: the description of each two-character transaction type) and rebuilds it from the flat file. `STEP05` deletes the VSAM cluster `AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS`, `STEP10` defines it again empty (key 2 bytes at offset 0, fixed 60-byte records), and `STEP15` copies every record of `AWS.M2.CARDDEMO.TRANTYPE.PS` into it. It is run by hand during installation and in the demo data refresh, and the CA-7 schedule triggers it. With the optional Db2 module, TRANEXTR first writes the flat file again from Db2. The job contains no business logic; what it must preserve is the file definition and the fact that the result is an exact copy of the flat file.

## 2. Rules at a glance

The job has no business rules. Its items:

| ID | Rule |
| :-- | :-- |
| SE-JTTYP-001 | Delete the transaction type cluster; the return code is then set to 0 unless the delete ended with a severe error (ERR-JTTYP-003). |
| SE-JTTYP-002 | Define the cluster: key 2 at offset 0, records 60 fixed, `SHAREOPTIONS(1 4)`, `ERASE`, no `REUSE`. |
| SE-JTTYP-003 | Load the cluster with every record of `TRANTYPE.PS`, in file order, with exclusive use of the cluster (`DISP=OLD`). |
| VAL-JTTYP-001 | The load rejects records out of key order or with duplicate keys (VSAM rule). |
| ERR-JTTYP-001 | No step has a `COND`, so every step runs even when an earlier one failed. |
| ERR-JTTYP-002 | The job waits to start while another job has the cluster allocated. |
| ERR-JTTYP-003 | A severe IDCAMS error (condition code 16) in the delete skips the return-code reset. |
| NUM-JTTYP-001 | Key and record size. |
| DEF-JTTYP-001 | The unconditional return-code reset hides every delete failure. |
| DEF-JTTYP-002 | The cluster has `SHAREOPTIONS(1 4)`, unlike the `(2 3)` that the other reload jobs code. |
| DEF-JTTYP-003 | Two comments misspell "TRANSACTION". |

## 3. Actors and triggers

- **Trigger:** submitted by an operator. It is in the README's initialisation sequence (`./README.md:164`) and its "Running Batch Jobs" sequence (`./README.md:223`), and in the demo scripts `scripts/run_full_batch.sh:37-38` and `scripts/remote_refresh.sh:40-41`. In the CA-7 schedule, CLOSEFIL (schedule ID 030) triggers it and it triggers `WAITSTEP` (`app/scheduler/CardDemo.ca7:159-189`). Control-M does not run it. The local runner has no TRANTYPE job; it loads `app/data/ASCII/trantype.txt` into the `TRANTYPE` dataset directly (`tools/batch-programs.toml:225-229`).
- **Preconditions:** no other job has `AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS` allocated (ERR-JTTYP-002); `AWS.M2.CARDDEMO.TRANTYPE.PS` exists and is sorted by key (`./README.md:144`, FB 60). The define names volume `AWSHJ1`, but the cluster is SMS-managed (storage class `SCTECH`, `app/catlg/LISTCAT.txt:3748`) and the catalog shows its data component on volume `YYYYTP` (`app/catlg/LISTCAT.txt:3794`), so on the reference system SMS chose the volume; `AWSHJ1` matters only where SMS does not manage these names (platform-inferred, medium). The CSD defines no CICS file on this cluster (checked with a search over `app/csd/CARDDEMO.CSD`), so no CICS close is needed for it.
- **Job card:** `CLASS=A,MSGCLASS=0,NOTIFY=&SYSUID` (`:1-2`). No parameters, no symbolic overrides.

## 4. Interfaces

| Step | Program | DD | Dataset | Disposition | Use | Source |
| :-- | :-- | :-- | :-- | :-- | :-- | :-- |
| `STEP05` | IDCAMS | `SYSPRINT` | `SYSOUT=*` | - | IDCAMS messages | `:23` |
| `STEP05` | IDCAMS | `SYSIN` | in-stream | - | `DELETE` and `SET` statements | `:24-28` |
| `STEP10` | IDCAMS | `SYSPRINT` | `SYSOUT=*` | - | IDCAMS messages | `:34` |
| `STEP10` | IDCAMS | `SYSIN` | in-stream | - | `DEFINE CLUSTER` statement | `:35-50` |
| `STEP15` | IDCAMS | `SYSPRINT` | `SYSOUT=*` | - | IDCAMS messages | `:55` |
| `STEP15` | IDCAMS | `TRANTYPE` | `AWS.M2.CARDDEMO.TRANTYPE.PS` | `SHR` | input, sequential, FB 60 | `:56-57` |
| `STEP15` | IDCAMS | `TTYPVSAM` | `AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS` | `OLD` | output, KSDS | `:58-59` |
| `STEP15` | IDCAMS | `SYSIN` | in-stream | - | `REPRO` statement | `:60-62` |

Return code of the job: the highest step return code. Console output is IDCAMS `SYSPRINT` only (incidental).

## 5. Data used

- ENT-TRAN-TYPE (`TRAN-TYPE-RECORD`, `app/cpy/CVTRA03Y.cpy:4-7`, 60 bytes), whole records copied unchanged from `TRANTYPE.PS` to the KSDS. No field is interpreted. The key is bytes 1-2 (`TRAN-TYPE`, `X(02)`); the description `TRAN-TYPE-DESC` (`X(50)`) and an 8-byte filler are carried along. The filler holds `00000000` in every sample record, and TRANEXTR writes the same eight zeros when it rebuilds the flat file from Db2 (`REPEAT('0',8)`, `app/app-transaction-type-db2/jcl/TRANEXTR.jcl:76-84`), so both sources give the same bytes. The content of the filler is incidental.
- Catalog state on 09/01/22 (`app/catlg/LISTCAT.txt:3742-3828`): the data component has `KEYLEN 2`, `RKP 0`, `MAXLRECL 60`, `SHROPTNS(1,4)`, `ERASE`, `NOREUSE` (`app/catlg/LISTCAT.txt:3779-3781`), `REC-TOTAL 7` with no inserts or updates (`app/catlg/LISTCAT.txt:3784-3787`), space `CYLINDER` primary 1 secondary 5 (`app/catlg/LISTCAT.txt:3791-3792`). This matches SE-JTTYP-002.
- Sample data: `app/data/ASCII/trantype.txt` has 7 records of 60 bytes, keys `01` (Purchase) to `07` (Adjustment), strictly ascending. Checked locally with a Python script over the file.

## 6. Processing flow

| # | Step | Program | Class | What it does | Runs when |
| --: | :-- | :-- | :-- | :-- | :-- |
| 1 | `STEP05` | IDCAMS | plumbing | delete the cluster | always |
| 2 | `STEP10` | IDCAMS | plumbing | define the cluster | always (no `COND`) |
| 3 | `STEP15` | IDCAMS | plumbing | copy `TRANTYPE.PS` into the cluster | always |

```mermaid
flowchart TD
    S([submit]) --> D[STEP05 DELETE cluster<br/>SET MAXCC = 0]
    D --> F[STEP10 DEFINE CLUSTER]
    F --> L[STEP15 REPRO TRANTYPE.PS to KSDS]
    L --> E([end, RC = highest step RC])
```

## 7. Business rules

None. The job moves data without interpreting it; see the side effects in section 9.

## 8. Validations and messages

#### VAL-JTTYP-001 · The load requires ascending, unique keys
- **When:** `STEP15` copies a record whose key (bytes 1-2) is not higher than the previous record's key.
- **Then:** VSAM rejects that record and IDCAMS prints an error message for it; the step ends with return code 8 or higher. The remaining records are still copied until IDCAMS reaches its error limit (by default it stops after 4 such errors).
- **Source:** `:61`
- **Fidelity:** must-preserve (the target load must also fail visibly on unsorted or duplicate input) · **Confidence:** platform-inferred (IDCAMS `REPRO` into a KSDS; no local run)

No operator-facing message text is produced by the job itself; all messages are IDCAMS `IDCnnnnI` messages in `SYSPRINT`.

## 9. Side effects

In execution order. There is no rollback: each step's change is permanent.

#### SE-JTTYP-001 · Delete the transaction type cluster
- **What:** `DELETE AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS CLUSTER`, which removes the cluster with its data and index components and all its records.
- **Return code:** `SET MAXCC = 0` follows without a condition, so the step ends with return code 0 whatever the delete did, unless the delete ends with condition code 16 and IDCAMS skips the `SET` (ERR-JTTYP-003). A missing cluster (first run) is therefore not an error, but neither is any other failure (DEF-JTTYP-001).
- **Source:** `:25-27`
- **Fidelity:** must-preserve (the reload starts from nothing) · **Confidence:** reviewed

#### SE-JTTYP-002 · Define the transaction type cluster
- **What:** `DEFINE CLUSTER` named `AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS` with:

| Attribute | Value |
| :-- | :-- |
| Organisation | `INDEXED` (KSDS) |
| Key | `KEYS(2 0)`: 2 bytes at offset 0 (`TRAN-TYPE`) |
| Record size | `RECORDSIZE(60 60)`: average and maximum 60, so fixed 60 |
| Space | `CYLINDERS(1 5)` on `VOLUMES(AWSHJ1)` |
| Sharing | `SHAREOPTIONS(1 4)` |
| Other | `ERASE`; no `REUSE` |
| Components | data `...TRANTYPE.VSAM.KSDS.DATA`, index `...TRANTYPE.VSAM.KSDS.INDEX` |

- **Source:** `:36-49`
- **Fidelity:** must-preserve (key and record size); incidental (space, volume, sharing, `ERASE`) · **Confidence:** reviewed

#### SE-JTTYP-003 · Load the cluster from the flat file
- **What:** `REPRO INFILE(TRANTYPE) OUTFILE(TTYPVSAM)` copies every record of `AWS.M2.CARDDEMO.TRANTYPE.PS`, in file order, into the new cluster, byte for byte. The output DD has `DISP=OLD`, which asks for exclusive use of the cluster name (ERR-JTTYP-002); the reload jobs for the card, customer and cross-reference files use `DISP=SHR` instead.
- **Source:** `:56-61`
- **Fidelity:** must-preserve (exact copy); incidental (disposition) · **Confidence:** reviewed

If a later step fails, everything before it has happened: the old cluster is gone, and the new one may be empty or partly loaded. TRANREPT then fails to open the file or finds no type descriptions.

## 10. Error handling

#### ERR-JTTYP-001 · Steps run regardless of earlier failures
- **When:** a step ends with a non-zero return code without abending.
- **Then:** the next step still runs, because no `EXEC` has a `COND` parameter. If `STEP10` fails (for example because the delete did not remove the cluster), `STEP15` loads the records into whatever cluster exists (DEF-JTTYP-001). An abend in a step skips the remaining steps (JES default).
- **Restart:** rerun the whole job; every step either recreates its object or is reset. There is no partial restart point.
- **Source:** `:22, 33, 54`
- **Fidelity:** defect-decide · **Confidence:** platform-inferred

#### ERR-JTTYP-002 · The job waits for exclusive use of the cluster
- **When:** another job (for example TRANREPT) has `AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS` allocated when this job is selected to run.
- **Then:** because of `DISP=OLD` the system requests an exclusive data set enqueue on the cluster name when the job starts, before `STEP05` runs. The job waits until the other job ends, and while it runs no other job can allocate the cluster. This protects the delete and the load against concurrent batch use within one system. No error is reported; the operator sees the job waiting for the data set.
- **Source:** `:58-59`
- **Fidelity:** must-preserve (no reader or updater may use the file during the reload); incidental (mechanism) · **Confidence:** platform-inferred

#### ERR-JTTYP-003 · A severe IDCAMS error skips the return-code reset
- **When:** the `DELETE` in `STEP05` ends with condition code 16, a severe error such as a catalog that cannot be accessed.
- **Then:** IDCAMS does not process the remaining commands of the step, so `SET MAXCC = 0` never runs and `STEP05` ends with return code 16. The job still goes on to `STEP10` (ERR-JTTYP-001). The unconditional reset therefore turns every delete result except 16 into 0. `STEP10` and `STEP15` hold one command each, so for them a severe error simply ends the step with 16.
- **Source:** `:25-27`
- **Fidelity:** incidental · **Confidence:** platform-inferred (high; IDCAMS condition-code rules, no local run)

## 11. Interactions

- **Upstream (optional Db2 module):** TRANEXTR copies `TRANTYPE.PS` to a backup generation, deletes it, and writes it again from Db2 table `CARDDEMO.TRANSACTION_TYPE`, ordered by type (`app/app-transaction-type-db2/jcl/TRANEXTR.jcl:31-38, 53-57, 65-84`). The README runs TRANEXTR before TRANTYPE (`./README.md:221-223`). Without the module, `TRANTYPE.PS` is the shipped sample.
- **Downstream:** TRANREPT (`CBTRN03C`) reads the type descriptions for the transaction report (DD `TRANTYPE`, `app/jcl/TRANREPT.jcl:69-70`; the procedure version `app/proc/TRANREPT.prc:67-68`).
- **Shared data:** ENT-TRAN-CATEGORY (loaded by TRANCATG) and the transaction records use these type codes; nothing in this job checks it.

## 12. Numeric, date and format details

#### NUM-JTTYP-001 · Key and record size
- Primary key: 2 bytes at offset 0 (`TRAN-TYPE`), unique, compared as bytes. Records: fixed 60 bytes. Space: `CYLINDERS(1 5)`.
- **Source:** `:37, 40-42`
- **Fidelity:** must-preserve (key, record size); incidental (space) · **Confidence:** reviewed

## 13. Known defects and oddities

#### DEF-JTTYP-001 · The unconditional return-code reset hides every delete failure
- `SET MAXCC = 0` is meant for "cluster not found" on a first run, but it has no `IF`, so it also hides a delete that failed for any other reason (cluster in use from another system, no authority, catalog error); only a severe error with condition code 16 escapes it (ERR-JTTYP-003). The card, customer and cross-reference reload jobs at least limit their reset to `IF MAXCC LE 08`. After a hidden failure `STEP10` fails because the name already exists, and `STEP15` copies the flat file on top of the old, loaded cluster: records with existing keys are rejected as duplicates, new keys are added, and the job ends with a non-zero code from `STEP10` and `STEP15`, but old and new data stay mixed.
- **Source:** `:27, 33, 54`
- **Status:** open · **Fidelity:** defect-decide · **Confidence:** platform-inferred

#### DEF-JTTYP-002 · Share options differ from the other reloaded clusters
- The cluster is defined with `SHAREOPTIONS(1 4)`. The other reload jobs that code share options (ACCTFILE, CARDFILE, CUSTFILE, XREFFILE, TCATBALF, TRANCATG, DISCGRP) use `SHAREOPTIONS(2 3)`, but not every catalogued cluster has `(2 3)`: DUSRSECJ codes none, and the user security cluster shows the default `(1,3)` (`app/catlg/LISTCAT.txt:3885`), as do the alternate indexes that CARDFILE and XREFFILE define (`app/catlg/LISTCAT.txt:284, 487`). DEFCUST, which defines a separate customer cluster, also codes `(1 4)` (`app/jcl/DEFCUST.jcl:39`).
- Cross-region option 1 lets either one job open the file for update or any number of jobs open it for reading, but not both at once. While a reader such as TRANREPT has the cluster open, the load's open for output in `STEP15` is therefore refused, and while the load has it open no reader can open it. Within one system the `DISP=OLD` enqueue (ERR-JTTYP-002) already keeps other jobs away during the reload, so the option adds little there. Cross-system option 4 lets other systems share the file without VSAM integrity checks. For a file that is only read after the load this has no visible effect.
- **Source:** `:42`
- **Status:** open · **Fidelity:** incidental · **Confidence:** reviewed (value); platform-inferred (meaning)

#### DEF-JTTYP-003 · Misspelt comments
- The step comments read "TRANSACATION TYPE". No effect on processing.
- **Source:** `:20, 31`
- **Status:** open · **Fidelity:** incidental · **Confidence:** reviewed

## 14. Open questions

| ID | Question | For |
| :-- | :-- | :-- |
| Q-JTTYP-001 | With the optional Db2 module installed, Db2 holds the master copy of the transaction types and this file is an extract. Should the target keep a single store for types and drop the flat-file reload? | business |

## 15. Equivalence contract

After a successful run (return code 0), the transaction type store contains exactly the records of `TRANTYPE.PS`: same count (7 for the sample data), same bytes, keyed by `TRAN-TYPE`, and nothing else. A reimplementation must fail (non-zero result, no silent partial load) when the input has keys out of order or duplicated (VAL-JTTYP-001), and nothing may read or update the file while the reload runs (ERR-JTTYP-002). IDCAMS message text may differ.

Review evidence (independent reviewers checked every item against the source, all WRONG and MINOR findings were fixed, and a fresh reviewer re-checked every changed or added item): [evidence/g-jobs-ops-review.md](../evidence/g-jobs-ops-review.md).

## 16. Paragraph coverage

| Step | Lines | Covered by |
| :-- | :-- | :-- |
| (job card) | 1-2 | §3 |
| `STEP05` | 22-28 | SE-JTTYP-001, ERR-JTTYP-003, DEF-JTTYP-001, DEF-JTTYP-003 |
| `STEP10` | 33-50 | SE-JTTYP-002, NUM-JTTYP-001, ERR-JTTYP-001, DEF-JTTYP-002, DEF-JTTYP-003 |
| `STEP15` | 54-62 | SE-JTTYP-003, VAL-JTTYP-001, ERR-JTTYP-001, ERR-JTTYP-002 |
