---
id: CBACT03C
title: Card cross-reference file listing (READXREF)
kind: batch
module: core
unit_code: XRRD
source_rev: 8a8a80a
sources:
  - {path: app/cbl/CBACT03C.cbl, sha256: 1b77213da771, role: program}
  - {path: app/jcl/READXREF.jcl, sha256: 3ec9d1c9efd6, role: job}
  - {path: app/cpy/CVACT03Y.cpy, sha256: ffc6079e09b2, role: record}
entry_points: [{type: jcl-step, job: READXREF, step: STEP05}, {type: scheduler, file: app/scheduler/CardDemo.ca7, lines: "421-447"}]
mapsets: []
copybooks: [CVACT03Y]
data_stores:
  - {dd: XREFFILE, dataset: AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS, entity: ENT-CARD-XREF, access: [read-sequential]}
calls: [CEE3ABD]
transfers_to: []
invoked_by: [READXREF.jcl]
local_run: "make run-batch PROGRAM=READXREF"
spec_status: draft
---

# CBACT03C: Card cross-reference file listing (READXREF)

## 1. Summary

The card cross-reference listing program reads the card cross-reference file (ENT-CARD-XREF) from start to end in key order and prints every record, unchanged and in full, as one SYSOUT line. It writes no file, changes nothing and ends with return code 0, or abends (U0999) on an open, read or close error. It is a utility for looking at the file; nothing in the batch cycle depends on it. The listing contains the card numbers in clear (DEF-XRRD-001).

## 2. Rules at a glance

| ID | Rule |
| :-- | :-- |
| BR-XRRD-001 | Every record of the cross-reference file is read once, in key order (XREF-CARD-NUM), and printed as one 50-byte SYSOUT line. |
| BR-XRRD-002 | Nothing is written or changed; the return code is 0 at normal end. |

## 3. Actors and triggers

- **Trigger:** JCL job `READXREF`, step `STEP05` (`app/jcl/READXREF.jcl:22-28`). In the CA-7 schedule `READCUST` triggers `READXREF` and `READXREF` triggers `WAITSTEP`, in the chain `CLOSEFIL` → `READACCT` → `READCARD` → `READCUST` → `READXREF` → `WAITSTEP` → `OPENFIL` (`app/scheduler/CardDemo.ca7:421-447`); the entry marks the job "maint only". Control-M does not list it.
- **Preconditions:** the cross-reference file can be opened for input (the CA-7 chain runs after CLOSEFIL has closed the CICS files).
- **Parameters:** none. The program does not use the date or the clock.

## 4. Interfaces

### 4.1 Files

| DD | Entity | Organisation | Open | Access | Key | Record | Source |
| :-- | :-- | :-- | :-- | :-- | :-- | --: | :-- |
| `XREFFILE` | ENT-CARD-XREF | indexed | INPUT | read next | `XREF-CARD-NUM` (16) | 50 | `:29-33, 37-40` |

The JCL allocates `XREFFILE` as `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS`, `DISP=SHR` (`app/jcl/READXREF.jcl:25-26`).

### 4.2 Console output (SYSOUT), in order

| Text (exact) | When | Source |
| :-- | :-- | :-- |
| `START OF EXECUTION OF PROGRAM CBACT03C` | start | `:71` |
| the 50-byte record as read, trailing spaces included | each record (BR-XRRD-001) | `:78` |
| `END OF EXECUTION OF PROGRAM CBACT03C` | normal end | `:85` |
| error lines of section 10 | on error | |

### 4.3 Return code

0 at normal end; abend U0999 on error (ERR-XRRD-005).

## 5. Data used

| Entity field | Read | Use |
| :-- | :-- | :-- |
| ENT-CARD-XREF (whole record) | yes | printed (BR-XRRD-001) |

No file is changed. Program-local data with behaviour (`:65`): `END-OF-FILE` (`'N'` until the cross-reference file is at end).

## 6. Processing flow

```mermaid
flowchart TD
    S([start]) --> O[open XREFFILE input]
    O --> R{read next record}
    R -- end of file --> C[close XREFFILE]
    R -- record --> D[print the record]
    D --> R
    C --> Z([end, RC 0])
```

1. Print the start line; open the file (`:71-72`, ERR-XRRD-001).
2. Repeat until end of file (`:74-81`): read the next record (ERR-XRRD-002); at end of file leave the loop; otherwise print the record (BR-XRRD-001).
3. Close the file (`:83`, ERR-XRRD-003); print the end line (`:85`).

## 7. Business rules

#### BR-XRRD-001 · Print every record once, in key order
- **When:** each pass of the main loop.
- **Then:** read the next ENT-CARD-XREF record in ascending `XREF-CARD-NUM` order and print the whole 50-byte record as one line, byte for byte as read (text and zoned-decimal fields are not reformatted). At end of file nothing is printed for the end-of-file read.
- Each record is printed once (DEF-XRRD-002, fixed).
- **Source:** `:74-81, 92-116`
- **Fidelity:** incidental (the listing exists for operators, DEF-XRRD-001) · **Confidence:** runtime-verified (`tests/integration/test_reader_jobs.py:30-34`; sample: 50 records, 52 SYSOUT lines)

#### BR-XRRD-002 · No output, return code 0
- **When:** normal end.
- **Then:** no file is written or changed and no count is printed; the return code is 0.
- **Source:** `:83-87`
- **Fidelity:** must-preserve · **Confidence:** runtime-verified

## 8. Validations

None.

## 9. Side effects

None. The cross-reference file is opened for input only.

## 10. Error handling

#### ERR-XRRD-001 · Open failure
- A status other than `00` on OPEN prints `ERROR OPENING XREFFILE`, the file status (ERR-XRRD-004) and abends (ERR-XRRD-005).
- **Source:** `:118-134` · **Confidence:** reviewed

#### ERR-XRRD-002 · Read failure
- Status `00`: print the record; `10`: end of file; anything else: `ERROR READING XREFFILE`, the status, abend.
- **Source:** `:92-116` · **Confidence:** reviewed

#### ERR-XRRD-003 · Close failure
- A status other than `00` on CLOSE prints `ERROR CLOSING XREFFILE`, the status and abends.
- **Source:** `:136-152` · **Confidence:** reviewed

#### ERR-XRRD-004 · File status display
- `FILE STATUS IS: NNNN` + 4 characters: `00` + the two status characters when the status is numeric and does not start with `9`; otherwise the first character, then the binary value of the second character as 3 digits. The same routine as CBTRN02C (ERR-POST-007).
- **Source:** `:161-174` · **Fidelity:** incidental

#### ERR-XRRD-005 · Abend
- Prints `ABENDING PROGRAM` and calls `CEE3ABD` with code 999, timing 0: U0999 (locally return code 16 and `CEE3ABD: USER ABEND U0999` on stderr). The file is not closed and the end line is not printed.
- **Source:** `:154-158` · **Confidence:** reviewed

## 11. Interactions

```mermaid
flowchart LR
    READCUST -->|CA-7 trigger| READXREF
    READXREF -->|CA-7 trigger| WAITSTEP
    F[(CARDXREF KSDS)] --> CBACT03C
    CBACT03C --> SYSOUT[/SYSOUT listing/]
```

- **Upstream:** the cross-reference file, maintained by the online card programs and CBIMPORT.
- **Downstream:** none.
- **Calls:** `CEE3ABD` only.

## 12. Numeric, date and format details

#### NUM-XRRD-001 · Record line
- The line is the record exactly as stored: card number (16), customer number (9 digits), account number (11 digits), 14 filler bytes (ENT-CARD-XREF). The sample file has 36-byte lines; the local loader pads them with spaces to 50, so each printed line ends in 14 spaces. No field is converted, so numbers appear as their stored digits (with overpunch sign characters where the field is signed) and dates as stored.
- **Source:** `:78` · **Confidence:** runtime-verified

## 13. Known defects and oddities

#### DEF-XRRD-001 · [security] Card numbers printed in full
- Every cross-reference record is printed in full, including `XREF-CARD-NUM` (the PAN) next to the customer and account numbers. See `docs/security/data-handling.md`.
- **Source:** `:78` · **Status:** open · **Fidelity:** defect-decide (Q-XRRD-001)

#### DEF-XRRD-002 · Each record was printed twice (fixed in this branch)
- The read paragraph used to print the record as well as the main loop, so every record appeared twice. The print in the read paragraph is now commented out (`:96`).
- **Status:** fixed in this branch, "batch: CBACT03C (READXREF) prints each xref record once" (`CHANGELOG.md:45`, commit edfd1a7, squashed into b5e0139).

## 14. Open questions

| ID | Question | For |
| :-- | :-- | :-- |
| Q-XRRD-001 | Is the full listing of the cross-reference file needed in the target? If so, which fields may it show (DEF-XRRD-001)? | security |

## 15. Equivalence contract

A reimplementation is equivalent when, for the same cross-reference file:

- SYSOUT has the same lines in the same order: the start line, one line per record in key order with the full record, the end line;
- the return code is equal (0, or the abend of section 10);
- no file is created or changed.

Sample check (`app/data/ASCII/cardxref.txt`): 50 records, 52 SYSOUT lines, return code 0.

## 16. Paragraph coverage

| Paragraph | Lines | Covered by |
| :-- | :-- | :-- |
| (file and data division) | 29-67 | §4.1, §5 |
| (procedure division main line) | 71-87 | §6, BR-XRRD-001, BR-XRRD-002 |
| `1000-XREFFILE-GET-NEXT` | 92-116 | BR-XRRD-001, ERR-XRRD-002 |
| `0000-XREFFILE-OPEN` | 118-134 | ERR-XRRD-001 |
| `9000-XREFFILE-CLOSE` | 136-152 | ERR-XRRD-003 |
| `9999-ABEND-PROGRAM` | 154-158 | ERR-XRRD-005 |
| `9910-DISPLAY-IO-STATUS` | 161-174 | ERR-XRRD-004 |

5 of 5 paragraphs plus the main line covered.
