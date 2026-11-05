---
id: a-signon-admin-review
title: Area A (sign-on and user administration), independent source review
source_rev: b5e0139
covers: [docs/modernization/spec/programs/COSGN00C.md, docs/modernization/spec/programs/COMEN01C.md, docs/modernization/spec/programs/COADM01C.md, docs/modernization/spec/programs/COUSR00C.md, docs/modernization/spec/programs/COUSR01C.md, docs/modernization/spec/programs/COUSR02C.md, docs/modernization/spec/programs/COUSR03C.md, docs/modernization/spec/areas/a-signon-admin-entities.md, docs/modernization/spec/areas/a-signon-admin-interactions.md]
---

# Area A: independent source review

The seven Area A specs were written from the source at `b5e0139` and then checked by three reviewer subagents that had not written them: one for `COSGN00C`, `COMEN01C` and `COADM01C`, one for `COUSR00C` and `COUSR01C`, one for `COUSR02C` and `COUSR03C`. Each reviewer checked **every** numbered item (BR, VAL, FLOW, SE, ERR, NUM, DEF and Q) against the COBOL, copybooks, BMS maps, symbolic maps and `CARDDEMO.CSD`, including the Source citations, and spot-checked the screen tables, message catalogs, decision tables, scenarios and paragraph coverage tables. None of these programs can run locally (CICS), so no item is runtime-verified except the COBOL semantics tested with GnuCOBOL (section 3).

Verdicts, as in [nl-pilot-check.md](nl-pilot-check.md): CORRECT, MINOR (right in substance, with an imprecise detail or citation), WRONG (the behaviour stated is not what the code does), PLATFORM (depends on CICS, VSAM, BMS or z/OS behaviour that cannot be run locally; the reviewer gave agreement and a confidence). For Q items, CORRECT means the question is legitimate and correctly framed.

## 1. Counts

Counts are for the items as they stood at review time (commits `083df46` and `95573cb`); items added because of the review (section 2.3) are not in them.

| Spec | Items | CORRECT | MINOR | WRONG | PLATFORM | WRONG / non-PLATFORM | WRONG+MINOR / non-PLATFORM |
| :-- | --: | --: | --: | --: | --: | --: | --: |
| COSGN00C | 41 | 33 | 0 | 0 | 8 | 0/33 = 0.0% | 0/33 = 0.0% |
| COMEN01C | 27 | 21 | 1 | 1 | 4 | 1/23 = 4.3% | 2/23 = 8.7% |
| COADM01C | 26 | 21 | 0 | 1 | 4 | 1/22 = 4.5% | 1/22 = 4.5% |
| COUSR00C | 40 | 29 | 4 | 0 | 7 | 0/33 = 0.0% | 4/33 = 12.1% |
| COUSR01C | 28 | 24 | 1 | 0 | 3 | 0/25 = 0.0% | 1/25 = 4.0% |
| COUSR02C | 37 | 30 | 3 | 0 | 4 | 0/33 = 0.0% | 3/33 = 9.1% |
| COUSR03C | 33 | 26 | 2 | 2 | 3 | 2/30 = 6.7% | 4/30 = 13.3% |
| **Area A** | **232** | **184** | **11** | **4** | **33** | **4/199 = 2.0%** | **15/199 = 7.5%** |

All 26 Q items were judged legitimate; two were sharpened (Q-MENU-002, Q-USRL-003). The reviewers agreed with 30 of the 33 PLATFORM items, several with added detail, and partly disagreed with 3 (DEF-USRL-001, DEF-USRL-002, DEF-USRA-004); all three were rewritten. 23 further errors were found in section text outside the numbered items (front matter, section 3, screen notes, message catalogs, decision tables, scenarios); all were corrected.

## 2. Findings and changes

All findings were fixed in the specs in commits `9df7198` (user programs) and `bd879a4` (sign-on and menus).

### 2.1 WRONG items

| Item | Finding | Change |
| :-- | :-- | :-- |
| DEF-MENU-003 | Said the main menu is reachable only through sign-on routing. The account and card programs (`COCRDLIC`, `COACTVWC`, `COACTUPC`, `COCRDSLC`, `COCRDUPC`) start without a commarea, initialise one with type `U` and transfer to `COMEN01C` on PF3 (`app/cbl/COCRDLIC.cbl:315-325, 384-404`). | Rewritten; new DEF-SGN-012 |
| DEF-ADMN-001 | Said only sign-on decides who reaches the admin menu. With the Db2 module, `COTRTLIC` and `COTRTUPC` start without a commarea, set type `A` and transfer to `COADM01C` on PF3 (`app/app-transaction-type-db2/cbl/COTRTLIC.cbl:515-525, 591-623`). | Rewritten, with COADM01C section 1 and the section 11 authorisation row |
| ERR-USRD-001 | Said the READ "other" response puts the cursor on first name. Both paths set the user ID length to -1 first (`app/cbl/COUSR03C.cbl:152, 184`), and BMS puts the cursor on the first -1 field in map order. | Corrected; new NUM-USRD-003 states the rule |
| ERR-USRD-002 | Same cursor error for the DELETE; also the colour is neutral after a successful READ (`app/cbl/COUSR03C.cbl:285`). | Corrected |

### 2.2 MINOR items and PLATFORM disagreements

| Item | Finding | Change |
| :-- | :-- | :-- |
| DEF-MENU-006 | Cited the DUMMY path of COADM01C instead of the PGMIDERR path. | Citation `app/cbl/COADM01C.cbl:270-277` |
| BR-USRL-006 | Missed the third PF7 outcome (page unchanged at the beginning of the file). | Added |
| FLOW-USRL-004 | The top message on PF7 to page 1 appears only when the list started at the first user. | Qualified |
| DEF-USRL-010 | The early sends show the previous page number on re-entry (`PAGENUM` is `FSET`). | Corrected |
| DEF-USRL-011 | "After a search" cannot cause a partial page; deletions or stale keys can. | Corrected, linked to DEF-USRL-014 |
| DEF-USRL-001, DEF-USRL-002 (PLATFORM, partly disagreed) | PF8 with a blank last key starts at high-values; CICS most likely positions at end of file (NORMAL), so no abend. | Rewritten; decision table row and Q-USRL-003 updated |
| ERR-USRL-001, SE-USRL-002 (PLATFORM, agreed with detail) | The PF7 path also abends after a NOTFND STARTBR; a deleted first-row user leads to `Unable to lookup User...`. | Added |
| VAL-USRA-002 | After a duplicate ID the cursor is on first name, not user ID (`app/cbl/COUSR01C.cbl:149, 265`). | Corrected, with the message catalog |
| DEF-USRA-004 (PLATFORM, partly disagreed) | Filler content is not "normally low-values": `STORAGECLEAR(NO)` and no VALUE clause. | Corrected |
| ERR-USRU-001 | Cursor on the user ID on the fetch path. | Corrected; new NUM-USRU-004 |
| DEF-USRU-001 | PF3 after a no-change save is not harmless: the record stays held across the transfer. | Corrected; new DEF-USRU-010 |
| DEF-USRU-002 | The record area is uninitialised in the new task, not "stale". | Corrected, with the decision table |
| DEF-USRU-009, SE-USRU-001 (PLATFORM, agreed with detail) | A plain READ does not wait for a record lock but may wait for the single VSAM string (`STRINGS(1)`). | Added |
| FLOW-USRD-004 | The form is cleared only after a successful DELETE. | Corrected |
| DEF-USRD-004 | Reload citation covered only the IDCAMS step. | `app/jcl/DUSRSECJ.jcl:23-88` |
| DEF-SGN-008, BR-MENU-004, DEF-MENU-001 (PLATFORM, agreed with detail) | Untouched password field returns underscores; INQUIRE PROGRAM checks the definition, not the load module; option 0 reads the count byte. | Added |

### 2.3 Omissions found, now items

| New item | What it records |
| :-- | :-- |
| DEF-SGN-012 [security] | Both menus can be reached without signing on (account and card programs, Db2 transaction-type programs). |
| DEF-SGN-013 | The attempted user ID stays in the commarea after a failed sign-on. |
| DEF-USRL-012 | Selection marks survive paging and then act on another user. |
| DEF-USRL-013 [security] | The selected user ID is taken from the terminal, not from server state. |
| DEF-USRL-014 | Stale first and last paging keys after a short page. |
| DEF-USRL-015 | The search field is read only by Enter and cleared after each forward page. |
| DEF-USRA-009 | User IDs with leading or embedded spaces are accepted. |
| NUM-USRU-004, NUM-USRD-003 | The BMS cursor rule and the message colour carry-over. |
| DEF-USRU-010 | The record stays held across the PF3 transfer. |
| DEF-USRU-011 [security] | No password rules on update. |

Also added to existing items: the privilege escalation path in DEF-USRA-005, the password echo in both directions in DEF-USRU-005, MAPFAIL on Clear and PA keys in the ERR "unchecked commands" items of the four user programs, `-` and `.` in the option field (NUM-MENU-001, Q-MENU-002), and the single VSAM string in the sign-on extraction contract. The items added in this section were written after the review and have not been independently re-checked.

## 3. COBOL semantics checked with GnuCOBOL 3.2 (`-std=ibm`)

Small test programs (not part of the repository), compiled by the spec author and by the reviewers:

| Behaviour | Result | Used by |
| :-- | :-- | :-- |
| `STRING 'User ' id DELIMITED BY SPACE ...` | `User TEST0001 has been deleted ...`; `AB CD` gives `User AB has been ...`; a leading space gives `User  has been ...` | NUM-USRA-002, NUM-USRU-002, NUM-USRD-001 |
| `9(08)` page number moved to the screen field | page 1 shows `00000001` | NUM-USRL-001 |
| Abbreviated condition `EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3` | PF8 skips a record, Enter does not | FLOW-USRL-005 |
| 160-byte commarea moved into the 194-byte area | the 34-byte extension is filled with spaces | ENT-COMMAREA-USER-ADMIN-EXT |
| Menu option normalisation | `05`, ` 5`, `5 ` give 5; `AB`, `A1`, low-values give 0; `1A`, `-1`, `1-` give 1 | NUM-MENU-001, NUM-ADMN-001 |
| Menu line building | `01. <name>`, 40 characters; the malformed `This option Accountis coming soon ...` | BR-MENU-001, DEF-MENU-004 |
| `FUNCTION UPPER-CASE` | `Pass wd1` becomes `PASS WD1` | BR-SGN-001 |

## 4. Status

With every WRONG finding fixed, all seven specs move to `spec_status: reviewed` (format.md, "Spec status"). The PLATFORM items, the BMS cursor rule and Q-USRL-003, Q-MENU-002 and Q-SGN-006 remain for the mainframe team. `check_spec.py --program` reports 0 errors and 0 warnings for each of the seven specs after the fixes.
