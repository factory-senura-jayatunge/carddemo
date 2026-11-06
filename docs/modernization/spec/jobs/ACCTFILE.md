---
id: ACCTFILE
title: Account master reload (delete, define and load the ACCTDATA KSDS)
kind: job
module: core
unit_code: JACCT
source_rev: 8a8a80a
sources:
  - {path: app/jcl/ACCTFILE.jcl, sha256: febba716137a, role: job}
  - {path: app/cpy/CVACT01Y.cpy, sha256: 81a08bad15af, role: copybook}
  - {path: app/catlg/LISTCAT.txt, sha256: d207911955e1, role: catalog, lines: "7-110"}
  - {path: app/csd/CARDDEMO.CSD, sha256: 28ded93b49e1, role: csd, lines: "1-12"}
  - {path: README.md, sha256: 8e46a80b63ce, role: runbook, lines: "135, 150-167, 213-235, 351-379"}
  - {path: scripts/run_full_batch.sh, sha256: 5380076d14e7, role: script, lines: "12-17"}
  - {path: scripts/remote_refresh.sh, sha256: 65be63e4c382, role: script, lines: "12-17"}
  - {path: scripts/run_posting.sh, sha256: a8e231c46316, role: script, lines: "12-17"}
  - {path: docs/runbooks/batch-cycle-restart.md, sha256: a380604a19e1, role: runbook, lines: "11-21, 73-81"}
entry_points: [{type: manual, job: ACCTFILE}, {type: script, file: scripts/run_full_batch.sh, lines: "16-17"}, {type: script, file: scripts/remote_refresh.sh, lines: "16-17"}, {type: script, file: scripts/run_posting.sh, lines: "16-17"}]
programs: [IDCAMS]
data_stores:
  - {dd: ACCTDATA, dataset: AWS.M2.CARDDEMO.ACCTDATA.PS, entity: ENT-ACCOUNT, copybook: CVACT01Y, access: [read-sequential]}
  - {dd: ACCTVSAM, dataset: AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS, entity: ENT-ACCOUNT, copybook: CVACT01Y, access: [delete, define, load]}
invoked_by: [operator, scripts/run_full_batch.sh, scripts/remote_refresh.sh, scripts/run_posting.sh]
spec_status: draft
---

# ACCTFILE: Account master reload

## 1. Summary

ACCTFILE throws away the account master file and rebuilds it from the flat sample file. Step `STEP05` deletes the VSAM cluster `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS`, `STEP10` defines it again empty (key 11 bytes at offset 0, fixed 300-byte records), and `STEP15` copies every record of `AWS.M2.CARDDEMO.ACCTDATA.PS` into it. It is run by hand during installation and as part of the demo "data refresh" before a batch cycle. It contains no business logic; what it must preserve is the file definition and the fact that the result is an exact copy of the flat file.

## 2. Rules at a glance

The job has no business rules. Its items:

| ID | Rule |
| :-- | :-- |
| SE-JACCT-001 | Delete the account cluster; a failure with return code 8 or less is ignored. |
| SE-JACCT-002 | Define the account cluster: key 11 at offset 0, records 300 fixed, `SHAREOPTIONS(2 3)`, `ERASE`, no `REUSE`. |
| SE-JACCT-003 | Load the cluster with every record of `ACCTDATA.PS`, in file order. |
| ERR-JACCT-001 | No step has a `COND`, so `STEP15` runs even when the define failed. |
| ERR-JACCT-002 | A severe IDCAMS error (condition code 16) in the delete skips the return-code reset and ends `STEP05` with 16. |
| ERR-JACCT-003 | While CICS has `ACCTDAT` open, the delete, the define and the load all fail and the old file stays. |
| VAL-JACCT-001 | The load rejects records out of key order or with duplicate keys (VSAM rule). |
| DEF-JACCT-001 | The return-code reset also hides a delete that failed because the file is in use. |
| DEF-JACCT-002 | The job does not close the file in CICS itself. |

## 3. Actors and triggers

- **Trigger:** submitted by an operator. It is in the README's initialisation sequence (`./README.md:154`) and its "Running Batch Jobs" sequence (`./README.md:216`), both after CLOSEFIL, and in the demo scripts `scripts/run_full_batch.sh:16-17`, `scripts/remote_refresh.sh:16-17` and `scripts/run_posting.sh:16-17`, each of which submits CLOSEFIL first (`scripts/run_full_batch.sh:12-13`, `scripts/remote_refresh.sh:12-13`, `scripts/run_posting.sh:12-13`). The restart runbook uses it to restore demo account data after a failed POSTTRAN (`docs/runbooks/batch-cycle-restart.md:73-75`). Neither the CA-7 nor the Control-M schedule runs it (see `SCHED-CA7.md`, `SCHED-CONTROLM.md`). The local runner has no ACCTFILE job; it loads `app/data/ASCII/acctdata.txt` into the `ACCTDATA` dataset directly for every run (`tools/batch-programs.toml:187-191`).
- **Preconditions:** CICS has closed file `ACCTDAT` (run CLOSEFIL first; DEF-JACCT-002, ERR-JACCT-003); `AWS.M2.CARDDEMO.ACCTDATA.PS` exists and is sorted by account ID (`./README.md:135`, FB 300). The define names volume `AWSHJ1`, but the cluster is SMS-managed (storage class `SCTECH`, `app/catlg/LISTCAT.txt:28`) and the catalog shows its data component on volume `YYYYO7` (`app/catlg/LISTCAT.txt:74`), so on the reference system SMS chose the volume; `AWSHJ1` matters only where SMS does not manage these names (platform-inferred, medium).
- **Job card:** `CLASS=A,MSGCLASS=0,NOTIFY=&SYSUID` (`:1-2`). No parameters, no symbolic overrides.

## 4. Interfaces

| Step | Program | DD | Dataset | Disposition | Use | Source |
| :-- | :-- | :-- | :-- | :-- | :-- | :-- |
| `STEP05` | IDCAMS | `SYSPRINT` | `SYSOUT=*` | - | IDCAMS messages | `:23` |
| `STEP05` | IDCAMS | `SYSIN` | in-stream | - | `DELETE` statement | `:24-28` |
| `STEP10` | IDCAMS | `SYSPRINT` | `SYSOUT=*` | - | IDCAMS messages | `:34` |
| `STEP10` | IDCAMS | `SYSIN` | in-stream | - | `DEFINE CLUSTER` statement | `:35-50` |
| `STEP15` | IDCAMS | `SYSPRINT` | `SYSOUT=*` | - | IDCAMS messages | `:55` |
| `STEP15` | IDCAMS | `ACCTDATA` | `AWS.M2.CARDDEMO.ACCTDATA.PS` | `SHR` | input, sequential | `:56-57` |
| `STEP15` | IDCAMS | `ACCTVSAM` | `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` | `SHR` | output, KSDS | `:58-59` |
| `STEP15` | IDCAMS | `SYSIN` | in-stream | - | `REPRO` statement | `:60-62` |

Return code of the job: the highest step return code. Console output is IDCAMS `SYSPRINT` only (incidental).

## 5. Data used

- ENT-ACCOUNT (`ACCOUNT-RECORD`, `app/cpy/CVACT01Y.cpy:4-17`, 300 bytes), whole records copied unchanged from `ACCTDATA.PS` to the KSDS. No field is interpreted; the key is bytes 1-11 (`ACCT-ID`, `9(11)`).
- Catalog state on 09/01/22 (`app/catlg/LISTCAT.txt:22-79`): the cluster's data component has `KEYLEN 11`, `RKP 0`, `MAXLRECL 300`, `SHROPTNS(2,3)`, `ERASE` and `NOREUSE` (`app/catlg/LISTCAT.txt:59-61`), which matches the define in SE-JACCT-002. The statistics are not the state right after this job: `REC-TOTAL 50` and `REC-INSERTED 0` agree with a load of the 50 sample accounts, but `REC-UPDATED 311` and `REC-RETRIEVED 350` (`app/catlg/LISTCAT.txt:64-68`) show that programs read and rewrote accounts after the load.

## 6. Processing flow

All three steps are plumbing; none carries a business decision.

| # | Step | Class | What it does | Runs when |
| --: | :-- | :-- | :-- | :-- |
| 1 | `STEP05` | plumbing | delete the cluster | always |
| 2 | `STEP10` | plumbing | define the cluster | always (no `COND`) |
| 3 | `STEP15` | plumbing | copy `ACCTDATA.PS` into the cluster | always (no `COND`) |

```mermaid
flowchart TD
    S([submit]) --> D[STEP05 DELETE cluster<br/>RC <= 8 reset to 0]
    D --> F[STEP10 DEFINE CLUSTER]
    F --> L[STEP15 REPRO ACCTDATA.PS to KSDS]
    L --> E([end, RC = highest step RC])
```

## 7. Business rules

None. The job moves data without interpreting it; see the side effects in section 9.

## 8. Validations and messages

#### VAL-JACCT-001 · The load requires ascending, unique keys
- **When:** `STEP15` copies a record whose key (bytes 1-11) is not higher than the previous record's key.
- **Then:** VSAM rejects that record and IDCAMS prints an error message for it; the step ends with return code 8 or higher. The remaining records are still copied until IDCAMS reaches its error limit (by default it stops after 4 such errors).
- **Source:** `:61`
- **Fidelity:** must-preserve (the target load must also fail visibly on unsorted or duplicate input) · **Confidence:** platform-inferred (IDCAMS `REPRO` into a KSDS; no local run)

No operator-facing message text is produced by the job itself; all messages are IDCAMS `IDCnnnnI` messages in `SYSPRINT`.

## 9. Side effects

In execution order. There is no rollback: each step's change is permanent.

#### SE-JACCT-001 · Delete the account cluster
- **What:** `DELETE AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS CLUSTER`, which removes the cluster with its data and index components and all its records.
- **Return code:** `IF MAXCC LE 08 THEN SET MAXCC = 0` turns any result of 8 or less into 0, so a missing cluster (first run) is not an error.
- **Source:** `:25-27`
- **Fidelity:** must-preserve · **Confidence:** reviewed

#### SE-JACCT-002 · Define the account cluster
- **What:** `DEFINE CLUSTER` named `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` with:

| Attribute | Value |
| :-- | :-- |
| Organisation | `INDEXED` (KSDS) |
| Key | `KEYS(11 0)`: 11 bytes at offset 0 |
| Record size | `RECORDSIZE(300 300)`: average and maximum 300, so fixed 300 |
| Space | `CYLINDERS(1 5)` on `VOLUMES(AWSHJ1)` |
| Sharing | `SHAREOPTIONS(2 3)`: one writer and many readers within a system |
| Other | `ERASE` (data overwritten on delete); no `REUSE` (catalogued as `NOREUSE`), so the loaded cluster cannot be reset to empty by opening it for output; a reload has to delete and define it again, as this job does |
| Components | data `...ACCTDATA.VSAM.KSDS.DATA`, index `...ACCTDATA.VSAM.KSDS.INDEX` |

- **Source:** `:36-49`
- **Fidelity:** must-preserve (key and record size); incidental (space, volume, `ERASE`) · **Confidence:** reviewed

#### SE-JACCT-003 · Load the cluster from the flat file
- **What:** `REPRO INFILE(ACCTDATA) OUTFILE(ACCTVSAM)` copies every record of `AWS.M2.CARDDEMO.ACCTDATA.PS`, in file order, into the new cluster. Records are copied byte for byte.
- **Source:** `:56-61`
- **Fidelity:** must-preserve · **Confidence:** reviewed

## 10. Error handling

#### ERR-JACCT-001 · Steps run regardless of earlier failures
- **When:** a step ends with a non-zero return code without abending.
- **Then:** the next step still runs, because no `EXEC` has a `COND` parameter. If `STEP10` fails (for example because the delete did not remove the cluster), `STEP15` loads the records into whatever cluster exists (DEF-JACCT-001), or cannot open it while CICS has it open (ERR-JACCT-003). An abend in a step skips the remaining steps (JES default).
- **Source:** `:22, 33, 54`
- **Fidelity:** defect-decide · **Confidence:** platform-inferred

#### ERR-JACCT-002 · A severe IDCAMS error skips the return-code reset
- **When:** the `DELETE` in `STEP05` ends with condition code 16, a severe error such as a catalog that cannot be accessed.
- **Then:** IDCAMS does not process the remaining commands of the step, so `IF MAXCC LE 08 THEN SET MAXCC = 0` never runs and `STEP05` ends with return code 16. The job still goes on to `STEP10` (ERR-JACCT-001). The reset therefore turns only results of 8 or less into 0. `STEP10` and `STEP15` hold one command each, so for them a severe error simply ends the step with 16.
- **Source:** `:25-27`
- **Fidelity:** incidental · **Confidence:** platform-inferred (high; IDCAMS condition-code rules, no local run)

#### ERR-JACCT-003 · Nothing is reloaded while CICS has the file open
- **When:** CICS file `ACCTDAT` is open when the job runs, because CLOSEFIL has not run or CICS has not yet closed the file (DEF-JACCT-002). CICS opens the file for update, since its definition allows add, update and delete (`app/csd/CARDDEMO.CSD:1-7`).
- **Then:** `STEP05` cannot delete the cluster (return code 8, reset to 0; DEF-JACCT-001), and `STEP10` fails because the name exists. With `SHAREOPTIONS(2 3)` only one region at a time may have the cluster open for output, and CICS already has it, so `REPRO` in `STEP15` cannot open `ACCTVSAM`, copies nothing and ends with return code 12. The old cluster keeps its data and CICS keeps using it; the job ends with return code 12.
- **Source:** `:25-27, 42, 58-61`, `app/csd/CARDDEMO.CSD:1-7`
- **Fidelity:** must-preserve (no reload while the online system uses the file) · **Confidence:** platform-inferred (high; VSAM share options and the CICS open mode, no local run)

## 11. Interactions

- **Upstream:** CLOSEFIL closes `ACCTDAT` in CICS (README order, `./README.md:153-154`).
- **Downstream:** every reader of the account master: POSTTRAN (`CBTRN02C`), INTCALC (`CBACT04C`), CREASTMT (`CBSTM03A`), READACCT (`CBACT01C`), CBEXPORT, and the online programs through CICS file `ACCTDAT` after OPENFIL.
- **Shared data:** `ACCTDATA.PS` is also the restore source in the restart runbook (`docs/runbooks/batch-cycle-restart.md:74-75`).

## 12. Numeric, date and format details

None. The job copies bytes; it does not interpret any field.

## 13. Known defects and oddities

#### DEF-JACCT-001 · The return-code reset hides a failed delete
- `IF MAXCC LE 08 THEN SET MAXCC = 0` is meant for "cluster not found", but it also hides other delete failures with return code 8, for example when the cluster is still allocated to CICS or to another job. Then `STEP10` fails because the name already exists. While CICS has the file open, `STEP15` cannot open it either and nothing is copied (ERR-JACCT-003). Only when the delete failed for another reason, or another job has the cluster open for input only (which `SHAREOPTIONS(2 3)` allows next to one writer), does `STEP15` copy the flat file on top of the old, loaded cluster: records with existing keys are rejected as duplicates and records with new keys are added. In both cases the job ends with a non-zero code, and the old data stays.
- **Source:** `:27, 33, 54`
- **Status:** open · **Fidelity:** defect-decide · **Confidence:** platform-inferred

#### DEF-JACCT-002 · The job does not close the file in CICS
- Unlike CARDFILE, CUSTFILE and TRANFILE, ACCTFILE has no CICS close and open step, so it depends on CLOSEFIL having run first and on OPENFIL afterwards.
- **Source:** `:22-62`
- **Status:** open · **Fidelity:** incidental · **Confidence:** reviewed

## 14. Open questions

| ID | Question | For |
| :-- | :-- | :-- |
| Q-JACCT-001 | Is a full delete-and-reload of the account master needed in the target outside demo set-up, or only a seed load for new environments? | operations |

## 15. Equivalence contract

After a successful run (return code 0), the account store contains exactly the records of `ACCTDATA.PS`: same count (50 for the sample data), same bytes, keyed by `ACCT-ID`, and nothing else. A reimplementation must fail (non-zero result, no silent partial load) when the input has keys out of order or duplicated (VAL-JACCT-001). IDCAMS message text may differ.

## 16. Paragraph coverage

| Step | Lines | Covered by |
| :-- | :-- | :-- |
| (job card) | 1-2 | §3 |
| `STEP05` | 22-28 | SE-JACCT-001, ERR-JACCT-002, DEF-JACCT-001 |
| `STEP10` | 33-50 | SE-JACCT-002, ERR-JACCT-001 |
| `STEP15` | 54-62 | SE-JACCT-003, VAL-JACCT-001, ERR-JACCT-001, ERR-JACCT-003 |
