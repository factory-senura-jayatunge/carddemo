# VSAM files left open by CICS

CICS and batch share the CardDemo VSAM clusters. The clusters are defined with
`SHAREOPTIONS(2 3)`, which allows one updater across regions and jobs. While CICS has a file
open, batch jobs that update, delete or redefine it fail. `CLOSEFIL` and `OPENFIL` hand the
files over between CICS and batch.

## What CLOSEFIL and OPENFIL do

Both jobs run `PGM=SDSF` and send MVS `MODIFY` commands to the CICS region `CICSAWSA`.
(The README job table lists them as IEFBR14; the JCL uses SDSF.)

| CICS file | Dataset | Closed by CLOSEFIL | Also closed and reopened by |
| :-------- | :------ | :----------------: | :-------------------------- |
| `TRANSACT` | `AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS` | Yes | `TRANFILE` |
| `CCXREF` | `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` | Yes | |
| `ACCTDAT` | `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` | Yes | |
| `CXACAIX` | `AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH` | Yes | `TRANFILE` |
| `USRSEC` | `AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS` | Yes | |
| `CARDDAT` | `AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS` | No | `CARDFILE` |
| `CARDAIX` | `AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.PATH` | No | `CARDFILE` |
| `CUSTDAT` | `AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS` | No | `CUSTFILE` |

The file names and datasets are from `app/csd/CARDDEMO.CSD`. CLOSEFIL issues, for example:

```
/F CICSAWSA,'CEMT SET FIL(ACCTDAT ) CLO'
```

and OPENFIL the same commands with `OPE`.

## Symptoms

- A batch job fails to open a file that CICS owns: `ERROR OPENING ACCOUNT MASTER FILE`
  (POSTTRAN, INTCALC) or `ERROR OPENING CROSS REF FILE`, then U0999. VSAM reports file
  status 93 (resource not available) in this case.
- An IDCAMS `DELETE` in ACCTFILE, XREFFILE, TRANBKP or DUSRSECJ fails because the cluster is
  in use, so the following `DEFINE` fails with a duplicate name.
- After the cycle, online transactions fail with file `NOTOPEN` or `DISABLED` conditions,
  because OPENFIL did not run or did not take effect.

## Diagnose

In the CICS region:

```
CEMT INQUIRE FILE(*)
```

Each CardDemo file shows `Ope`/`Clo` and `Ena`/`Dis`/`Une`. In the CLOSEFIL or OPENFIL job
output, check the `CMDOUT` and `ISFOUT` sysouts. Each command should be accepted. If the job
ran but nothing changed, the usual causes are:

- the region is not named `CICSAWSA`: update the `/F` commands in both jobs;
- the job's user lacks authority to issue `MODIFY` through SDSF: grant it, or issue the
  CEMT commands from a terminal;
- a transaction was still using the file. CICS closes the file only when current tasks finish
  with it. Wait and inquire again.

## Fix

**Before batch:** close the files the job needs.

```
CEMT SET FILE(ACCTDAT) CLOSED
CEMT SET FILE(CCXREF)  CLOSED
CEMT SET FILE(CXACAIX) CLOSED
CEMT SET FILE(TRANSACT) CLOSED
CEMT SET FILE(USRSEC)  CLOSED
```

or resubmit CLOSEFIL. Add `CARDDAT`, `CARDAIX` and `CUSTDAT` if you run CARDFILE or CUSTFILE
separately, since CLOSEFIL does not close them. Then resubmit the failed job. A job that failed
at open time changed nothing (see [batch-cycle-restart.md](batch-cycle-restart.md)).

**After batch:** open and enable the files.

```
CEMT SET FILE(ACCTDAT) OPEN ENABLED
```

for each file, or resubmit OPENFIL and check `CEMT INQUIRE FILE(*)` again. Sign on with CC00
and open Account View (CAVW) to confirm.

## Prevent

- Keep CLOSEFIL first and OPENFIL last in every chain that updates VSAM. The CA7 and Control-M
  definitions in `app/scheduler` already wrap their chains this way.
- Do not run jobs that redefine files (ACCTFILE, CARDFILE, XREFFILE, CUSTFILE, TRANBKP,
  TRANIDX) while CICS users are signed on.
