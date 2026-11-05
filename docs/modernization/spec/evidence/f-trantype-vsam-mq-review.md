# Area f-trantype-vsam-mq: review evidence

Independent review of the area F specs: the transaction type programs COTRTUPC, COTRTLIC and COBTUPDT, the MQ programs COACCT01 and CODATE01, the module jobs CREADB21, MNTTRDB2 and TRANEXTR, and the module CSD files CRDDEMOD and CRDDEMOM. The two area files `areas/f-trantype-vsam-mq-entities.md` and `areas/f-trantype-vsam-mq-interactions.md` were checked too. Source revision: 8a8a80a (the sources are unchanged up to cf63270).

## Method

Four reviewer agents worked independently of the author, read-only, each with its own scratch directory: one for COTRTUPC, one for COTRTLIC, one for COACCT01 and CODATE01 (with the MQ entity tables), and one for COBTUPDT, the five job specs and the area files. Each reviewer checked **every** item heading against the cited source lines and any other source it needed. It also spot-checked the interface tables (section 4), the message tables, the scenarios (section 15) and the paragraph coverage (section 16). Verdicts:

- **CORRECT:** matches the source.
- **IMPRECISE:** essentially right, but a detail (line range, length, condition, wording) is off or missing.
- **WRONG:** contradicts the source, or describes behaviour that does not happen.
- **UNVERIFIABLE:** depends on CICS, Db2, MQ or z/OS behaviour that the source cannot settle.

Where a reviewer could, it confirmed pure-COBOL claims with GnuCOBOL 3.2 (`cobc -x -std=ibm`). The author then checked each WRONG and IMPRECISE finding against the source before changing the spec. Every WRONG finding is fixed, and so is every IMPRECISE finding except the ones listed under "Not changed" below.

## Results per spec

| Spec | Items | CORRECT | IMPRECISE | WRONG | UNVERIFIABLE | Fixed in |
| :-- | --: | --: | --: | --: | --: | :-- |
| COTRTUPC (TTUP) | 59 | 43 | 10 | 6 | 0 | d87c5ff |
| COTRTLIC (TTLS) | 71 | 63 | 7 | 0 | 1 | 2087d16 |
| COACCT01 (MQAC) | 44 | 33 | 6 | 0 | 5 | 8140bf9 |
| CODATE01 (MQDT) | 38 | 31 | 1 | 0 | 6 | 8140bf9 |
| COBTUPDT (TTBU) | 35 | 29 | 6 | 0 | 0 | e90d10f |
| CREADB21 (JCRDB) | 17 | 13 | 3 | 0 | 1 | e90d10f |
| MNTTRDB2 (JMNTT) | 9 | 9 | 0 | 0 | 0 | - |
| TRANEXTR (JTREX) | 18 | 15 | 3 | 0 | 0 | e90d10f |
| CRDDEMOD-CSD (JCSDD) | 8 | 7 | 1 | 0 | 0 | e90d10f |
| CRDDEMOM-CSD (JCSDM) | 9 | 9 | 0 | 0 | 0 | - |
| **Total** | **308** | **252** | **37** | **6** | **13** | |

Error rate: **WRONG 6 of 308 items (1.9%)**, all in COTRTUPC (6 of 59, 10.2%). WRONG or IMPRECISE: 43 of 308 (14.0%). The specs already marked all 13 UNVERIFIABLE items platform-inferred. All items were checked, so every spec meets the "reviewed" rule in `format.md` (all items, or at least half) and is now `spec_status: reviewed`.

Two items were added after the review, at the orchestrator's request, from an area A finding: DEF-TTLS-017 and DEF-TTUP-017 (a direct start followed by PF3 reaches the admin menu without sign-on). The author checked them against `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:515-525, 591-623`, `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:366-374, 429-460` and `app/cbl/COADM01C.cbl:86-95`. They are not part of the counts above.

## WRONG findings (all COTRTUPC, all fixed)

All six come from one misreading. The first-entry test at `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:366-370` checks `CDEMO-FROM-PROGRAM` and the context in the working-storage copy of the commarea **before** `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:376` loads it from `DFHCOMMAREA`. In practice, therefore, only `EIBCALEN` = 0 takes the "initialise" branch. The author confirmed this in the source: `COPY COCOM01Y` at `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:292` is a separate 01-level with no VALUE clauses, outside the `WS-COMMAREA` that `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:352-354` initialises.

| Item | Finding | Fix |
| :-- | :-- | :-- |
| FLOW-TTUP-001 | Said ENT-COMMAREA is initialised on entry from COADM01C or COTRTLIC. | Only on a direct start. Otherwise the commarea is restored, the dispatch at `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:465-473` clears only the extension, the work fields and `CDEMO-ACCT-ID`, and the user ID, user type and from-program are kept. |
| FLOW-TTUP-003 | Said the PF3 target program is "always blank in practice". | PF3 goes to the caller: `COADM01C`/`CA00` from the menu, `COTRTLIC`/`CTLI` from the list. |
| VAL-TTUP-002 | Said the state ends K. | K is set and overwritten at once (`app/app-transaction-type-db2/cbl/COTRTUPC.cbl:729-730`). The entered code is cleared from the screen and the field is shown empty in red. |
| VAL-TTUP-003 | Same as VAL-TTUP-002. | Same fix. |
| DEF-TTUP-009 | Said the commarea is wiped on entry and PF3 never returns to the list. | Rewritten. The test reads the commarea before restoring it, and the restore reads bytes 161-265 past the 160-byte commarea. PF3 returns to the caller. |
| DEF-TTUP-014 | Said the next Enter after F12 always gives state E. | A valid change gives N. No change, or an invalid change, gives E. |

The same misreading also affected section 4.3, the module extraction notes, section 11, scenarios S3 and S16, the interaction edge COTRTUPC → COADM01C and the mermaid state diagram. All of these were corrected. A new scenario S18 covers PF5 on the delete confirmation screen, which abends.

## Main IMPRECISE findings fixed

- **COTRTUPC:**
  - BR-TTUP-004: the foreign key citation is now `app/app-transaction-type-db2/ctl/DB2CREAT.ctl:96-99`.
  - BR-TTUP-005: covers every state that reaches the comparison.
  - FLOW-TTUP-004: K is never stored, V is declared but never set, and PF5 in states 9 and 8 abends.
  - FLOW-TTUP-005: the edits set E or N before the decision runs.
  - FLOW-TTUP-011: the type code is editable in states L and F.
  - ERR-TTUP-001: a positive SQLCODE still stores the "old" fields.
  - ERR-TTUP-006: adds PF5 in state 9.
  - DEF-TTUP-003: the path goes through state E.
  - DEF-TTUP-007: seed citation corrected.
  - DEF-TTUP-010: PF3 returns to the caller.
- **COTRTLIC:**
  - VAL-TTLS-001 and VAL-TTLS-002: the page number shows 0 after a filter change.
  - ERR-TTLS-001: the DSNTIAC length prefix is 0, because `INITIALIZE` wipes `VALUE +720`.
  - ERR-TTLS-002: a positive update warning is committed at task end without a message.
  - DEF-TTLS-004: the success screen also shows the "No change detected" message.
  - DEF-TTLS-005: 2-digit numeric codes.
  - FLOW-TTLS-002: see "Rejected finding" below.
  - Also fixed outside the items: the confirmation bypass through paging and filter changes (added to DEF-TTLS-003), section 4.1, section 4.3 and S6.
- **COACCT01 and CODATE01:**
  - BR-MQAC-005 and BR-MQDT-003: the reply's Format and CCSID are overwritten.
  - SE-MQAC-004: the error record before the first get, and termination loops.
  - ERR-MQAC-004: the previous request was already committed, and a get warning other than 2080 consumes the request.
  - DEF-MQAC-001: README response layout and listener role.
  - DEF-MQAC-006 and DEF-MQDT-011: INITIALIZE and blanking statements that have no effect.
  - DEF-MQAC-009: removed a false claim about comments.
  - Section 5 of both specs: `IS INITIAL` with `STORAGECLEAR(NO)`.
  - ENT-MQ-ERROR-MSG: RESP and RESP2 truncation.
- **COBTUPDT:**
  - BR-TTBU-006, SE-TTBU-004 and ERR-TTBU-002: SQLCODE -911 rolls back the whole unit of work and processing continues.
  - NUM-TTBU-002: GnuCOBOL edits `-10405` as `-0405`; on z/OS the truncate-then-edit result ` -405` is expected (platform-inferred).
  - NUM-TTBU-004: the online programs left-trim, and seed rows keep the literal length.
  - DEF-TTBU-001: a missing dataset is a JCL error and an authorisation failure is S913; after a later read error the last good record is re-applied.
- **Jobs:**
  - ERR-JCRDB-002 and DEF-JCRDB-002: FREEPLN frees `COTRTLIC` names that no build creates, so RC 8 is expected on every run and the category load is skipped.
  - DEF-JCRDB-006 and FLOW-JCSDD-003: the plan name comes from `samples/jcl/CICDBCMP.jcl:42`.
  - BR-JTREX-001 and BR-JTREX-002: the 60-byte length is platform-inferred because of the DSNTIAUL null indicator.
  - ERR-JTREX-001: STEP50 still runs after RC 4 in STEP10 or STEP20.
  - CRDDEMOM-CSD section 5: citation corrected.
- **Area files:**
  - `TR_DESCRIPTION` stored length.
  - Seed citations corrected.
  - file:line citations instead of spec section references in the edges table.
  - The -911 caveat on PINV-f-02.
  - The PF3 return COTRTUPC → COTRTLIC.

## Rejected finding

The COTRTLIC reviewer reported, outside the items, that PF3 in COTRTUPC never returns to COTRTLIC (its finding N1), and on that basis rated FLOW-TTLS-002 IMPRECISE. That finding relied on the COTRTUPC spec's DEF-TTUP-009, which the COTRTUPC reviewer showed to be wrong, and the author confirmed against the source. When COTRTLIC is the caller, COTRTUPC keeps its commarea, so PF3 transfers to `COTRTLIC` with `CDEMO-FROM-TRANID` = `CTTU` and context 0 (`app/app-transaction-type-db2/cbl/COTRTUPC.cbl:429-460`). COTRTLIC's fresh-entry test (`app/app-transaction-type-db2/cbl/COTRTLIC.cbl:544-554`) then applies as FLOW-TTLS-002 says. FLOW-TTLS-002 is therefore unchanged, and it is counted as IMPRECISE above only because that was the reviewer's verdict.

DEF-TTLS-012, which the reviewer rated CORRECT, repeated the same wrong premise ("COTRTUPC clears the commarea"). Its reason was rewritten: COTRTUPC never reads the user type, and its PF3 sets `A`.

## Not changed

- MNTTRDB2: the reviewer noted Control-M oddities that no item states: no INCOND, a folder name used twice, `PARENT_FOLDER` set to the disclosure group folder, and a condition deleted by both smart folders. They are left for area G, which owns `app/scheduler/`.
- The remaining items marked "minor" were not changed: §4 omissions of SYSPRINT/SYSUDUMP DDs and unused CSD attributes.

## GnuCOBOL runtime checks

All checks used GnuCOBOL 3.2 with `cobc -x -std=ibm`. Scratch programs were run outside the repository.

**Author test 1 (type code and text functions)**, used by NUM-TTUP-001, BR-TTUP-001, VAL-TTUP-006, NUM-TTUP-004 and DEF-TTUP-008. The program applies `FUNCTION TEST-NUMVAL`, then `NUMVAL` into `PIC 9(02)`, to each 2-character input. It also takes the `TRIM` length of 50 spaces and the centring position of `Changes validated.Press F5 to save` in 40 characters:

| Input | TEST-NUMVAL | Result in 9(02) |
| :-- | :-- | :-- |
| `1 ` | 0 | `01` |
| ` 5` | 0 | `05` |
| `-1` | 0 | `01` |
| `1.` | 0 | `01` |
| `A1` | 1 | rejected |
| `00` | 0 | `00` (rejected later as zero) |
| `+3` | 0 | `03` |
| `.5` | 0 | `00` (0.5 passes the zero check) |

`LENGTH(TRIM(50 spaces))` = 0. The message length is 34, and it starts at position 4.

**Author test 2 (COBTUPDT message and file handling)**, used by NUM-TTBU-002, NUM-TTBU-003, ERR-TTBU-002, ERR-TTBU-004 and DEF-TTBU-001:
- Moving SQLCODE (`S9(9) COMP`) to `PIC ----9` gives ` -803`, ` -532` and `-0405` (for -10405).
- A `STRING ... DELIMITED BY SIZE INTO` an 80-byte field that is not cleared leaves the old text behind: `No records found.TRANSACTION_TYPE table. SQLCODE:-0405` and `ERROR: TYPE NOT VALIDSACTION_TYPE table. SQLCODE:-0405`.
- OPEN of a missing file gives status 35. Every following `READ ... INTO` gives status 47, the AT END flag is never set and the INTO record stays blank, so the loop does not end.

**Reviewer checks:**
- COTRTUPC: the NUMVAL table above, and `-0` rejected as zero.
- COTRTLIC:
  - `INITIALIZE` zeroes a `COMP VALUE +720` field.
  - `----9` edits 100, -532 and 0 as `  100`, ` -532` and `    0`.
  - A `PIC 9(1)` counter wraps from 9 + 1 to 0, and 0 - 1 gives 1.
  - Centring positions are 3 for 40 characters and 10 for 27.
  - The `TRIM` length of spaces is 0.
- MQ:
  - The invalid-request reply text is 63 bytes, and the RETRIEVE error text is exactly 25 bytes.
  - `ERROR WHILE READING ACCTFILE` is cut to `ERROR WHILE READING ACCTF`.
  - Moving 84 into the 2-digit field gives `84`, and -123456 into the 5-digit field gives `23456`.
  - `INITIALIZE ... REPLACING NUMERIC BY ZEROES` leaves the alphanumeric fields unchanged.
- Batch: all the COBTUPDT results above reproduced. The reviewer also showed that truncating to `S9(4)` before the edit gives ` -405`.

These checks cover the COBOL language behaviour only. CICS, Db2 and MQ behaviour (SQLCODE values, DSNTIAC output, syncpoint and backout, trigger monitor) cannot run locally, and the specs mark it platform-inferred.

## Cross-area links

DEF-TTLS-017 and DEF-TTUP-017 refer to area A's DEF-ADMN-001 and PINV-a-signon-admin-01. The interactions file adds PINV-f-06. These IDs resolve only once area A (branch `nl/A`) is merged. The full `check_spec.py` run passed with 0 errors and 0 warnings on a temporary tree made of nl/F, origin/modernization and nl/A.
