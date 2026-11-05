---
id: b-accounts-cards-review
title: Area b-accounts-cards, independent source review of the NL specs
source_rev: b5e0139
covers: [docs/modernization/spec/programs/COCRDSLC.md, docs/modernization/spec/programs/COACTVWC.md, docs/modernization/spec/programs/COCRDUPC.md, docs/modernization/spec/programs/COCRDLIC.md, docs/modernization/spec/programs/COACTUPC.md]
---

# Area b-accounts-cards: independent source review

Each spec was written by one subagent and checked by a different worker subagent that had not written it. The reviewer read the whole program source with line numbers, the copybooks, the BMS map and symbolic map, the cited CSD and JCL lines, the calling programs at the cited lines and the sample data, and gave a verdict for every item. The scope was every numbered item, every Q row and every row of the screen field tables, message catalogs and in-item decision tables, so the reviewed share is 100% of the items, well above the one third the area rules require. The writer then checked each finding against the source and fixed it; no finding was disputed unless stated below.

Verdicts:

- **CORRECT:** the item matches the source.
- **WRONG:** a statement in the item contradicts the source.
- **IMPRECISE:** right in substance, with a missing case, an imprecise detail, a wrong citation or a confidence tag that is too strong.
- **UNVERIFIABLE:** the item depends on CICS, VSAM, BMS or compiler behaviour that the source cannot show and that cannot be run locally; the reviewer confirmed the source part and that the item is tagged `platform-inferred`.
- **Omission:** behaviour that no item described. Each omission became a new item or an extension of an existing one.

Error rates are computed on the checked items that are not UNVERIFIABLE, as in [nl-pilot-check.md](nl-pilot-check.md).

## 1. Summary

| Spec | Items + Q in the spec | Units checked | CORRECT | WRONG | IMPRECISE | UNVERIFIABLE | Omissions | WRONG / non-UNVERIFIABLE | WRONG+IMPRECISE / non-UNVERIFIABLE |
| :-- | --: | --: | --: | --: | --: | --: | --: | --: | --: |
| COCRDSLC | 53 + 6 | 129 | 122 | 1 | 4 | 2 | 4 | 1/127 = 0.8% | 5/127 = 3.9% |
| COACTVWC | 59 + 9 | 156 | 144 | 3 | 6 | 3 | 5 | 3/153 = 2.0% | 9/153 = 5.9% |
| COCRDUPC | 77 + 8 | 164 | 148 | 2 | 10 | 4 | 4 | 2/160 = 1.3% | 12/160 = 7.5% |
| COCRDLIC | 65 + 9 | 169 | 146 | 2 | 16 | 5 | 6 | 2/164 = 1.2% | 18/164 = 11.0% |
| COACTUPC, validation part | 79 + 10 | 88 | 70 | 6 | 9 | 3 | 5 | 6/85 = 7.1% | 15/85 = 17.6% |
| COACTUPC, flow and update part | 75 + 10 | 125 | 101 | 10 | 11 | 3 | 4 | 10/122 = 8.2% | 21/122 = 17.2% |
| **Area B** | **408 + 52** | **831** | **731** | **24** | **56** | **20** | **28** | **24/811 = 3.0%** | **80/811 = 9.9%** |

"Items + Q in the spec" are the counts after the fixes; "Units checked" are the counts at review time (items, Q rows and every table row, catalog row and scenario the reviewer judged one by one). Every item was checked, so the reviewed share is 100% in every spec; COACTUPC had two reviewers, one for the validation rules and messages and one for the flow and save path.

Findings in section text outside the counted units: COCRDSLC 2 (1 WRONG, 1 IMPRECISE), COCRDUPC 4 (2 WRONG, 2 IMPRECISE), COACTUPC validation part 3. All were fixed.

Most errors cluster in two patterns, which a target team should keep in mind for the remaining CICS programs:

- **Fields that the program never sets.** `INITIALIZE` skips FILLER, and a record built in working storage writes whatever the storage holds (DEF-CRDU-003, DEF-ACUP-020). The writers first assumed "spaces".
- **Flags set before the step that is supposed to set them.** The card update and account update programs set their "found" flags in the edit step, so a later failed re-read still looks successful (DEF-CRDU-021, DEF-ACUP-019). The card list lowers the page number before the message step tests it (DEF-CRDL-019).

The COACTUPC rates are higher because it is the largest program (4,236 lines, 34 validation rules) and its save path has the two patterns above; its reviewers compared every message text in both catalogs with the source literals, and the validation reviewer recomputed every message length by script.

All item IDs are stable: fixes changed text, and the omissions became new items at the end of each series (DEF-CRDV-014, DEF-ACVW-018, DEF-CRDU-021, DEF-CRDL-019, DEF-ACUP-019, DEF-ACUP-020, DEF-ACUP-122, Q-ACUP-010).

## 2. Per program

### COCRDSLC (card detail view, unit CRDV)

Checked: 52 headed items, 6 Q rows, 26 screen field rows, 11 message catalog rows and 34 rows of in-item tables (129). Every quoted message was compared with the source literals by script.

| Finding | Verdict | Change in the spec |
| :-- | :-- | :-- |
| ERR-CRDV-004 said Enter on an unchanged empty screen gives MAPFAIL. `TRNNAME` and `ERRMSG` are FSET and the search fields are sent with the modified flag, so only PA1, PA2 and Clear give MAPFAIL. | WRONG | Rewritten; outcome on screen unchanged |
| BR-CRDV-001: the commented-out account line would not join the key (key is the 16-byte card number). | IMPRECISE | Reason corrected |
| BR-CRDV-003: re-show works only while the protected fields come back; Clear or PA keys lose the card. | IMPRECISE | Case added, tag split |
| FLOW-CRDV-002: "PA keys send no data" is 3270 behaviour. | IMPRECISE (tag) | platform-inferred added |
| VAL-CRDV-008 row 2: first-entry display depends on the caller. | IMPRECISE | Row rewritten |
| DEF-CRDV-006, DEF-CRDV-007 | UNVERIFIABLE | None; already platform-inferred and in Q-CRDV-005 |
| O1: Clear or PA key on the list-called screen loses the card | Omission | New DEF-CRDV-014 |
| O2: PF3 hands the edited account and card back to the list, which shows them | Omission | Module notes and shared contract extended |
| O3, O4: `WS-TRANID` and two never-shown texts missing from the unused lists | Omission | Added |
| Section 11 said the sign-on program reads `CDEMO-USER-ID`; it only sets it, nothing reads it | prose, WRONG | Corrected |
| Section 11 exit row said the list starts fresh | prose, IMPRECISE | Corrected (see O2) |

### COACTVWC (account view, unit ACVW)

Checked: 58 headed items with their in-item tables, 5 decision table rows, 10 message catalog rows, 9 Q rows, 73 screen field rows and the screen table introduction (156). The reviewer also compiled GnuCOBOL probes for the edited money picture, the RESP-to-text moves, the STRING truncation at 75 characters, the SSN format and the duplicate `0000-MAIN-EXIT` paragraph, and recomputed the sample-data values in the scenarios.

| Finding | Verdict | Change in the spec |
| :-- | :-- | :-- |
| FLOW-ACVW-013 listed `SEND-LONG-TEXT-EXIT` as a PERFORM THRU end; the paragraph is never performed | WRONG (minor) | Removed, note added |
| NUM-ACVW-005: the customer text keeps 7 RESP2 digits, all zero only below 100 | WRONG (minor) | Corrected |
| Section 4.1 intro: not all data fields are underlined | WRONG (minor) | Corrected |
| BR-ACVW-001: `READINTEG` has no effect on non-RLS files; conclusion (no lock) holds | IMPRECISE | Reason corrected |
| BR-ACVW-002: several cards per account give DUPKEY, nothing copied | IMPRECISE | Corrected, refers to DEF-ACVW-003 |
| BR-ACVW-005: customer and card number stay stale after an input or cross-reference error; card programs read `CDEMO-ACCT-ID` only from the list | IMPRECISE | Extended |
| FLOW-ACVW-010: `DFHDFCOL` gives the map colour (green) | IMPRECISE | Corrected, tag added |
| DEF-ACVW-014, DEF-ACVW-017: citations | IMPRECISE (citation) | Citations fixed, `WS-TRANID` added |
| VAL-ACVW-007, DEF-ACVW-003, DEF-ACVW-015 | UNVERIFIABLE | None; already platform-inferred |
| O1: stale customer and card number in the commarea after a failed lookup | Omission | New DEF-ACVW-018 |
| O2 to O5: smaller omissions (tags on VAL-ACVW-001, -004, -005 and further details) | Omission | Added |

### COCRDUPC (card update, unit CRDU)

Checked: 76 headed items with their in-item tables, 25 cells of the user-level decision table, 22 message catalog rows plus the never-shown line, 28 screen field rows, 8 Q rows and 24 equivalence scenarios (164). The reviewer compiled GnuCOBOL probes for the expiry redefinitions, the all-zero short input, the CVV move from spaces, `TRIM` of spaces and `INITIALIZE` of a group with FILLER, and confirmed the three main defects (DEF-CRDU-001 card moved to any account, DEF-CRDU-002 CVV written as 000, DEF-CRDU-003 FILLER written from commarea bytes).

| Finding | Verdict | Change in the spec |
| :-- | :-- | :-- |
| FLOW-CRDU-009: the "found" information flag never reaches the screen; it only makes a PF12 re-read look successful | WRONG | Rewritten |
| DEF-CRDU-014: wrong target of `app/cbl/COCRDUPC.cbl:675-677`, wrong count of unused messages, missing unused items | WRONG | Rewritten |
| Section 5 and section 16 counts | prose, WRONG (2) | Corrected (7 condition names, 6 texts; 43 + 2 paragraphs) |
| FLOW-CRDU-010, FLOW-CRDU-011, SE-CRDU-001, ERR-CRDU-001, VAL-CRDU-004: a failed PF12 re-read still sets state Details | IMPRECISE (5) | Exception added to each |
| VAL-CRDU-001, VAL-CRDU-002: short all-zero entry gives "not provided" on GnuCOBOL | IMPRECISE (2) | Row added, platform-inferred, Q-CRDU-006 extended |
| BR-CRDU-001: key fields are open but ignored on the failure screens | IMPRECISE | Corrected |
| DEF-CRDU-004: dispatch row 1 can fire before the extension is cleared | IMPRECISE | Corrected |
| DEF-CRDU-019: commarea positions of CVV and card number | IMPRECISE | Corrected |
| Section 8.3 header and section 6.1 diagram | prose, IMPRECISE (2) | Corrected |
| SE-CRDU-005, DEF-CRDU-005, DEF-CRDU-012, DEF-CRDU-015 | UNVERIFIABLE | None; already platform-inferred |
| O1: failed PF12 re-read shows Details with stale values | Omission | New DEF-CRDU-021, scenario S25 |
| O2: short all-zero entry | Omission | VAL rows, Q-CRDU-006, scenario S26 |
| O3: FILLER source on a direct start | Omission | DEF-CRDU-003 extended |
| O4: unused items | Omission | DEF-CRDU-014 and section 5 |

### COCRDLIC (card list, unit CRDL)

Checked: 64 headed items with their in-item tables, 35 rows of the paging and selection decision tables, 11 message catalog rows, 27 screen field rows, 9 Q rows and 23 equivalence scenarios (169). The reviewer confirmed the paging mechanics (7 rows per page, first and last card key, STARTBR with "greater or equal" in both directions, the read-ahead that skips the filter, the page number wrapping after 9), the admin-versus-user question (DEF-CRDL-001: the user type is only ever set, never tested, and the filters compare only with the values typed on the screen), and the 254-byte commarea extension, with a GnuCOBOL compile of the level structure.

| Finding | Verdict | Change in the spec |
| :-- | :-- | :-- |
| Paging table row "PF7, page above 1": PF7 from page 2 lowers the page to 1 first (`app/cbl/COCRDLIC.cbl:508`), so the message step (`app/cbl/COCRDLIC.cbl:901-904`) shows `NO PREVIOUS PAGES TO DISPLAY` with a blank information line | WRONG | Row split into "from page 2" and "from page 3 or higher" |
| Scenario S3 (PF7 from page 2) left out that message | WRONG | Corrected; marked current behaviour |
| BR-CRDL-006, BR-CRDL-008, FLOW-CRDL-016 row 2, catalog row M5, DEF-CRDL-009: same root cause | IMPRECISE (5) | Corrected |
| VAL-CRDL-001, VAL-CRDL-002 and their table row: a filter of only zeros and blanks passes the zero test before the numeric test and counts as no filter (GnuCOBOL-confirmed) | IMPRECISE (3) | Row added, platform-inferred for Enterprise COBOL, Q-CRDL-005 extended |
| VAL-CRDL-005: whether lower-case `s`/`u` reaches the program depends on terminal upper-case translation | IMPRECISE (tag) | platform-inferred added |
| VAL-CRDL-007: PF7/PF8 messages also replace a selection error | IMPRECISE | Corrected |
| FLOW-CRDL-001: direct start with PF3 or PF7 | IMPRECISE | Cases added |
| DEF-CRDL-003: the card and account programs also start without sign-on | IMPRECISE | Corrected |
| DEF-CRDL-008: the PF7 consequence needs at least 7 earlier matches | IMPRECISE | Cases added |
| S19: Clear and PA keys drop the filters | IMPRECISE | Split into S19 and new S24 |
| Screen rows `TRNNAME`, `ERRMSG` leave out FSET | IMPRECISE (2) | Corrected |
| ERR-CRDL-002, ERR-CRDL-003, ERR-CRDL-004, DEF-CRDL-012, DEF-CRDL-017 | UNVERIFIABLE | None; already platform-inferred (DEF-CRDL-017 citation extended) |
| O1: misleading message when paging back to page 1 | Omission | New DEF-CRDL-019 |
| O2: filters of only zeros and blanks | Omission | VAL rows, Q-CRDL-005 |
| O3: direct start with PF3 or PF7 | Omission | FLOW-CRDL-001 |
| O4: page 0 after the wrap, PF7 there | Omission | NUM-CRDL-001, DEF-CRDL-010 |
| O5: selection error lost with PF7 on page 1 or PF8 at the end | Omission | DEF-CRDL-007, VAL-CRDL-007 |
| O6: next-page indicator is a space after a reset (no visible effect) | Omission | Note in section 4.3 |

### COACTUPC (account update, unit ACUP)

COACTUPC was written in two parts, and each part was reviewed by a different reviewer.

**Validation part** (section 8.1 to 8.12, items numbered 101 and above, VAL-ACUP-001 to VAL-ACUP-034, the coverage rows `1100` to `1280` and `CSUTLDPY`). Checked: 88 items and Q rows, plus every table row (the decision tables under the VAL items, all 25 rows of the edit order table, 36 message catalog rows, 44 rules-at-a-glance rows, 52 coverage rows and 17 data rows). The reviewer recomputed the lookup table sizes in `CSLKPCDY.cpy` (490, 410, 80, 56 and 240 entries), every message length, and the sample-data statistics by script: none of the 50 sample customers passes all edits (state and ZIP 44, phone 1 area code 26, phone 2 area code 25, FICO below 300 21, state 5, SSN part 1 4, last name 1).

| Finding | Verdict | Change in the spec |
| :-- | :-- | :-- |
| VAL-ACUP-009, VAL-ACUP-014, VAL-ACUP-029, NUM-ACUP-103: message lengths off by one (64, 66, 65; longest 66) | WRONG (4) | Corrected, catalog intro too |
| DEF-ACUP-107: month and day are rewritten by NUMVAL before CSUTLDTC is called; only the saved date keeps the space | WRONG | Rewritten |
| DEF-ACUP-116: the duplicated unused condition name cannot be referenced at all; two unused texts missing | WRONG | Corrected |
| BR-ACUP-102: a blank received field becomes low-values, `TRIM` does not remove them, so a field blank in the file always counts as changed; for the sample data `No change detected with respect to values fetched.` can never appear | IMPRECISE | Rule extended; new DEF-ACUP-122; DEF-ACUP-110 and the scenarios S6 and S17 corrected |
| BR-ACUP-104: follow-on checks run only after the field's basic check | IMPRECISE | Corrected, intro too |
| VAL-ACUP-007: `-5`, `+5`, `5.` accepted as 5 | IMPRECISE | Note added, platform-inferred |
| VAL-ACUP-023, VAL-ACUP-027: the alphabetic edit allows spaces | IMPRECISE (2) | Titles and text corrected |
| ERR-ACUP-102: data-exception risk wider than stated | IMPRECISE | Extended |
| DEF-ACUP-103: the missing END-IF has no visible effect | IMPRECISE | Corrected; fidelity now incidental |
| DEF-ACUP-119: two punctuation statements wrong | IMPRECISE | Corrected |
| Data division coverage row | IMPRECISE | Merged into one row |
| ERR-ACUP-101, VAL-ACUP-012, DEF-ACUP-105 | UNVERIFIABLE | None; already platform-inferred |
| Omissions: blank stored field counts as a change; day and month input forms; unused texts; the follow-on check rule; the "change nothing" acceptance scenario | Omission (5) | DEF-ACUP-122 and extensions listed above |

**Flow and update part** (items numbered 001 to 099, Q-ACUP-001 to Q-ACUP-009, sections 2 to 6, 8.13, 11, 15 and the other coverage rows). Checked: 82 items and Q rows, 15 section units (the screen field table, files, commarea, calls, the data table, the state diagram and dispatch text, the flow message catalog, the interactions and the coverage rows) and 28 equivalence scenarios (125). The reviewer compiled a GnuCOBOL probe that confirmed `INITIALIZE` leaves FILLER unchanged and the `+ZZZ,ZZZ,ZZZ.99` renderings, and recomputed the scenario values from the sample data.

| Finding | Verdict | Change in the spec |
| :-- | :-- | :-- |
| BR-ACUP-006, SE-ACUP-006, NUM-ACUP-010, DEF-ACUP-002, section 5 table: the save fills the rest of both records with spaces. The update records are in working storage and their FILLER (account 113-300, customer 333-500) is never set, so whatever the task's storage holds is written, including over the real group ID slot | WRONG (5) | Corrected; new DEF-ACUP-020 and Q-ACUP-010 |
| BR-ACUP-004: a failed F12 re-read still moves to the shown state, because the found flags were set before the read | WRONG | Corrected; new DEF-ACUP-019 |
| Section 6 diagram: Enter in the error state with every value restored stays in the error state with `No change detected with respect to values fetched.` | WRONG | Corrected |
| FLOW-ACUP-005 and section 4.3: `CDEMO-LAST-MAPSET` is 7 characters, so it holds `COACTUP` | WRONG (2) | Corrected |
| DEF-ACUP-018: three literals listed as unused are used | WRONG | List corrected |
| FLOW-ACUP-009 row 1, FLOW-ACUP-010, ERR-ACUP-001, ERR-ACUP-003, section 6 re-entry text: same F12 cause | IMPRECISE (5) | Corrected |
| SE-ACUP-007, S10, section 11 shared contract 2: same FILLER cause | IMPRECISE (3) | Corrected |
| FLOW-ACUP-016, FLOW-ACUP-020, NUM-ACUP-002 | IMPRECISE (3) | Corrected |
| DEF-ACUP-012, SE-ACUP-008, DEF-ACUP-008 | UNVERIFIABLE | None; already platform-inferred |
| O1: empty fields, including the phone pattern, are saved as low-values | Omission | BR-ACUP-006, NUM-ACUP-003 |
| O2: failed F12 re-read | Omission | DEF-ACUP-019 |
| O3: Enter in the error state with restored values | Omission | FLOW-ACUP-009 row 3 |
| O4: no question about the FILLER content | Omission | DEF-ACUP-020, Q-ACUP-010 |
