---
id: XREFFILE
title: Card cross-reference reload (delete, define and load the CARDXREF KSDS, rebuild its account AIX)
kind: job
module: core
unit_code: JXREF
source_rev: 8a8a80a
sources:
  - {path: app/jcl/XREFFILE.jcl, sha256: ae0edd90056c, role: job}
  - {path: app/cpy/CVACT03Y.cpy, sha256: ffc6079e09b2, role: copybook}
  - {path: app/catlg/LISTCAT.txt, sha256: d207911955e1, role: catalog, lines: "334-531"}
  - {path: app/csd/CARDDEMO.CSD, sha256: 28ded93b49e1, role: csd, lines: "37-49, 63-75"}
  - {path: README.md, sha256: 8e46a80b63ce, role: runbook, lines: "138, 157, 218, 365"}
  - {path: scripts/run_full_batch.sh, sha256: 5380076d14e7, role: script, lines: "22-23"}
  - {path: scripts/remote_refresh.sh, sha256: 65be63e4c382, role: script, lines: "22-23"}
  - {path: docs/runbooks/cics-vsam-files.md, sha256: 263569d29e9a, role: runbook, lines: "16, 18, 37-38, 57-58, 89-90"}
entry_points: [{type: manual, job: XREFFILE}, {type: script, file: scripts/run_full_batch.sh, lines: "22-23"}, {type: script, file: scripts/remote_refresh.sh, lines: "22-23"}]
programs: [IDCAMS]
data_stores:
  - {dd: XREFDATA, dataset: AWS.M2.CARDDEMO.CARDXREF.PS, copybook: CVACT03Y, access: [read-sequential]}
  - {dd: XREFVSAM, dataset: AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS, copybook: CVACT03Y, access: [delete, define, load]}
  - {dataset: AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX, access: [delete, define, build]}
  - {dataset: AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH, access: [define]}
invoked_by: [operator, scripts/run_full_batch.sh, scripts/remote_refresh.sh]
spec_status: draft
---

# XREFFILE: Card cross-reference reload

## 1. Summary

XREFFILE throws away the card cross-reference file (ENT-CARD-XREF: card number, customer ID, account ID) and rebuilds it from the flat sample file, together with its alternate index by account. `STEP05` deletes the cluster `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` and its alternate index, `STEP10` defines the cluster again empty (key 16 bytes at offset 0, fixed 50-byte records), `STEP15` copies every record of `AWS.M2.CARDDEMO.CARDXREF.PS` into it, and `STEP20` to `STEP30` define the non-unique alternate index on the account ID (11 bytes at offset 25), its path, and build it. Unlike CARDFILE and CUSTFILE it does not close or reopen any CICS file itself; it relies on CLOSEFIL having closed `CCXREF` and `CXACAIX`. It is run by hand during installation and in the demo data refresh. It contains no business logic; what it must preserve is the file and index definitions and the fact that the result is an exact copy of the flat file.

## 2. Rules at a glance

The job has no business rules. Its items:

| ID | Rule |
| :-- | :-- |
| SE-JXREF-001 | Delete the cross-reference cluster and its alternate index; each failure with return code 8 or less is ignored. |
| SE-JXREF-002 | Define the cluster: key 16 at offset 0, records 50 fixed, `SHAREOPTIONS(2 3)`, `ERASE`, no `REUSE`. |
| SE-JXREF-003 | Load the cluster with every record of `CARDXREF.PS`, in file order. |
| SE-JXREF-004 | Define a non-unique, upgraded alternate index on the account ID (11 bytes at offset 25). |
| SE-JXREF-005 | Define the path over the alternate index. |
| SE-JXREF-006 | Build the alternate index from the loaded cluster. |
| VAL-JXREF-001 | The load rejects records out of key order or with duplicate card numbers (VSAM rule). |
| ERR-JXREF-001 | No step has a `COND`, so every step runs even when an earlier one failed. |
| NUM-JXREF-001 | Keys, record size and alternate-index capacity. |
| DEF-JXREF-001 | The return-code reset also hides a delete that failed because the file is still open. |
| DEF-JXREF-002 | The job closes no CICS file itself and depends on CLOSEFIL. |
| DEF-JXREF-003 | The alternate index holds at most 2 cards per account. |

## 3. Actors and triggers

- **Trigger:** submitted by an operator. It is in the README's initialisation sequence (`./README.md:157`) and its "Running Batch Jobs" sequence (`./README.md:218`), both after CLOSEFIL, and in the demo scripts `scripts/run_full_batch.sh:22-23` and `scripts/remote_refresh.sh:22-23`, which also submit CLOSEFIL first. Neither the CA-7 nor the Control-M schedule runs it. The local runner has no XREFFILE job; it loads `app/data/ASCII/cardxref.txt` into the `CARDXREF` dataset, with the same alternate key, directly for every run (`tools/batch-programs.toml:205-211`).
- **Preconditions:** CICS files `CCXREF` and `CXACAIX` are closed (CLOSEFIL closes both, `docs/runbooks/cics-vsam-files.md:16, 18`); no batch job has the cluster open; `AWS.M2.CARDDEMO.CARDXREF.PS` exists and is sorted by card number (`./README.md:138`, FB 50); volume `AWSHJ1` exists.
- **Job card:** `CLASS=A,MSGCLASS=0,NOTIFY=&SYSUID` (`:1-2`). No parameters, no symbolic overrides.

## 4. Interfaces

| Step | Program | DD | Dataset | Disposition | Use | Source |
| :-- | :-- | :-- | :-- | :-- | :-- | :-- |
| `STEP05` | IDCAMS | `SYSPRINT` | `SYSOUT=*` | - | IDCAMS messages | `:23` |
| `STEP05` | IDCAMS | `SYSIN` | in-stream | - | two `DELETE` statements | `:24-31` |
| `STEP10` | IDCAMS | `SYSPRINT` | `SYSOUT=*` | - | IDCAMS messages | `:37` |
| `STEP10` | IDCAMS | `SYSIN` | in-stream | - | `DEFINE CLUSTER` statement | `:38-53` |
| `STEP15` | IDCAMS | `SYSPRINT` | `SYSOUT=*` | - | IDCAMS messages | `:58` |
| `STEP15` | IDCAMS | `XREFDATA` | `AWS.M2.CARDDEMO.CARDXREF.PS` | `SHR` | input, sequential, FB 50 | `:59-60` |
| `STEP15` | IDCAMS | `XREFVSAM` | `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` | `SHR` | output, KSDS | `:61-62` |
| `STEP15` | IDCAMS | `SYSIN` | in-stream | - | `REPRO` statement | `:63-65` |
| `STEP20` | IDCAMS | `SYSPRINT` | `SYSOUT=*` | - | IDCAMS messages | `:70` |
| `STEP20` | IDCAMS | `SYSIN` | in-stream | - | `DEFINE ALTERNATEINDEX` statement | `:71-83` |
| `STEP25` | IDCAMS | `SYSPRINT` | `SYSOUT=*` | - | IDCAMS messages | `:88` |
| `STEP25` | IDCAMS | `SYSIN` | in-stream | - | `DEFINE PATH` statement | `:89-93` |
| `STEP30` | IDCAMS | `SYSPRINT` | `SYSOUT=*` | - | IDCAMS messages | `:98` |
| `STEP30` | IDCAMS | `SYSIN` | in-stream | - | `BLDINDEX` statement | `:99-103` |

`STEP20` to `STEP30` name the cluster, alternate index and path by dataset name inside the IDCAMS statements, not through DD statements. CICS file `CCXREF` is defined on the cluster and `CXACAIX` on the path (`app/csd/CARDDEMO.CSD:37-39, 63-65`). Return code of the job: the highest step return code. Console output is IDCAMS `SYSPRINT` only (incidental).

## 5. Data used

- ENT-CARD-XREF (`CARD-XREF-RECORD`, `app/cpy/CVACT03Y.cpy:4-8`, 50 bytes), whole records copied unchanged from `CARDXREF.PS` to the KSDS. No field is interpreted. The primary key is bytes 1-16 (`XREF-CARD-NUM`, `X(16)`); the alternate key is bytes 26-36 (`XREF-ACCT-ID`, `9(11)`); `XREF-CUST-ID` (bytes 17-25) and a 14-byte filler are carried along. The card number is sensitive data; the job copies it and does not print it.
- Catalog state on 09/01/22 (`app/catlg/LISTCAT.txt:351-531`): the cluster's data component has `KEYLEN 16`, `RKP 0`, `MAXLRECL 50`, `SHROPTNS(2,3)`, `ERASE`, `NOREUSE` and `REC-TOTAL 50` (`app/catlg/LISTCAT.txt:403-408`), space `CYLINDER` primary 1 secondary 5 (`app/catlg/LISTCAT.txt:414-416`). The AIX is `UPGRADE` (`app/catlg/LISTCAT.txt:466`); its data component has `KEYLEN 11`, `AXRKP 25`, `MAXLRECL 50`, `SHROPTNS(1,3)`, `SPANNED`, `NONUNIQKEY`, `REC-TOTAL 50` and free space 10% per CI and 20% per CA (`app/catlg/LISTCAT.txt:482-493`), so the sample has one AIX record, that is one card, per account. The path has `UPDATE` (`app/catlg/LISTCAT.txt:364`). This matches SE-JXREF-002 and SE-JXREF-004.
- Sample data: `app/data/ASCII/cardxref.txt` has 50 records of 36 significant bytes (the local loader pads them to 50), card numbers strictly ascending, 50 distinct account IDs. Checked locally with a Python script over the file.

## 6. Processing flow

| # | Step | Program | Class | What it does | Runs when |
| --: | :-- | :-- | :-- | :-- | :-- |
| 1 | `STEP05` | IDCAMS | plumbing | delete the cluster and the AIX | always |
| 2 | `STEP10` | IDCAMS | plumbing | define the cluster | always (no `COND`) |
| 3 | `STEP15` | IDCAMS | plumbing | copy `CARDXREF.PS` into the cluster | always |
| 4 | `STEP20` | IDCAMS | plumbing | define the AIX on the account ID | always |
| 5 | `STEP25` | IDCAMS | plumbing | define the path | always |
| 6 | `STEP30` | IDCAMS | plumbing | build the AIX | always |

```mermaid
flowchart TD
    S([submit, after CLOSEFIL]) --> D[STEP05 DELETE cluster and AIX<br/>RC <= 8 reset to 0]
    D --> F[STEP10 DEFINE CLUSTER]
    F --> L[STEP15 REPRO CARDXREF.PS to KSDS]
    L --> A[STEP20 DEFINE ALTERNATEINDEX<br/>account ID, non-unique]
    A --> P[STEP25 DEFINE PATH]
    P --> B[STEP30 BLDINDEX]
    B --> E([end, RC = highest step RC])
```

## 7. Business rules

None. The job moves data without interpreting it; see the side effects in section 9.

## 8. Validations and messages

#### VAL-JXREF-001 · The load requires ascending, unique card numbers
- **When:** `STEP15` copies a record whose key (bytes 1-16) is not higher than the previous record's key.
- **Then:** VSAM rejects that record and IDCAMS prints an error message for it; the step ends with return code 8 or higher. The remaining records are still copied until IDCAMS reaches its error limit (by default it stops after 4 such errors).
- **Source:** `:64`
- **Fidelity:** must-preserve (the target load must also fail visibly on unsorted or duplicate input) · **Confidence:** platform-inferred (IDCAMS `REPRO` into a KSDS; no local run)

No operator-facing message text is produced by the job itself; all messages are IDCAMS `IDCnnnnI` messages in `SYSPRINT`.

## 9. Side effects

In execution order. There is no rollback: each step's change is permanent.

#### SE-JXREF-001 · Delete the cross-reference cluster and its alternate index
- **What:** two IDCAMS commands, each followed by a return-code reset:

```
DELETE AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS CLUSTER
IF MAXCC LE 08 THEN SET MAXCC = 0
DELETE AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX ALTERNATEINDEX
IF MAXCC LE 08 THEN SET MAXCC = 0
```

- Deleting a cluster also deletes its alternate indexes and paths, so when the first delete works the second normally finds nothing (return code 8, reset to 0). On a first run neither exists and both are reset.
- **Source:** `:25-30`
- **Fidelity:** must-preserve (the reload starts from nothing) · **Confidence:** reviewed (statements); platform-inferred (cascade delete)

#### SE-JXREF-002 · Define the cross-reference cluster
- **What:** `DEFINE CLUSTER` named `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` with:

| Attribute | Value |
| :-- | :-- |
| Organisation | `INDEXED` (KSDS) |
| Key | `KEYS(16 0)`: 16 bytes at offset 0 (`XREF-CARD-NUM`) |
| Record size | `RECORDSIZE(50 50)`: average and maximum 50, so fixed 50 |
| Space | `CYLINDERS(1 5)` on `VOLUMES(AWSHJ1)` |
| Sharing | `SHAREOPTIONS(2 3)` |
| Other | `ERASE`; no `REUSE` |
| Components | data `...CARDXREF.VSAM.KSDS.DATA`, index `...CARDXREF.VSAM.KSDS.INDEX` |

- **Source:** `:39-52`
- **Fidelity:** must-preserve (key and record size); incidental (space, volume, `ERASE`) · **Confidence:** reviewed

#### SE-JXREF-003 · Load the cluster from the flat file
- **What:** `REPRO INFILE(XREFDATA) OUTFILE(XREFVSAM)` copies every record of `AWS.M2.CARDDEMO.CARDXREF.PS`, in file order, into the new cluster, byte for byte. No AIX exists yet, so nothing else is updated.
- **Source:** `:59-64`
- **Fidelity:** must-preserve · **Confidence:** reviewed

#### SE-JXREF-004 · Define the alternate index on the account ID
- **What:** `DEFINE ALTERNATEINDEX` named `AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX` with:

| Attribute | Value |
| :-- | :-- |
| Base cluster | `RELATE(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS)` |
| Key | `KEYS(11,25)`: 11 bytes at offset 25 (`XREF-ACCT-ID`) |
| Uniqueness | `NONUNIQUEKEY`: one AIX record per account, listing every card of that account |
| Maintenance | `UPGRADE`: VSAM updates the AIX whenever the base cluster changes through an upgrade-set open |
| Record size | `RECORDSIZE(50,50)` (NUM-JXREF-001) |
| Free space | `FREESPACE(10,20)`: 10% of each control interval, 20% of each control area left empty |
| Space | `CYLINDERS(5,1)` on `VOLUMES(AWSHJ1)` |
| Sharing | not specified, so the default `SHAREOPTIONS(1 3)` (LISTCAT shows `SHROPTNS(1,3)`) |
| Components | data `...CARDXREF.VSAM.AIX.DATA`, index `...CARDXREF.VSAM.AIX.INDEX` |

- **Source:** `:72-82`
- **Fidelity:** must-preserve (key, non-unique); incidental (space, volume, free space) · **Confidence:** reviewed

#### SE-JXREF-005 · Define the path
- **What:** `DEFINE PATH (NAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH) PATHENTRY(AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX))`. The path is the name a program opens to read the cross-reference by account: CICS file `CXACAIX` points at it (`app/csd/CARDDEMO.CSD:63-65`), and INTCALC opens it as DD `XREFFIL1` (`app/jcl/INTCALC.jcl:31-32`). No `UPDATE`/`NOUPDATE` is given, so the default `UPDATE` applies (LISTCAT `app/catlg/LISTCAT.txt:364`).
- **Source:** `:90-92`
- **Fidelity:** must-preserve (access by account) · **Confidence:** reviewed

#### SE-JXREF-006 · Build the alternate index
- **What:** `BLDINDEX INDATASET(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS) OUTDATASET(AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX)` reads every cross-reference record, takes the account ID from bytes 26-36, sorts the (account, card number) pairs and writes one AIX record per account. The job supplies no `IDCUT1`/`IDCUT2` work files, so the sort must fit in storage (enough for the sample's 50 records).
- **Source:** `:100-102`
- **Fidelity:** must-preserve (the index must reflect every card) · **Confidence:** platform-inferred

If a later step fails, everything before it has happened: the old cluster is gone, and the new one may be empty, loaded without an index, or loaded with an index but no path. POSTTRAN, INTCALC and the online functions that look up a card or an account then fail or find nothing.

## 10. Error handling

#### ERR-JXREF-001 · Steps run regardless of earlier failures
- **When:** a step ends with a non-zero return code without abending.
- **Then:** the next step still runs, because no `EXEC` has a `COND` parameter. If `STEP10` fails (for example because the delete did not remove the cluster), `STEP15` loads the records into whatever cluster exists, and `STEP20` and `STEP25` fail on the existing AIX and path names (DEF-JXREF-001). An abend in a step skips the remaining steps (JES default).
- **Restart:** rerun the whole job; every step either recreates its object or is reset. There is no partial restart point.
- **Source:** `:22, 36, 57, 69, 87, 97`
- **Fidelity:** defect-decide · **Confidence:** platform-inferred

## 11. Interactions

- **Upstream:** CLOSEFIL must have closed CICS files `CCXREF` and `CXACAIX` (`docs/runbooks/cics-vsam-files.md:16, 18`); OPENFIL reopens them afterwards. The README sequences and both demo scripts submit CLOSEFIL first (DEF-JXREF-002).
- **Downstream:** every reader of the cross-reference: POSTTRAN (`CBTRN02C`, DD `XREFFILE`, `app/jcl/POSTTRAN.jcl:32-33`), INTCALC (`CBACT04C`, DD `XREFFILE` and the path as `XREFFIL1`, `app/jcl/INTCALC.jcl:29-32`), CREASTMT (DD `XREFFILE`, `app/jcl/CREASTMT.JCL:84`), TRANREPT (`CBTRN03C`), READXREF (`CBACT03C`), TRANVALD (`CBTRN01C`), CBEXPORT, and the online programs through CICS files `CCXREF` (by card) and `CXACAIX` (by account), for example `COBIL00C`, `COTRN02C`, `COACTVWC` and `COACTUPC`.
- **Shared data:** the account ID must match ENT-ACCOUNT (loaded by ACCTFILE), the customer ID the customer master (loaded by CUSTFILE), and the card number the card master (loaded by CARDFILE); nothing in this job checks it.
- **Operations:** the runbook names XREFFILE among the jobs whose delete fails when the cluster is still in use (`docs/runbooks/cics-vsam-files.md:37-38`) and that must not run while CICS users are signed on (`docs/runbooks/cics-vsam-files.md:89-90`).

## 12. Numeric, date and format details

#### NUM-JXREF-001 · Keys, record size and alternate-index capacity
- Primary key: 16 bytes at offset 0, text (`XREF-CARD-NUM`), unique. Records: fixed 50 bytes.
- Alternate key: 11 bytes at offset 25 (`XREF-ACCT-ID`, digits), non-unique.
- A non-unique AIX record holds a 5-byte header, the 11-byte alternate key and one 16-byte card number per card of that account. With the maximum record size of 50 (`RECORDSIZE(50,50)`), one account can have at most (50 - 5 - 11) / 16 = 2 cards (rounded down) in the index (DEF-JXREF-003).
- Space: cluster `CYLINDERS(1 5)`; AIX `CYLINDERS(5,1)`.
- **Source:** `:43-44, 74-80`
- **Fidelity:** must-preserve (keys, record size); incidental (space) · **Confidence:** reviewed (values); platform-inferred (AIX record layout)

## 13. Known defects and oddities

#### DEF-JXREF-001 · The return-code reset hides a failed delete
- `IF MAXCC LE 08 THEN SET MAXCC = 0` is meant for "not found", but it also hides other delete failures with return code 8, for example when CICS or a batch job still has the cluster open (DEF-JXREF-002). Then `STEP10` fails because the name already exists, `STEP15` copies the flat file on top of the old, loaded cluster (records with existing keys are rejected as duplicates), `STEP20` and `STEP25` fail on the existing names, and `STEP30` builds into the existing AIX. The job ends with a non-zero code, but the old data stays.
- **Source:** `:27, 30, 36, 57, 69, 87`
- **Status:** open · **Fidelity:** defect-decide · **Confidence:** platform-inferred

#### DEF-JXREF-002 · No CICS close or open in the job
- CARDFILE and CUSTFILE close and reopen their own CICS files; XREFFILE does not. Run on its own, without CLOSEFIL first, it meets CICS files `CCXREF` and `CXACAIX` that may be open (they open on first reference), and the delete fails as in DEF-JXREF-001. After the run the files stay closed until OPENFIL runs.
- **Source:** `:22-31`
- **Status:** open · **Fidelity:** defect-decide · **Confidence:** reviewed (absence of the steps); platform-inferred (effect)

#### DEF-JXREF-003 · At most 2 cards per account in the alternate index
- With `RECORDSIZE(50,50)` a non-unique AIX record has room for 2 card numbers (NUM-JXREF-001). An account with a third card makes `BLDINDEX` fail for that account, and a later online add through the upgrade set fails. The sample data has one card per account, so this does not show. The card master's index (CARDFILE) allows 8 cards per account, so the two indexes disagree on the limit.
- **Source:** `:74-77`
- **Status:** open · **Fidelity:** defect-decide · **Confidence:** platform-inferred

## 14. Open questions

| ID | Question | For |
| :-- | :-- | :-- |
| Q-JXREF-001 | How many cards may one account have? The cross-reference index allows 2, the card index 8, the sample has 1. The target's account-to-card lookup must not impose a smaller limit than the business needs. | business |
| Q-JXREF-002 | Is a full delete-and-reload of the cross-reference needed in the target outside demo set-up, or only a seed load for new environments? | operations |

## 15. Equivalence contract

After a successful run (return code 0), the cross-reference store contains exactly the records of `CARDXREF.PS`: same count (50 for the sample data), same bytes, keyed by `XREF-CARD-NUM`, and nothing else; and a lookup by account ID returns, for each account, exactly the card numbers whose bytes 26-36 equal it, in ascending card-number order. A reimplementation must fail (non-zero result, no silent partial load) when the input has keys out of order or duplicated (VAL-JXREF-001), and nothing may use the cross-reference while the reload runs. IDCAMS message text may differ.

## 16. Paragraph coverage

| Step | Lines | Covered by |
| :-- | :-- | :-- |
| (job card) | 1-2 | §3 |
| `STEP05` | 22-31 | SE-JXREF-001, DEF-JXREF-001, DEF-JXREF-002 |
| `STEP10` | 36-53 | SE-JXREF-002, NUM-JXREF-001, ERR-JXREF-001 |
| `STEP15` | 57-65 | SE-JXREF-003, VAL-JXREF-001, ERR-JXREF-001 |
| `STEP20` | 69-83 | SE-JXREF-004, NUM-JXREF-001, DEF-JXREF-003 |
| `STEP25` | 87-93 | SE-JXREF-005 |
| `STEP30` | 97-103 | SE-JXREF-006, DEF-JXREF-003 |
