---
id: c-transactions-review
title: Area c-transactions, independent review evidence
source_rev: b5e0139
covers: [docs/modernization/spec/programs/CSUTLDTC.md, docs/modernization/spec/programs/COTRN00C.md, docs/modernization/spec/programs/COTRN01C.md, docs/modernization/spec/programs/COTRN02C.md, docs/modernization/spec/programs/CORPT00C.md, docs/modernization/spec/areas/c-transactions-entities.md, docs/modernization/spec/areas/c-transactions-interactions.md]
---

# Area c-transactions: independent review evidence

Three worker subagents that had not written the specs checked them against the source at `b5e0139`. One reviewer took CSUTLDTC, COTRN00C and COTRN01C; one took COTRN02C; one took CORPT00C and the two area files. Each reviewer checked **every** numbered item, the message catalogs, the screen tables, the paragraph coverage tables and the equivalence scenarios, and compiled its own GnuCOBOL probes (section 3). Verdicts follow `nl-pilot-check.md`: CORRECT, MINOR (right in substance, with an imprecise or missing detail), WRONG, PLATFORM (depends on CICS, BMS, VSAM, JES or LE behaviour that cannot be run locally; the reviewer gave an opinion and a confidence).

## 1. Verdicts

| Spec | Items | CORRECT | MINOR | WRONG | PLATFORM | WRONG / non-PLATFORM | WRONG+MINOR / non-PLATFORM |
| :-- | --: | --: | --: | --: | --: | --: | --: |
| CSUTLDTC | 16 | 14 | 0 | 0 | 2 | 0/14 = 0.0% | 0/14 = 0.0% |
| COTRN00C | 42 | 31 | 7 | 0 | 4 | 0/38 = 0.0% | 7/38 = 18.4% |
| COTRN01C | 29 | 26 | 1 | 0 | 2 | 0/27 = 0.0% | 1/27 = 3.7% |
| COTRN02C | 56 | 43 | 3 | 1 | 9 | 1/47 = 2.1% | 4/47 = 8.5% |
| CORPT00C | 42 | 35 | 2 | 0 | 5 | 0/37 = 0.0% | 2/37 = 5.4% |
| Programs | 185 | 149 | 13 | 1 | 22 | **1/163 = 0.6%** | **14/163 = 8.6%** |
| Area files (rows) | 60 | 49 | 10 | 1 | 0 | 1/60 = 1.7% | 11/60 = 18.3% |
| All | 245 | 198 | 23 | 2 | 22 | **2/223 = 0.9%** | **25/223 = 11.2%** |

Items reviewed: 185 of 185 numbered items (100%). The COTRN02C reviewer's summary line said 42 CORRECT and 10 PLATFORM; its per-item table gives 43 and 9, which is what is counted here. The area-file rows group table rows and paragraphs as the reviewer judged them (entity blocks, corrections, relationships, edges, CRUD rows, PINV rows).

Section text outside the numbered items had 16 findings (4 of them wrong statements, marked below), and all three reviewers noted that this evidence file was cited by the specs but not yet written. All PLATFORM items were agreed in substance; the reviewers' confidence is recorded with each item's **Confidence** tag.

## 2. Findings and changes

| Finding | Verdict | Change |
| :-- | :-- | :-- |
| COTRN02C BR-TRNA-004: `INITIALIZE` was said to set the FILLER to spaces; it does not, so the written record keeps the FILLER of the highest-ID record read by the READPREV (probe: `Z` FILLER survives) | WRONG | BR-TRNA-004 rewritten; Source adds `app/cbl/COTRN02C.cbl:677` |
| Entity correction "ENT-TRANSACTION FILLER": same error ("spaces in transaction add records") | WRONG | Proposed text now says bill payments and transaction add inherit it; evidence `COTRN02C.cbl:446-450, 677` |
| COTRN02C VAL-TRNA-006: partly blank fields only fail later for fields with a format check | MINOR | Text lists the fields stored as received |
| COTRN02C NUM-TRNA-001: `-00000000.00` comes back as `+00000000.00` | MINOR | Added (runtime-verified) |
| COTRN02C DEF-TRNA-005: "first card of the account" misleading | MINOR | Reworded; several cards fail with DUPKEY first |
| COTRN00C BR-TRNL-002: a short backward page keeps the previous first ID | MINOR | Corrected |
| COTRN00C BR-TRNL-004: backward read reaching the start inside the page leaves the page number unchanged | MINOR | Added |
| COTRN00C BR-TRNL-005: PF7 sets next page = yes before the page test, also on page 1 | MINOR | Corrected; new DEF-TRNL-012 |
| COTRN00C VAL-TRNL-001: commarea page number reset to 0, search field not blanked | MINOR | Added; DEF-TRNL-009 and S10 updated |
| COTRN00C VAL-TRNL-002: page messages can also replace the selection message | MINOR | Added |
| COTRN00C VAL-TRNL-005: page number set to 1 only when the look-behind reaches the start | MINOR | Corrected |
| COTRN00C DEF-TRNL-008: the "search started mid-file" cause cannot produce a short backward page | MINOR | Cause replaced (deletes or a stale first ID) |
| COTRN00C ERR-TRNL-003: on PF8 the skip READNEXT gets INVREQ and shows `Unable to lookup transaction...` before the ENDBR abend | PLATFORM, corrected | Rewritten |
| COTRN01C DEF-TRNV-001: wrong CSD line for CT01 `DTIMOUT(NO)` | MINOR | `CARDDEMO.CSD:433`; control-interval lock scope added |
| COTRN01C SE-TRNV-001: on a non-RLS LSR file the exclusive control may cover the control interval | PLATFORM, detail | Added |
| CORPT00C NUM-RPTS-003: CBTRN03C's date test ends the report loop (`NEXT SENTENCE`) instead of skipping; the sort step does the filtering | MINOR | Corrected; also in the ENT-REPORT-JOB paragraph |
| CORPT00C DEF-RPTS-003: `1a` normalises to `01` and can pass | MINOR | Corrected |
| ENT-DATE-CHECK-RESULT: `app/cpy/CSUTLDWY.cpy:60-80` stops short | MINOR | `app/cpy/CSUTLDWY.cpy:60-85` |
| Four "to COSGN00C" edges said "no commarea"; the XCTL passes the commarea area with routing fields set | MINOR | Edges and CORPT00C FLOW-RPTS-001, §11 reworded |
| PINV-c-02 omitted INTCALC as a writer of `TRAN-PROC-TS` | MINOR | `CBACT04C.cbl:499-500` added, and the format check `COTRN02C.cbl:369-381` |
| ENT-COMMAREA correction omitted COTRN02C's first-entry use of 203-218 | MINOR | Added |
| Timestamp-layout correction: cite the entry format check | MINOR | `COTRN02C.cbl:354-381` added |
| CSUTLDTC §11: said COACTUPC reads only the severity; it also shows the message number | section text, wrong | Corrected |
| CSUTLDTC §15: S6 is also asserted by the tests; S13 only for `YYYY-XX-DD` | section text | Corrected |
| COTRN00C §4.1: `TRNIDIN` has no `IC`; the program sets the cursor | section text | Corrected |
| COTRN00C §4.2: `READINTEG(UNCOMMITTED)` applies only to RLS | section text | Reason corrected |
| COTRN00C S10, S12 | section text | Expected results completed |
| COTRN01C §4.1: header fields are also `FSET` | section text, wrong | Corrected |
| COTRN01C §4.1: labels turquoise, values blue; title and dashes neutral | section text | Corrected |
| COTRN02C §1: "twelve" data fields all format-checked; there are eleven, and five have no format check | section text, wrong | Corrected |
| COTRN02C §4.1: `CTRL=(ALARM,FREEKB)` sounds the alarm on every send | section text | Added |
| COTRN02C §11: TRANREPT citation should be `app/proc/TRANREPT.prc:38-46` | section text | Corrected |
| COTRN02C S8, S12, S13: start state or amount not stated | section text | Completed |
| CORPT00C §4.1: labels without `ATTRB` default to autoskip, not unprotected | section text, wrong | Corrected |
| CORPT00C §6: diagram missing PF3 from Error/Submitted and other keys | section text | Transitions added |
| CORPT00C S9, S10: end date must be filled for the expected message | section text | Completed |
| Evidence file cited but missing | all specs | This file |

**Omissions the reviewers found, now added:** COTRN00C DEF-TRNL-011 (selection marks survive paging, new scenario S17), DEF-TRNL-012 (PF7 on page 1 re-enables PF8, new scenario S18), DEF-TRNL-013 (paging when the first or last record of the page is gone; Q-TRNL-003 extended). COTRN02C DEF-TRNA-015 (PF5 on an empty `TRANSACT` copies unread data); the always-true check at `app/cbl/COTRN02C.cbl:480` in DEF-TRNA-011; Clear and PA keys in ERR-TRNA-002; ENT-CARD named in BR-TRNA-007. COTRN01C DEF-TRNV-003 (an `FSET` ID field keeps the end of the old ID). CORPT00C: no state between the prompt and `Y` (BR-RPTS-005), the 80,000-byte `JOB-DATA` (DEF-RPTS-009), `2 22` and other NUMVAL-C results (NUM-RPTS-001). Item counts after the review: CSUTLDTC 16, COTRN00C 45, COTRN01C 29, COTRN02C 57, CORPT00C 42 (189).

**Confirmed without change:** every message text in the five message catalogs is exact, including the double space in `Transaction added successfully.  Your Tran ID is <ID>.` and the three `STRING`-built CORPT00C messages; every screen position, length and literal matches the BMS (continuation lines rebuilt); every commarea position matches `COCOM01Y` plus the extensions; the 17 job records of ENT-REPORT-JOB and their field positions are exact; the ENT-DATE-CHECK-RESULT layout and message numbers match `CSUTLDTC.cbl:42-70`; COTRN02C's check order, amount and date positions and ID generation are right; every send ends the task in COTRN02C and CORPT00C; all paragraph coverage tables list every paragraph with correct line ranges; all item IDs referenced from the area files resolve.

## 3. Runtime probes

All with GnuCOBOL 3.2, `cobc -x -std=ibm -fsign=EBCDIC`.

| Probe | Result | Used by |
| :-- | :-- | :-- |
| CSUTLDTC with `tools/shims/CEEDAYS.cbl`, repository driver `tests/unit/cobol/csutldtc_test.cbl` and an 8-byte caller (spec writer, then reviewer 1) | Scenarios S1-S15 give the severity, message number and 80-byte line in the spec; `0000-01-01`, `0022-01-01`, `1581-12-31`, `1582-10-14` give 2513; `2022-02-30`, `2023-02-29`, `2022-01-00` give 2508; `2022-00-01` gives 2517; an 8-byte caller shows two binary bytes after `TstDate:` | CSUTLDTC BR-DTCK-002..006, DEF-DTCK-003; COTRN02C VAL-TRNA-010; CORPT00C VAL-RPTS-004 |
| `+99999999.99` edit of `S9(09)V99` | 504.77 → `+00000504.77`, -5.50 → `-00000005.50`, 0 → `+00000000.00`, 123456789.12 → `+23456789.12` | NUM-TRNL-001, NUM-TRNV-001, NUM-TRNA-001 |
| `NUMVAL-C` into `+99999999.99` | `-00000000.00` → `+00000000.00`; `+00000012.50` and `-99999999.99` unchanged | NUM-TRNA-001 |
| `INITIALIZE` of `TRAN-RECORD` after `MOVE ALL 'Z'` | the 20-byte FILLER stays `Z...` | BR-TRNA-004, entity correction |
| `9(16)` 9999999999999999 + 1 | `0000000000000000` | NUM-TRNA-004 |
| `NUMVAL-C` into `PIC 99` / `PIC 9999` | `5 `, ` 5`, `-5`, `5-`, `$5` → `05`; `ab`, `.5` → `00`; `1a`, `1.` → `01`; `13` → `13`; `22  `, `  22` → `0022`; `abcd`, `0   ` → `0000`; `2022` → `2022`; `-202` → `0202`; `2 22` → `0222` | NUM-RPTS-001, DEF-RPTS-003 |
| CORPT00C month-end logic (`app/cbl/CORPT00C.cbl:215-236` with `CSDAT01Y`) | 2022-07-06 → 07-01..07-31; 2024-02-10 → ..02-29; 2023-02-15 → ..02-28; 2022-12-15 → ..12-31; 2023-12-31 → ..12-31; 2022-04-30 → ..04-30; 2100-02-01 → ..02-28; 2000-02-01 → ..02-29 | BR-RPTS-002, NUM-RPTS-002 |
| CORPT00C `STRING` messages and job records | the three messages and all 17 records exact; fillers add up to 80 bytes; `/*EOF` is written; `JOB-DATA` is 80,000 bytes | BR-RPTS-005..008, VAL-RPTS-005, ENT-REPORT-JOB |

The online programs cannot run locally (no CICS translator or region); their items are `reviewed` or `platform-inferred`, never `runtime-verified` except where a probe above covers the COBOL semantics.

## 4. What remains open

- PLATFORM items (22) rest on reviewer opinion about CICS, BMS, VSAM, JES and LE behaviour; they are collected in the mainframe questions Q-DTCK-001..004, Q-TRNL-003, Q-TRNV-003, Q-TRNA-004 and Q-RPTS-003.
- Business and security decisions: Q-TRNL-001, -002, -004; Q-TRNV-001, -002; Q-TRNA-001, -002, -003, -005; Q-RPTS-001, -002, -004.
- For the batch area: Q-RPTS-005 (`TRANREPT.prc` starts `//REPROC PROC` and nests `PROC=REPROC`) and the CBTRN03C `NEXT SENTENCE` behaviour noted under NUM-RPTS-003.
- Each item was checked once, by one reviewer. No spec-only reimplementation was run for this area: four of the five programs are CICS programs that cannot run locally, and CSUTLDTC's behaviour is covered by the probes above.
