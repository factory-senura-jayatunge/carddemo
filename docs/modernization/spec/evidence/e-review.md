# Area e-authorization: review evidence

Independent review of the area E specs: the authorization module programs COPAUA0C, COPAUS0C, COPAUS1C, COPAUS2C, CBPAUP0C, DBUNLDGS, PAUDBLOD and PAUDBUNL, the module jobs CBPAUP0J, DBPAUTP0, LOADPADB, UNLDGSAM and UNLDPADB, and the module CSD file CRDDEMO2 (spec `jobs/CRDDEMO2-CSD.md`). The two area files `areas/e-authorization-entities.md` and `areas/e-authorization-interactions.md` were reviewed separately (section "Area files"). Source revision: 59cc6c2, the last commit that touches `app/app-authorization-ims-db2-mq/`, `app/cpy/` or `app/jcl/XREFFILE.jcl`.

## Method

Four reviewer agents worked independently of the author and of each other, read-only on the repository, each with its own scratch directory: one for COPAUA0C; one for COPAUS0C, COPAUS1C and COPAUS2C; one for CBPAUP0C, DBUNLDGS, PAUDBLOD and PAUDBUNL; and one for the five job specs and the CSD spec. Each reviewer checked **every** item heading against the cited source lines and any other source it needed (copybooks, BMS maps, DBDs, PSBs, DDL, JCL, the CSD, the module README and the EBCDIC sample unload), and reported non-item errors it found in the interface tables, data tables and flow descriptions. Verdicts:

- **CORRECT:** matches the source.
- **MINOR:** essentially right, but a detail (line range, condition, wording, Confidence label) is off or missing.
- **WRONG:** contradicts the source, or describes behaviour that does not happen.

Where a reviewer could, it confirmed pure-COBOL claims with GnuCOBOL 3.2 (`cobc -x -std=ibm`). The author then checked each WRONG and MINOR finding against the source before changing the spec. Every WRONG and every MINOR finding is fixed; the few non-item suggestions that were not applied are listed under "Not changed".

## Results per spec

| Spec | Items | CORRECT | MINOR | WRONG | Fixed in |
| :-- | --: | --: | --: | --: | :-- |
| COPAUA0C (AUTH) | 55 | 47 | 7 | 1 | 7f761d0 |
| COPAUS0C (AUSM) | 38 | 31 | 6 | 1 | f308542 |
| COPAUS1C (AUDT) | 39 | 34 | 5 | 0 | f308542 |
| COPAUS2C (AUFR) | 17 | 17 | 0 | 0 | f308542 (one non-item fix) |
| CBPAUP0C (AUPG) | 40 | 34 | 5 | 1 | b936a5a |
| DBUNLDGS (DBUL) | 17 | 15 | 2 | 0 | b936a5a |
| PAUDBLOD (PDLD) | 20 | 18 | 2 | 0 | b936a5a |
| PAUDBUNL (PDUL) | 17 | 14 | 3 | 0 | b936a5a |
| CBPAUP0J (JAUPG) | 12 | 11 | 1 | 0 | 1ca6f7e |
| DBPAUTP0 (JDBPA) | 12 | 9 | 3 | 0 | 1ca6f7e |
| LOADPADB (JLDPA) | 10 | 9 | 1 | 0 | 1ca6f7e |
| UNLDGSAM (JULGS) | 11 | 11 | 0 | 0 | - |
| UNLDPADB (JULPA) | 10 | 10 | 0 | 0 | - |
| CRDDEMO2-CSD (JCSD2) | 14 | 11 | 3 | 0 | 1ca6f7e |
| **Total** | **312** | **271** | **38** | **3** | |

Error rate: **WRONG 3 of 312 items (1.0%)**, one each in COPAUA0C, COPAUS0C and CBPAUP0C. WRONG or MINOR: 41 of 312 (13.1%). All items were checked, so every spec meets the "reviewed" rule in `format.md` (all items, or at least half) and is now `spec_status: reviewed`.

Five items were added after the review from reviewer findings: COPAUS0C DEF-AUSM-010, DEF-AUSM-011 and Q-AUSM-004, and PAUDBUNL DEF-PDUL-005 and Q-PDUL-003. The author checked them against the source lines they cite; they are not part of the counts above.

## WRONG findings (all fixed)

| Item | Finding | Fix |
| :-- | :-- | :-- |
| COPAUA0C NUM-AUTH-002 | Said that with a negative available credit any positive amount is declined while a zero or negative amount is approved, and that limit - balance is exact. The test is `WS-TRANSACTION-AMT > WS-AVAILABLE-AMT` (`app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:668, 676`): with available credit -50.00, an amount of 0 or -10 is greater and is declined. `WS-AVAILABLE-AMT` is `S9(09)V99` (`app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:62`) while the account operands are `S9(10)V99`, and there is no `ON SIZE ERROR`. | Only amounts less than or equal to the negative available credit are approved, every zero or positive amount is declined, and a difference of 1,000,000,000.00 or more loses its high-order digits. |
| COPAUS0C BR-AUSM-004 | Said the next-page flag is set to `N` at the start of a search. `SET NEXT-PAGE-NO` (`app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:183`) runs before the commarea is copied in (`app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:200`), which restores the previous value, and nothing in the search resets it. | The flag keeps its commarea value when the list ends while it is being filled. New DEF-AUSM-011: after a stale `Y`, PF8 clears the rows and shows an empty page with no message. Section 6 step 1 corrected too. |
| CBPAUP0C FLOW-AUPG-001 | Said `P-CHKP-FREQ` and `P-CHKP-DIS-FREQ` take their defaults when they are `00000`. `IF P-CHKP-FREQ = SPACES OR 0 OR LOW-VALUES` (`app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:201, 204`) compares the `X(05)` field with the numeric literal `0`, which is compared as the text `0` padded with spaces. | The default applies to spaces, `0    ` or low-values; `00000` is kept, so a checkpoint is taken after every summary and a display frequency of `00000` prints every checkpoint. The section 4.1 table is corrected the same way. Verified on GnuCOBOL (test 2 below). |

## MINOR findings fixed

- **COPAUA0C:**
  - FLOW-AUTH-001: the request queue name has no `VALUE`, so after a failed `RETRIEVE` it keeps uninitialized content, not blanks.
  - BR-AUTH-005: the full requested amount is approved; the strict comparison only means that an amount equal to the available credit is approved.
  - BR-AUTH-009 and DEF-AUTH-004: the declined amount added comes from the last earlier request whose card was found (only those reach 8500), not necessarily the previous request.
  - SE-AUTH-004: the insert is of the child segment under a qualified parent, with `SEGLENGTH (LENGTH OF PENDING-AUTH-DETAILS)`.
  - DEF-AUTH-005 and section 11: an approved negative amount does decrease `PA-CREDIT-BALANCE` (DEF-AUTH-007).
  - DEF-AUTH-010: A001 to A003 set neither error code, I001 to I003 do not set code 2; citations added.
  - Non-item, section 5: the card, account and decision flags are set on every request; the summary, customer and decline-reason flags carry over.
- **COPAUS0C:**
  - FLOW-AUSM-002: sign-on does not initialize the commarea, so the account field is likely not numeric straight after sign-on; zeros appear only after a program moved them there.
  - BR-AUSM-001 and ERR-AUSM-001: `CXACAIX` is a non-unique alternate index (`app/jcl/XREFFILE.jcl:72-75`); `DUPKEY` for a multi-card account falls under "other response" and stops the list. New DEF-AUSM-010 and Q-AUSM-004.
  - VAL-AUSM-004: Clear and the PA keys give an unchecked `MAPFAIL`; the account ID and list rows come back blank too.
  - VAL-AUSM-005: PF8 with a saved key still schedules the PSB, reads the summary and re-positions before it tests the flag.
  - DEF-AUSM-004: credit limit and credit balance are also truncated (8 integer digits).
- **COPAUS1C:**
  - BR-AUDT-001: the detail `GNP` also runs after a summary-read system error.
  - BR-AUDT-002: wording aligned with DEF-AUDT-004.
  - BR-AUDT-003: COPAUA0C never sets `4400` either; the description goes into 15 positions, which every description fits.
  - DEF-AUDT-003: when COPAUS2C returns status `F` its Db2 error text is shown.
  - DEF-AUDT-007: the debug `DISPLAY` runs before the replace, also when the replace then fails.
  - Non-item, section 4.1: field attributes listed exactly (`ERRMSG` is `BRT,FSET`, the title is `BRT`).
- **COPAUS2C:** non-item, section 5: `PA-AUTH-ORIG-TIME` is not used.
- **CBPAUP0C:**
  - NUM-AUPG-001: `WS-DAY-DIFF PIC S9(4) COMP` holds -9999 to +9999 only; larger differences are cut to 4 digits under TRUNC(STD) or wrap at the halfword under TRUNC(BIN) (test 2).
  - DEF-AUPG-004: the century-change effect depends on that option; details of the new century age normally.
  - DEF-AUPG-008: checkpoint and error lines print the account number whatever the debug flag.
  - DEF-AUPG-010: citations and the full list of unused fields.
  - Q-AUPG-007: with the `>` test, `00001` compared numerically would mean every 2 summaries.
- **DBUNLDGS:**
  - DEF-DBUL-001: the details cannot be related to their summary reliably; the summary counts are not kept in step.
  - DEF-DBUL-003: a rerun overwrites the load-mode GSAM files; Confidence platform-inferred.
- **PAUDBLOD:**
  - DEF-PDLD-003 and ERR-PDLD-002: after any permanent read error every further `READ` fails (status `46`), so the loop never ends.
  - DEF-PDLD-005: `ROOT-UNQUAL-SSA` is used by the root `ISRT`; removed from the unused list.
- **PAUDBUNL:**
  - BR-PDUL-003: `ROOT-SEG-KEY` is in the FD record area and set once per summary; the 2nd and later details rely on the record area surviving `WRITE`. New DEF-PDUL-005 and Q-PDUL-003, also referenced from PINV-e-authorization-05 and the UNLDPADB summary.
  - NUM-PDUL-001: the key is set for each summary read, including skipped ones.
  - DEF-PDUL-001: a write failure is not detected at all unless the close also fails.
- **Jobs and CSD:**
  - DEF-JAUPG-002: the other jobs use three IMS library prefixes, not one.
  - ERR-JDBPA-001: `DISP=(,CATLG,DELETE)` deletes the unload only on an abend; a failure reported by return code catalogs it.
  - NUM-JDBPA-002: the JOB-level `REGION=0K` overrides the step's `REGION=4M`; `TIME=30` added.
  - DEF-JDBPA-002: lines 1-44 carry trailing spaces (baseline entry cited).
  - DEF-JLDPA-002: wording of the merge (existing segments kept, deleted ones re-inserted).
  - FLOW-JCSD2-004: with static SQL the user needs `EXECUTE` on plan `AWS01PLN`, not table privileges.
  - DEF-JCSD2-001 and section 1: `RESSEC(NO)`/`CMDSEC(NO)` turn off resource and command security; attach security is outside the CSD.
  - DEF-JCSD2-004: the README also never defines transactions `CPVS` and `CPVD`, which it installs.

## Not changed

- The batch reviewer noted that DBUNLDGS and PAUDBUNL move `PA-ACCT-ID` to `ROOT-SEG-KEY` before the `IS NUMERIC` test, which could raise a data exception on the all-spaces root under some compiler options. It was offered as a possible risk, not a finding; BR-DBUL-001 and BR-PDUL-002 now carry it as a platform-inferred caveat, without a new DEF item.
- The CSD reviewer left `PRIORITY(HIGH)` in FLOW-JCSD2-004 as CORRECT but noted that its effect on open TCBs may be small in current CICS releases. The item is already platform-inferred.
- PAUDBLOD BR-PDLD-003: a wording nit (no `GU`, so no asterisk lines, for a non-numeric key) that section 4.3 already states.

## Sample data checks

The batch and job reviewers parsed `app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat` independently of the author: 51,736 bytes, 226 variable-length records (2 × 88-byte header and trailer, 22 × 140-byte summaries, 202 × 240-byte details), in the order header, summary, its details, ..., trailer. One summary has an all-spaces key (`X'404040404040'`, counts `X'4040'` = 16448) and no details. All 202 details have response code `00`; for every other summary the stored approved count equals its number of details and the declined count is 0. Detail dates run from 23292 to 24096 (`YYDDD`). This confirms the sample-data statements in CBPAUP0C section 15, DBUNLDGS section 15, PAUDBUNL section 15 and DBPAUTP0 SE-JDBPA-002.

## GnuCOBOL runtime checks

All checks used GnuCOBOL 3.2.0 with `cobc -x -std=ibm`; test 2 was also run with `-fbinary-truncate`. Scratch programs were run outside the repository.

**Test 1 (author), text comparison and year-end age**, used by CBPAUP0C BR-AUPG-001, BR-AUPG-005, NUM-AUPG-002 and DEF-AUPG-005. A `PIC 9(8)` counter is compared with an `X(05)` field as in `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:160, 360`, and the age of a 31 December detail on 1 January is computed through the stored complement as in `app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl:280-282`:

```cobol
       01 CNT        PIC 9(8) VALUE 0.
       01 FREQ       PIC X(5) VALUE '00001'.
       01 FREQ2      PIC X(5).
       01 WS-AUTH-DATE PIC 9(05).
       01 DIFF       PIC S9(4) COMP.
       01 CUR        PIC 9(05).
       01 D9C        PIC S9(5) COMP-3.
       ...
           PERFORM VARYING CNT FROM 1 BY 1 UNTIL CNT > 2000
              IF CNT > FREQ DISPLAY 'FIRST GREATER AT ' CNT
                 MOVE 3000 TO CNT END-IF
           END-PERFORM
           MOVE 5 TO FREQ2          *> then CNT 49999999 / 50000000 > FREQ2
           MOVE 10 TO FREQ2         *> then CNT 9999999 / 10000000 >= FREQ2
           COMPUTE D9C = 99999 - 24365
           MOVE 25001 TO CUR
           COMPUTE WS-AUTH-DATE = 99999 - D9C
           COMPUTE DIFF = CUR - WS-AUTH-DATE
```

| Check | Result |
| :-- | :-- |
| first counter value greater than `'00001'` | 1000 |
| `MOVE 5` to `X(05)` | `5    ` (left-aligned) |
| counter greater than `'5    '` | 49,999,999 no; 50,000,000 yes |
| counter greater than or equal to `'10   '` | 9,999,999 no; 10,000,000 yes |
| age of 24365 on 25001 | 636 |

The reviewer re-ran the comparisons independently with the same results.

**Test 2 (review), frequency default and day-difference size**, used by CBPAUP0C FLOW-AUPG-001, NUM-AUPG-001 and DEF-AUPG-004:

```cobol
       01 F       PIC X(05).
       01 D       PIC S9(4) COMP.
       01 A       PIC 9(05).
       01 B       PIC 9(05).
           MOVE '00000' TO F
           IF F = SPACES OR 0 OR LOW-VALUES ...   *> as CBPAUP0C.cbl:201
           MOVE '0    ' TO F
           IF F = SPACES OR 0 OR LOW-VALUES ...
           MOVE 25100 TO A MOVE 15050 TO B  COMPUTE D = A - B
           MOVE 00001 TO A MOVE 99365 TO B  COMPUTE D = A - B
```

| Check | `-std=ibm` | `-std=ibm -fbinary-truncate` |
| :-- | :-- | :-- |
| `00000` defaulted? | no (kept) | no (kept) |
| `0    ` defaulted? | yes | yes |
| 25100 - 15050 in `S9(4) COMP` | +10050 | +50 |
| 00001 - 99365 in `S9(4) COMP` | +31708 | -9364 |

`-fbinary-truncate` corresponds to the z/OS compiler option TRUNC(STD), the default build to TRUNC(BIN); which option the mainframe build uses is not in the repository, so the z/OS result is platform-inferred.
