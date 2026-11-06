# Jobs, procedures, CSD and schedules: operations view

This folder holds one spec per JCL member of `app/jcl` (38), per cataloged procedure of `app/proc` (REPROC, TRANREPT-PROC), for the CICS definitions (`CARDDEMO-CSD.md`) and for the two schedules (`SCHED-CA7.md`, `SCHED-CONTROLM.md`): 43 specs, area g (`g-jobs-ops`). The JCL, CSD and scheduler members of the three optional modules are specified by areas e and f. This page has no front matter and the checker skips it; the facts here are cited from the specs and their sources. It covers what an operator or a DAG designer needs across jobs: which jobs exist and what they are for, the seven different batch cycle orders in the repository, the decision the target scheduler needs, the scheduler findings, and the mainframe samples that are not application behaviour.

## Job catalogue

| Group | Specs | What they do |
| :-- | :-- | :-- |
| CICS hand-over | `CLOSEFIL.md`, `OPENFIL.md`, `WAITSTEP.md` | Close and reopen five CICS files (`TRANSACT`, `CCXREF`, `ACCTDAT`, `CXACAIX`, `USRSEC`) through SDSF `MODIFY` commands; WAITSTEP is a fixed 36-second pause (`SYSIN` value `00003600` centiseconds, FLOW-JWAIT-001). None of them checks that CICS acted (ERR-JCLOSE-001, ERR-JOPEN-001, DEF-JWAIT-003). |
| Batch cycle | `POSTTRAN.md`, `INTCALC.md`, `TRANBKP.md`, `COMBTRAN.md`, `CREASTMT.md`, `TXT2PDF1.md`, `TRANIDX.md` | Post the daily transactions (CBTRN02C), compute interest (CBACT04C), back up and empty the transaction master, merge posted and interest transactions back into it, print statements (CBSTM03A) and convert them to PDF, rebuild the timestamp alternate index. |
| Reports and listings | `TRANREPT.md`, `TRANREPT-PROC.md`, `PRTCATBL.md`, `READACCT.md`, `READCARD.md`, `READCUST.md`, `READXREF.md` | Transaction report by date range (batch job, and the procedure the online report function submits through TDQ `JOBS`), category balance listing, and four diagnostic readers that print every record of the account, card, customer and cross-reference files; READACCT also writes three demo extract files (`app/jcl/READACCT.jcl:37-48`). |
| Master and reference reloads | `ACCTFILE.md`, `CARDFILE.md`, `XREFFILE.md`, `CUSTFILE.md`, `TRANFILE.md`, `TRANTYPE.md`, `TRANCATG.md`, `DISCGRP.md`, `TCATBALF.md`, `DUSRSECJ.md` | Delete, define and load a VSAM file from the shipped flat file (or in-stream records for DUSRSECJ). Demo data refresh; running one in production replaces live data (for TCATBALF: every running balance, DEF-JTCAT-002). |
| One-time set-up | `DEFGDGB.md`, `DEFGDGD.md`, `DALYREJS.md`, `REPTFILE.md`, `CBADMCDJ.md`, `DEFCUST.md` | GDG bases, CSD definitions with DFHCSDUP, an obsolete customer cluster definition. |
| Migration and transfer | `CBEXPORT-JOB.md`, `CBIMPORT-JOB.md`, `FTPJCL.md`, `INTRDRJ1.md`, `INTRDRJ2.md`, `ESDSRRDS.md` | Export and import of all masters as one multi-record file, FTP and internal-reader samples, ESDS/RRDS samples. |
| Procedure | `REPROC.md` | IDCAMS `REPRO` from `FILEIN` to `FILEOUT`, used by TRANBKP, PRTCATBL and TRANREPT. |
| Online definitions | `CARDDEMO-CSD.md` | 64 `DEFINE`s: 18 transactions, 18 programs, 17 mapsets, 8 files, 2 libraries, TDQ `JOBS`. |
| Schedules | `SCHED-CA7.md`, `SCHED-CONTROLM.md` | Two scheduler definitions that disagree with each other and with the README (below). |

Neither schedule runs ACCTFILE, CARDFILE, XREFFILE, CUSTFILE, TRANFILE, DUSRSECJ or TRANIDX; they are in the README and the demo scripts. ESDSRRDS, CBEXPORT, CBIMPORT, DEFCUST, REPTFILE, DALYREJS, FTPJCL, INTRDRJ1, INTRDRJ2, CBADMCDJ, TRANREPT, DEFGDGB and DEFGDGD are in no schedule and no script and run only by hand (INTRDRJ2 is also submitted by INTRDRJ1). The report function also runs online: CR00 (CORPT00C) submits a job that calls the procedure TRANREPT, not the TRANREPT member (`app/cbl/CORPT00C.cbl:94, 515-518`, FLOW-JTRPTP-001).

## Seven batch cycle orders

The repository describes the batch cycle seven times, and no two agree: the README table, the restart runbook, three demo scripts and the two schedules. The runbook says its order is taken from the README and `run_full_batch.sh` (`docs/runbooks/batch-cycle-restart.md:9`), so it is not an independent source. The scripts submit each job by FTP to JES with fixed `sleep`s between some of them (`scripts/run_full_batch.sh:11-66`); submission is asynchronous, so a script's order is not an enforced dependency order. A fourth script, `scripts/remote_refresh.sh`, only reloads the masters (CLOSEFIL, ten reloads, OPENFIL, `:12-50`) and is not a cycle.

Numbers are the position in each source. For CA-7, the column gives what a run under schedule ID 030 does, read with the schedule ID rule of `SCHED-CA7.md` (FLOW-JCA7-005, FLOW-JCA7-006): a trigger fires only under its own schedule ID and the triggered job keeps that ID (platform-inferred). D, W and M are the Control-M daily, weekly and monthly folders (`SCHED-CONTROLM.md`).

| Job | README table (`README.md:213-235`) | Runbook (`docs/runbooks/batch-cycle-restart.md:11-24`) | `run_full_batch.sh` | `run_posting.sh` | `run_interest_calc.sh` | CA-7, SCHID 030 (`app/scheduler/CardDemo.ca7`) | Control-M (`app/scheduler/CardDemo.controlm`) |
| :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- |
| CLOSEFIL | 1 | 1 | Job 1 (`:12-13`) | Job 1 (`:12-13`) | Job 1 (`:14-15`) | starts the run; started again by every WAITSTEP | first of D, W (DISCGRP folder only), M |
| ACCTFILE | 2 | 2 (demo only) | Job 2 (`:16-17`) | Job 2 (`:16-17`) | no | no | no |
| CARDFILE, XREFFILE, CUSTFILE | 3-5 | 2 (demo only) | Jobs 3-5 (`:19-26`) | no | no | no | no |
| TRANBKP (reset) | 6 "Creates Transaction database" | 2 (demo only) | Job 6 (`:28-29`) | Job 4 (`:22-23`) | no | no | D, every day |
| TRANEXTR (Db2 module) | 7 | no | no | no | no | no | W (Saturday, waits for MNTTRDB2) |
| TRANCATG | 8 | 2 (demo only) | no | no | no | only under SCHID 031 | no |
| TRANTYPE | 9 | 2 (demo only) | Job 9 (`:37-38`) | no | no | right after CLOSEFIL | no |
| DISCGRP | 10 | 2 (demo only) | Job 7 (`:31-32`) | no | no | no | W (Saturday, waits for MNTTRDB2) |
| TCATBALF | 11 | 2 (demo only) | Job 8 (`:34-35`) | Job 3 (`:19-20`) | no | only under SCHID 032 | no |
| DUSRSECJ | 12 | 2 (demo only) | "Job 11" (`:40-41`) | no | no | no | no |
| MNTTRDB2 (Db2 module) | no | no | no | no | no | no | own folder, never ordered |
| CBPAUP0J (IMS module) | 21, last | after the cycle (`:24`) | no | no | no | right after CLOSEFIL, before POSTTRAN | no |
| POSTTRAN | 13 | 3 | Job 12 (`:46-47`) | Job 5 (`:25-26`) | no | after CBPAUP0J | no |
| INTCALC | 14 | 4 | Job 13 (`:50-51`) | no | Job 2 (`:18-19`) | no | M (never ordered) |
| TRANBKP (backup) | 15 "Backup Transaction database" | 5 | Job 14 (`:54-55`) | no | Job 3 (`:22-23`) | no | no |
| COMBTRAN | 16 | 6 | "Job 15" (`:57-58`) | no | Job 4 (`:25-26`) | no | M, without TRANBKP before it |
| CREASTMT | 17 | 7 | no | no | no | right after CLOSEFIL, in parallel with POSTTRAN | no |
| TXT2PDF1 | no | no | no | no | no | after CREASTMT | no |
| TRANIDX | 18 | 8 | "Job 15" again (`:61-62`) | Job 6 (`:29-30`) | Job 5 (`:29-30`) | no | no |
| READACCT, READCARD, READCUST, READXREF | no | no | no | no | no | READACCT right after CLOSEFIL, in parallel with POSTTRAN; READCARD, READCUST and READXREF in a chain after it | no |
| PRTCATBL | no | optional, before POSTTRAN, as a `TCATBALF` restore point (`:77-78`) | no | no | no | only under SCHID 031 | no |
| WAITSTEP | 20, after OPENFIL | "can be placed between jobs" (`:23`) | `sleep` instead | `sleep` instead | `sleep` instead | at the end of every path | before every OPENFIL |
| OPENFIL | 19 | 9 | Job 16 (`:65-66`) | Job 7 (`:33-34`) | Job 6 (`:33-34`) | after every WAITSTEP | last of D, W (DISCGRP folder only), M |

What the differences mean for the target:

- **The README, the runbook and the full-batch script describe a complete cycle** (post, interest, back up, combine, index), and the two smaller scripts together describe the same cycle split into posting and interest. The runbook marks the reload block as demo only, but that block also holds the TRANBKP reset that empties `TRANSACT` before POSTTRAN (below). The full-batch script leaves out TRANCATG and CREASTMT and numbers two jobs 15 and none 10. The posting script reloads the account master and the category balances (demo data) before posting. The README lists WAITSTEP after OPENFIL and CBPAUP0J last; both are notes rather than positions.
- **CA-7 has no interest cycle.** Under schedule ID 030 it starts the purge and posting, the transaction type reload, the four readers and the statements at the same time from one CLOSEFIL, and every WAITSTEP closes the files again and restarts the round (DEF-JCA7-002, DEF-JCA7-006). The category and category balance reloads and the balance listing run only under schedule IDs 031 and 032, which loop without end (DEF-JCA7-008). What starts a run is not in the listing (DEF-JCA7-001, DEF-JCA7-009), and two job names are nine characters long (DEF-JCA7-003). It reads as an illustration copied from an older listing (dated 2006, DEF-JCA7-004).
- **Control-M has no posting.** It backs up and empties the transaction master every day without reloading it (DEF-JCTLM-001); the Saturday folders for disclosure groups and the Db2 type extract wait for MNTTRDB2, which is never ordered, so they run only if an operator forces MNTTRDB2; interest is in a folder that is never ordered (DEF-JCTLM-002, DEF-JCTLM-005). It was uploaded on 9 September 2025.
- **The local runner** (`tools/carddemo.py`, `make jobs`) runs single jobs, not a cycle; TRANREPT and CBEXPORT run POSTTRAN first and CBIMPORT runs CBEXPORT first.

## Decision for the target scheduler (Q-JCA7-001, Q-JCTLM-001)

Which cycle should the Airflow DAGs implement? Every source must be changed to be usable, so the choice is which one to start from. Facts that hold for every option:

- POSTTRAN opens `TRANSACT` for output and needs it empty (`app/cbl/CBTRN02C.cbl:256`, PINV-g-jobs-ops-02 in `../areas/g-jobs-ops-interactions.md`), and COMBTRAN leaves it loaded at the end of the cycle (`app/jcl/COMBTRAN.jcl:41-48`). A cycle that runs more than once therefore needs a TRANBKP reset before POSTTRAN; the README does it at position 6, the posting script as Job 4 and Control-M every day.
- With a reset in every cycle, `TRANSACT` holds only the latest cycle's transactions; older ones exist only in the `TRANSACT.BKUP` and `TRANSACT.COMBINED` generations, five of each (`app/jcl/DEFGDGB.jcl:24-28, 54-58`, PINV-g-jobs-ops-04).
- INTCALC charges `balance × rate / 1200` per run (`app/cbl/CBACT04C.cbl:466`), a monthly rate: run in every daily cycle, it charges a month's interest each day.
- Nothing in any source checks that CICS really closed or opened the files; WAITSTEP and the scripts' `sleep`s only wait.

| Option | Order | What it implies |
| :-- | :-- | :-- |
| A. README table (and `run_full_batch.sh`) | CLOSEFIL, the ten reloads (with the TRANBKP reset), POSTTRAN, INTCALC, TRANBKP, COMBTRAN, CREASTMT, TRANIDX, OPENFIL | Every cycle resets all masters, balances and users to the shipped sample data: right for a demo, destructive in production. Interest every cycle. The script variant also has no completion checks and leaves out TRANCATG and CREASTMT. |
| B. Runbook order as written | CLOSEFIL, POSTTRAN, INTCALC, TRANBKP, COMBTRAN, CREASTMT, TRANIDX, OPENFIL (reload block skipped) | Runs once on fresh data, then fails: the second POSTTRAN finds `TRANSACT` loaded. Usable only with a TRANBKP reset added before POSTTRAN, which gives option F. Interest every cycle. |
| C. Split cycle (`run_posting.sh` daily, `run_interest_calc.sh` periodic) | Posting: CLOSEFIL, TRANBKP, POSTTRAN, TRANIDX, OPENFIL. Interest: CLOSEFIL, INTCALC, TRANBKP, COMBTRAN, TRANIDX, OPENFIL | Interest at its own frequency, which matches the monthly rate and the Control-M folder names. The ACCTFILE and TCATBALF reloads in the posting script must be dropped. COMBTRAN reloads only the last posting day's transactions plus the interest, so earlier days survive only in the five backup generations. No statements in either script. |
| D. CA-7 listing | Under 030: CLOSEFIL, then CBPAUP0J and POSTTRAN, TRANTYPE, the readers and CREASTMT in parallel, WAITSTEP, OPENFIL and CLOSEFIL again | Not runnable as written: no interest, backup or combine, no reset before POSTTRAN, posting gated by an optional-module job, statements produced while posting runs, endless rounds. Only the close, run, pause, reopen pattern carries over. |
| E. Control-M folders | Daily: CLOSEFIL, TRANBKP, WAITSTEP, OPENFIL. Monthly: CLOSEFIL, INTCALC, COMBTRAN, WAITSTEP, OPENFIL (never ordered). Weekly: DISCGRP or TRANEXTR, blocked on MNTTRDB2 | Not runnable as written: no posting at all, so the daily backup empties the master and, on a day with no online writes, most likely deletes it (PINV-g-jobs-ops-06); monthly interest without a backup before COMBTRAN; weekly refreshes never start. The daily/weekly/monthly split is the only part with business meaning. |
| F. Derived from the data dependencies | CLOSEFIL, TRANBKP (reset), POSTTRAN, INTCALC (at the chosen frequency), TRANBKP, COMBTRAN, then CREASTMT and TRANIDX, OPENFIL; reloads, TRANREPT, PRTCATBL and the readers on demand | The README cycle without the demo reloads, ordered by the file hand-offs and invariants in `../areas/g-jobs-ops-interactions.md` (PINV-g-jobs-ops-01 to 06) instead of by any one source. Needs the decisions below; DAG tasks replace WAITSTEP with a check that CICS has closed or opened the files. On a cycle without INTCALC, COMBTRAN must be skipped or given an empty interest generation; otherwise it merges the previous cycle's `SYSTRAN(0)` again, because it selects its inputs by relative generation (PINV-g-jobs-ops-03). |

Area g's reading is that F is the only option that is consistent with the data dependencies in every source; A is right only for a demo reset, and B, D and E cannot run repeatedly as written. Whatever is chosen, these decisions go with it:

- interest frequency: every cycle or monthly, and on which day (Q-JINTC-002, Q-JCTLM-002);
- statements: after COMBTRAN as in the README, or right after CLOSEFIL as in CA-7, and how often (Q-JSTMT-001);
- the authorization purge CBPAUP0J: before posting (CA-7) or after the cycle (README, runbook) (Q-JCA7-003);
- how much transaction history the target keeps, since the source keeps five backup generations (PINV-g-jobs-ops-04);
- how often the reference files (TRANTYPE, TRANCATG, DISCGRP) are reloaded, and whether the Db2 extract TRANEXTR should feed TRANTYPE and TRANCATG (DEF-JCTLM-005);
- where TRANREPT, PRTCATBL and the readers run: on demand, or as cycle tasks.

## Scheduler findings

CA-7 (`SCHED-CA7.md`):

- A printed `LJOB` listing with 30 entries for 17 job names. Every job names schedule member 000652, whose content is not shown, so what starts a run is unknown; a `DEMAND` without a schedule ID runs CLOSEFIL alone (DEF-JCA7-001, DEF-JCA7-009, Q-JCA7-002).
- Triggers are per job name and schedule ID. Under 030 one CLOSEFIL starts four paths in parallel on the same files, and every WAITSTEP triggers both OPENFIL and CLOSEFIL, which restarts the round (DEF-JCA7-002, DEF-JCA7-006). Runs under 031 and 032 loop too, and the 031 run reopens and closes the files at the same time (DEF-JCA7-008). OPENFIL's "COMP TRIGGERS" flag is `N`, but it triggers CLOSEFIL under 031 (`app/scheduler/CardDemo.ca7:138, 532`).
- `CLOSEFIL1` and `CLOSEFIL2` are not valid job names (DEF-JCA7-003).
- Every listing has the same statistics (1058 runs, last run 17 July 2006) and the same trigger appears several times, so it is a template (DEF-JCA7-004).
- POSTTRAN runs only after the optional CBPAUP0J and without TRANBKP emptying `TRANSACT` first (DEF-JCA7-007, DEF-JPOST-002).

Control-M (`SCHED-CONTROLM.md`):

- No POSTTRAN anywhere; the daily TRANBKP empties the transaction master and deletes its index every day (DEF-JCTLM-001).
- MNTTRDB2 and the monthly folder have no scheduling days, so they run only when forced (DEF-JCTLM-002); the two Saturday smart folders wait for MNTTRDB2's condition, so they are blocked too.
- Folder name `WEEKLY-TransactionTypesDBRefresh` is used twice and parent folder attributes are crossed (DEF-JCTLM-003).
- Two Saturday smart folders consume and delete the same MNTTRDB2 condition (DEF-JCTLM-004).
- TRANEXTR refreshes the type and category flat files but TRANTYPE and TRANCATG never load them (DEF-JCTLM-005).
- The monthly interest folder has no posting before INTCALC, no TRANBKP before COMBTRAN and no TRANIDX (DEF-JCTLM-006, DEF-JCOMB-002).
- Conditions are scoped by order date, so a new day's chain does not wait for the previous day's (DEF-JCTLM-009).
- No failure handling: no `ON`/`DO`, no shouts; a failed job leaves the CICS files closed (ERR-JCTLM-001). Every job has `MAXRERUN="5"`, which caps the number of reruns but does not by itself cause one; nothing reruns a failed job automatically (ERR-JCTLM-001).

Both schedules use WAITSTEP as a fixed pause instead of checking that CICS closed or opened the files, and neither links to the other.

## Operations notes

- **CICS hand-over.** CLOSEFIL closes five of the eight CICS files. CARDFILE, CUSTFILE and TRANFILE close and reopen their own files with in-job SDSF steps; CARDDAT, CARDAIX and CUSTDAT are never closed by CLOSEFIL, which matters for the jobs that only read them (DEF-JCLOSE-003). A file closed by command stays unavailable until OPENFIL opens it (SE-JOPEN-001). The CSD files are `RECOVERY(NONE)` (ERR-JCSD-001), so online updates are not backed out.
- **Restart.** The runbook `docs/runbooks/batch-cycle-restart.md` gives the restart rules (restore from `TRANSACT.BKUP`, `TCATBALF.BKUP`); the job specs record where it was stale or leaves a gap (DEF-JINTC-003, corrected in commit 4a03121; DEF-JTBKP-001, where restoring `TRANSACT.BKUP(-1)` loses the day's postings) and the area entities file lists the corrections.
- **Generations.** The cycle selects its inputs by relative generation (`BKUP(0)`, `SYSTRAN(0)`), so an extra backup between TRANBKP and COMBTRAN, such as a TRANREPT run, changes what is combined (DEF-JCOMB-001, PINV-g-jobs-ops-03).
- **Business date.** INTCALC's `PARM='2022071800'` (DEF-JINTC-001) and the TRANREPT batch member's start and end dates (`app/jcl/TRANREPT.jcl:43-44`) are hard-coded; the local runner defaults the business date to 2022-07-06, which `CARDDEMO_BUSINESS_DATE` changes.

## Mainframe samples (not specified)

`samples/jcl` and `samples/proc` are compile and test samples for a mainframe installation. They carry no application behaviour, are not counted by `check_spec.py --require-all`, and have no spec. What they contain:

| Member | Purpose | Notes |
| :-- | :-- | :-- |
| `samples/jcl/BATCMP.jcl` | Compile and link one batch COBOL program with procedure BUILDBAT (`:42`). | Template: replace `BATCHPGM` (`:4-5`, set at `:33`) and `HLQ` (`:34`). |
| `samples/jcl/CICCMP.jcl` | Compile a CICS COBOL program with BUILDONL (`:41`), then `NEWCOPY` through SDSF (`:45`). | Run after the maps are compiled. |
| `samples/jcl/CICDBCMP.jcl` | Compile a CICS Db2 program with BLDCIDB2 (`:54`). | Needs the Db2 subsystem ID (`:7`). |
| `samples/jcl/BMSCMP.jcl` | Assemble a BMS map with BUILDBMS (`:38`), then `NEWCOPY` (`:42`). | |
| `samples/jcl/IMSMQCMP.jcl` | Translate, compile and link a CICS IMS MQ program with in-line steps (`:28-113`). | |
| `samples/jcl/LISTCAT.jcl` | `LISTCAT LEVEL(AWS.M2.CARDDEMO) ALL` into `AWS.M2.CARDDEMO.LISTCAT` (`:21-36`). | The repository copy of such a listing is `app/catlg/LISTCAT.txt`. |
| `samples/jcl/RACFCMDS.jcl` | RACF commands in TSO batch: add transaction `CT02` to profile `CARD` (`:24`), connect a user to a group (`:29`). | The null statement `//` at `:26` ends the job, so the `CONNECT` is never run. |
| `samples/jcl/REPRTEST.jcl` | Test of procedure REPROC: `TRANSACT` to `TRANSACT.BKUP(+1)` (`:22-33`). | Same as TRANBKP's first step. |
| `samples/jcl/SORTTEST.jcl` | Test of the TRANREPT date filter for one day (`:19-35`). | Writes `TRANSACT.DALY(+1)`. |
| `samples/proc/BUILDBAT.prc` | Batch compile (`IGYCRCTL`), listing copy, link (`HEWL`). | `PROC` label `BLDBAT` (`:24`). |
| `samples/proc/BUILDONL.prc` | CICS compile and link. | `PROC` label `BLDONL` (`:25`). |
| `samples/proc/BLDCIDB2.prc` | Db2 precompile, CICS translate, compile, link, bind package and plan (`:46-158`). | `PROC` label `BLDONL` (`:25`), the same as BUILDONL. |
| `samples/proc/BUILDBMS.prc` | BMS map assembly (physical map and DSECT copybook). | `PROC` label `BUILDBMS` (`:23`), the same as the member name. |

As with `app/proc/TRANREPT.prc` (DEF-JTRPTP-001), the `PROC` labels of three of the four sample procedures differ from their member names; z/OS finds cataloged procedures by member name, so this matters only for tools and in-stream copies.
