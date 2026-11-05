# Area c-transactions: entity proposals

Proposals from area `c-transactions` (COTRN00C, COTRN01C, COTRN02C, CORPT00C, CSUTLDTC) for `../entities.md`, written against commit b5e0139. Layout as in `README.md` of this folder.

## ENT-DATE-CHECK-RESULT · Result line of the date check CSUTLDTC

- **Defined in:** `app/cbl/CSUTLDTC.cbl:42-57` (`WS-MESSAGE`, 80 bytes), returned in the third parameter `LS-RESULT` (`app/cbl/CSUTLDTC.cbl:86, 97`). Caller copies: `app/cbl/COTRN02C.cbl:65-69`, `app/cbl/CORPT00C.cbl:132-136` (`CSUTLDTC-RESULT`), `app/cpy/CSUTLDWY.cpy:60-80` (`WS-DATE-VALIDATION-RESULT`, used by COACTUPC).
- **Storage:** none; a parameter area only.

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `WS-SEVERITY` (`-N`) | 1 | 4 | `X(04)` / `9(4)` | digits(4) | LE condition severity; `0000` = valid date, `0003` for every CEEDAYS error |
| FILLER | 5 | 11 | `X(11)` | text(11) | `Mesg Code: ` |
| `WS-MSG-NO` (`-N`) | 16 | 4 | `X(04)` / `9(4)` | digits(4) | LE message number (`2507`, `2508`, `2509`, `2513`, `2517`, `2518`, `2520`, `2521`, or `0000`) |
| FILLER | 20 | 1 | `X(01)` | text(1) | space |
| `WS-RESULT` | 21 | 15 | `X(15)` | text(15) | verdict, for example `Date is valid`, `Datevalue error` (CSUTLDTC BR-DTCK-002) |
| FILLER | 36 | 1 | `X(01)` | text(1) | space |
| FILLER | 37 | 9 | `X(09)` | text(9) | `TstDate: ` |
| `WS-DATE` | 46 | 10 | `X(10)` | text(10) | date as given by the caller |
| FILLER | 56 | 1 | `X(01)` | text(1) | space |
| FILLER | 57 | 10 | `X(10)` | text(10) | `Mask used:` |
| `WS-DATE-FMT` | 67 | 10 | `X(10)` | text(10) | picture as given by the caller |
| FILLER | 77 | 4 | `X(01)`, `X(03)` | text(4) | spaces |

The callers name positions 1-4 `CSUTLDTC-RESULT-SEV-CD` and 16-19 `CSUTLDTC-RESULT-MSG-NUM` (`app/cbl/COTRN02C.cbl:66-68`), and positions 20-80 `CSUTLDTC-RESULT-MSG` (61 bytes, never read).

## ENT-REPORT-JOB · Job stream for the transaction report, written to TDQ JOBS

- **Defined in:** `app/cbl/CORPT00C.cbl:81-127` (`JOB-DATA`, 17 records of 80 bytes; redefined as `JOB-LINES OCCURS 1000` at `app/cbl/CORPT00C.cbl:126-127`). No copybook.
- **Storage:** extrapartition transient data queue `JOBS`, output, `DDNAME(INREADER)`, `RECORDSIZE(80)`, fixed, unblocked (`app/csd/CARDDEMO.CSD:499-505`), read by the JES internal reader. Not stored anywhere CardDemo can read back.
- **Writer:** CORPT00C (BR-RPTS-006, BR-RPTS-007). **Consumer:** JES, which runs job `TRNRPT00` with procedure `TRANREPT` (`app/proc/TRANREPT.prc`, specified by area `g-jobs-ops`).
- **Key:** none; order matters.

Record sequence (each record is text(80), padded with spaces):

| # | Content | Variable part |
| --: | :-- | :-- |
| 1 | `//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,` | |
| 2 | `// NOTIFY=&SYSUID` | |
| 3 | `//*` | |
| 4 | `//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')` | |
| 5 | `//*` | |
| 6 | `//STEP10 EXEC PROC=TRANREPT` | |
| 7 | `//*` | |
| 8 | `//STEP05R.SYMNAMES DD *` | |
| 9 | `TRAN-CARD-NUM,263,16,ZD` | |
| 10 | `TRAN-PROC-DT,305,10,CH` | |
| 11 | `PARM-START-DATE,C'<start>'` | start date |
| 12 | `PARM-END-DATE,C'<end>'` | end date |
| 13 | `/*` | |
| 14 | `//STEP10R.DATEPARM DD *` | |
| 15 | `<start> <end>` | both dates |
| 16 | `/*` | |
| 17 | `/*EOF` | |

Fields inside the variable records:

| Field | Record | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | --: | :-- | :-- | :-- |
| literal `PARM-START-DATE,C'` | 11 | 1 | 18 | `X(18)` | text(18) | sort symbol name and opening quote |
| `PARM-START-DATE-1` | 11 | 19 | 10 | `X(10)` | date-text | start date `YYYY-MM-DD` |
| literal `'` + spaces | 11 | 29 | 52 | `X(52)` | text(52) | closing quote |
| literal `PARM-END-DATE,C'` | 12 | 1 | 16 | `X(16)` | text(16) | sort symbol name and opening quote |
| `PARM-END-DATE-1` | 12 | 17 | 10 | `X(10)` | date-text | end date |
| literal `'` + spaces | 12 | 27 | 54 | `X(54)` | text(54) | closing quote |
| `PARM-START-DATE-2` | 15 | 1 | 10 | `X(10)` | date-text | start date, read by `CBTRN03C` as `WS-START-DATE` (`app/cbl/CBTRN03C.cbl:122-125`) |
| FILLER | 15 | 11 | 1 | `X` | text(1) | space |
| `PARM-END-DATE-2` | 15 | 12 | 10 | `X(10)` | date-text | end date, read as `WS-END-DATE` |
| FILLER | 15 | 22 | 59 | `X(59)` | text(59) | spaces |

The start and end dates are inclusive and are compared as text with positions 305-314 of ENT-TRANSACTION (`TRAN-PROC-TS` date part) by the sort step and by `CBTRN03C` (`app/proc/TRANREPT.prc:40-46`, `app/cbl/CBTRN03C.cbl:173-174`). Records 8-13 and 14-16 are JCL overrides of the procedure's `SYMNAMES` and `DATEPARM` DDs; their step and DD names are a contract with the procedure (CORPT00C section 11).

## Corrections to existing entities

| Entity | Field or statement | Current text | Proposed text | Evidence (file:line) |
| :-- | :-- | :-- | :-- | :-- |
| ENT-COMMAREA | extension paragraph after "Bill payment extension `CDEMO-CB00-INFO` ..." | (no statement for the transaction programs) | Transaction list, view and add append the same 58-byte layout at positions 161-218 under their own prefixes: `CDEMO-CT00-INFO` (`app/cbl/COTRN00C.cbl:62-70`), `CDEMO-CT01-INFO` (`app/cbl/COTRN01C.cbl:53-61`), `CDEMO-CT02-INFO` (`app/cbl/COTRN02C.cbl:72-80`): `-TRNID-FIRST` (161, 16), `-TRNID-LAST` (177, 16), `-PAGE-NUM` (193, 8), `-NEXT-PAGE-FLG` (201, 1), `-TRN-SEL-FLG` (202, 1), `-TRN-SELECTED` (203, 16). The list writes the selected transaction ID at 203-218 and the view reads it from there, under the other name. The menu passes only 160 bytes, so the extension arrives as spaces. Report request (`CORPT00C`) uses no extension. | `app/cbl/COTRN00C.cbl:62-70`; `app/cbl/COTRN01C.cbl:53-61, 99-109`; `app/cbl/COTRN02C.cbl:72-80, 119`; `app/cbl/CORPT00C.cbl:138` |
| ENT-TRANSACTION | timestamp layouts table | three rows (daily input, POSTTRAN, bill payment) | add a row: Transaction add (`COTRN02C`), both fields: `YYYY-MM-DD` followed by 16 spaces, as typed by the user, example `2022-07-06` + 16 spaces | `app/cbl/COTRN02C.cbl:464-465` |
| ENT-TRANSACTION | `TRAN-PROC-TS` meaning | When it was posted | When it was posted (POSTTRAN, bill payment); for transaction add, a date typed by the user, not a posting time. The report job selects on its first 10 characters. | `app/cbl/COTRN02C.cbl:465`; `app/proc/TRANREPT.prc:40-46` |
| ENT-TRANSACTION | FILLER meaning | Unused. Spaces in POSTTRAN records; bill payments inherit it from the record read before (DEF-BILL-006) | Unused. Spaces in POSTTRAN and transaction add records (`INITIALIZE`); bill payments inherit it from the record read before (DEF-BILL-006) | `app/cbl/COTRN02C.cbl:450` |
| ENT-TRANSACTION | writers (new line under "CICS file") | (none) | Online writers: bill payment (`COBIL00C`) and transaction add (`COTRN02C`), both with ID = highest existing ID + 1. Online readers: list (`COTRN00C`, browse), view (`COTRN01C`, read with update lock). | `app/cbl/COTRN02C.cbl:444-449, 711-721`; `app/cbl/COTRN00C.cbl:593-696`, `app/cbl/COTRN01C.cbl:267-278` |
| ENT-TRAN-TYPE and ENT-TRAN-CATEGORY | "Neither pilot program reads them" | Neither pilot program reads them; the codes they write are: | Neither pilot program nor transaction add (`COTRN02C`) reads them; transaction add accepts any 2-digit type and 4-digit category without a lookup. The codes the pilots write are: | `app/cbl/COTRN02C.cbl:322-337, 452-453` |

## Relationships

| From | To | Join fields | Cardinality | Evidence |
| :-- | :-- | :-- | :-- | :-- |
| ENT-TRANSACTION | ENT-CARD-XREF | `TRAN-CARD-NUM` = `XREF-CARD-NUM` | many to one; enforced only at insert by transaction add (card looked up in the cross-reference) | `app/cbl/COTRN02C.cbl:193-230, 459` |
| ENT-TRANSACTION | ENT-TRAN-CATEGORY | (`TRAN-TYPE-CD`, `TRAN-CAT-CD`) = category key | many to one; not enforced by transaction add | `app/cbl/COTRN02C.cbl:322-337` |
| ENT-REPORT-JOB | ENT-TRANSACTION | start and end date against `TRAN-PROC-TS`(1:10) | one request selects many transactions | `app/cbl/CORPT00C.cbl:101-121`; `app/proc/TRANREPT.prc:40-46` |
| ENT-DATE-CHECK-RESULT | (caller) | returned in the third CALL parameter | one per call | `app/cbl/COTRN02C.cbl:389-427`; `app/cbl/CORPT00C.cbl:388-426` |
