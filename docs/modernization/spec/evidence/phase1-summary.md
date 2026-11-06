---
id: phase1-summary
title: Phase 1 (natural-language spec) summary, inventory, coverage and review error rates
source_rev: e6e50c2
covers: [docs/modernization/spec/evidence/nl-pilot-check.md, docs/modernization/spec/evidence/a-signon-admin-review.md, docs/modernization/spec/evidence/b-accounts-cards-review.md, docs/modernization/spec/evidence/c-transactions-review.md, docs/modernization/spec/evidence/d1-core-batch-review.md, docs/modernization/spec/evidence/d2-batch-utilities-review.md, docs/modernization/spec/evidence/e-review.md, docs/modernization/spec/evidence/f-trantype-vsam-mq-review.md, docs/modernization/spec/evidence/g-jobs-ops-review.md, docs/modernization/spec/entities.md, docs/modernization/spec/components.md, docs/modernization/spec/interactions.md]
---

# Phase 1 summary: the natural-language spec

Phase 1 is complete. Every in-scope source under `app/` has a spec, every paragraph and job step of those sources is mapped to spec items, and every spec has been checked against the source by a reviewer that did not write it. The area proposals are merged into `entities.md`, `components.md` and `interactions.md`. The figures below were taken on `modernization` at `e6e50c2`, the merge of the last areas (G, the lead's G fold and C), with this summary and the area G merge notes added.

## 1. Inventory

`check_spec.py --inventory --require-all` reports 100 of 100 in-scope sources with a spec, 0 errors and 0 warnings.

| Sources | Where | Count | Specs |
| :-- | :-- | --: | :-- |
| COBOL programs, base | `app/cbl/` | 31 | `programs/` |
| Assembler routines | `app/asm/` | 2 | `programs/` |
| JCL jobs, base | `app/jcl/` | 38 | `jobs/` |
| Cataloged procedures | `app/proc/` | 2 | `jobs/` |
| CSD, base | `app/csd/` | 1 | `jobs/` |
| Schedules (CA-7, Control-M) | `app/scheduler/` | 2 | `jobs/` |
| Authorization module (programs, jobs, CSD) | `app/app-authorization-ims-db2-mq/` | 8 + 5 + 1 | `programs/`, `jobs/` |
| Transaction type module (programs, jobs, CSD) | `app/app-transaction-type-db2/` | 3 + 3 + 1 | `programs/`, `jobs/` |
| VSAM-MQ module (programs, CSD) | `app/app-vsam-mq/` | 2 + 1 | `programs/`, `jobs/` |
| **Total** | | **100** | **46 program specs, 54 job specs** |

Of the 100 specs, 92 are `reviewed` and 8 are `verified`.

## 2. Coverage and checks

| Check | Result |
| :-- | :-- |
| Paragraph and step coverage | 993 of 993 paragraphs and job steps of the specified sources are mapped to items |
| Numbered items | 2,598 across the 100 specs |
| Source citations checked | 8,326 (each exists and is in range at the spec's `source_rev`) |
| Item references checked | 14,787 (each ID resolves to an item, entity or invariant) |
| Spec-only reimplementations (`checks/*_reimpl`) | 8 (INTCALC, POSTTRAN, READACCT, TRANREPT, TRANVALD, CBEXPORT, CBIMPORT, CBSTM03B), each matched against the GnuCOBOL runner on sample and stress data: 16 of 16 comparisons pass |
| `make -C modernization verify-spec` | OK; it now runs `check_spec.py --require-all`, so a missing spec fails it |
| `make check` | OK (lint, docs-check, agents-md-check, spec-check, build, 138 tests, 87.9% paragraph coverage against the 85% minimum) |

The 8 `verified` specs are the batch programs with a reimplementation: CBACT01C, CBACT04C, CBTRN01C and CBTRN03C (area D1), CBEXPORT, CBIMPORT and CBSTM03B (area D2) and CBTRN02C (pilot). The other batch programs that run locally only read and print records (CBACT02C, CBACT03C, CBCUS01C), or cannot run unmodified off z/OS (CBSTM03A). The online, IMS, Db2 and MQ programs cannot run locally, so `reviewed` is their highest status in this phase.

System artifacts after the merges: 55 cross-component invariants (INV-001 to INV-055). Each invariant names the area proposal it came from.

## 3. Specs and review results per area

Specs, status and items are as merged. The review counts are from each area's evidence file, at review time, before the fixes. Items that the fixes added are not in them. Every WRONG finding was fixed before the specs were marked `reviewed`. MINOR (called IMPRECISE in areas B and F) means right in substance with an imprecise detail or citation.

| Area | Evidence | Program specs | Job specs | Reviewed | Verified | Items now | Q items now | Checked at review | WRONG | MINOR | WRONG rate | WRONG + MINOR rate | Denominator |
| :-- | :-- | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | :-- |
| Pilot (COBIL00C, CBTRN02C) | [nl-pilot-check.md](nl-pilot-check.md) | 2 | 0 | 1 | 1 | 105 | 11 | 90 | 1 | 3 | 1.2% | 4.7% | 85 non-PLATFORM items |
| A, sign-on and user admin | [a-signon-admin-review.md](a-signon-admin-review.md) | 7 | 0 | 7 | 0 | 243 | 23 | 232 | 4 | 11 | 2.0% | 7.5% | 199 non-PLATFORM items |
| B, accounts and cards | [b-accounts-cards-review.md](b-accounts-cards-review.md) | 5 | 0 | 5 | 0 | 460 | 52 | 831 | 24 | 56 | 3.0% | 9.9% | 811 units (items, Q rows, table rows, scenarios) |
| C, transactions | [c-transactions-review.md](c-transactions-review.md) | 5 | 0 | 5 | 0 | 210 | 21 | 185 | 1 | 13 | 0.6% | 8.6% | 163 non-PLATFORM items |
| D1, core batch | [d1-core-batch-review.md](d1-core-batch-review.md) | 7 | 0 | 3 | 4 | 157 | 18 | 151 of 156 sampled | 1 | 0 | 0.7% | 0.7% | 151 sampled items |
| D2, batch utilities | [d2-batch-utilities-review.md](d2-batch-utilities-review.md) | 7 | 0 | 4 | 3 | 236 | 28 | 208 | 2 | 12 | 1.0% | 6.7% | 208 items |
| E, authorization module | [e-review.md](e-review.md) | 8 | 6 | 14 | 0 | 321 | 45 | 312 | 3 | 38 | 1.0% | 13.1% | 312 items |
| F, transaction type and VSAM-MQ | [f-trantype-vsam-mq-review.md](f-trantype-vsam-mq-review.md) | 5 | 5 | 10 | 0 | 351 | 41 | 308 | 6 | 37 | 1.9% | 14.0% | 308 items |
| G, jobs, procedures, CSD, schedules | [g-jobs-ops-review.md](g-jobs-ops-review.md) | 0 | 43 | 43 | 0 | 515 | 66 | 479 | 4 | 36 | 1.3% | 12.5% | 319 non-PLATFORM items |
| **All** | | **46** | **54** | **92** | **8** | **2,598** | **305** | | **46** | **252** | **1.8%** | **9.9%** | 2,556 (sum of the denominators) |

Notes on comparing the rows:

- The areas did not use the same denominator. A, C, G and the pilot exclude PLATFORM items, which depend on CICS, VSAM, IMS, Db2, MQ or z/OS behaviour and cannot be checked locally. B excludes UNVERIFIABLE units but also counts table rows and scenarios, so its denominator is larger than its item count. D2, E and F divide by every reviewed item. D1 sampled at least half of each spec's items and all items of CBACT04C and CBTRN03C. The "All" row adds the counts as reported, so it is an indication, not an exact rate.
- WRONG rates are between 0.6% and 3.0% in every area. The highest are COACTUPC in area B (16 WRONG of 207 units over its two review parts) and COTRTUPC in area F (6 of 59 items), the two longest update programs.
- The rates cover the program and job specs. Several areas also reviewed their area proposals: C counted 1 WRONG in 60 area-file rows, E 2 WRONG in 101 units, and D2 0 WRONG in the 58 claims of its interaction proposal and in the 44 claims its revision added. B re-checked its fixes and found 3 WRONG in 137 units, all introduced by the fixes and then corrected.
- "Q items now" counts the distinct open-question IDs in each spec's tables. Each one is a decision for the business or operations owners, routed in the spec to the role that must answer it.

## 4. What remains open

- The 305 open questions in the specs, and Q-ENT-001 in `entities.md`, the sample account data that holds a disclosure-group-like value in the ZIP field. Phase 2 has to answer the ones that change behaviour before the matching code is written.
- PLATFORM and platform-inferred items stay unconfirmed until they are run on a z/OS system or replaced by the target behaviour. They are mostly CICS, VSAM, IMS, Db2, MQ and scheduler behaviour.
- `jobs/TXT2PDF1.md` and the READXREF and TXT2PDF1 rows of `areas/g-jobs-ops-interactions.md` say "WAITSTEP, then OPENFIL" without the schedule-ID qualifier. `interactions.md` states that WAITSTEP's successors depend on the schedule ID it runs under.
