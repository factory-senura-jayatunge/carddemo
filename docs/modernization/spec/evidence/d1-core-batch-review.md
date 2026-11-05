---
id: d1-core-batch-review
title: Area d1-core-batch, review and equivalence evidence
source_rev: 8a8a80a
covers: [docs/modernization/spec/programs/CBACT01C.md, docs/modernization/spec/programs/CBACT02C.md, docs/modernization/spec/programs/CBACT03C.md, docs/modernization/spec/programs/CBACT04C.md, docs/modernization/spec/programs/CBCUS01C.md, docs/modernization/spec/programs/CBTRN01C.md, docs/modernization/spec/programs/CBTRN03C.md, docs/modernization/spec/areas/d1-core-batch-entities.md, docs/modernization/spec/areas/d1-core-batch-interactions.md]
---

# Area d1-core-batch: review and equivalence evidence

Seven batch programs are specified in this area: CBACT01C (READACCT), CBACT02C (READCARD), CBACT03C (READXREF), CBACT04C (INTCALC), CBCUS01C (READCUST), CBTRN01C (TRANVALD) and CBTRN03C (TRANREPT). Two independent checks were run on the specs, as for the pilot (`docs/modernization/spec/evidence/nl-pilot-check.md`):

1. **Spec-only reimplementation** of the four programs with behaviour worth reproducing (INTCALC, TRANREPT, TRANVALD, READACCT). Each was written by a separate worker subagent that saw only the program's spec, `entities.md`, `areas/d1-core-batch-entities.md`, `format.md` and the sample inputs, never the COBOL, JCL, tests, stress generators or reference outputs. Its outputs were compared byte for byte with the GnuCOBOL build on the sample data and on stress data it never saw.
2. **Source review.** A reviewer subagent that wrote neither the specs nor the reimplementations checked sampled items against the COBOL, copybooks, JCL, proc, schedulers and local runs.

All results below were run on branch `nl/D1` at commit `779ff5f` (sources at `8a8a80a`) with GnuCOBOL 3.2 and the project flags. `make -C modernization verify-spec` passes there: `check_spec` 0 errors, 0 warnings, and all ten sample and stress comparisons (the four checks below plus POSTTRAN) OVERALL PASS.

## 1. Spec-only reimplementations

The checks are in `docs/modernization/spec/checks/<job>_reimpl/`. `d1_harness.py` runs one job step on a chosen data directory (with an optional PARM, DATEPARM record and pre-sorted TRANFILE), keeps stderr apart and writes binary outputs as hex. A stress generator may write several independent `case-*` data sets, which `run_check.py --stress` runs one by one. Compared in every run: every output record, the account master after the run where the program rewrites it, the whole SYSOUT, the `CEE3ABD` abend line and the return code. Only the wall-clock timestamps of the INTCALC transactions are masked (their layout and equality are still checked).

### 1.1 Sample data

| Job | Input | Outputs compared | Reference | Result |
| :-- | :-- | :-- | :-- | :-- |
| INTCALC | account and category balance files after the sample POSTTRAN day, PARM `2022071800` | TRANSACT, ACCTDATA after, SYSOUT, RC | 50 transactions, 50 accounts, 302 SYSOUT lines, RC 0 | identical |
| TRANREPT | TRANSACT after the sample POSTTRAN day, filtered and sorted as the job's SORT does, DATEPARM `2022-01-01 2022-07-06` | TRANREPT, SYSOUT, RC | 262 transactions, 453 report lines, 267 SYSOUT lines, RC 0 | identical |
| TRANVALD | `app/data/ASCII` | SYSOUT, RC | 1802 SYSOUT lines, RC 0 | identical |
| READACCT | `app/data/ASCII` | OUTFILE, ARRYFILE, VBRCFILE (record bytes), ACCTDATA after, SYSOUT, RC | 50 + 50 + 100 records, 752 SYSOUT lines, RC 0 | identical |

**Independence caveat:** each spec's section 15 states the sample counts, so a reimplementer could check itself against them. The stress runs close that gap.

### 1.2 Stress data

| Job | What the stress data exercises | Reference | Result |
| :-- | :-- | :-- | :-- |
| INTCALC | own group, `ZEROAPR` and unknown groups (DEFAULT fallback), 12 categories per account, zero, small and large negative and near-overflow balances, non-zero cycle fields, a second card for one account loaded after the first with a lower number, PARM `2023013100` | 124 transactions, 50 accounts, 362 SYSOUT lines, RC 0 | identical |
| TRANVALD | cards missing from the cross-reference, cross-references to missing accounts, a blank card number, a repeated record, reversed order | 1796 SYSOUT lines, RC 0 | identical |
| READACCT | plain-digit sign bytes, negative amounts and negative zero, zero / negative-zero / negative / maximum cycle debits, blank, undashed, short and non-date reissue dates, text in the ZIP and group fields, unsorted input | 53 + 53 + 106 records, 797 SYSOUT lines, RC 0 | identical |
| TRANREPT | 9 cases, below | | identical in all 9 |

TRANREPT stress cases (every case compares the report, SYSOUT and RC):

| Case | What it exercises | Report lines | SYSOUT lines | RC |
| :-- | :-- | --: | --: | --: |
| `case-main` | an out-of-range record late in a sorted file (DEF-TRPT-001: the report stops there, no totals), range bounds, zero and negative card totals, totals above 999,999,999.99 | 388 | 233 | 0 |
| `case-allin` | the same records without it: end-of-file totals, page breaks that an account total block skips over | 503 | 305 | 0 |
| `case-lastout` | the last record out of range: no totals at all | 497 | 302 | 0 |
| `case-empty` | empty TRANFILE | 0 | 3 | 0 |
| `case-reversed` | start date after end date | 0 | 3 | 0 |
| `case-blankdates` | empty TRANFILE with blank DATEPARM dates | 0 | 3 | 0 |
| `case-blankcard` | a blank card number on the first record | 32 | 18 | 0 |
| `case-badcat` | a category missing from TRANCATG | 75 | 51 | 16 |
| `case-badcard` | a card missing from CARDXREF | 50 | 36 | 16 |

The `case-main` and `case-lastout` runs feed out-of-range records straight into the program, which the submitted job never does because its SORT step filters first; the spec states this behaviour as is in FLOW-TRPT-001, BR-TRPT-002 and DEF-TRPT-001, with the decision left to Q-TRPT-001.

### 1.3 Iterations: spec errors found by the checks

| Found by | Spec item | What was wrong | Fix |
| :-- | :-- | :-- | :-- |
| TRANREPT reimplementer (contradiction in its notes), confirmed by the added `case-blankdates` | FLOW-TRPT-001 | The spec said the work record starts as spaces, "below any start date". It has no `VALUE` clause and starts as binary zeros locally, so with blank DATEPARM dates the empty-input case still ends without totals. With spaces the reimplementation printed totals. | Corrected; z/OS initial content noted as undefined |
| the same finding, `case-blankcard` | BR-TRPT-004 | "The first transaction always differs" is false for a blank card number; the account column then comes from a cross-reference record never read (binary zeros locally). | Corrected |
| TRANREPT reimplementer | §4.2 column header | `Amount` field width (16) vs its 14-character value was ambiguous. | Placement stated (columns 107-112) |
| source review (section 2), confirmed by the added INTCALC stress card | BR-INTC-007, DEF-INTC-007 | Locally a read through the non-unique alternate key returns the first record loaded, not the lowest card number. | Corrected; the reimplementation follows the new text and matches |

After these fixes all four reimplementations match on every run. TRANVALD and READACCT matched on the first attempt; INTCALC matched on the first attempt and again after the BR-INTC-007 correction; TRANREPT matched its first seven stress cases at once and the two added cases after the spec fix. The reimplementers' remaining decisions are listed in each folder's `notes.md`; none of them affects a compared output except where noted above. The main ones are: a missing input file is treated as open status `35`; read, write and close errors other than end of file and "not found" are not modelled because text inputs cannot produce them; duplicate keys in a loaded indexed file keep the first record.

## 2. Source review

The reviewer sampled every numbered item of CBACT04C and CBTRN03C and at least half of the items of the other five, counting each section 4 table (files, SYSOUT, the CBACT04C record layout, the CBTRN03C report lines, the CBACT01C field listing) as one item. It reran READACCT, READCARD, READXREF, READCUST, TRANVALD, INTCALC and TRANREPT locally and compiled its own test programs for working-storage initial values and duplicate alternate keys. Verdicts: CORRECT; MINOR (wording or citation only); WRONG (behaviour stated incorrectly); PLATFORM (a local behaviour claimed for z/OS, or the reverse).

| Program | Items | Sampled | CORRECT | MINOR | WRONG | PLATFORM |
| :-- | --: | --: | --: | --: | --: | --: |
| CBACT01C | 29 | 24 | 24 | 0 | 0 | 0 |
| CBACT02C | 11 | 11 | 11 | 0 | 0 | 0 |
| CBACT03C | 12 | 12 | 12 | 0 | 0 | 0 |
| CBACT04C | 41 | 41 | 40 | 0 | 1 | 0 |
| CBCUS01C | 12 | 12 | 12 | 0 | 0 | 0 |
| CBTRN01C | 20 | 20 | 20 | 0 | 0 | 0 |
| CBTRN03C | 31 | 31 | 31 | 0 | 0 | 0 |
| **Total** | **156** | **151** | **150** | **0** | **1** | **0** |

**Error rate:** WRONG 1/151 = **0.7%**; WRONG + MINOR on numbered items 1/151 = 0.7%. Counting the 4 citation errors in unnumbered section text as well, 5/155 = 3.2%. The reviewer saw the specs after the FLOW-TRPT-001 fix of section 1.3; counting that error too (found by the checks, not the review), the behavioural error rate across both methods is 2/151 = 1.3%. Not sampled: SE-ACRD-001, DEF-ACRD-002, DEF-ACRD-004, DEF-ACRD-005, DEF-ACRD-006.

**Findings and what was changed:**

| Finding | Verdict | Change |
| :-- | :-- | :-- |
| CBACT04C BR-INTC-007: locally the read by account returns the first record loaded, not the lowest card number (reviewer's test: two cards, higher number loaded first, the first loaded returned, status `00`) | WRONG | BR-INTC-007 and DEF-INTC-007 corrected; INTCALC stress data and reimplementation updated (section 1.3) |
| CBACT02C, CBACT03C, CBCUS01C: the CA-7 line ranges in the front matter and section 3 stopped one or two lines before the job's own trigger line | citation (unnumbered) | `app/scheduler/CardDemo.ca7` ranges now 367-394, 421-448, 394-421 |
| CBTRN03C section 3: the proc range left out the report DD and `PEND` | citation (unnumbered) | `app/proc/TRANREPT.prc:21-79` |
| CBACT04C section 15: the "after the sample POSTTRAN day" figures need POSTTRAN first, which the local INTCALC job does not run | note | Clarified; the INTCALC check's sample run now runs POSTTRAN first, so the figures are reproduced (section 1.1) |

**Confirmed without change, with the reviewer's notes:** all message texts and SYSOUT orders of the seven programs; the CBTRN03C report columns against `app/cpy/CVTRA07Y.cpy` and the local report; the INTCALC transaction layout byte for byte; the READACCT packed values, VB header and implicit-close warnings; DEF-INTC-002 (working storage starts as low-values under `-std=ibm`, the reviewer's own test); the changelog references of the "fixed in this branch" defects; every paragraph coverage table.

## 3. Status set from this evidence

| Spec | spec_status | Basis |
| :-- | :-- | :-- |
| CBACT04C, CBTRN03C, CBTRN01C, CBACT01C | verified | spec-only reimplementation identical on sample and stress data; source review |
| CBACT02C, CBACT03C, CBCUS01C | reviewed | source review of every numbered item; their behaviour (list each record) is covered by the local runs the reviewer made, and no reimplementation check was built for them |

## 4. What remains open

- Behaviour that no run reaches rests on the source review only: I/O errors other than end of file, "not found" and a missing file (open, read, write, rewrite and close errors), all z/OS-only behaviour (status `02` on the INTCALC alternate-key read, dataset deletion on abend, storage contents after a short PARM, the z/OS sign display of SYSOUT numbers).
- The INTCALC timestamps are compared by layout and equality only.
- The open business, operations and security questions are in each spec: Q-ACRD-001..002, Q-CDRD-001, Q-XRRD-001, Q-INTC-001..007, Q-CURD-001, Q-TVAL-001..003, Q-TRPT-001..003.
