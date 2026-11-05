# Batch cycle failure and restart

The base batch cycle posts the day's transactions, computes interest and fees, rebuilds the
transaction master and produces statements. This runbook gives the order, what each job
changes, and how to restart after a failure without double-posting.

## Cycle order

From the README "Running Batch Jobs" table and `scripts/run_full_batch.sh`:

| Step | Job | Program | Reads | Writes |
| ---: | :-- | :------ | :---- | :----- |
| 1 | CLOSEFIL | SDSF (CEMT) | | Closes `TRANSACT`, `CCXREF`, `ACCTDAT`, `CXACAIX`, `USRSEC` in CICS |
| 2 | Data refresh (ACCTFILE, CARDFILE, XREFFILE, CUSTFILE, TRANBKP, TRANCATG, TRANTYPE, DISCGRP, TCATBALF, DUSRSECJ) | IDCAMS, IEBGENER | The `*.PS` sample data | Reloads every master file. **Demo only**: this resets all data to the shipped sample. Skip it in a real cycle. |
| 3 | POSTTRAN | `CBTRN02C` | `DALYTRAN.PS`, `CARDXREF.VSAM.KSDS` | `TRANSACT.VSAM.KSDS` (opened OUTPUT), `ACCTDATA.VSAM.KSDS` and `TCATBALF.VSAM.KSDS` (updated in place), `DALYREJS(+1)` |
| 4 | INTCALC | `CBACT04C` | `TCATBALF`, `CARDXREF` and its AIX path, `DISCGRP` | `ACCTDATA` (updated in place), `SYSTRAN(+1)` |
| 5 | TRANBKP | IDCAMS (`REPROC`) | `TRANSACT.VSAM.KSDS` | `TRANSACT.BKUP(+1)`, then deletes and redefines `TRANSACT.VSAM.KSDS` (empty) and deletes its AIX |
| 6 | COMBTRAN | SORT, IDCAMS | `TRANSACT.BKUP(0)`, `SYSTRAN(0)` | `TRANSACT.COMBINED(+1)`, loaded into `TRANSACT.VSAM.KSDS` |
| 7 | CREASTMT | IDCAMS, SORT, `CBSTM03A` | `TRANSACT`, `CARDXREF`, `ACCTDATA`, `CUSTDATA` | `TRXFL.VSAM.KSDS`, `STATEMNT.PS`, `STATEMNT.HTML` |
| 8 | TRANIDX | IDCAMS | `TRANSACT.VSAM.KSDS` | `TRANSACT.VSAM.AIX` and its path (keys 26 at offset 304, the processed timestamp) |
| 9 | OPENFIL | SDSF (CEMT) | | Opens the files closed in step 1 |

`WAITSTEP` (`COBSWAIT`, wait time in centiseconds in SYSIN) can be placed between jobs.
With the optional module, `CBPAUP0J` follows; see [authorization-purge.md](authorization-purge.md).

### One-time prerequisites

Check these before the first cycle. Missing ones show up as JCL errors, not abends.

- `DEFGDGB` has defined the GDG bases `TRANSACT.BKUP`, `TRANSACT.DALY`, `TRANREPT`,
  `TCATBALF.BKUP`, `SYSTRAN` and `TRANSACT.COMBINED`.
- `DALYREJS` has defined the `DALYREJS` GDG base. The README's initialization table does not
  list this job, but POSTTRAN allocates `AWS.M2.CARDDEMO.DALYREJS(+1)` and fails without it.
- `TRANSACT.VSAM.KSDS` is empty when POSTTRAN starts. `CBTRN02C` opens it `OUTPUT`, and the
  cluster is defined without `REUSE`. In the README sequence, the TRANBKP run in the data
  refresh empties it.
- INTCALC's `PARM='2022071800'` is the date prefix of every interest transaction ID
  (`TRAN-ID` = 10-character PARM + 6-digit counter). Set it to the cycle's business date each
  run, or interest transactions from different runs get the same IDs.
- CREASTMT defines `TRXFL.VSAM.KSDS` on `VOLUMES(TSU023)`; the other jobs use `AWSHJ1`.
  Change both to volumes that exist at your site.

## Before you restart anything

1. Find the failing job and step in the job log. Note the return code or abend code, and the
   `ERROR ...` and `FILE STATUS IS:` lines in SYSOUT.
2. Hold the downstream jobs in the scheduler so nothing runs on partial data.
3. List the GDGs (`LISTCAT ENTRIES(AWS.M2.CARDDEMO.TRANSACT.BKUP) ALL`, likewise
   `SYSTRAN`, `TRANSACT.COMBINED`, `DALYREJS`) and write down the absolute generation names
   (`GnnnnV00`) that exist now. Restarts below refer to generations relative to this point,
   and every extra run shifts `(0)`.

## POSTTRAN (`CBTRN02C`)

| Symptom | Cause | Action |
| :------ | :---- | :----- |
| JCL error on `DALYREJS` | GDG base missing | Run `DALYREJS` once, then resubmit POSTTRAN. Nothing was changed. |
| `ERROR OPENING TRANSACTION FILE`, U0999 | `TRANSACT` is not empty (TRANBKP did not run) or does not exist | Run TRANBKP, then resubmit. |
| `ERROR OPENING ACCOUNT MASTER FILE` or `CROSS REF FILE`, U0999, file status 93 or similar | CICS still has `ACCTDAT` or `CCXREF` open | See [cics-vsam-files.md](cics-vsam-files.md), then resubmit. |
| Open errors on `DALYTRAN` | Input missing or wrong DCB (expects FB 350) | Fix the input, resubmit. |
| Abend after records were processed (`ERROR WRITING TO TRANSACTION FILE`, `ERROR REWRITING TRANSACTION BALANCE FILE`, `ERROR READING ACCOUNT FILE`, ...) | I/O error, out of space, or a duplicate `DALYTRAN-ID` (file status 22 on the transaction write) | Partial update. Follow "Restart after a partial POSTTRAN" below. |
| Return code 4, `TRANSACTIONS REJECTED  :` greater than zero | Some transactions failed validation | Not a failure. The cycle can continue. See [daily-rejects.md](daily-rejects.md). |

A U0999 abend at open time changes nothing: no record has been read, and `DALYREJS(+1)` is
deleted by its `DISP=(NEW,CATLG,DELETE)`.

### Restart after a partial POSTTRAN

By the time of the abend, `CBTRN02C` has already, for every transaction it processed:
added the amount to `ACCT-CURR-BAL` and the cycle credit or debit in `ACCTDATA`, added it to
(or created) the matching `TCATBALF` record, and written it to `TRANSACT`.

1. Restore `ACCTDATA.VSAM.KSDS` and `TCATBALF.VSAM.KSDS` to their state before POSTTRAN.
   - Demo data: rerun `ACCTFILE` and `TCATBALF`, which delete, redefine and reload them from
     `ACCTDATA.PS` and `TCATBALF.PS`.
   - Real data: restore from the backups you took before POSTTRAN. CardDemo does not back up
     `ACCTDATA` itself. `PRTCATBL` unloads `TCATBALF` to `TCATBALF.BKUP(+1)`; run it before
     POSTTRAN if you want a `TCATBALF` restore point. To restore, delete and define the
     cluster with the statements in `TCATBALF.jcl` steps STEP05 and STEP10, then
     `REPRO` from the backup generation. Do not `REPRO` on top of the damaged file:
     POSTTRAN may have created new `TCATBALF` keys.
2. Empty `TRANSACT.VSAM.KSDS`: rerun TRANBKP. It saves the partial file as a new
   `TRANSACT.BKUP` generation, which is harmless here but uses one of the five slots.
3. Fix the cause (for a duplicate key, remove or correct the duplicate `DALYTRAN-ID`).
4. Resubmit POSTTRAN, then continue with INTCALC.

## INTCALC (`CBACT04C`)

`CBACT04C` reads `TCATBALF` in key order. For each account it computes monthly interest per
category from the disclosure group rate (`(TRAN-CAT-BAL * DIS-INT-RATE) / 1200`), writes one
interest transaction per category to `SYSTRAN(+1)`, then rewrites the account: adds the
total interest to `ACCT-CURR-BAL` and sets `ACCT-CURR-CYC-CREDIT` and
`ACCT-CURR-CYC-DEBIT` to zero.

| Symptom | Cause | Action |
| :------ | :---- | :----- |
| `DISCLOSURE GROUP RECORD MISSING` / `TRY WITH DEFAULT GROUP CODE` | No `DISCGRP` record for the account's group, type and category | Informational. The program uses group `DEFAULT`. |
| `ERROR READING DEFAULT DISCLOSURE GROUP`, U0999 | No `DEFAULT` record either | Load `DISCGRP` (run the `DISCGRP` job), then restart as below. |
| `ACCOUNT NOT FOUND:` | `TCATBALF` has an account missing from `ACCTDATA` or `CARDXREF` | Fix the reference data, then restart as below. |
| `ERROR WRITING TRANSACTION RECORD` or `ERROR RE-WRITING ACCOUNT FILE`, U0999 | I/O error or space | Restart as below. |

### Restart after a failed INTCALC

Accounts processed before the abend already carry the interest and have zero cycle totals.
`SYSTRAN(+1)` is deleted by the abend, so those interest transactions are lost while the
balances keep them.

1. Restore `ACCTDATA.VSAM.KSDS` to its state after POSTTRAN. Running INTCALC again on top
   of a partial run charges interest twice. CardDemo has no job that backs up `ACCTDATA`,
   so add an IDCAMS `REPRO` of the cluster to a sequential dataset between POSTTRAN and
   INTCALC if you need this restore point. With demo data, rerun the cycle from the data
   refresh instead.
2. Resubmit INTCALC with the same `PARM` date.

### Known defect: last account

Open issue #64: the account that comes last in `TCATBALF` gets its interest transactions
written to `SYSTRAN`, but its `ACCTDATA` record is never rewritten. The main loop exits on end
of file before the final `1050-UPDATE-ACCOUNT` runs. After INTCALC, that account's balance
does not include the interest and its cycle totals are not reset. Check it by hand until the
program is fixed.

## TRANBKP (after INTCALC)

TRANBKP backs up `TRANSACT` to `TRANSACT.BKUP(+1)` (step `STEP05R`), deletes the cluster and
its AIX (`STEP05`), and defines an empty cluster (`STEP10`, `COND=(4,LT)`).

`STEP05` has no `COND`. If the backup step ends with a return code above 4 without abending,
the cluster is still deleted, and `STEP10` is skipped, so `TRANSACT` no longer exists. The
backup generation is cataloged anyway and may be incomplete.

1. Do not run COMBTRAN.
2. Find the last good backup: the generation before the one this run cataloged
   (`TRANSACT.BKUP(-1)` if nothing else ran since).
3. Define `TRANSACT.VSAM.KSDS` with the `STEP10` statements, `REPRO` the good generation into
   it, and fix the cause of the backup failure.
4. Rerun TRANBKP.

## COMBTRAN

COMBTRAN sorts `TRANSACT.BKUP(0)` (today's posted transactions) together with `SYSTRAN(0)`
(interest) by transaction ID into `TRANSACT.COMBINED(+1)`, then `REPRO`s it into the empty
`TRANSACT.VSAM.KSDS`.

- **`STEP05R` (SORT) fails:** nothing has changed. Fix and resubmit COMBTRAN.
- **`STEP10` (REPRO) fails:** `TRANSACT` may be partly loaded. Delete and define it with the
  TRANBKP `STEP05` and `STEP10` statements, run in their own IDCAMS job. Do not rerun
  TRANBKP itself: it would back up the partial file as a new `TRANSACT.BKUP(0)`, and COMBTRAN
  would then combine the wrong input. Then resubmit COMBTRAN. It writes a new
  `TRANSACT.COMBINED` generation from the same unchanged inputs.

## CREASTMT

CREASTMT can be rerun from the top. `DELDEF01` deletes and redefines `TRXFL.VSAM.KSDS`,
`STEP030` deletes the previous `STATEMNT.PS` and `STATEMNT.HTML`, and every later step has
`COND=(0,NE)`, so a failure stops the job before the statements are written. Fix the cause and
resubmit.

## TRANIDX

TRANIDX defines `TRANSACT.VSAM.AIX`, its path, and builds the index. It expects the AIX not
to exist; TRANBKP `STEP05` deletes it. If you rerun TRANIDX after a partial run, first delete
the AIX (`DELETE AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX ALTERNATEINDEX`, which also removes the
path), then resubmit.

## Finish

1. Run OPENFIL and check the files in CICS ([cics-vsam-files.md](cics-vsam-files.md)).
2. Release the held jobs.
3. Record which generations were created or skipped.
