---
id: nl-pilot-check
title: NL decomposition pilot, self-check evidence
source_rev: b5e0139
covers: [docs/modernization/spec/format.md, docs/modernization/spec/programs/COBIL00C.md, docs/modernization/spec/programs/CBTRN02C.md, docs/modernization/spec/entities.md, docs/modernization/spec/components.md]
---

# NL decomposition pilot: self-check evidence

Two independent checks were run on the pilot specs:

1. **Source review.** A reviewer subagent that did not write the specs sampled spec items and checked each one against the COBOL, copybooks, BMS, CSD and JCL at `b5e0139`.
2. **Spec-only reimplementation.** A second subagent, given only the CBTRN02C spec, `entities.md`, `format.md` and the four sample input files (no COBOL, JCL, tests, tools or reference outputs), wrote a standard-library Python version of POSTTRAN. Its outputs were compared byte for byte with the GnuCOBOL runner on the sample day and on a stress data set the reimplementer never saw.

## 1. Source review

A worker subagent that had not written the specs checked every numbered item, each message catalog, the CBTRN02C decision table (section 8) and reject layout (section 4.2) against the source at `b5e0139`. It also reran POSTTRAN on fresh sample data and compiled its own GnuCOBOL test of working-storage initial values and INITIALIZE. Verdicts: CORRECT, MINOR (right in substance, with an imprecise detail), WRONG, PLATFORM (depends on CICS, VSAM or z/OS behaviour that cannot be run locally; the reviewer gave an opinion and a confidence).

| Spec | Items | CORRECT | MINOR | WRONG | PLATFORM | WRONG / non-PLATFORM | WRONG+MINOR / non-PLATFORM |
| :-- | --: | --: | --: | --: | --: | --: | --: |
| COBIL00C | 47 | 41 | 1 | 0 | 5 | 0/42 = 0.0% | 1/42 = 2.4% |
| CBTRN02C | 43 | 40 | 2 | 1 | 0 | 1/43 = 2.3% | 3/43 = 7.0% |
| Both | 90 | 81 | 3 | 1 | 5 | **1/85 = 1.2%** | **4/85 = 4.7%** |

Counting the 5 errors found in section text outside the numbered items as well, the rate including MINOR is 9/90 = 10.0%. The reviewer agreed with all 5 PLATFORM items, adding detail to two of them.

**Findings and what was changed:**

| Finding | Verdict | Change in the spec |
| :-- | :-- | :-- |
| DEF-POST-007: on an account read error other than "not found", the spec said the transaction is validated against the previous record. In fact the limit and expiry checks are skipped, the transaction is posted, and the previous transaction's account is rewritten. | WRONG | DEF-POST-007 rewritten (separate cases for cross-reference and account) |
| BR-POST-002: a blank expiry date does not reject when the transaction date is also blank | MINOR | Edge case corrected |
| BR-POST-006: three copies are numeric moves, byte-identical only for clean signs | MINOR | Caveat added; equivalence contract qualified |
| DEF-BILL-007: POSTTRAN uses its layout only in `TRAN-PROC-TS` | MINOR | Corrected, Source added |
| COBIL00C §3 cited the wrong `COMEN01C` lines | section text | `COMEN01C.cbl:177-187` |
| COBIL00C §4.2 said four CICS files, there are three | section text | Corrected |
| COBIL00C §16 said 17 of 17 paragraphs, there are 16 plus the data division row | section text | Corrected |
| CBTRN02C §5 and ENT-DAILY-TRAN called `DALYTRAN-PROC-TS` and FILLER "ignored", but they are copied into rejects | section text | Corrected in both |
| ENT-DAILY-REJECT cited `POSTTRAN.jcl` for the GDG definition | section text | `DALYREJS.jcl:24-28` |
| VAL-BILL-006 (PA keys and Clear send no data), SE-BILL-001 (the REWRITE releases the lock on the payment path) | PLATFORM, agreed in part | Detail added |
| ENT-TRANSACTION FILLER "spaces" ignores DEF-BILL-006 | entity note | Corrected |
| ERR-BILL-004 had no Source line | note | Added |

**Omissions the reviewer found, now added as items:** COBIL00C DEF-BILL-011 (several screens sent in one interaction), DEF-BILL-012 (failed READPREV after a good STARTBR, likely ASRA), DEF-BILL-013 (balance edited without an account read), DEF-BILL-014 (account can be changed on the confirmation screen), DEF-BILL-015 (PF3 passes 218 bytes; the menu keeps 160). CBTRN02C DEF-POST-012 (account rewrite errors other than "invalid key" ignored), DEF-POST-013 (rejects deleted on abend by `DISP=(NEW,CATLG,DELETE)`), DEF-POST-014 (only status `00` counts as success, for example `97` after an implicit verify abends).

**Confirmed without change:** all 14 COBIL00C message texts and all CBTRN02C console and error texts are exact; both paragraph coverage tables list every paragraph with correct line ranges; every position, length and PIC in the six entity layouts matches the copybooks.

## 2. Spec-only reimplementation of CBTRN02C

### 2.1 Sample day (`app/data/ASCII`, business date 2022-07-06)

Reference: `python3 tools/carddemo.py run POSTTRAN` (GnuCOBOL 3.2, `-std=ibm -fsign=EBCDIC`). The reimplementation, the stress generator and the comparison are in `checks/posttran_reimpl/`; after `make build`, `run_check.py` repeats 2.1 and `run_check.py --stress` repeats 2.2.

| Check | Runner | Reimplementation | Result |
| :-- | --: | --: | :-- |
| Processed | 300 | 300 | pass |
| Posted | 262 | 262 | pass |
| Rejected (all reason 102) | 38 | 38 | pass |
| Return code | 4 | 4 | pass |
| `DALYREJS` (430-byte records) | 38 | 38, byte-identical | pass |
| `TRANSACT` after (350-byte records) | 262 | 262, byte-identical except `TRAN-PROC-TS` (bytes 305-330); timestamp layout valid in all 262 | pass |
| `ACCTDATA` after | 50 | 50, byte-identical | pass |
| `TCATBALF` after (50 new) | 100 | 100, byte-identical, including the FILLER of new records | pass |
| `TCATBAL record not found ...` lines | 50 | 50 | pass |
| Start and end lines, count lines | present | identical | pass |

**Independence caveat:** spec section 15 states the expected sample-day counts, so the reimplementer could check itself against them. The stress run below closes that gap.

### 2.2 Blind stress run

A generator built a data set from the sample files that the reimplementer never saw and whose expected results were not in any document. It exercises every reject reason and the edge cases the sample day does not reach:

- daily records in reversed order (running balances, BR-POST-007);
- unknown card numbers (reason 100) and a cross-reference to a missing account (101);
- transactions dated after the account expiry, on the expiry day, and one day before; an account with a blank expiry date (103 and its boundary);
- a future-dated transaction that is also over limit (103 overrides 102, BR-POST-003);
- amounts of 999,999,999.99, 0.00 and negative values; new type and category combinations (new category balance records and inherited FILLER);
- an account with a 10.00 credit limit, so negative amounts are also rejected 102 (DEF-POST-005).

| Check | Runner | Reimplementation | Result |
| :-- | --: | --: | :-- |
| Processed | 302 | 302 | pass |
| Posted | 232 | 232 | pass |
| Rejected: 100 / 101 / 102 / 103 | 3 / 2 / 51 / 14 | 3 / 2 / 51 / 14 | pass |
| Return code | 4 | 4 | pass |
| `DALYREJS` | 70 | 70, byte-identical | pass |
| `TRANSACT` after | 232 | 232, byte-identical except `TRAN-PROC-TS` | pass |
| `ACCTDATA` after | 50 | 50, byte-identical | pass |
| `TCATBALF` after (48 new) | 98 | 98, byte-identical | pass |
| `TCATBAL record not found ...` lines | 48 | 48 | pass |

Both runs: **OVERALL PASS**, no difference in any compared byte. CBTRN02C's spec is therefore marked `verified` for the behaviour these runs reach. Not reached by either run: arithmetic overflow (NUM-POST-003), abends (ERR-POST-001..008) and reason 109 (ERR-POST-006); those items rest on the source review and on the GnuCOBOL tests in section 3.

### 2.3 Ambiguities the reimplementer reported, and how the spec now answers them

| # | Ambiguity | Resolution |
| --: | :-- | :-- |
| 1 | `cardxref.txt` lines are 36 bytes, the record is 50 | Note added to ENT-CARD-XREF in `entities.md` |
| 2 | `tcatbal.txt` mixes CRLF and LF | Note added to `entities.md` (49 CRLF lines, 1 LF line) |
| 3 | How computed zoned values are written (zero, sign) | NUM-POST-001 "Writing", verified on GnuCOBOL (section 3) |
| 4 | Negative zero on input | NUM-POST-001 "Reading": compares as zero, so it goes to cycle credit; verified |
| 5 | Plain digit or invalid byte in the sign position | Plain digit reads as positive (verified); invalid bytes stay unspecified (data-quality question, not program behaviour) |
| 6 | Which account fields are re-encoded on rewrite | BR-POST-005: only the changed fields; other bytes are written back as read |
| 7 | Category balance rewrite | BR-POST-004: only `TRAN-CAT-BAL` is re-encoded |
| 8 | Source of the new category FILLER | BR-POST-004 and DEF-POST-010: a not-found READ leaves the work record unchanged, so FILLER comes from the last record read successfully |
| 9 | Sign on overflow | NUM-POST-003: sign kept; a negative result truncated to zero is stored as negative zero on GnuCOBOL |
| 10 | Timestamp hundredths | Already specified (NUM-POST-007); clock reads are per posted record |
| 11 | Output key order | KSDS keys are digits only, so EBCDIC and ASCII orders agree; no spec change |
| 12 | Output after an abend | ERR-POST-008: end lines are not printed, earlier writes stay |
| 13 | Daily records of other lengths | Not specified: the dataset is fixed 350-byte; a different length is a data-preparation error |
| 14 | Where error lines go | Section 4.3: all `DISPLAY`, so SYSOUT; only the local CEE3ABD shim writes to stderr |
| 15 | Account key in the category key | Already specified (NUM-POST-005): the 11 bytes as read |

The reimplementer also found that `format.md`'s example IDs did not match the CBTRN02C spec (`BR-POST-003`, `NUM-POST-001`, `ERR-POST-001`); they now read `BR-POST-001`, `NUM-POST-007` and `ERR-POST-008`.

## 3. GnuCOBOL semantics tests

Small programs compiled with the project flags (`cobc -x -std=ibm -fsign=EBCDIC -ftab-width=4`, GnuCOBOL 3.2) to settle claims the specs depend on.

**INITIALIZE leaves FILLER untouched** (DEF-BILL-006, DEF-POST-010). A group `K PIC 9(04)`, `B PIC S9(03)V99`, `FILLER PIC X(06)` filled with `Z` and then `INITIALIZE`d shows `[000000000ZZZZZZ]`: the named fields are zeroed, the FILLER keeps `ZZZZZZ`. Side observation: INITIALIZE writes the signed zero of `B` as `0`, not `{`, whereas `MOVE 0` writes `{`. No pilot output depends on this, because every initialised amount is then overwritten.

**Zoned arithmetic results** (NUM-POST-001, NUM-POST-003), fields `PIC S9(09)V99` and `PIC S9(10)V99`, raw bytes shown:

| Operation | Stored bytes | Meaning |
| :-- | :-- | :-- |
| 999,999,999.99 + 0.02 | `0000000000A` | +0.01 (high digit lost, sign kept) |
| -999,999,999.99 + -0.02 | `0000000000J` | -0.01 |
| -999,999,999.99 + -0.01 | `0000000000}` | negative zero |
| 5.00 + -5.00 | `0000000000{` | positive zero |
| MOVE 0 | `0000000000{` | positive zero |
| `0000000000}` >= 0 | true | negative zero compares as zero |
| 0 + `0000000000}` | `0000000000{` | normalised to positive zero |
| 0 + `00000001234` (plain digit in sign byte) | `0000000123D` | read as +12.34 |
| 9,999,999,999.99 + 0.01 (S9(10)V99) | `00000000000{` | +0.00 |

The z/OS results for overflow are platform-inferred: Enterprise COBOL also truncates high-order digits without ON SIZE ERROR, but whether a truncated negative zero keeps its sign there was not tested.

## 4. What remains open

- Behaviour not reached by any run (abends, reason 109, I/O errors such as DEF-POST-007, overflow on the real files) rests on source review only.
- The source review sampled every numbered item once, by one reviewer. A second reviewer on a later program would show whether the 1.2% / 4.7% rates hold.
- CICS behaviour in COBIL00C cannot run locally; its items are `reviewed` or `platform-inferred`, never `runtime-verified`.
- Open business and platform questions are listed in each spec (Q-BILL-*, Q-POST-*, Q-ENT-*).
