---
id: g-jobs-ops-review
title: Area G (jobs, procedures, CSD and schedules), independent source review
source_rev: 7916431
covers: [docs/modernization/spec/jobs/SCHED-CA7.md, docs/modernization/spec/jobs/SCHED-CONTROLM.md, docs/modernization/spec/jobs/CARDDEMO-CSD.md, docs/modernization/spec/jobs/CLOSEFIL.md, docs/modernization/spec/jobs/OPENFIL.md, docs/modernization/spec/jobs/WAITSTEP.md, docs/modernization/spec/jobs/POSTTRAN.md, docs/modernization/spec/jobs/INTCALC.md, docs/modernization/spec/jobs/TRANBKP.md, docs/modernization/spec/jobs/COMBTRAN.md, docs/modernization/spec/jobs/CREASTMT.md, docs/modernization/spec/jobs/PRTCATBL.md, docs/modernization/spec/jobs/DALYREJS.md, docs/modernization/spec/jobs/TRANREPT.md, docs/modernization/spec/jobs/TRANREPT-PROC.md, docs/modernization/spec/jobs/REPROC.md, docs/modernization/spec/jobs/DEFGDGB.md, docs/modernization/spec/jobs/DEFGDGD.md, docs/modernization/spec/jobs/INTRDRJ1.md, docs/modernization/spec/jobs/INTRDRJ2.md, docs/modernization/spec/jobs/TXT2PDF1.md, docs/modernization/spec/jobs/DEFCUST.md, docs/modernization/spec/jobs/TRANFILE.md, docs/modernization/spec/jobs/TRANIDX.md, docs/modernization/spec/jobs/CBEXPORT-JOB.md, docs/modernization/spec/jobs/CBIMPORT-JOB.md, docs/modernization/spec/jobs/ESDSRRDS.md, docs/modernization/spec/jobs/FTPJCL.md, docs/modernization/spec/jobs/CARDFILE.md, docs/modernization/spec/jobs/ACCTFILE.md, docs/modernization/spec/jobs/CUSTFILE.md, docs/modernization/spec/jobs/XREFFILE.md, docs/modernization/spec/jobs/TCATBALF.md, docs/modernization/spec/jobs/TRANCATG.md, docs/modernization/spec/jobs/TRANTYPE.md, docs/modernization/spec/jobs/DISCGRP.md, docs/modernization/spec/jobs/DUSRSECJ.md, docs/modernization/spec/jobs/CBADMCDJ.md, docs/modernization/spec/jobs/READACCT.md, docs/modernization/spec/jobs/READCARD.md, docs/modernization/spec/jobs/READCUST.md, docs/modernization/spec/jobs/READXREF.md, docs/modernization/spec/jobs/REPTFILE.md, docs/modernization/spec/jobs/README.md, docs/modernization/spec/areas/g-jobs-ops-entities.md, docs/modernization/spec/areas/g-jobs-ops-interactions.md]
---

# Area G: independent source review

The 43 area G specs (38 JCL members, 2 procedures, the CSD and the two schedules), the operations view `jobs/README.md` and the two area proposals were written from the source and then checked by seven reviewer subagents that had not written them. Every spec was reviewed, not a sample:

| Reviewer | Scope |
| :-- | :-- |
| 1 | SCHED-CA7, SCHED-CONTROLM, CARDDEMO-CSD, CLOSEFIL, OPENFIL, WAITSTEP |
| 2 | POSTTRAN, INTCALC, TRANBKP, COMBTRAN, CREASTMT, PRTCATBL, DALYREJS |
| 3 | TRANREPT, TRANREPT-PROC, REPROC, DEFGDGB, DEFGDGD, INTRDRJ1, INTRDRJ2, TXT2PDF1 |
| 4 | DEFCUST, TRANFILE, TRANIDX, CBEXPORT-JOB, CBIMPORT-JOB, ESDSRRDS, FTPJCL, CARDFILE |
| 5 | ACCTFILE, CUSTFILE, XREFFILE, TCATBALF, TRANCATG, TRANTYPE, DISCGRP, DUSRSECJ |
| 6 | CBADMCDJ, READACCT, READCARD, READCUST, READXREF, REPTFILE, and every factual entry of `jobs/README.md` |
| 7 | `areas/g-jobs-ops-entities.md` and `areas/g-jobs-ops-interactions.md` |

Each reviewer checked **every** numbered item (BR, VAL, FLOW, SE, ERR, NUM, DEF and Q) against the JCL, procedures, control cards, `CARDDEMO.CSD`, the CA-7 and Control-M definitions, `app/catlg/LISTCAT.txt`, the called COBOL programs and the copybooks, including the Source citations; spot-checked the front matter, step, DD and coverage tables; and listed omissions. Reviewers 2 and 4 also compared SORT and `OUTREC` positions with the copybooks and DD names with the programs' `SELECT` clauses, and reviewers 4 and 5 counted records and keys in the sample files with read-only scripts. JCL, IDCAMS, DFSORT, CICS and the schedulers cannot run locally, so no job item is runtime-verified beyond what the program specs already verified; the reviewers judged platform behaviour from their knowledge of z/OS and gave agreement and a confidence.

Verdicts, as in [nl-pilot-check.md](nl-pilot-check.md): CORRECT, MINOR (right in substance, with an imprecise detail or citation), WRONG (the behaviour stated is not what the source does), PLATFORM (depends on z/OS, JES, IDCAMS, DFSORT, CICS or scheduler behaviour that cannot be run locally; the reviewer agreed, partly disagreed or disagreed). For Q items, CORRECT means the question is legitimate and correctly framed; all Q items were judged legitimate.

The reviews were made on `nl/G` at `7916431` (reviewers 1 to 5 and 7) and `4dc994f` (reviewer 6, after the merge of the other areas' program specs); the sources under `app/` are the same at both. After reporting, each reviewer was asked to apply its own findings to the files it had reviewed, re-verifying each finding first; the author spot-checked the edits and rewrote `jobs/README.md` from reviewer 6's findings. The fixes are in commit `d6e21d9` (all files except the eight master reload specs) and in the commit that adds this file (the master reload specs and the corrections made after the merge of `2deecf1`).

## 1. Counts

Counts are for the items as they stood at review time; items added because of the review (section 2.5) are not in them.

### 1.1 Job specs

| Spec | Items | CORRECT | MINOR | WRONG | PLATFORM (agreed) | WRONG / non-PLATFORM | WRONG+MINOR / non-PLATFORM |
| :-- | --: | --: | --: | --: | --: | --: | --: |
| SCHED-CA7 | 19 | 10 | 1 | 0 | 8 (3) | 0/11 = 0.0% | 1/11 = 9.1% |
| SCHED-CONTROLM | 21 | 12 | 1 | 0 | 8 (7) | 0/13 = 0.0% | 1/13 = 7.7% |
| CARDDEMO-CSD | 20 | 13 | 1 | 1 | 5 (3) | 1/15 = 6.7% | 2/15 = 13.3% |
| CLOSEFIL | 8 | 4 | 1 | 0 | 3 (3) | 0/5 = 0.0% | 1/5 = 20.0% |
| OPENFIL | 6 | 3 | 0 | 0 | 3 (2) | 0/3 = 0.0% | 0/3 = 0.0% |
| WAITSTEP | 7 | 4 | 1 | 0 | 2 (1) | 0/5 = 0.0% | 1/5 = 20.0% |
| POSTTRAN | 8 | 4 | 2 | 1 | 1 (1) | 1/7 = 14.3% | 3/7 = 42.9% |
| INTCALC | 10 | 7 | 0 | 0 | 3 (3) | 0/7 = 0.0% | 0/7 = 0.0% |
| TRANBKP | 13 | 5 | 2 | 0 | 6 (4) | 0/7 = 0.0% | 2/7 = 28.6% |
| COMBTRAN | 8 | 3 | 1 | 0 | 4 (2) | 0/4 = 0.0% | 1/4 = 25.0% |
| CREASTMT | 20 | 12 | 2 | 0 | 6 (6) | 0/14 = 0.0% | 2/14 = 14.3% |
| PRTCATBL | 9 | 3 | 0 | 0 | 6 (6) | 0/3 = 0.0% | 0/3 = 0.0% |
| DALYREJS | 7 | 5 | 0 | 0 | 2 (2) | 0/5 = 0.0% | 0/5 = 0.0% |
| TRANREPT | 13 | 7 | 1 | 1 | 4 (4) | 1/9 = 11.1% | 2/9 = 22.2% |
| TRANREPT-PROC | 12 | 8 | 1 | 0 | 3 (2) | 0/9 = 0.0% | 1/9 = 11.1% |
| REPROC | 3 | 1 | 0 | 0 | 2 (2) | 0/1 = 0.0% | 0/1 = 0.0% |
| DEFGDGB | 13 | 11 | 1 | 0 | 1 (0) | 0/12 = 0.0% | 1/12 = 8.3% |
| DEFGDGD | 15 | 12 | 1 | 0 | 2 (2) | 0/13 = 0.0% | 1/13 = 7.7% |
| INTRDRJ1 | 9 | 5 | 0 | 0 | 4 (2) | 0/5 = 0.0% | 0/5 = 0.0% |
| INTRDRJ2 | 4 | 3 | 1 | 0 | 0 (0) | 0/4 = 0.0% | 1/4 = 25.0% |
| TXT2PDF1 | 8 | 6 | 0 | 0 | 2 (1) | 0/6 = 0.0% | 0/6 = 0.0% |
| DEFCUST | 10 | 7 | 1 | 0 | 2 (2) | 0/8 = 0.0% | 1/8 = 12.5% |
| TRANFILE | 22 | 11 | 2 | 0 | 9 (7) | 0/13 = 0.0% | 2/13 = 15.4% |
| TRANIDX | 10 | 6 | 0 | 0 | 4 (3) | 0/6 = 0.0% | 0/6 = 0.0% |
| CBEXPORT-JOB | 12 | 9 | 1 | 0 | 2 (2) | 0/10 = 0.0% | 1/10 = 10.0% |
| CBIMPORT-JOB | 10 | 6 | 3 | 0 | 1 (1) | 0/9 = 0.0% | 3/9 = 33.3% |
| ESDSRRDS | 13 | 7 | 2 | 1 | 3 (3) | 1/10 = 10.0% | 3/10 = 30.0% |
| FTPJCL | 11 | 7 | 0 | 0 | 4 (4) | 0/7 = 0.0% | 0/7 = 0.0% |
| CARDFILE | 19 | 9 | 3 | 0 | 7 (6) | 0/12 = 0.0% | 3/12 = 25.0% |
| ACCTFILE | 8 | 4 | 1 | 0 | 3 (2) | 0/5 = 0.0% | 1/5 = 20.0% |
| CUSTFILE | 13 | 6 | 0 | 0 | 7 (6) | 0/6 = 0.0% | 0/6 = 0.0% |
| XREFFILE | 14 | 6 | 0 | 0 | 8 (4) | 0/6 = 0.0% | 0/6 = 0.0% |
| TCATBALF | 10 | 6 | 0 | 0 | 4 (4) | 0/6 = 0.0% | 0/6 = 0.0% |
| TRANCATG | 10 | 6 | 0 | 0 | 4 (4) | 0/6 = 0.0% | 0/6 = 0.0% |
| TRANTYPE | 11 | 6 | 1 | 0 | 4 (4) | 0/7 = 0.0% | 1/7 = 14.3% |
| DISCGRP | 9 | 5 | 0 | 0 | 4 (4) | 0/5 = 0.0% | 0/5 = 0.0% |
| DUSRSECJ | 13 | 6 | 1 | 0 | 6 (4) | 0/7 = 0.0% | 1/7 = 14.3% |
| CBADMCDJ | 18 | 13 | 0 | 0 | 5 (4) | 0/13 = 0.0% | 0/13 = 0.0% |
| READACCT | 10 | 6 | 2 | 0 | 2 (2) | 0/8 = 0.0% | 2/8 = 25.0% |
| READCARD | 5 | 3 | 1 | 0 | 1 (1) | 0/4 = 0.0% | 1/4 = 25.0% |
| READCUST | 6 | 4 | 0 | 0 | 2 (2) | 0/4 = 0.0% | 0/4 = 0.0% |
| READXREF | 5 | 4 | 1 | 0 | 0 (0) | 0/5 = 0.0% | 1/5 = 20.0% |
| REPTFILE | 7 | 4 | 0 | 0 | 3 (3) | 0/4 = 0.0% | 0/4 = 0.0% |
| **All 43 job specs** | **479** | **279** | **36** | **4** | **160 (128)** | **4/319 = 1.3%** | **40/319 = 12.5%** |

Rates:

- **WRONG / non-PLATFORM items: 4/319 = 1.3%.** WRONG + MINOR / non-PLATFORM: 40/319 = 12.5%.
- Of the 160 PLATFORM items the reviewers fully agreed with 128. They partly disagreed with 31 and disagreed with 1 (ERR-JUSEC-001); all 32 were rewritten.
- **Strict rate**, counting every item that was not fully right (WRONG, MINOR, and PLATFORM not fully agreed) over all items: (4 + 36 + 32) / 479 = 72/479 = 15.0%.

### 1.2 Operations view and area proposals

These documents have few item IDs, so each table row, bullet or factual sentence counted as one entry.

| Document | Entries | CORRECT | MINOR | WRONG | PLATFORM (agreed) |
| :-- | --: | --: | --: | --: | --: |
| `jobs/README.md` | 73 | 55 | 14 | 2 | 2 (2) |
| `areas/g-jobs-ops-entities.md` | 45 | 37 | 6 | 0 | 2 (2) |
| `areas/g-jobs-ops-interactions.md` (all CA-7, Control-M and CRUD rows, the invariants and the diagram) | 113 | 92 | 17 | 0 | 4 (3) |

The reviewer of the area proposals verified that all 175 item IDs the two files cite exist and mean what the files say.

## 2. Findings and changes

### 2.1 WRONG items and entries

| Item | Finding | Change |
| :-- | :-- | :-- |
| FLOW-JCSD-002 | Said ten programs carry `LANGUAGE(COBOL)` and eight do not; the spec's own list has eleven (`app/csd/CARDDEMO.CSD:190, 197, 236, 243, 258, 265, 272, 279, 286, 293, 300`), and seven have none. It also described the program `TRANSID` as used when another region links to the program; it names the mirror transaction for an outbound distributed program link and is inert without `REMOTESYSTEM`. | Eleven and seven, with the list; `TRANSID` reworded, also in DEF-JCSD-002. |
| NUM-JPOST-001 | Said `DALYREJS` is `RECFM=F` "unlike the other GDGs of the cycle, which are FB"; INTCALC writes `SYSTRAN(+1)` as `F` too (`app/jcl/INTCALC.jcl:37-41`). | `DALYREJS` and `SYSTRAN` are `F`, `TRANSACT.BKUP` and `TCATBALF.BKUP` are `FB`. |
| DEF-JTRPT-001 | Said differing `SYMNAMES` and `DATEPARM` windows give a report for their intersection. CBTRN03C's out-of-range branch is `NEXT SENTENCE`, which leaves the read loop (`app/cbl/CBTRN03C.cbl:173-178, 207`): the first selected record outside `DATEPARM` ends the report without totals and with return code 0; a wider `DATEPARM` only changes the heading. | Rewritten, citing FLOW-TRPT-001 and DEF-TRPT-001; FLOW-JTRPTP-002 now says the online path avoids it by writing the same window into both places. |
| ERR-JESRR-001 | Said that if `STEP01` fails, both loads fail on the missing input. A non-zero return code still catalogues `ESDSRRDS.PS` (`app/jcl/ESDSRRDS.jcl:48`), so the loads copy whatever it holds; only an abend or a JCL error leaves no input, and then no later step runs. | Rewritten around the three cases. |
| README: Control-M Saturday refresh | Said Control-M refreshes disclosure groups and the Db2 extract on Saturdays. Both smart folders wait for the MNTTRDB2 condition (`app/scheduler/CardDemo.controlm:34, 59`), and MNTTRDB2 is never ordered (DEF-JCTLM-002). | "waits for MNTTRDB2", in the order table and the bullets. |
| README: "consistent reading" cycle | Proposed CLOSEFIL, POSTTRAN, INTCALC, TRANBKP, COMBTRAN, CREASTMT, TRANIDX, OPENFIL as consistent with all sources. It cannot run twice: POSTTRAN needs `TRANSACT` empty (`app/cbl/CBTRN02C.cbl:256`) and COMBTRAN leaves it loaded (`app/jcl/COMBTRAN.jcl:41-48`). | Replaced by a decision table (section 2.6). |

### 2.2 PLATFORM disagreements

| Items | Finding | Change |
| :-- | :-- | :-- |
| FLOW-JCA7-001, -002, -004, -005, DEF-JCA7-001, -002, ERR-JCA7-001; CLOSEFIL, OPENFIL, WAITSTEP §1 and §3; README CA-7 column; interactions CA-7 rows | The specs read CA-7 triggers as firing on every completion of the triggering job. Each trigger carries a `SCHID`; with no `TRGID`, the trigger fires only for a run under that schedule ID and the triggered job inherits it (confidence about 75%). Under 030 the TRANCATG and TCATBALF branches never run, and the "OPENFIL closes the files again" loop exists only under 031. "SCHD RESOLUTION REQUIRED N" does not show that no schedule exists; every job names schedule member 000652. `QTM` sets the deadline start time. | All rewritten under the schedule ID reading (platform-inferred); new FLOW-JCA7-006, DEF-JCA7-008, DEF-JCA7-009; "no calendar schedule" became a question (Q-JCA7-002). |
| DEF-JCTLM-007; FLOW-JCTLM-002, -005, DEF-JCTLM-002 | `APPL_TYPE="OS"` is Control-M's generic job type and does not contradict `PLATFORM="UNIX"`; unscheduled folders must be forced, not ordered. | Corrected; new Q-JCTLM-005 (which Control-M platform). |
| DEF-JCSD-003 | `RESSEC(NO)` and `CMDSEC(NO)` do not decide who may start a transaction; attach security is set outside the CSD. | Restated. |
| SE-JOPEN-001, SE-JCLOSE-001 | A file closed by `CEMT` stays unenabled and `OPENTIME(FIRSTREF)` does not reopen it; without OPENFIL the files stay unavailable. | Corrected. |
| DEF-JWAIT-002 | The z/OS result of moving `36` and six blanks to `PIC 9(8) COMP` is predictable: 36,000,000 centiseconds, about 100 hours. | Aligned with NUM-WAIT-001 and DEF-WAIT-001. |
| ERR-JTBKP-001, ERR-JTBKP-002 | A missing `TRANSACT` is a JCL allocation error that stops the job, not return code 12 or a COND bypass. | Corrected; new DEF-JTBKP-005. |
| SE-JCOMB-001, NUM-JCOMB-001 | `DCB=(*.SORTIN)` copies only DCB values coded on the SORTIN DD, and none are; FB 350 comes from DFSORT taking the first input's attributes. | Corrected. |
| ERR-JTRPTP-001, ERR-JIRD1-001, NUM-JIRD1-001, ERR-JGDGB-001, ERR-JPDF-001 | `NOTIFY=&SYSUID` resolves to the CICS region's user; a missing member fails at open, not allocation; the internal reader also accepts blocked records; `SET MAXCC=0` also hides earlier failures and IDCAMS stops after code 16; CA-7's condition-code test is not in the listing. | Corrected; new Q-JPDF-003. |
| DEF-JTRAN-001, DEF-JTRAN-006, DEF-JTIDX-002, DEF-JCARD-001 | While CICS holds the cluster, the later `REPRO` and `BLDINDEX` steps fail too; `BLDINDEX` into a built AIX without `REUSE` fails; the AIX pointer overflow most likely leaves pointers out with a warning rather than failing. | Corrected; new ERR-JTRAN-003, ERR-JCARD-003. |
| DEF-JACCT-001, DEF-JCUST-001, DEF-JXREF-001, DEF-JUSEC-003, SE-JXREF-006, ERR-JXREF-001, DEF-JXREF-003 | Same open-cluster mechanism under `SHAREOPTIONS(2 3)` and `(1 3)`: the load fails at open rather than merging duplicates; `BLDINDEX` sorts with DFSORT by default; the XREFFILE outcome depends on whether the AIX delete succeeded; no program writes the cross-reference online. | Corrected (section 2.5). |
| ERR-JUSEC-001 (disagree) | The `STEP01` failure sequence could not happen: an abend bypasses the later steps, and a non-zero return code catalogues the file. | Rewritten around the three cases. |
| ERR-JCSDUP-001 | After some errors DFHCSDUP may only syntax-check the remaining commands, so "the account descriptions win" is not certain. | Made conditional; added to Q-JCSDUP-002. |

### 2.3 MINOR items

All 36 MINOR items were applied. By kind:

- **Citations** (13): ranges that missed the cited fact or cited the wrong line, for example DEF-JSTMT-006 (`app/scheduler/CardDemo.ca7:465-527`), ERR-JWAIT-001 (`app/asm/MVSWAIT.asm:26-27`), DEF-JTBKP-003 (`app/catlg/LISTCAT.txt:1733-2903`), DEF-JDCUST-004, ERR-JRACCT-001, NUM-JRACCT-001, DEF-JRCARD-001, FLOW-JTRPTP-001.
- **Overstated scope** (14): ERR-JPOST-001 (only the errors CBTRN02C checks abend), SE-JCSD-001 (only CARDFILE, CUSTFILE and TRANFILE close their own files), SE-JCARD-001 and DEF-JCARD-004 (no online program adds cards; the account programs do not use the card files), ERR-JIMPT-001 (a failed `ERROUT` write does not abend), DEF-JIMPT-002 (five data files, not six), DEF-JCARD-005, DEF-JTTYP-002, ERR-JRXREF-001, SE-JACCT-002 and others.
- **Wording** (9): DEF-JPOST-002 title, DEF-JTBKP-001 (restoring `BKUP(-1)` loses the day's postings), DEF-JCOMB-001 (job step `STEP05R` and procedure step `STEP01R`), SE-JESRR-002, SE-JUSEC-002 (only an abend deletes the new file), FLOW-JGDGD-001, ERR-JIRD2-001, DEF-JGDGB-001, SE-JCTLM-001.

### 2.4 Errors outside the numbered items

- `TRANFILE.md` §1, §5 and §15, SE-JTRAN-004 and DEF-JTRAN-005 said the seed transaction is all binary zeros. Bytes 343-350 of `app/data/EBCDIC/AWS.M2.CARDDEMO.DALYTRAN.PS.INIT` hold EBCDIC `00000100`, inside the FILLER. Corrected everywhere.
- `READCARD.md` and `READCUST.md` §5 said the card and customer files have no entity; ENT-CARD and ENT-CUSTOMER exist. Corrected, with the front matter `entity:` fields. A check after the merge of `2deecf1` found the same stale statement in ten more specs (CARDFILE, CUSTFILE, DEFCUST, DEFGDGD, DISCGRP, ESDSRRDS, DUSRSECJ, REPTFILE, CBEXPORT-JOB, CBIMPORT-JOB); TXT2PDF1 was left as it is, since no entity covers the statement file. They now name ENT-CARD, ENT-CUSTOMER, ENT-DISC-GROUP, ENT-USER-SECURITY, ENT-TRAN-REPORT, ENT-EXPORT-RECORD and ENT-IMPORT-ERROR, and every data store in the front matter whose records have a catalog entity now carries its `entity:` field (the CREASTMT `TRXFL` stores now name ENT-STMT-TRAN instead of ENT-TRANSACTION).
- After the same merge, `areas/g-jobs-ops-entities.md` was re-checked against the current catalog, `components.md` and `interactions.md`: the INV-002 row now quotes the merged text and proposes only what is still missing, two quotes were made exact, and the citation of the batch cycle diagram moved from `interactions.md:62-64` to `interactions.md:98-100`.
- `CARDFILE.md` §11 named COACTVWC, COACTUPC, COPAUA0C and COPAUS0C as users of the card files; they only declare the names. The users are COCRDLIC, COCRDSLC and COCRDUPC.
- `SCHED-CA7.md` §1 and the front matter (every chain starts with CLOSEFIL; no calendar schedule), `CLOSEFIL.md` and `OPENFIL.md` §1 (first and last job of every chain), `PRTCATBL.md` §3 and §11, `CREASTMT.md` §1 and §11, `TRANBKP.md` §3 and §11, `INTRDRJ1.md` §11, `TRANREPT.md` §3 and §5, `XREFFILE.md` §5 (`SPANNED` not coded), `DISCGRP.md` §3, `FTPJCL.md` §4 (`REGION=0M` overrides the step), missing Source lines in CBEXPORT-JOB, CBIMPORT-JOB, ESDSRRDS, FTPJCL and DALYREJS, and the value `defect-fix`, which is not in the format's fidelity vocabulary, in six specs. All corrected.
- Area proposals: the proposed statement work file entity duplicated ENT-STMT-TRAN, which the merged entity catalog now has; it was replaced by a pointer. Nine reload CRUD rows lacked the read of the flat file; TRANIDX and INTRDRJ1 rows lacked their reads; PINV-g-jobs-ops-01 and -04 were reworded.

### 2.5 Omissions found, now items or rows

| New item | What it records |
| :-- | :-- |
| FLOW-JCA7-006, DEF-JCA7-008, DEF-JCA7-009 | What a run under each schedule ID triggers; the 031 and 032 runs never end; a `DEMAND` without a schedule ID triggers nothing. |
| DEF-JCTLM-009, Q-JCTLM-005 | Conditions are scoped by order date; which Control-M platform the table is for. |
| DEF-JCSD-008, DEF-JCSD-009 [security] | CSUTLDTC has no PROGRAM definition; every transaction is traced with `CONFDATA(NO)`. |
| DEF-JPOST-003 | Nothing prevents posting the same `DALYTRAN.PS` twice once `TRANSACT` has been emptied. |
| DEF-JINTC-004 | The cross-reference AIX is non-unique but CBACT04C declares a unique alternate key. |
| DEF-JTBKP-005 | TRANBKP on a defined but never-loaded cluster most likely leaves no `TRANSACT`. |
| DEF-JTRPT-005 | TRANREPT copies `TRANSACT` with no CLOSEFIL before it. |
| DEF-JTRPTP-005 [security], DEF-JTRPTP-006 | The submitted job runs under the CICS region's user; interleaved or partial jobs in TDQ `JOBS` and the shared job name. |
| Q-JPDF-003 | The CA-7 condition-code test for TXT2PDF1's trigger. |
| ERR-JTRAN-003, ERR-JCARD-003 | The later IDCAMS steps fail while CICS holds the cluster. |
| ERR-JIMPT-002, DEF-JESRR-006, DEF-JFTP-006 | A failed `ERROUT` write is ignored; the null statement ends ESDSRRDS; the JOB `REGION=0M` overrides the step. |
| ERR-JACCT-002, ERR-JCUST-003, ERR-JXREF-002, ERR-JTCAT-003, ERR-JTCATG-003, ERR-JTTYP-003, ERR-JDISC-003, ERR-JUSEC-002 | An IDCAMS error of severity 16 ends the step before `SET MAXCC = 0`, so the reload ends with code 16 (in DUSRSECJ the DEFINE is also skipped). |
| ERR-JACCT-003, ERR-JCUST-004, ERR-JXREF-003, ERR-JUSEC-003, SE-JXREF-007 | The reload fails while CICS holds the cluster open; no program writes the cross-reference online. |
| DEF-JUSEC-005 | The README initialisation list runs DUSRSECJ before CLOSEFIL, while the batch list and both scripts close the files first. |
| DEF-JCSDUP-009, DEF-JRACCT-005, DEF-JREPT-004 | CBADMCDJ adds no `ADD GROUP ... LIST`, so a cold start does not install the group; READACCT reads the account file while POSTTRAN may update it (both triggered under SCHID 030); TRANREPT fails to allocate `TRANREPT(+1)` without the GDG base. |
| PINV-g-jobs-ops-06 and seven file hand-off rows | TRANBKP must not run against a never-loaded master (the Control-M daily folder reaches that state); POSTTRAN to INTCALC through `TCATBALF` and `ACCTDATA`, the reloads to the cycle, INTCALC to CREASTMT, POSTTRAN to TRANREPT. |

Also answered: area C's Q-RPTS-005 (the `//REPROC PROC` label and nested `PROC=REPROC` in `app/proc/TRANREPT.prc`) in DEF-JTRPTP-001, with a pointer from `TRANREPT.md`.

### 2.6 Operations view

`jobs/README.md` was rewritten from reviewer 6's findings: seven cycle orders instead of five (adding `scripts/run_posting.sh` and `scripts/run_interest_calc.sh`, with a note on `scripts/remote_refresh.sh` and on asynchronous FTP submission), the CA-7 column under the schedule ID reading, the Control-M weekly folders blocked on MNTTRDB2, and the open question on the target scheduler (Q-JCA7-001, Q-JCTLM-001) turned into a decision table: six options with their order and what each implies, the facts that hold for every option, and the decisions that go with the choice.

## 3. Limits

- No JCL, IDCAMS, DFSORT, CICS or scheduler behaviour was executed; 160 of 479 items depend on such behaviour and carry the reviewers' agreement and confidence, not a test.
- The editors who applied the findings were the reviewer sessions themselves. The author checked the result of each group (the item rewrites named in section 2.1, the new items, the schedule ID rewrite of SCHED-CA7) and reran `check_spec.py`, but the applied fixes have not had a second independent review.
- The counts in section 1 come from the reviewers' tables; where a reviewer's summary and table differed (reviewers 2 and 5), the per-spec table was used.
