# Jobs, procedures, CSD and schedules: operations view

This folder holds one spec per JCL member of `app/jcl` (38), per cataloged procedure of `app/proc` (REPROC, TRANREPT-PROC), for the CICS definitions (`CARDDEMO-CSD.md`) and for the two schedules (`SCHED-CA7.md`, `SCHED-CONTROLM.md`): 43 specs, area g (`g-jobs-ops`). The JCL, CSD and scheduler members of the three optional modules are specified by areas e and f. This page has no front matter and the checker skips it; the facts here are cited from the specs and their sources. It covers what an operator or a DAG designer needs across jobs: which jobs exist and what they are for, the five different batch cycle orders in the repository, the scheduler findings, and the mainframe samples that are not application behaviour.

## Job catalogue

| Group | Specs | What they do |
| :-- | :-- | :-- |
| CICS hand-over | `CLOSEFIL.md`, `OPENFIL.md`, `WAITSTEP.md` | Close and reopen five CICS files (`TRANSACT`, `CCXREF`, `ACCTDAT`, `CXACAIX`, `USRSEC`) through SDSF `MODIFY` commands; WAITSTEP is a fixed 36-second pause. None of them checks that CICS acted (ERR-JCLOSE-001, ERR-JOPEN-001, DEF-JWAIT-003). |
| Batch cycle | `POSTTRAN.md`, `INTCALC.md`, `TRANBKP.md`, `COMBTRAN.md`, `CREASTMT.md`, `TXT2PDF1.md`, `TRANIDX.md` | Post the daily transactions (CBTRN02C), compute interest (CBACT04C), back up and empty the transaction master, merge posted and interest transactions back into it, print statements (CBSTM03A) and convert them to PDF, rebuild the timestamp alternate index. |
| Reports and listings | `TRANREPT.md`, `TRANREPT-PROC.md`, `PRTCATBL.md`, `READACCT.md`, `READCARD.md`, `READCUST.md`, `READXREF.md` | Transaction report by date range (batch job, and the procedure the online report function submits through TDQ `JOBS`), category balance listing, and four diagnostic readers that print every record of a master. |
| Master and reference reloads | `ACCTFILE.md`, `CARDFILE.md`, `XREFFILE.md`, `CUSTFILE.md`, `TRANFILE.md`, `TRANTYPE.md`, `TRANCATG.md`, `DISCGRP.md`, `TCATBALF.md`, `DUSRSECJ.md` | Delete, define and load a VSAM file from the shipped flat file (or in-stream records for DUSRSECJ). Demo data refresh; running one in production replaces live data (for TCATBALF: every running balance, DEF-JTCAT-002). |
| One-time set-up | `DEFGDGB.md`, `DEFGDGD.md`, `DALYREJS.md`, `REPTFILE.md`, `CBADMCDJ.md`, `DEFCUST.md` | GDG bases, CSD definitions with DFHCSDUP, an obsolete customer cluster definition. |
| Migration and transfer | `CBEXPORT-JOB.md`, `CBIMPORT-JOB.md`, `FTPJCL.md`, `INTRDRJ1.md`, `INTRDRJ2.md`, `ESDSRRDS.md` | Export and import of all masters as one multi-record file, FTP and internal-reader samples, ESDS/RRDS samples. |
| Procedure | `REPROC.md` | IDCAMS `REPRO` from `FILEIN` to `FILEOUT`, used by TRANBKP, PRTCATBL and TRANREPT. |
| Online definitions | `CARDDEMO-CSD.md` | 64 `DEFINE`s: 18 transactions, 18 programs, 17 mapsets, 8 files, 2 libraries, TDQ `JOBS`. |
| Schedules | `SCHED-CA7.md`, `SCHED-CONTROLM.md` | Two scheduler definitions that disagree with each other and with the README (below). |

Neither schedule runs ACCTFILE, CARDFILE, XREFFILE, CUSTFILE, TRANFILE, DUSRSECJ or TRANIDX; they are in the README and the demo scripts. ESDSRRDS, CBEXPORT, CBIMPORT, DEFCUST, REPTFILE, DALYREJS, FTPJCL, INTRDRJ1, INTRDRJ2, CBADMCDJ, TRANREPT, DEFGDGB and DEFGDGD are in no schedule and no script and run only by hand (INTRDRJ2 is also submitted by INTRDRJ1).

## Five batch cycle orders

The repository describes the batch cycle five times, and no two agree. Numbers are the position in each source; "c1" to "c4" are the CA-7 chains (`SCHED-CA7.md` FLOW-JCA7-001 to FLOW-JCA7-004); D, W and M are the Control-M daily, weekly and monthly folders (`SCHED-CONTROLM.md`).

| Job | README table (`README.md:213-235`) | Runbook (`docs/runbooks/batch-cycle-restart.md:11-24`) | `scripts/run_full_batch.sh` | CA-7 (`app/scheduler/CardDemo.ca7`) | Control-M (`app/scheduler/CardDemo.controlm`) |
| :-- | :-- | :-- | :-- | :-- | :-- |
| CLOSEFIL | 1 | 1 | Job 1 (`:12-13`) | first of c1, c2, c3; in c4 | first of D, W, M |
| ACCTFILE, CARDFILE, XREFFILE, CUSTFILE | 2-5 | 2 (demo only) | Jobs 2-5 (`:16-26`) | no | no |
| TRANBKP (reset) | 6 "Creates Transaction database" | 2 (demo only) | Job 6 (`:28-29`) | no | D, every day |
| TRANEXTR (Db2 module) | 7 | no | no | no | W (Saturday) |
| TRANCATG | 8 | 2 (demo only) | no | c2 | no |
| TRANTYPE | 9 | 2 (demo only) | Job 9 (`:37-38`) | c2 | no |
| DISCGRP | 10 | 2 (demo only) | Job 7 (`:31-32`) | no | W (Saturday) |
| TCATBALF | 11 | 2 (demo only) | Job 8 (`:34-35`) | c2 | no |
| DUSRSECJ | 12 | 2 (demo only) | "Job 11" (`:40-41`) | no | no |
| MNTTRDB2 (Db2 module) | no | no | no | no | W (never ordered) |
| CBPAUP0J (IMS module) | 21, last | after the cycle (`:24`) | no | c1, before POSTTRAN | no |
| POSTTRAN | 13 | 3 | Job 12 (`:46-47`) | c1 | no |
| INTCALC | 14 | 4 | Job 13 (`:50-51`) | no | M (never ordered) |
| TRANBKP (backup) | 15 "Backup Transaction database" | 5 | Job 14 (`:54-55`) | no | no |
| COMBTRAN | 16 | 6 | Job 15 (`:57-58`) | no | M, without TRANBKP before it |
| CREASTMT | 17 | 7 | no | c3, right after CLOSEFIL | no |
| TXT2PDF1 | no | no | no | c3 | no |
| TRANIDX | 18 | 8 | "Job 15" again (`:61-62`) | no | no |
| READACCT, READCARD, READCUST, READXREF | no | no | no | c2 | no |
| PRTCATBL | no | before a restart | no | c4 | no |
| WAITSTEP | 20, after OPENFIL | "can be placed between jobs" (`:23`) | `sleep` instead | before every OPENFIL and some CLOSEFILs | before every OPENFIL |
| OPENFIL | 19 | 9 | Job 16 (`:65-66`) | last of every chain | last of D, W, M |

What the differences mean for the target:

- **Only the README, the runbook and the full-batch script describe a complete cycle** (post, interest, back up, combine, index), and the runbook marks the reload block as demo only. The script leaves out TRANCATG and CREASTMT and numbers two jobs 15 and none 10. The README lists WAITSTEP after OPENFIL and CBPAUP0J last; both are notes rather than positions.
- **CA-7 has no interest cycle.** It posts (after the optional IMS purge), reloads three reference files, runs diagnostic readers, prints statements without waiting for posting and lists category balances. It has no calendar schedule, its merged triggers fan out and loop, and two job names are nine characters long (DEF-JCA7-001 to DEF-JCA7-004). It reads as an illustration copied from an older listing (dated 2006).
- **Control-M has no posting.** It backs up and empties the transaction master every day without reloading it, refreshes disclosure groups and the Db2 type extract on Saturdays without loading the extract into VSAM, and computes interest in a folder that is never ordered (DEF-JCTLM-001, DEF-JCTLM-002, DEF-JCTLM-005). It was uploaded on 9 September 2025.
- **The local runner** (`tools/carddemo.py`, `make jobs`) runs single jobs, not a cycle; TRANREPT and CBEXPORT run POSTTRAN first and CBIMPORT runs CBEXPORT first.

**Open question for the DAG design (Q-JCA7-001, Q-JCTLM-001):** which cycle should the Airflow DAGs implement: the README sequence, the runbook order, the CA-7 chains, the Control-M folders, or the full-batch script? A consistent reading of all sources is the runbook order (CLOSEFIL, POSTTRAN, INTCALC, TRANBKP, COMBTRAN, CREASTMT, TRANIDX, OPENFIL, with the reload block only for demo resets), but the frequency of interest (Q-JINTC-002, Q-JCTLM-002), the place of statements (Q-JSTMT-001) and of the authorization purge (Q-JCA7-003) need a business decision.

## Scheduler findings

CA-7 (`SCHED-CA7.md`):

- A printed `LJOB` listing with 30 entries for 17 job names; no calendar, so every chain starts on demand (DEF-JCA7-001).
- Triggers are per job name: one CLOSEFIL completion starts chains 1, 2 and 3 together, and WAITSTEP triggers both OPENFIL and CLOSEFIL, which loops (DEF-JCA7-002, DEF-JCA7-006). OPENFIL's "COMP TRIGGERS" flag is `N` but it triggers CLOSEFIL (`app/scheduler/CardDemo.ca7:138, 532`).
- `CLOSEFIL1` and `CLOSEFIL2` are not valid job names (DEF-JCA7-003).
- Every listing has the same statistics (1058 runs, last run 17 July 2006), so it is a template (DEF-JCA7-004).
- POSTTRAN runs only after the optional CBPAUP0J and without TRANBKP emptying `TRANSACT` first (DEF-JCA7-007, DEF-JPOST-002).

Control-M (`SCHED-CONTROLM.md`):

- No POSTTRAN anywhere; the daily TRANBKP empties the transaction master and deletes its index every day (DEF-JCTLM-001).
- MNTTRDB2 and the monthly folder have no scheduling days (DEF-JCTLM-002).
- Folder name `WEEKLY-TransactionTypesDBRefresh` is used twice and parent folder attributes are crossed (DEF-JCTLM-003).
- Two Saturday smart folders consume and delete the same MNTTRDB2 condition (DEF-JCTLM-004).
- TRANEXTR refreshes the type and category flat files but TRANTYPE and TRANCATG never load them (DEF-JCTLM-005).
- The monthly interest folder has no posting before INTCALC, no TRANBKP before COMBTRAN and no TRANIDX (DEF-JCTLM-006, DEF-JCOMB-002).
- No failure handling: no `ON`/`DO`, no shouts; a failed job leaves the CICS files closed (ERR-JCTLM-001).

Both schedules use WAITSTEP as a fixed pause instead of checking that CICS closed or opened the files, and neither links to the other.

## Operations notes

- **CICS hand-over.** CLOSEFIL closes five of the eight CICS files. CARDFILE, CUSTFILE and TRANFILE close and reopen their own files with in-job SDSF steps; CARDDAT, CARDAIX and CUSTDAT are never closed by CLOSEFIL (DEF-JCLOSE-003). The CSD files are `RECOVERY(NONE)` (ERR-JCSD-001), so online updates are not backed out.
- **Restart.** The runbook `docs/runbooks/batch-cycle-restart.md` gives the restart rules (restore from `TRANSACT.BKUP`, `TCATBALF.BKUP`); the job specs record where it is stale (for example DEF-JINTC-003) and the area entities file lists the corrections.
- **Generations.** The cycle selects its inputs by relative generation (`BKUP(0)`, `SYSTRAN(0)`), so an extra backup between TRANBKP and COMBTRAN changes what is combined (DEF-JCOMB-001).
- **Business date.** INTCALC's `PARM='2022071800'` and TRANREPT's date cards are hard-coded (DEF-JINTC-001); the local runner fixes the business date at 2022-07-06.

## Mainframe samples (not specified)

`samples/jcl` and `samples/proc` are compile and test samples for a mainframe installation. They carry no application behaviour, are not counted by `check_spec.py --require-all`, and have no spec. What they contain:

| Member | Purpose | Notes |
| :-- | :-- | :-- |
| `samples/jcl/BATCMP.jcl` | Compile and link one batch COBOL program with procedure BUILDBAT (`:42`). | Template: replace `BATCHPGM` and `HLQ` (`:4-6`). |
| `samples/jcl/CICCMP.jcl` | Compile a CICS COBOL program with BUILDONL (`:41`), then `NEWCOPY` through SDSF (`:45`). | Run after the maps are compiled. |
| `samples/jcl/CICDBCMP.jcl` | Compile a CICS Db2 program with BLDCIDB2 (`:54`). | Needs the Db2 subsystem ID (`:7`). |
| `samples/jcl/BMSCMP.jcl` | Assemble a BMS map with BUILDBMS (`:38`), then `NEWCOPY` (`:42`). | |
| `samples/jcl/IMSMQCMP.jcl` | Translate, compile and link a CICS IMS MQ program with in-line steps (`:28-100`). | |
| `samples/jcl/LISTCAT.jcl` | `LISTCAT LEVEL(AWS.M2.CARDDEMO) ALL` into `AWS.M2.CARDDEMO.LISTCAT` (`:21-36`). | The repository copy of such a listing is `app/catlg/LISTCAT.txt`. |
| `samples/jcl/RACFCMDS.jcl` | RACF commands in TSO batch: add transaction `CT02` to profile `CARD` (`:24`), connect a user to a group (`:29`). | The null statement `//` at `:26` ends the job, so the `CONNECT` is never run. |
| `samples/jcl/REPRTEST.jcl` | Test of procedure REPROC: `TRANSACT` to `TRANSACT.BKUP(+1)` (`:22-33`). | Same as TRANBKP's first step. |
| `samples/jcl/SORTTEST.jcl` | Test of the TRANREPT date filter for one day (`:19-35`). | Writes `TRANSACT.DALY(+1)`. |
| `samples/proc/BUILDBAT.prc` | Batch compile (`IGYCRCTL`), listing copy, link (`HEWL`). | `PROC` label `BLDBAT` (`:24`). |
| `samples/proc/BUILDONL.prc` | CICS compile and link. | `PROC` label `BLDONL` (`:25`). |
| `samples/proc/BLDCIDB2.prc` | Db2 precompile, CICS translate, compile, link, bind package and plan (`:46-158`). | `PROC` label `BLDONL` (`:25`), the same as BUILDONL. |
| `samples/proc/BUILDBMS.prc` | BMS map assembly (physical map and DSECT copybook). | |

As with `app/proc/TRANREPT.prc` (DEF-JTRPTP-001), the `PROC` labels of the sample procedures differ from their member names; z/OS finds cataloged procedures by member name, so this matters only for tools and in-stream copies.
