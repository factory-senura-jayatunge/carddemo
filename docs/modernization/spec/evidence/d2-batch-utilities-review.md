---
id: d2-batch-utilities-review
title: D2 batch utilities, review and spec-only check evidence
source_rev: b5e0139
covers: [docs/modernization/spec/programs/CBSTM03A.md, docs/modernization/spec/programs/CBSTM03B.md, docs/modernization/spec/programs/CBEXPORT.md, docs/modernization/spec/programs/CBIMPORT.md, docs/modernization/spec/programs/COBSWAIT.md, docs/modernization/spec/programs/MVSWAIT.md, docs/modernization/spec/programs/COBDATFT.md, docs/modernization/spec/areas/d2-batch-utilities-entities.md, docs/modernization/spec/areas/d2-batch-utilities-interactions.md]
---

# D2 batch utilities: review and check evidence

The area covers seven units: the statement job program CBSTM03A (`STMT`) and its file-access subroutine CBSTM03B (`STIO`), the branch migration pair CBEXPORT (`EXPT`) and CBIMPORT (`IMPT`), the wait utility COBSWAIT (`WAIT`) and the two assembler routines MVSWAIT (`MWAT`) and COBDATFT (`DTFT`).

The specs went through two independent checks:

1. **Source review.** Three reviewer subagents (a fourth later reviewed the interaction proposal, section 1.4) that had not written the specs checked the items against the source at `b5e0139`, which covers the COBOL, assembler, copybooks, macros, JCL, shims and tools. Each reviewer checked every item of its specs, not a sample. Each also ran the GnuCOBOL build to test the runtime claims.
2. **Spec-only reimplementation.** For CBSTM03B, CBEXPORT and CBIMPORT, a separate subagent received only the program spec, `format.md`, `entities.md`, the area entity proposal and the sample input files. It saw no COBOL, JCL, tests, tools, check scripts or reference outputs. Each subagent wrote a standard-library Python version. Its outputs were then compared byte for byte with the GnuCOBOL runner, on the sample data and on stress data the subagent never saw.

All runs below were made on commit `1bfa042` (branch `nl/D2`), with GnuCOBOL 3.2.0 and Python 3.14.

## 1. Source review

The verdicts follow `nl-pilot-check.md`:
- **CORRECT:** the item is right.
- **MINOR:** right in substance, with an imprecise detail.
- **WRONG:** the item contradicts the source.

The reviewers judged platform claims as reasoning and gave none a separate PLATFORM verdict. The rate columns divide by the number of reviewed items.

### 1.1 Program specs

| Spec | Items | Reviewed | CORRECT | MINOR | WRONG | WRONG / reviewed | WRONG+MINOR / reviewed |
| :-- | --: | --: | --: | --: | --: | --: | --: |
| CBSTM03A | 50 | 50 | 46 | 4 | 0 | 0.0% | 8.0% |
| CBSTM03B | 24 | 24 | 23 | 1 | 0 | 0.0% | 4.2% |
| CBEXPORT | 42 | 42 | 39 | 3 | 0 | 0.0% | 7.1% |
| CBIMPORT | 55 | 55 | 51 | 2 | 2 | 3.6% | 7.3% |
| COBSWAIT | 8 | 8 | 8 | 0 | 0 | 0.0% | 0.0% |
| MVSWAIT | 11 | 11 | 10 | 1 | 0 | 0.0% | 9.1% |
| COBDATFT | 18 | 18 | 17 | 1 | 0 | 0.0% | 5.6% |
| **All** | **208** | **208** | **194** | **12** | **2** | **1.0%** | **6.7%** |

The item counts leave out the open-question rows (Q-*). The reviewers also read those rows, and two of their remarks added to Q-STMT-008.

### 1.2 Entity proposal

Entity sections have no item IDs, so each table row or bullet counted as one claim. ENT-STMT-TRAN was reviewed as claim groups.

| Section | Claims | CORRECT | MINOR | WRONG |
| :-- | --: | --: | --: | --: |
| ENT-EXPORT-RECORD, ENT-IMPORT-ERROR | 89 | 88 | 0 | 1 |
| ENT-STMT-REQUEST, ENT-DATE-CONV-PARM | 53 | 50 | 2 | 1 |
| ENT-STMT-TRAN | 6 groups | 5 | 1 | 0 |

### 1.3 Findings and what was changed

Every WRONG finding is fixed. All MINOR findings were also applied.

| Finding | Verdict | Change |
| :-- | :-- | :-- |
| DEF-IMPT-003 said only CBEXPORT calls `CEE3ABD` without arguments. CBSTM03A does too (`app/cbl/CBSTM03A.CBL:923`). | WRONG | Both programs are named, with citations. |
| DEF-IMPT-010 gave an impossible clock example (time from the next day). The date parts are read before the time parts. | WRONG | Replaced with two cases that can occur. |
| ENT-IMPORT-ERROR sample: "followed by 45 spaces". | WRONG | 64 spaces (19 + 43 FILLER + 2 FD padding), 132 bytes. |
| ENT-DATE-CONV-PARM `CODATECN-1I-S1`: "never checked by COBDATFT". The type-1 path tests that byte (shim rejects `-`; the assembler's 20-byte compare starts there). | WRONG | Row rewritten: unchecked only on the type-2 path. |
| CBSTM03A FLOW-STMT-001: PSA offset 536 is PSATNEW (new TCB), not the current TCB (PSATOLD, 540). | MINOR | Corrected. Added to DEF-STMT-012 and Q-STMT-008, together with the unstated null `PSAPTR` assumption. |
| CBSTM03A SE-STMT-001: the header line numbering read as 17 lines. | MINOR | Row 1 (`ST-LINE0`) and rows 2-16 (15 records). |
| CBSTM03A NUM-STMT-009 and the section 11 invariant: the cross-reference order does not affect matching, because the scan restarts at slot 1. | MINOR | Reason corrected in both places. |
| CBSTM03A DEF-STMT-013: only the outer end-of-file test (`app/cbl/CBSTM03A.CBL:318`) is redundant. | MINOR | Corrected. |
| CBSTM03B ERR-STIO-002: the first TRNXFILE read accepts `00`/`04`, so an empty TRNXFILE abends. | MINOR | Status list and citation corrected. |
| CBEXPORT NUM-EXPT-006: no Source line; the all-spaces example omits the FICO case (X'000F'). | MINOR | Both added. |
| CBEXPORT NUM-EXPT-007: no Fidelity or Confidence line. | MINOR | Added. |
| CBEXPORT DEF-EXPT-009: the claim "kept until deleted by hand" is at `data-handling.md:175`. | MINOR | Citation added. |
| CBIMPORT BR-IMPT-003: the Source cited only the customer mapping. | MINOR | Every type's mapping cited. |
| CBIMPORT DEF-IMPT-012 counted the error file twice. | MINOR | Reworded. |
| MVSWAIT DEF-MWAT-001: `RETURN` takes `T` as a positional operand, not `T=YES`. | MINOR | Corrected. |
| COBDATFT DEF-DTFT-002: the 25 trailing bytes are "not necessarily spaces". | MINOR | Corrected. |
| ENT-DATE-CONV-PARM naming note and sample observation. | MINOR | Fields named exactly; the observed bytes (1-10) are separated from the inferred bytes (11-20). |
| ENT-STMT-TRAN sample timestamp `.6200`: the hundredths come from the wall clock. | MINOR | Example marked as varying. |

**Errors in section text (outside numbered items), also fixed:**
- CBIMPORT section 1 and the section 6 diagram said nine counters; there are eight. The blind reimplementer found this too.
- CBEXPORT section 3 described the local run imprecisely, and the error-line Source in section 4.3 was incomplete.
- CBIMPORT SE-IMPT-002..008 had no Fidelity or Confidence lines.
- CBSTM03A DEF-STMT-001 said the case of a last card with more than 10 transactions was not run. The reviewer ran it: all transactions printed. The 52-card garbage count depends on the data (1,307 and 1,287 in two runs).
- CBSTM03A section 15 now also says that the real cycle (after INTCALC and COMBTRAN) gives 312 transactions and at most 7 per card, according to the reviewer's simulation.

**Omissions the reviewers found, now added:** in ERR-IMPT-001, a successful but non-zero open status such as `97` also abends CBIMPORT (as DEF-EXPT-006 says for CBEXPORT).

**Not counted as errors, recorded for the reader:**
- COBSWAIT's text-to-binary conversion and CBSTM03B's account-key conversion follow different rules. For example, `5A` gives 0 in COBSWAIT and 5 in CBSTM03B. Each spec describes its own rule.
- The assembler-only claims (implicit lengths, literal pool, save area, `STIMER BINTVL` units) were checked by reasoning, not by runs.

### 1.4 Interaction proposal

A fourth reviewer, who had not seen the other reviews, checked `areas/d2-batch-utilities-interactions.md` against the JCL, the CA-7 and Control-M schedules, the CSD, the COBOL and assembler sources, `components.md`, `interactions.md` and the seven specs. It reviewed every edge row, CRUD row, invariant and diagram edge, then re-checked the revised document.

| Pass | Claims | Reviewed | CORRECT | MINOR | WRONG | WRONG / reviewed | WRONG+MINOR / reviewed |
| :-- | --: | --: | --: | --: | --: | --: | --: |
| First review | 58 | 58 | 46 | 12 | 0 | 0.0% | 20.7% |
| Re-check of the claims the revision added | 44 | 44 | 41 | 3 | 0 | 0.0% | 6.8% |

The re-check confirmed that all 12 first-pass findings were fixed. All 15 MINOR findings across both passes are fixed:
- **Spec item column:** six edge rows pointed at the wrong spec item. They now cite the section that describes the job step or schedule trigger (CBSTM03A §3, COBSWAIT §3).
- **TXT2PDF1:** it reads only `STATEMNT.PS` and writes `STATEMNT.PS.PDF`.
- **Line ranges:** the CBIMPORT job (`app/jcl/CBIMPORT.jcl:22-65`) and the Control-M entries.
- **CRUD:** the COBDATFT row is removed, because a parameter block in the caller's storage is not a data store.
- **PINV-d2-02:** each program declares its own FD key at offset 27.
- **PINV-d2-03:** now cites `tests/integration/test_export_import.py:11-36`.
- **Cards data-store row:** now includes the alternate index on account (`CARDAIX`, `KEYS(11 16)`, non-unique).
- **Diagram:** it now draws the missing edges. A TRANSACT edge wrongly suggested that DELDEF01 uses TRANSACT; that is fixed.

**Omissions the reviewer found, now added:**
- component and data-store rows for `components.md`;
- CREASTMT DELDEF01 and STEP030;
- TXT2PDF1 as the consumer of the text statements;
- the six other CA-7 jobs that trigger WAITSTEP;
- the CLOSEFIL → READACCT → CBACT01C → COBDATFT path;
- MVSWAIT's `STIMER` call through the `ASMWAIT` macro;
- the COBSWAIT SYSIN input;
- a note on the local stand-ins.

**Conflict left to the lead:** CA-7 runs CREASTMT after CLOSEFIL (`app/scheduler/CardDemo.ca7:465-468`). `interactions.md` follows the runbook (`docs/runbooks/batch-cycle-restart.md:18-20`) and places it between COMBTRAN and TRANIDX. The proposal states both orders.
### 1.5 Follow-up from the lead's cross-area read (area G, batch schedules)

The NL lead compared these specs with the area G job and schedule specs. All of the findings below were checked against `app/scheduler/CardDemo.ca7` and `app/scheduler/CardDemo.controlm` and fixed:

| Finding | Check against the source | Change |
| :-- | :-- | :-- |
| COBSWAIT `entry_points` and §3 listed only part of the triggers. The citation `ca7:72` is the POSTTRAN listing header, not a trigger. | WAITSTEP is triggered at `ca7:97, 189, 298, 330, 448, 522, 564` and in the Control-M folders at `controlm:14-19, 44-49, 81-86`. | `entry_points` and §3 list all ten. §3 points to `jobs/WAITSTEP.md` §3 for what WAITSTEP triggers next. |
| CBSTM03A §11 stated the upstream state without saying it depends on the README and runbook order. | CLOSEFIL triggers CBPAUP0J (`app/scheduler/CardDemo.ca7:43`, then POSTTRAN at `app/scheduler/CardDemo.ca7:70`) and CREASTMT (`app/scheduler/CardDemo.ca7:468`) at once. | §3 and §11 say that in CA-7, CREASTMT runs in parallel with posting. |
| CBSTM03A cited the CA-7 trigger as `ca7:465-476`, while the CREASTMT job spec cites `465-468`. | `app/scheduler/CardDemo.ca7:465` is the CLOSEFIL entry and `app/scheduler/CardDemo.ca7:468` the trigger line; `app/scheduler/CardDemo.ca7:470-476` is CREASTMT's own listing. | `465-468` in §3 and `entry_points`. |
| CBEXPORT and CBIMPORT stated file status `39` as certain, while the job spec says "file-status error". | The status is platform-inferred. | Both now say "a file-status error (most likely `39`)", and Q-EXPT-002 and Q-IMPT-003 ask which status. |

The glossary entry for U0999 in `format.md` now names CBEXPORT, CBIMPORT and CBSTM03A as the programs whose abend code is not set. These specs already said so, so no change was needed for that point.
## 2. Spec-only reimplementations

Each check lives in `docs/modernization/spec/checks/<program>_reimpl/`:
- `<program>_from_spec.py`: the blind reimplementation.
- `notes.md`: its own notes.
- `reference.py`: runs the GnuCOBOL program.
- `gen_stress.py`: builds the hidden data.
- `compare.py` and `run_check.py`: compare the two.

After `make build`, `run_check.py` repeats the sample comparison and `run_check.py --stress` the stress comparison. `python3 docs/modernization/spec/tools/verify_spec.py` (or `make -C modernization verify-spec`) runs all of them; on `1bfa042` it ended `verify-spec: OK`.

Each reimplementation passed on its first comparison, with no spec change needed between writing and comparing.

### 2.1 CBSTM03B (`checks/cbstm03b_reimpl/`)

A COBOL driver (`stio_driver.cbl`) calls the real CBSTM03B once per request line and prints the return code and the 1,000-byte data area after each call. The reimplementation reads the same request lines.

| Scenario | Calls compared | Differing | Return codes seen | Result |
| :-- | --: | --: | :-- | :-- |
| sample (the job's call sequence on the sample data) | 460 | 0 | `00 10` | pass |
| stress (every DD and operation, unopened and doubly opened files, reads past end, key lengths 1-25, unknown DDs and operations, seeded random mix) | 766 | 0 | `00 10 23 41 42 46 47` | pass |
| missing (the same requests, ACCTFILE absent) | 766 | 0 | `00 10 23 35 41 42 46 47` | pass |

### 2.2 CBEXPORT (`checks/cbexport_reimpl/`)

The stress records include negative values, negative zero, maximum values, plain-digit sign bytes, key extremes and letters in text keys, with the input records shuffled.

| Scenario | EXPORT records | SYSOUT lines | Abend line | RC | Result |
| :-- | --: | --: | :-- | --: | :-- |
| sample (after POSTTRAN) | 462 = 462, 0 differing | 20 = 20 | none | 0 | pass |
| stress (edge records added) | 471 = 471, 0 differing | 20 = 20 | none | 0 | pass |
| empty (five empty inputs) | 0 = 0 | 20 = 20 | none | 0 | pass |
| missing-card (CARDDATA absent) | not created on either side | 3 = 3 | `CEE3ABD: USER ABEND U0000` | 16 | pass |
| missing-cust (CUSTDATA absent) | not created on either side | 3 = 3 | `CEE3ABD: USER ABEND U0000` | 16 | pass |

### 2.3 CBIMPORT (`checks/cbimport_reimpl/`)

The import error record's timestamp has two parts that come from the wall clock and the process time zone even when the date and time are pinned: bytes 15-16 (hundredths) and 17-21 (UTC offset). The comparison checks the layout of those 7 bytes and compares every other byte exactly. The stress data:
- export records renumbered so that the types interleave, with gaps in the sequence numbers;
- unknown types, sequence numbers up to 4,294,967,295, and packed and binary edge values;
- non-blank header and FILLER bytes.

| Scenario | CUST | ACCT | XREF | TRNX | CARD | ERR | SYSOUT | RC | Result |
| :-- | --: | --: | --: | --: | --: | --: | --: | --: | :-- |
| sample (CBEXPORT output) | 50 | 50 | 50 | 262 | 50 | 0 | 14 | 0 | pass, 0 differing |
| stress (483 records) | 53 | 53 | 54 | 265 | 53 | 5 | 14 | 0 | pass, 0 differing |
| empty | 0 | 0 | 0 | 0 | 0 | 0 | 14 | 0 | pass |
| missing (no export) | none of the six outputs is created on either side ||||||3, abend line equal | 16 | pass |

The counts are the same on both sides. The error-timestamp layout check found 0 bad records.

### 2.4 Independence caveat

Section 15 of each spec states the sample-day counts, so a reimplementer could check itself against them. The stress scenarios close that gap: they were generated by code the reimplementers never saw, and their expected results appear in no document they had.

### 2.5 Ambiguities the reimplementers reported

The full lists are in each `notes.md`. The points that matter for a modernization, and their resolution:

| Program | Ambiguity | Resolution |
| :-- | :-- | :-- |
| CBIMPORT | Section 1 said nine counters, while other sections list eight. | Fixed (section 1.3). |
| CBIMPORT, entities | ENT-EXPORT-RECORD's `packed` type describes the writer (pad `0`, signs C/D/F), while NUM-IMPT-003 describes a more lenient reader. | A sentence in ENT-EXPORT-RECORD now points to NUM-IMPT-003. |
| CBEXPORT, CBIMPORT, CBSTM03B | How the loader treats duplicate keys or a file length that is not a multiple of the record length. | Not specified: these files are fixed-length KSDS unloads, and such input is a data-preparation error. All three reimplementations keep the first record of a duplicate key. |
| CBIMPORT | No status value is given for an output write failure. | It cannot happen locally. The spec keeps "status other than `00`" without inventing a value. |
| CBEXPORT, CBIMPORT | `format.md` describes CEE3ABD abends as U0999, while these programs pass no code and the local shim prints U0000. | The program specs (DEF-EXPT-005, DEF-IMPT-003) are right for these programs. The lead has since qualified the glossary entry (section 1.5). |
| CBEXPORT | `entities.md` gives TRANSACT's batch DD as `TRANFILE` (POSTTRAN); CBEXPORT uses `TRANSACT`. | Different jobs. Reported to the entity catalog owner. |
| CBEXPORT, CBIMPORT | Key at offset 27 in the program vs `KEYS(4 28)` in IDCAMS. | Already open as DEF-EXPT-002 and DEF-IMPT-004. The reimplementations follow the program, as the local build does. |
| CBSTM03B | Section 11 describes the repository's unit-test driver, which presets the RC and data area per call. The check's driver keeps them from the previous call, as CBSTM03A does. | Both statements are true for their driver. The equivalence contract is the persistent area (BR-STIO-002). |

## 3. Checks without a reimplementation

| Unit | Why no blind reimplementation | Local check |
| :-- | :-- | :-- |
| CBSTM03A | It cannot run unmodified off z/OS: the TIOT prologue dereferences address 0, which segfaults locally (exit 139). | `checks/cbstm03a_harness/run_harness.py` runs it with only `app/cbl/CBSTM03A.CBL:266-291` commented out. On `1bfa042`: RC 0, no console output, `STMTFILE` 96,960 bytes (1,212 records of 80), `HTMLFILE` 608,200 bytes (6,082 records of 100). The reviewer reproduced every quoted text and HTML line byte for byte, plus 20 edited-data scenarios. |
| COBSWAIT | It only computes a wait interval; the wait itself is MVSWAIT. | `checks/cobswait_probe/run_probe.py` links COBSWAIT with a COBOL stand-in for MVSWAIT that prints the binary value received. On `1bfa042`: 9 of 9 SYSIN cases pass. |
| MVSWAIT, COBDATFT | They are assembler; locally they are replaced by COBOL shims (`tools/shims/`). | Source review only. The COBDATFT shim was run through the repository's test driver and READACCT (50 accounts). The differences between the shim and the assembler are recorded as DEF-DTFT-001 and DEF-DTFT-002. |

The specs of these four units are therefore `reviewed`, not `verified`.

## 4. Resulting status

| Spec | spec_status | Basis |
| :-- | :-- | :-- |
| CBSTM03B | verified | Full review (0 WRONG) and blind reimplementation passing sample and stress |
| CBEXPORT | verified | Full review (0 WRONG) and blind reimplementation passing sample and stress |
| CBIMPORT | verified | Full review (2 WRONG, fixed) and blind reimplementation passing sample and stress |
| CBSTM03A | reviewed | Full review (0 WRONG); harness run |
| COBSWAIT | reviewed | Full review (0 WRONG); probe run |
| MVSWAIT | reviewed | Full review (0 WRONG) |
| COBDATFT | reviewed | Full review (0 WRONG) |

`python3 docs/modernization/spec/tools/check_spec.py` reports 0 errors and 0 warnings for the whole spec set, including the two area proposals.

## 5. What remains open

- Behaviour that no run reaches rests on the source review only:
  - the z/OS prologue of CBSTM03A;
  - write and read I/O errors in CBEXPORT and CBIMPORT;
  - counter overflow;
  - every assembler path on z/OS.
- Three cross-area points went to the NL lead, who owns `format.md` and `entities.md`: the U0999 glossary wording, the second batch DD name of TRANSACT, and a project-wide rule for duplicate keys and short records in KSDS unloads (section 2.5). The CREASTMT schedule order also goes to the lead (section 1.4).
- Two kinds of question can only be settled on a mainframe, and the modernized system does not depend on either answer. They stay open as Q-items that cite this file:
  - the export cluster's key position (Q-EXPT-002, Q-IMPT-003);
  - the z/OS control-block, literal-pool and save-area behaviour (Q-STMT-008, Q-DTFT-001, Q-MWAT-001, Q-MWAT-002).
- Open business and platform questions are listed in each spec (Q-STMT-*, Q-STIO-*, Q-EXPT-*, Q-IMPT-*, Q-WAIT-*, Q-MWAT-*, Q-DTFT-*). The platform questions need someone with mainframe access.
