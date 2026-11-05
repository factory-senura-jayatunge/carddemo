---
id: CBCUS01C
title: Customer file listing (READCUST)
kind: batch
module: core
unit_code: CURD
source_rev: 8a8a80a
sources:
  - {path: app/cbl/CBCUS01C.cbl, sha256: c894b02b933f, role: program}
  - {path: app/jcl/READCUST.jcl, sha256: 971f5e80ab02, role: job}
  - {path: app/cpy/CVCUS01Y.cpy, sha256: 944fd9a8eb10, role: record}
entry_points: [{type: jcl-step, job: READCUST, step: STEP05}, {type: scheduler, file: app/scheduler/CardDemo.ca7, lines: "394-419"}]
mapsets: []
copybooks: [CVCUS01Y]
data_stores:
  - {dd: CUSTFILE, dataset: AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS, entity: ENT-CUSTOMER, access: [read-sequential]}
calls: [CEE3ABD]
transfers_to: []
invoked_by: [READCUST.jcl]
local_run: "make run-batch PROGRAM=READCUST"
spec_status: draft
---

# CBCUS01C: Customer file listing (READCUST)

## 1. Summary

The customer listing program reads the customer master (ENT-CUSTOMER) from start to end in key order and prints every record, unchanged and in full, as one SYSOUT line. It writes no file, changes nothing and ends with return code 0, or abends (U0999) on an open, read or close error. It is a utility for looking at the file; nothing in the batch cycle depends on it. The listing contains the social security number, government ID, date of birth and bank account of every customer in clear (DEF-CURD-001).

## 2. Rules at a glance

| ID | Rule |
| :-- | :-- |
| BR-CURD-001 | Every record of the customer file is read once, in key order (CUST-ID), and printed as one 500-byte SYSOUT line. |
| BR-CURD-002 | Nothing is written or changed; the return code is 0 at normal end. |

## 3. Actors and triggers

- **Trigger:** JCL job `READCUST`, step `STEP05` (`app/jcl/READCUST.jcl:21-27`). In the CA-7 schedule `READCARD` triggers `READCUST` and `READCUST` triggers `READXREF`, in the chain `CLOSEFIL` → `READACCT` → `READCARD` → `READCUST` → `READXREF` → `WAITSTEP` → `OPENFIL` (`app/scheduler/CardDemo.ca7:394-419`); the entry marks the job "maint only". Control-M does not list it.
- **Preconditions:** the customer file can be opened for input (the CA-7 chain runs after CLOSEFIL has closed the CICS files).
- **Parameters:** none. The program does not use the date or the clock.

## 4. Interfaces

### 4.1 Files

| DD | Entity | Organisation | Open | Access | Key | Record | Source |
| :-- | :-- | :-- | :-- | :-- | :-- | --: | :-- |
| `CUSTFILE` | ENT-CUSTOMER | indexed | INPUT | read next | `CUST-ID` (9) | 500 | `:29-33, 37-40` |

The JCL allocates `CUSTFILE` as `AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS`, `DISP=SHR` (`app/jcl/READCUST.jcl:24-25`).

### 4.2 Console output (SYSOUT), in order

| Text (exact) | When | Source |
| :-- | :-- | :-- |
| `START OF EXECUTION OF PROGRAM CBCUS01C` | start | `:71` |
| the 500-byte record as read, trailing spaces included | each record (BR-CURD-001) | `:78` |
| `END OF EXECUTION OF PROGRAM CBCUS01C` | normal end | `:85` |
| error lines of section 10 | on error | |

### 4.3 Return code

0 at normal end; abend U0999 on error (ERR-CURD-005).

## 5. Data used

| Entity field | Read | Use |
| :-- | :-- | :-- |
| ENT-CUSTOMER (whole record) | yes | printed (BR-CURD-001) |

No file is changed. Program-local data with behaviour (`:65`): `END-OF-FILE` (`'N'` until the customer file is at end).

## 6. Processing flow

```mermaid
flowchart TD
    S([start]) --> O[open CUSTFILE input]
    O --> R{read next record}
    R -- end of file --> C[close CUSTFILE]
    R -- record --> D[print the record]
    D --> R
    C --> Z([end, RC 0])
```

1. Print the start line; open the file (`:71-72`, ERR-CURD-001).
2. Repeat until end of file (`:74-81`): read the next record (ERR-CURD-002); at end of file leave the loop; otherwise print the record (BR-CURD-001).
3. Close the file (`:83`, ERR-CURD-003); print the end line (`:85`).

## 7. Business rules

#### BR-CURD-001 · Print every record once, in key order
- **When:** each pass of the main loop.
- **Then:** read the next ENT-CUSTOMER record in ascending `CUST-ID` order and print the whole 500-byte record as one line, byte for byte as read (text and zoned-decimal fields are not reformatted). At end of file nothing is printed for the end-of-file read.
- Each record is printed once (DEF-CURD-002, fixed).
- **Source:** `:74-81, 92-116`
- **Fidelity:** incidental (the listing exists for operators, DEF-CURD-001) · **Confidence:** runtime-verified (`tests/integration/test_reader_jobs.py:36-40`; sample: 50 records, 52 SYSOUT lines)

#### BR-CURD-002 · No output, return code 0
- **When:** normal end.
- **Then:** no file is written or changed and no count is printed; the return code is 0.
- **Source:** `:83-87`
- **Fidelity:** must-preserve · **Confidence:** runtime-verified

## 8. Validations

None.

## 9. Side effects

None. The customer file is opened for input only.

## 10. Error handling

#### ERR-CURD-001 · Open failure
- A status other than `00` on OPEN prints `ERROR OPENING CUSTFILE`, the file status (ERR-CURD-004) and abends (ERR-CURD-005).
- **Source:** `:118-134` · **Confidence:** reviewed

#### ERR-CURD-002 · Read failure
- Status `00`: print the record; `10`: end of file; anything else: `ERROR READING CUSTOMER FILE`, the status, abend.
- **Source:** `:92-116` · **Confidence:** reviewed

#### ERR-CURD-003 · Close failure
- A status other than `00` on CLOSE prints `ERROR CLOSING CUSTOMER FILE`, the status and abends.
- **Source:** `:136-152` · **Confidence:** reviewed

#### ERR-CURD-004 · File status display
- `FILE STATUS IS: NNNN` + 4 characters: `00` + the two status characters when the status is numeric and does not start with `9`; otherwise the first character, then the binary value of the second character as 3 digits. The same routine as CBTRN02C (ERR-POST-007).
- **Source:** `:161-174` · **Fidelity:** incidental

#### ERR-CURD-005 · Abend
- Prints `ABENDING PROGRAM` and calls `CEE3ABD` with code 999, timing 0: U0999 (locally return code 16 and `CEE3ABD: USER ABEND U0999` on stderr). The file is not closed and the end line is not printed.
- **Source:** `:154-158` · **Confidence:** reviewed

## 11. Interactions

```mermaid
flowchart LR
    READCARD -->|CA-7 trigger| READCUST
    READCUST -->|CA-7 trigger| READXREF
    F[(CUSTDATA KSDS)] --> CBCUS01C
    CBCUS01C --> SYSOUT[/SYSOUT listing/]
```

- **Upstream:** the customer master, maintained by the online account update program and CBIMPORT.
- **Downstream:** none.
- **Calls:** `CEE3ABD` only.

## 12. Numeric, date and format details

#### NUM-CURD-001 · Record line
- The line is the record exactly as stored: customer number (9 digits), names, address, phones, SSN (9 digits), government ID, date of birth, EFT account, primary-holder flag, FICO score (3 digits), 168 filler bytes (ENT-CUSTOMER). No field is converted, so numbers appear as their stored digits (with overpunch sign characters where the field is signed) and dates as stored.
- **Source:** `:78` · **Confidence:** runtime-verified

## 13. Known defects and oddities

#### DEF-CURD-001 · [security] Customer personal data printed in full
- Every customer record is printed in full, including `CUST-SSN`, `CUST-GOVT-ISSUED-ID`, `CUST-DOB-YYYY-MM-DD` and `CUST-EFT-ACCOUNT-ID`, next to the name and address. See `docs/security/data-handling.md`.
- **Source:** `:78` · **Status:** open · **Fidelity:** defect-decide (Q-CURD-001)

#### DEF-CURD-002 · Each record was printed twice (fixed in this branch)
- The read paragraph used to print the record as well as the main loop, so every record appeared twice. The print in the read paragraph is now commented out (`:96`).
- **Status:** fixed in this branch, "batch: CBCUS01C (READCUST) prints each customer once" (`CHANGELOG.md:44`, commit 836107f, squashed into b5e0139).

## 14. Open questions

| ID | Question | For |
| :-- | :-- | :-- |
| Q-CURD-001 | Is the full listing of the customer file needed in the target? If so, which fields may it show (DEF-CURD-001)? | security |

## 15. Equivalence contract

A reimplementation is equivalent when, for the same customer file:

- SYSOUT has the same lines in the same order: the start line, one line per record in key order with the full record, the end line;
- the return code is equal (0, or the abend of section 10);
- no file is created or changed.

Sample check (`app/data/ASCII/custdata.txt`): 50 records, 52 SYSOUT lines, return code 0.

## 16. Paragraph coverage

| Paragraph | Lines | Covered by |
| :-- | :-- | :-- |
| (file and data division) | 29-67 | §4.1, §5 |
| (procedure division main line) | 71-87 | §6, BR-CURD-001, BR-CURD-002 |
| `1000-CUSTFILE-GET-NEXT` | 92-116 | BR-CURD-001, ERR-CURD-002 |
| `0000-CUSTFILE-OPEN` | 118-134 | ERR-CURD-001 |
| `9000-CUSTFILE-CLOSE` | 136-152 | ERR-CURD-003 |
| `Z-ABEND-PROGRAM` | 154-158 | ERR-CURD-005 |
| `Z-DISPLAY-IO-STATUS` | 161-174 | ERR-CURD-004 |

5 of 5 paragraphs plus the main line covered.
