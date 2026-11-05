# Area d1-core-batch: entity proposals

Proposals from the core batch area (CBACT01C-04C, CBCUS01C, CBTRN01C, CBTRN03C) for `../entities.md`, written at commit 8a8a80a. New entity IDs below are used by the area's specs; the customer and card records use the IDs `ENT-CUSTOMER` and `ENT-CARD` that the spec lead is adding, so this file only proposes corrections and observations for them.

## ENT-DISC-GROUP · Disclosure group (interest rate per group, type and category)

- **Copybook:** `app/cpy/CVTRA02Y.cpy:4-10` (`DIS-GROUP-RECORD`, 50 bytes)
- **Dataset:** `AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS`, KSDS, `KEYS(16 0)`, `RECORDSIZE(50 50)` (`app/jcl/DISCGRP.jcl:36-49`), loaded by REPRO from `AWS.M2.CARDDEMO.DISCGRP.PS` (`app/jcl/DISCGRP.jcl:54-62`). **Batch DD:** `DISCGRP` (INTCALC). Not defined in CICS.
- **Primary key:** `DIS-GROUP-KEY` = `DIS-ACCT-GROUP-ID` + `DIS-TRAN-TYPE-CD` + `DIS-TRAN-CAT-CD`.

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `DIS-ACCT-GROUP-ID` | 1 | 10 | `X(10)` | text(10) | Disclosure group; matched against `ENT-ACCOUNT.ACCT-GROUP-ID`; `DEFAULT` is the fallback group |
| `DIS-TRAN-TYPE-CD` | 11 | 2 | `X(02)` | text(2) | Transaction type (ENT-TRAN-TYPE) |
| `DIS-TRAN-CAT-CD` | 13 | 4 | `9(04)` | digits(4) | Category within the type (ENT-TRAN-CATEGORY) |
| `DIS-INT-RATE` | 17 | 6 | `S9(04)V99` | decimal(6,2) | Annual interest rate in percent (`00150{` = 15.00); INTCALC charges balance × rate / 1200 per month |
| FILLER | 23 | 28 | `X(28)` | text | Unused; 28 zeros in the sample data |

Sample `app/data/ASCII/discgrp.txt`: 51 records, three groups with the same 17 type and category keys each (`01`/0001-0004, `02`/0001-0003, `03`/0001-0003, `04`/0001-0003, `05`/0001, `06`/0001-0002, `07`/0001).

| Group | Rates |
| :-- | :-- |
| `A000000000` | 15.00 for 01/0001, 04/*, 05/0001, 06/*, 07/0001; 25.00 for 01/0002-0004; 0.00 for types 02 and 03 |
| `DEFAULT` | the same as `A000000000` except 07/0001 = 0.00 |
| `ZEROAPR` | 0.00 for every key |

Data observation: no sample account has a group in `ACCT-GROUP-ID` (Q-ENT-001), so INTCALC always uses `DEFAULT`; filling in `A000000000` changes nothing on the sample cycle (CBACT04C DEF-INTC-006).

## ENT-DATE-PARM · Report date range parameter (TRANREPT)

- **Defined in:** `app/cbl/CBTRN03C.cbl:87-88, 122-125` (no copybook). **Dataset:** `AWS.M2.CARDDEMO.DATEPARM` (`app/jcl/TRANREPT.jcl:73-74`), or an in-stream record written by CORPT00C (`app/cbl/CORPT00C.cbl:116-121`). Sequential, 80 bytes; only the first record is read.

| Field | Pos | Len | Type | Meaning |
| :-- | --: | --: | :-- | :-- |
| `WS-START-DATE` | 1 | 10 | date-text | First processing date reported |
| FILLER | 11 | 1 | text | Separator (a space) |
| `WS-END-DATE` | 12 | 10 | date-text | Last processing date reported |
| (unused) | 22 | 59 | text | |

Local run: `2022-01-01 2022-07-06` (`tools/carddemo.py:617-629`).

## ENT-TRAN-REPORT · Transaction detail report (TRANREPT output)

- **Copybook:** `app/cpy/CVTRA07Y.cpy:4-66` (header, detail and total line layouts). **Dataset:** `AWS.M2.CARDDEMO.TRANREPT(+1)`, `RECFM=FB,LRECL=133`, `DISP=(NEW,CATLG,DELETE)` (`app/jcl/TRANREPT.jcl:76-80`).
- Line layouts with column positions are in CBTRN03C §4.2: name header, blank line, column header, rule of 133 hyphens, detail (transaction ID, account ID, type and description, category and description, source, amount `-ZZZ,ZZZ,ZZZ.ZZ`), account, page and grand total lines (amount `+ZZZ,ZZZ,ZZZ.ZZ` in columns 98-112).

## ENT-ACCT-EXTRACT · Account extract files (READACCT output)

Three sequential files written by CBACT01C from each account record (`app/cbl/CBACT01C.cbl:56-85`, `app/jcl/READACCT.jcl:37-48`). No copybook; the layouts are in the program's FD entries. Nothing in the repository reads them.

**OUTFILE** `AWS.M2.CARDDEMO.ACCTDATA.PSCOMP`, `RECFM=FB,LRECL=107`, record `OUT-ACCT-REC`:

| Field | Pos | Len | PIC | Type | Content |
| :-- | --: | --: | :-- | :-- | :-- |
| `OUT-ACCT-ID` | 1 | 11 | `9(11)` | digits(11) | `ACCT-ID` |
| `OUT-ACCT-ACTIVE-STATUS` | 12 | 1 | `X` | text(1) | `ACCT-ACTIVE-STATUS` |
| `OUT-ACCT-CURR-BAL` | 13 | 12 | `S9(10)V99` | decimal(12,2) | `ACCT-CURR-BAL` |
| `OUT-ACCT-CREDIT-LIMIT` | 25 | 12 | `S9(10)V99` | decimal(12,2) | `ACCT-CREDIT-LIMIT` |
| `OUT-ACCT-CASH-CREDIT-LIMIT` | 37 | 12 | `S9(10)V99` | decimal(12,2) | `ACCT-CASH-CREDIT-LIMIT` |
| `OUT-ACCT-OPEN-DATE` | 49 | 10 | `X(10)` | date-text | `ACCT-OPEN-DATE` |
| `OUT-ACCT-EXPIRAION-DATE` | 59 | 10 | `X(10)` | date-text | `ACCT-EXPIRAION-DATE` |
| `OUT-ACCT-REISSUE-DATE` | 69 | 10 | `X(10)` | text(10) | `ACCT-REISSUE-DATE` as `YYYYMMDD` + 2 spaces |
| `OUT-ACCT-CURR-CYC-CREDIT` | 79 | 12 | `S9(10)V99` | decimal(12,2) | `ACCT-CURR-CYC-CREDIT` |
| `OUT-ACCT-CURR-CYC-DEBIT` | 91 | 7 | `S9(10)V99 COMP-3` | packed(12,2) | `ACCT-CURR-CYC-DEBIT`, or 2525.00 when it is zero |
| `OUT-ACCT-GROUP-ID` | 98 | 10 | `X(10)` | text(10) | `ACCT-GROUP-ID` |

**ARRYFILE** `AWS.M2.CARDDEMO.ACCTDATA.ARRYPS`, `RECFM=FB,LRECL=110`, record `ARR-ARRAY-REC`: `ARR-ACCT-ID` (1, 11, `9(11)`), then 5 occurrences of { `ARR-ACCT-CURR-BAL` (12, `S9(10)V99` zoned) + `ARR-ACCT-CURR-CYC-DEBIT` (7, `S9(10)V99 COMP-3`) } at positions 12, 31, 50, 69, 88, then `ARR-FILLER` (107, 4, `X(4)`).

**VBRCFILE** `AWS.M2.CARDDEMO.ACCTDATA.VBPS`, `RECFM=VB,LRECL=84`, records of 10 to 80 bytes; the program writes two per account:

| Record | Len | Content |
| :-- | --: | :-- |
| `VBRC-REC1` | 12 | `ACCT-ID` (11) + `ACCT-ACTIVE-STATUS` (1) |
| `VBRC-REC2` | 39 | `ACCT-ID` (11) + `ACCT-CURR-BAL` (12, zoned) + `ACCT-CREDIT-LIMIT` (12, zoned) + reissue year (4, the first 4 characters of `ACCT-REISSUE-DATE`) |

*Type note:* `packed(p,s)` is a new type for the catalog conventions: IBM packed decimal, two digits per byte, the sign in the low half of the last byte (`C` positive, `D` negative); 12 digits take 7 bytes.

## Corrections to existing entities

| Entity | Field or statement | Current text | Proposed text | Evidence (file:line) |
| :-- | :-- | :-- | :-- | :-- |
| ENT-ACCOUNT | `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT` meaning | "Sum of ... posted this cycle" | add: "reset to zero by INTCALC for every account that has a category balance" | `app/cbl/CBACT04C.cbl:351-356` |
| ENT-ACCOUNT | `ACCT-GROUP-ID` meaning | "Disclosure (interest) group" | add: "key into ENT-DISC-GROUP; blank in the sample, so INTCALC uses `DEFAULT`" | `app/cbl/CBACT04C.cbl:210, 437-440` |
| ENT-ACCOUNT | Used by | (list) | unchanged; CBACT01C copies every field to the ENT-ACCT-EXTRACT files | `app/cbl/CBACT01C.cbl:215-243` |
| ENT-TCAT-BAL | `TRAN-CAT-BAL` meaning | "Balance for this category (interest base for INTCALC)" | add: "INTCALC reads it but never resets it, so it keeps growing across cycles" | `app/cbl/CBACT04C.cbl:28-32, 463-469` |
| ENT-CARD-XREF | Alternate key | "several cards per account allowed" | add: "CBACT04C reads it by `XREF-ACCT-ID` without `WITH DUPLICATES`; on z/OS a second card per account makes INTCALC abend (CBACT04C DEF-INTC-007)" | `app/cbl/CBACT04C.cbl:34-39, 394-414` |
| ENT-TRANSACTION | Timestamp layouts table | three writers | add row: "INTCALC interest transactions, both fields: `YYYY-MM-DD-HH.MM.SS.hh0000`, example `2022-07-06-12.00.00.130000`" | `app/cbl/CBACT04C.cbl:498-500, 615-628` |
| ENT-TRANSACTION | Dataset | KSDS only | add: "INTCALC writes the same layout to the sequential GDG `AWS.M2.CARDDEMO.SYSTRAN(+1)` (FB 350), which COMBTRAN merges into the KSDS; TRANREPT reads the sorted copy `TRANSACT.DALY(+1)`" | `app/jcl/INTCALC.jcl:37-41`, `app/jcl/TRANREPT.jcl:37-55` |
| ENT-TRAN-TYPE and ENT-TRAN-CATEGORY | readers | "Neither pilot program reads them" | add: "CBTRN03C reads both by key for the report descriptions; a missing code abends it" | `app/cbl/CBTRN03C.cbl:495-513` |
| ENT-CARD | (observation) | | `CARD-CVV-CD` and the embossed name are printed in full by READCARD (CBACT02C DEF-CDRD-001) | `app/cbl/CBACT02C.cbl:78` |
| ENT-CUSTOMER | (observation) | | `CUST-SSN`, `CUST-GOVT-ISSUED-ID` and `CUST-DOB-YYYY-MM-DD` are printed in full by READCUST (CBCUS01C DEF-CURD-001) | `app/cbl/CBCUS01C.cbl:78` |

## Relationships

| From | To | Join fields | Cardinality | Evidence |
| :-- | :-- | :-- | :-- | :-- |
| ENT-ACCOUNT | ENT-DISC-GROUP | `ACCT-GROUP-ID` = `DIS-ACCT-GROUP-ID` (plus type and category from ENT-TCAT-BAL); fallback `DEFAULT` | many to many (group shared by accounts; one rate per type and category) | `app/cbl/CBACT04C.cbl:210-213, 416-461` |
| ENT-TCAT-BAL | ENT-DISC-GROUP | `TRANCAT-TYPE-CD`, `TRANCAT-CD` = `DIS-TRAN-TYPE-CD`, `DIS-TRAN-CAT-CD` | many to one per group | `app/cbl/CBACT04C.cbl:211-212` |
| ENT-TRANSACTION | ENT-TRAN-CATEGORY | `TRAN-TYPE-CD` + `TRAN-CAT-CD` | many to one | `app/cbl/CBTRN03C.cbl:191-195` |
| ENT-TRANSACTION | ENT-TRAN-TYPE | `TRAN-TYPE-CD` | many to one | `app/cbl/CBTRN03C.cbl:189-190` |
