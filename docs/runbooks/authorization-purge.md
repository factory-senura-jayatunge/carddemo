# Expired authorization purge (CBPAUP0J)

Part of the optional Credit Card Authorizations module (`app/app-authorization-ims-db2-mq`).
`CBPAUP0J` runs the IMS batch program `CBPAUP0C` to delete pending authorizations older than a
number of days, and to keep the authorization summary counts consistent.

## What it runs

From `app/app-authorization-ims-db2-mq/jcl/CBPAUP0J.jcl`:

```
//STEP01  EXEC PGM=DFSRRC00,
//             PARM='BMP,CBPAUP0C,PSBPAUTB'
```

- A BMP, so the IMS control region must be up. PSB `PSBPAUTB`
  (`ims/PSBPAUTB.psb`), database `DBPAUTP0` (`ims/DBPAUTP0.dbd`).
- `STEPLIB` second library is `XXXXXXXX.PROD.LOADLIB`. Replace it with the load library that
  holds `CBPAUP0C` before the first run.
- In the CA7 definitions (`app/scheduler/CardDemo.ca7`), CLOSEFIL triggers `CBPAUP0J`, which
  triggers POSTTRAN.

## Parameters (SYSIN)

`CBPAUP0C` reads one SYSIN record into `PRM-INFO`:

| Columns | Field | Shipped value | Meaning | Default when blank or zero |
| :------ | :---- | :------------ | :------ | :------------------------- |
| 1-2 | `P-EXPIRY-DAYS` | `00` | Delete authorizations at least this many days old | 5 (only when not numeric) |
| 4-8 | `P-CHKP-FREQ` | `00001` | Take an IMS checkpoint after more than this many summaries | 5 |
| 10-14 | `P-CHKP-DIS-FREQ` | `00001` | Display a `CHKP SUCCESS` line every this many checkpoints | 10 |
| 16 | `P-DEBUG-FLAG` | `Y` | `Y` displays every read and delete | |

**The shipped value `00` deletes every pending authorization**, because `00` is numeric and
any authorization is at least zero days old. Set the days you actually want, for example
`05,00100,00010,N`, before scheduling the job against data you care about. Debug `Y` writes
a line for every read and the account ID for every delete to SYSOUT; use `N` outside testing.

## What it does

For each authorization summary segment (`PAUTSUM0`, read with `GN`), `CBPAUP0C` reads its
detail segments (`PAUTDTL1`, read with `GNP`) and computes the age as today's `YYDDD`
(`ACCEPT ... FROM DAY`) minus the authorization date (stored as `99999 - PA-AUTH-DATE-9C`).
When the age is at least the expiry days, it deletes the detail (`DLET`) and subtracts it from
the summary's approved or declined count and amount in working storage. It takes an IMS
checkpoint (`CHKP`) once more than `P-CHKP-FREQ` summaries have been read since the last one,
and once at the end.

Behaviour to know about, from the code:

- The summary is deleted when `PA-APPROVED-AUTH-CNT` is zero or less. The condition tests the
  approved count twice and never the declined count. Deleting a summary also deletes every
  detail still under it, including declined or unexpired ones.
- The adjusted counts and amounts are never written back (there is no `REPL`), so summaries
  that are kept still show their old totals.

Because the age is a difference of `YYDDD` values, it is not a true day count across a year
end: an authorization from late December looks hundreds of days old on 1 January and is purged
early.

## Check a run

The job ends with:

```
# TOTAL SUMMARY READ  :
# SUMMARY REC DELETED :
# TOTAL DETAILS READ  :
# DETAILS REC DELETED :
```

Return code 0 means it completed. Compare the deleted counts with what you expected from the
expiry days. Pending Authorization Summary (CPVS) in CICS should no longer list the deleted
authorizations.

## Failures

On any IMS error, `CBPAUP0C` displays the failing call and status, sets return code 16 and
ends. It does not abend.

| Message | Meaning | Action |
| :------ | :------ | :----- |
| `AUTH SUMMARY READ FAILED  :` with DIBSTAT | `GN` on the summary segment failed | Check the IMS status code and that `DBPAUTP0` is available to the BMP |
| `AUTH DETAIL READ FAILED  :` | `GNP` on the detail failed | As above |
| `AUTH DETAIL DELETE FAILED :` or `AUTH SUMMARY DELETE FAILED :` | `DLET` failed | Check the database is not stopped or read-only (`/DIS DB DBPAUTP0`) |
| `CHKP FAILED: DIBSTAT -` | Checkpoint failed | Check the IMS log and region |
| JCL or scheduling errors from `DFSRRC00` | PSB not generated, control region down, wrong `STEPLIB` | Fix the environment |

### Restart

Because `CBPAUP0C` ends with `GOBACK` rather than an abend, IMS treats the end as normal and
commits every delete made up to the failure, not only those before the last checkpoint.
Rerunning is safe: the next run reads the remaining segments and applies the same age test.
Fix the cause and resubmit with the same SYSIN.

Because the job ends with return code 16 instead of abending, make sure your scheduler treats
16 as a failure, so the next job in the chain does not start.
