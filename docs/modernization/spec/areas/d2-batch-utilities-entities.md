# Area d2-batch-utilities: entity proposals

Merged in f9ea11a into `../entities.md`.

Proposed additions to `../entities.md` from the d2 specs (CBSTM03A, CBSTM03B, CBEXPORT, CBIMPORT, COBSWAIT, COBDATFT, MVSWAIT), written against commit b5e0139. Conventions as in `../entities.md` (Pos 1-based, Len in bytes). ENT-CUSTOMER and ENT-CARD, which these programs also use, are final in `../entities.md`; the d2 specs found no correction to them.

## ENT-EXPORT-RECORD · Multi-record export file

- **Copybook:** `app/cpy/CVEXPORT.cpy:9-100` (`EXPORT-RECORD`, 500 bytes): a 40-byte common header (`app/cpy/CVEXPORT.cpy:10-18`) and a 460-byte data area `EXPORT-RECORD-DATA` (`app/cpy/CVEXPORT.cpy:19`) that five layouts redefine, one per record type (`app/cpy/CVEXPORT.cpy:24-42`, `app/cpy/CVEXPORT.cpy:47-60`, `app/cpy/CVEXPORT.cpy:65-79`, `app/cpy/CVEXPORT.cpy:84-88`, `app/cpy/CVEXPORT.cpy:93-100`). The file record in the writer is a plain 500-byte area whose only named field is the key at the same bytes as `EXPORT-SEQUENCE-NUM` (`app/cbl/CBEXPORT.cbl:94-97`).
- **Dataset:** `AWS.M2.CARDDEMO.EXPORT.DATA`, KSDS, `RECORDSIZE(500 500)`, deleted and defined again by step `STEP01` of every CBEXPORT run (`app/jcl/CBEXPORT.jcl:27-38`). Batch DD `EXPFILE` in CBEXPORT (writer, `app/jcl/CBEXPORT.jcl:62-63`) and CBIMPORT (reader, `app/jcl/CBIMPORT.jcl:28-29`).
- **Primary key:** `EXPORT-SEQUENCE-NUM`, 4 bytes at position 28 (offset 27), binary. The programs declare it at offset 27 (`app/cbl/CBEXPORT.cbl:65-69, 94-97`) and the local build defines the file the same way (`tools/batch-programs.toml:255-258`), but the IDCAMS definition says `KEYS(4 28)`, offset 28 (`app/jcl/CBEXPORT.jcl:32`), one byte later (CBEXPORT DEF-EXPT-002). Because the key is a big-endian unsigned binary number, ascending key order is ascending sequence-number order.
- **Record type:** byte 1, `C` customer, `A` account, `X` card cross-reference, `T` transaction, `D` card. Nothing else in the record says which layout applies.

Type notation used below, in addition to the conventions at the top of `entities.md`:

| Type | Encoding |
| :-- | :-- |
| `bin-u(n)` | Unsigned binary integer, `n` bytes, big-endian (most significant byte first). `PIC 9(1-4) COMP` is 2 bytes, `9(5-9)` 4 bytes, `9(10-18)` 8 bytes. |
| `bin-s(n),s` | Signed binary integer, `n` bytes, big-endian, two's complement, holding the value times 10^s (for `S9(10)V99 COMP`: the amount in cents). |
| `packed(p,s)` | Packed decimal (`COMP-3`): two digits per byte, high nibble first, the last nibble is the sign: `C` positive, `D` negative, `F` unsigned (picture without `S`). When `p` is even, the first nibble is a pad `0`. Length `floor(p/2)+1` bytes. This is what CBEXPORT writes; CBIMPORT is more lenient when it reads (it ignores the pad nibble and treats every sign nibble except `D` as positive, `programs/CBIMPORT.md` NUM-IMPT-003). |
| `zoned(p,s)` | The `decimal(p,s)` type of `entities.md`: digit characters with the sign folded into the last byte. |

### Common header (all record types)

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `EXPORT-REC-TYPE` | 1 | 1 | `X(1)` | text(1) | Record type: `C`, `A`, `X`, `T`, `D` |
| `EXPORT-TIMESTAMP` | 2 | 26 | `X(26)` | text(26) | Run timestamp, the same in every record of a run. As written by CBEXPORT: 22 characters `YYYY-MM-DD HH:MM:SS.00` in positions 2-23, then 4 bytes of uninitialised working storage in positions 24-27 (X'00' in the local GnuCOBOL build). Redefined as `EXPORT-DATE` (2-11), `EXPORT-DATE-TIME-SEP` (12), `EXPORT-TIME` (13-27); no program uses the redefinition. |
| `EXPORT-SEQUENCE-NUM` | 28 | 4 | `9(9) COMP` | bin-u(4) | Sequence number 1..N in write order; the record key |
| `EXPORT-BRANCH-ID` | 32 | 4 | `X(4)` | text(4) | Always `0001` from CBEXPORT |
| `EXPORT-REGION-CODE` | 36 | 5 | `X(5)` | text(5) | Always `NORTH` from CBEXPORT |
| `EXPORT-RECORD-DATA` | 41 | 460 | `X(460)` | text(460) | Data area, one of the five layouts below |

### Customer data (type `C`, `EXPORT-CUSTOMER-DATA`, `app/cpy/CVEXPORT.cpy:24-42`)

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `EXP-CUST-ID` | 41 | 4 | `9(09) COMP` | bin-u(4) | ENT-CUSTOMER.CUST-ID |
| `EXP-CUST-FIRST-NAME` | 45 | 25 | `X(25)` | text(25) | First name |
| `EXP-CUST-MIDDLE-NAME` | 70 | 25 | `X(25)` | text(25) | Middle name |
| `EXP-CUST-LAST-NAME` | 95 | 25 | `X(25)` | text(25) | Last name |
| `EXP-CUST-ADDR-LINE` (1) | 120 | 50 | `X(50)` | text(50) | Address line 1 (`OCCURS 3`) |
| `EXP-CUST-ADDR-LINE` (2) | 170 | 50 | `X(50)` | text(50) | Address line 2 |
| `EXP-CUST-ADDR-LINE` (3) | 220 | 50 | `X(50)` | text(50) | Address line 3 |
| `EXP-CUST-ADDR-STATE-CD` | 270 | 2 | `X(02)` | text(2) | State code |
| `EXP-CUST-ADDR-COUNTRY-CD` | 272 | 3 | `X(03)` | text(3) | Country code |
| `EXP-CUST-ADDR-ZIP` | 275 | 10 | `X(10)` | text(10) | ZIP code |
| `EXP-CUST-PHONE-NUM` (1) | 285 | 15 | `X(15)` | text(15) | Phone 1 (`OCCURS 2`) |
| `EXP-CUST-PHONE-NUM` (2) | 300 | 15 | `X(15)` | text(15) | Phone 2 |
| `EXP-CUST-SSN` | 315 | 9 | `9(09)` | digits(9) | Social security number; sensitive |
| `EXP-CUST-GOVT-ISSUED-ID` | 324 | 20 | `X(20)` | text(20) | Government ID; sensitive |
| `EXP-CUST-DOB-YYYY-MM-DD` | 344 | 10 | `X(10)` | date-text | Date of birth; sensitive |
| `EXP-CUST-EFT-ACCOUNT-ID` | 354 | 10 | `X(10)` | text(10) | Bank account for electronic payments; sensitive |
| `EXP-CUST-PRI-CARD-HOLDER-IND` | 364 | 1 | `X(01)` | text(1) | Primary card holder indicator |
| `EXP-CUST-FICO-CREDIT-SCORE` | 365 | 2 | `9(03) COMP-3` | packed(3,0) unsigned | FICO score, sign nibble `F` (score 274 = X'274F') |
| FILLER | 367 | 134 | `X(134)` | text | Spaces from CBEXPORT |

### Account data (type `A`, `EXPORT-ACCOUNT-DATA`, `app/cpy/CVEXPORT.cpy:47-60`)

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `EXP-ACCT-ID` | 41 | 11 | `9(11)` | digits(11) | ENT-ACCOUNT.ACCT-ID |
| `EXP-ACCT-ACTIVE-STATUS` | 52 | 1 | `X(01)` | text(1) | Active status |
| `EXP-ACCT-CURR-BAL` | 53 | 7 | `S9(10)V99 COMP-3` | packed(12,2) | Current balance (first nibble is the pad `0`) |
| `EXP-ACCT-CREDIT-LIMIT` | 60 | 12 | `S9(10)V99` | zoned(12,2) | Credit limit |
| `EXP-ACCT-CASH-CREDIT-LIMIT` | 72 | 7 | `S9(10)V99 COMP-3` | packed(12,2) | Cash credit limit |
| `EXP-ACCT-OPEN-DATE` | 79 | 10 | `X(10)` | date-text | Open date |
| `EXP-ACCT-EXPIRAION-DATE` | 89 | 10 | `X(10)` | date-text | Expiration date (the source's spelling) |
| `EXP-ACCT-REISSUE-DATE` | 99 | 10 | `X(10)` | date-text | Reissue date |
| `EXP-ACCT-CURR-CYC-CREDIT` | 109 | 12 | `S9(10)V99` | zoned(12,2) | Current cycle credit |
| `EXP-ACCT-CURR-CYC-DEBIT` | 121 | 8 | `S9(10)V99 COMP` | bin-s(8),2 | Current cycle debit in cents (-0.01 = X'FFFFFFFFFFFFFFFF') |
| `EXP-ACCT-ADDR-ZIP` | 129 | 10 | `X(10)` | text(10) | ZIP code |
| `EXP-ACCT-GROUP-ID` | 139 | 10 | `X(10)` | text(10) | Disclosure group |
| FILLER | 149 | 352 | `X(352)` | text | Spaces from CBEXPORT |

### Card cross-reference data (type `X`, `EXPORT-CARD-XREF-DATA`, `app/cpy/CVEXPORT.cpy:84-88`)

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `EXP-XREF-CARD-NUM` | 41 | 16 | `X(16)` | text(16) | Card number (PAN); sensitive |
| `EXP-XREF-CUST-ID` | 57 | 9 | `9(09)` | digits(9) | Customer number |
| `EXP-XREF-ACCT-ID` | 66 | 8 | `9(11) COMP` | bin-u(8) | Account number |
| FILLER | 74 | 427 | `X(427)` | text | Spaces from CBEXPORT |

### Transaction data (type `T`, `EXPORT-TRANSACTION-DATA`, `app/cpy/CVEXPORT.cpy:65-79`)

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `EXP-TRAN-ID` | 41 | 16 | `X(16)` | text(16) | Transaction ID |
| `EXP-TRAN-TYPE-CD` | 57 | 2 | `X(02)` | text(2) | Type code |
| `EXP-TRAN-CAT-CD` | 59 | 4 | `9(04)` | digits(4) | Category code |
| `EXP-TRAN-SOURCE` | 63 | 10 | `X(10)` | text(10) | Source channel |
| `EXP-TRAN-DESC` | 73 | 100 | `X(100)` | text(100) | Description |
| `EXP-TRAN-AMT` | 173 | 6 | `S9(09)V99 COMP-3` | packed(11,2) | Amount (no pad nibble: 11 digits + sign = 12 nibbles) |
| `EXP-TRAN-MERCHANT-ID` | 179 | 4 | `9(09) COMP` | bin-u(4) | Merchant number |
| `EXP-TRAN-MERCHANT-NAME` | 183 | 50 | `X(50)` | text(50) | Merchant name |
| `EXP-TRAN-MERCHANT-CITY` | 233 | 50 | `X(50)` | text(50) | Merchant city |
| `EXP-TRAN-MERCHANT-ZIP` | 283 | 10 | `X(10)` | text(10) | Merchant ZIP |
| `EXP-TRAN-CARD-NUM` | 293 | 16 | `X(16)` | text(16) | Card number (PAN); sensitive |
| `EXP-TRAN-ORIG-TS` | 309 | 26 | `X(26)` | ts-text | Origin timestamp |
| `EXP-TRAN-PROC-TS` | 335 | 26 | `X(26)` | ts-text | Processing timestamp |
| FILLER | 361 | 140 | `X(140)` | text | Spaces from CBEXPORT |

### Card data (type `D`, `EXPORT-CARD-DATA`, `app/cpy/CVEXPORT.cpy:93-100`)

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `EXP-CARD-NUM` | 41 | 16 | `X(16)` | text(16) | Card number (PAN); sensitive |
| `EXP-CARD-ACCT-ID` | 57 | 8 | `9(11) COMP` | bin-u(8) | Account number |
| `EXP-CARD-CVV-CD` | 65 | 2 | `9(03) COMP` | bin-u(2) | Card verification value; sensitive (CVV 747 = X'02EB') |
| `EXP-CARD-EMBOSSED-NAME` | 67 | 50 | `X(50)` | text(50) | Embossed name |
| `EXP-CARD-EXPIRAION-DATE` | 117 | 10 | `X(10)` | date-text | Expiration date (the source's spelling) |
| `EXP-CARD-ACTIVE-STATUS` | 127 | 1 | `X(01)` | text(1) | Active status |
| FILLER | 128 | 373 | `X(373)` | text | Spaces from CBEXPORT |

How each field is filled from the source records (byte copy, packed or binary conversion, sign handling) is in the CBEXPORT spec (`programs/CBEXPORT.md`, sections 7 and 12).

**Sample observations.**

- *Local run* (`python3 tools/carddemo.py run CBEXPORT`, which runs POSTTRAN first, clock 2022-07-06 12:00:00): 462 records, `C` 50, `A` 50, `X` 50, `T` 262, `D` 50, in that order, sequence numbers 1-462 without gaps. Header of record 1: `C`, `2022-07-06 12:00:00.00`, X'00000000', X'00000001', `0001`, `NORTH`.
- *Mainframe sample* `app/data/EBCDIC/AWS.M2.CARDDEMO.EXPORT.DATA.PS` (EBCDIC, 250,000 bytes): 500 records, `C` 50, `A` 50, `X` 50, `T` 300, `D` 50, in that order, key at offset 27 as in the program; sequence numbers 1-450 and 460-509 (451-459 missing); every timestamp is the 26-character `2025-09-28 22:53:40.000000`. This file was produced by a program version other than the one in the repository, which writes `.00` and 4 uninitialised bytes, and numbers without gaps. Its transaction master also held 300 records, where the local sample day holds the 262 that POSTTRAN posted (CBEXPORT Q-EXPT-001).

**Sensitive fields.** Type `C`: `EXP-CUST-SSN`, `EXP-CUST-GOVT-ISSUED-ID`, `EXP-CUST-DOB-YYYY-MM-DD`, `EXP-CUST-EFT-ACCOUNT-ID`, plus names, address and phones (PII) and the FICO score. Types `X`, `T`, `D`: the full card number. Type `D`: `EXP-CARD-CVV-CD`, which PCI DSS forbids storing after authorisation (`docs/security/data-handling.md:199-203`). Every field is in clear text or plain binary; the file is not encrypted or masked.

Used by: CBEXPORT (writes), CBIMPORT (reads).

## ENT-IMPORT-ERROR · Import error record

- **Defined in:** `app/cbl/CBIMPORT.cbl:157-165` (`WS-ERROR-RECORD`, working storage, 130 bytes). It is written with `WRITE ... FROM` (`app/cbl/CBIMPORT.cbl:449`) into the 132-byte FD record `ERROR-OUTPUT-RECORD` (`app/cbl/CBIMPORT.cbl:111-114`). The move pads it with spaces, so bytes 131-132 of every record are spaces.
- **Dataset:** `AWS.M2.CARDDEMO.IMPORT.ERRORS`, sequential, `RECFM=FB`, `LRECL=132`, `DISP=(NEW,CATLG,DELETE)` (`app/jcl/CBIMPORT.jcl:61-65`). **Batch DD:** `ERROUT`. Locally the runner writes `CBIMPORT.ERROUT.ps` (`tools/carddemo.py:680-688`).
- **Key:** none. Records are written in the order the errors occur, which is ascending export sequence number.
- **Writer:** CBIMPORT, one record per export record whose type is not `C`, `A`, `X`, `T` or `D`. No program in the repository reads the file.

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `ERR-TIMESTAMP` | 1 | 26 | `X(26)` | text(26) | The 21 characters of `FUNCTION CURRENT-DATE` at the time the record is built, `YYYYMMDDHHMMSShh` + `+hhmm` or `-hhmm` (UTC offset, `00000` when the offset is unknown), followed by 5 spaces |
| FILLER | 27 | 1 | `X(01)` | text(1) | `\|` (`VALUE '\|'`) |
| `ERR-RECORD-TYPE` | 28 | 1 | `X(01)` | text(1) | The export record's type byte, unchanged (any byte value, including low-values) |
| FILLER | 29 | 1 | `X(01)` | text(1) | `\|` |
| `ERR-SEQUENCE` | 30 | 7 | `9(07)` | digits(7) | The low-order 7 digits of the export sequence number (an unsigned 4-byte binary value up to 4,294,967,295): 12345678 gives `2345678`, 4294967295 gives `4967295` |
| FILLER | 37 | 1 | `X(01)` | text(1) | `\|` |
| `ERR-MESSAGE` | 38 | 50 | `X(50)` | text(50) | Always `Unknown record type encountered` (31 characters) followed by 19 spaces |
| FILLER | 88 | 43 | `X(43)` | text | Spaces (`VALUE SPACES`) |
| (FD padding) | 131 | 2 | - | text | Spaces, added when the 130-byte work record is moved to the 132-byte file record |

Sample observations: the sample round trip writes no error records. A stress run with the clock pinned to 2024-02-29 08:15:30 in time zone UTC-5 wrote, for a type `c` record with sequence number 12345678: `2024022908153050-0500     |c|2345678|Unknown record type encountered` followed by 64 spaces (19 from `ERR-MESSAGE`, 43 from the FILLER, 2 of FD padding), 132 bytes in all. Bytes 15-16 (hundredths, here `50`) and 17-21 (UTC offset) come from the wall clock and the process time zone even when the date and time are pinned.

Sensitive fields: none. The record holds a timestamp, the record type and the sequence number. No customer, card or account data is copied into it.

## ENT-STMT-TRAN · Transaction keyed by card for statements

- **Copybook:** `app/cpy/COSTM01.CPY:20-36` (`TRNX-RECORD`, 350 bytes; member `COSTM01`, copied by `app/cbl/CBSTM03A.CBL:51`). The FD in CBSTM03B sees the same record as a 32-byte key (`FD-TRNX-CARD` + `FD-TRNX-ID`) and 318 bytes of data (`app/cbl/CBSTM03B.CBL:58-63`).
- **Dataset:** `AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS`, KSDS, `KEYS(32 0)`, `RECORDSIZE(350 350)`, `SHAREOPTIONS(2 3)`, `ERASE`, `CISZ(4096)`, on `VOLUMES(TSU023)` (`app/jcl/CREASTMT.JCL:29-39`). It is a work file rebuilt on every CREASTMT run: `DELDEF01` deletes and redefines it (`app/jcl/CREASTMT.JCL:22-40`), `STEP010` sorts and reformats ENT-TRANSACTION into the sequential `AWS.M2.CARDDEMO.TRXFL.SEQ` (`app/jcl/CREASTMT.JCL:44-55`), and `STEP020` loads that with `REPRO` (`app/jcl/CREASTMT.JCL:56-62`). `TRXFL.SEQ` stays catalogued until the next run deletes it.
- **Batch DD:** `TRNXFILE` (`app/jcl/CREASTMT.JCL:83`), read sequentially by CBSTM03B for CBSTM03A. **Local build:** dataset `TRXFL`, LRECL 350, indexed, key offset 0 length 32 (`tools/batch-programs.toml:248-253`).
- **Primary key:** `TRNX-KEY` = `TRNX-CARD-NUM` + `TRNX-ID` (32 bytes). Unique, because `TRAN-ID` is unique in ENT-TRANSACTION.

### How STEP010 builds a record from ENT-TRANSACTION

`SORT FIELDS=(263,16,CH,A,1,16,CH,A)` orders the transactions by card number, then transaction ID, both as character strings, ascending. `OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)` then builds each output record (`app/jcl/CREASTMT.JCL:53-54`):

| Output bytes | Input bytes (ENT-TRANSACTION) | Content |
| :-- | :-- | :-- |
| 1-16 | 263-278 | `TRAN-CARD-NUM` |
| 17-278 | 1-262 | `TRAN-ID` through `TRAN-MERCHANT-ZIP`, unchanged |
| 279-328 | 279-328 | `TRAN-ORIG-TS` (26) and the first 24 bytes of `TRAN-PROC-TS` |
| 329-350 | - | not built by OUTREC; padding up to the SORTOUT LRECL of 350 (`app/jcl/CREASTMT.JCL:50`) |

The reformatted record is 328 bytes long. The last 2 bytes of `TRAN-PROC-TS` and the 20-byte FILLER of ENT-TRANSACTION are dropped. The byte that fills positions 329-350 is set by DFSORT on z/OS (platform-inferred). The local harness `docs/modernization/spec/checks/cbstm03a_harness/run_harness.py:41-43` fills them with spaces. No program reads those positions.

### Fields

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `TRNX-CARD-NUM` | 1 | 16 | `X(16)` | text(16) | Card number (PAN), from `TRAN-CARD-NUM`; key part 1; sensitive |
| `TRNX-ID` | 17 | 16 | `X(16)` | text(16) | Transaction ID, from `TRAN-ID`; key part 2 |
| `TRNX-TYPE-CD` | 33 | 2 | `X(02)` | text(2) | Transaction type, from `TRAN-TYPE-CD` |
| `TRNX-CAT-CD` | 35 | 4 | `9(04)` | digits(4) | Category, from `TRAN-CAT-CD` |
| `TRNX-SOURCE` | 39 | 10 | `X(10)` | text(10) | Channel, from `TRAN-SOURCE` |
| `TRNX-DESC` | 49 | 100 | `X(100)` | text(100) | Description, from `TRAN-DESC` |
| `TRNX-AMT` | 149 | 11 | `S9(09)V99` | decimal(11,2) | Amount, from `TRAN-AMT` (same zoned-decimal bytes) |
| `TRNX-MERCHANT-ID` | 160 | 9 | `9(09)` | digits(9) | Merchant number |
| `TRNX-MERCHANT-NAME` | 169 | 50 | `X(50)` | text(50) | Merchant name |
| `TRNX-MERCHANT-CITY` | 219 | 50 | `X(50)` | text(50) | Merchant city |
| `TRNX-MERCHANT-ZIP` | 269 | 10 | `X(10)` | text(10) | Merchant ZIP |
| `TRNX-ORIG-TS` | 279 | 26 | `X(26)` | ts-text | Origin timestamp, from `TRAN-ORIG-TS` |
| `TRNX-PROC-TS` | 305 | 26 | `X(26)` | text(26) | Bytes 1-24 of `TRAN-PROC-TS`, then 2 padding bytes (see above) |
| FILLER | 331 | 20 | `X(20)` | text | Padding |

`TRNX-KEY` is the group of positions 1-32 and `TRNX-REST` the group of positions 33-350 (318 bytes). CBSTM03A stores `TRNX-REST` whole in its table, but prints only `TRNX-ID`, `TRNX-DESC` and `TRNX-AMT`.

### Sample observations

Built from TRANSACT after POSTTRAN on the sample data (the harness default): 262 records for 50 distinct cards. Every card number is in ENT-CARD-XREF. Records per card: 1 card has 1, 2 cards have 2, 2 have 3, 7 have 4, 5 have 5 and 33 have 6. Positions 305-328 hold POSTTRAN's processing timestamp cut to 24 characters, for example `2022-07-06-12.00.00.5100` (the hundredths come from the wall clock and vary from run to run). The longest `TRNX-DESC` is 48 characters.

### Sensitive fields

`TRNX-CARD-NUM` is a full card number and is the leading part of the key, so this work file is another at-rest copy of every PAN in TRANSACT. Its sequential copy `TRXFL.SEQ` stays catalogued between runs too.

Used by: CBSTM03A (through CBSTM03B). The copybook is used only by CBSTM03A (`docs/inventory.md:289`).

## ENT-STMT-REQUEST · Statement file-access request area

- **Source:** no copybook; the layout is written out twice and must stay identical: `WS-M03B-AREA` in the caller (`app/cbl/CBSTM03A.CBL:71-83`) and `LK-M03B-AREA` in the callee's linkage section (`app/cbl/CBSTM03B.CBL:100-112`). Test driver copy: `tests/unit/cobol/cbstm03b_test.cbl:20-26`.
- **Dataset:** none. A 1040-byte parameter passed by reference on every `CALL 'CBSTM03B'` (`app/cbl/CBSTM03B.CBL:114`).
- **Keys:** none. `LK-M03B-DD` selects the file, `LK-M03B-OPER` the operation.
- **Lifetime:** owned by the caller and kept between calls. CBSTM03B writes only `LK-M03B-RC` (for the four known DD names) and `LK-M03B-FLDT` (after a successful read); it never changes the other fields and never initialises the area.

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `LK-M03B-DD` (`WS-M03B-DD`) | 1 | 8 | `X(08)` | text(8) | DD name of the file: `TRNXFILE`, `XREFFILE`, `CUSTFILE` or `ACCTFILE`, exact and case-sensitive; any other value makes the call do nothing |
| `LK-M03B-OPER` (`WS-M03B-OPER`) | 9 | 1 | `X(01)` | text(1) | Operation. 88-levels: `M03B-OPEN` `O`, `M03B-CLOSE` `C`, `M03B-READ` `R` (read next, sequential files), `M03B-READ-K` `K` (read by key, keyed files), `M03B-WRITE` `W`, `M03B-REWRITE` `Z` (both declared, not implemented) |
| `LK-M03B-RC` (`WS-M03B-RC`) | 10 | 2 | `X(02)` | text(2) | Out: file status of the named file after the call (`00`, `10`, `23`, `35`, `41`, `42`, `46`, `47` ...) |
| `LK-M03B-KEY` (`WS-M03B-KEY`) | 12 | 25 | `X(25)` | text(25) | In: key characters for `K` |
| `LK-M03B-KEY-LN` (`WS-M03B-KEY-LN`) | 37 | 4 | `S9(4)` | decimal(4,0) | In: number of key characters used, zoned decimal with the sign in the last byte (11 is `001A` in the ASCII build); not validated |
| `LK-M03B-FLDT` (`WS-M03B-FLDT`) | 41 | 1000 | `X(1000)` | text(1000) | Out: the record read, left-aligned and padded with spaces (transaction 350, cross-reference 50, customer 500, account 300 bytes); unchanged by every other request |

Total length 1040 bytes (8 + 1 + 2 + 25 + 4 + 1000).

Observations on use:

- CBSTM03A sets the DD name and the operation (through the 88-levels) before each call, presets `WS-M03B-RC` to `00` (`MOVE ZERO`) before opens, closes, keyed reads and the cross-reference read, and the data area to spaces before reads (`app/cbl/CBSTM03A.CBL:345-414, 730-919`). For keyed reads it moves `XREF-CUST-ID` (9 digits) or `XREF-ACCT-ID` (11 digits) into the key and the field length into the key length (`app/cbl/CBSTM03A.CBL:372-374, 396-398`). It never uses `W` or `Z`.
- The caller copies the data area into the matching record layout: ENT-STMT-TRAN, ENT-CARD-XREF, ENT-CUSTOMER (CUSTREC names) or ENT-ACCOUNT (`app/cbl/CBSTM03A.CBL:364, 388, 412, 756, 839`).
- Behaviour per request, status values and the account key conversion: `programs/CBSTM03B.md` (unit `STIO`).

**Sensitive fields:** the data area carries whole records: card numbers (ENT-CARD-XREF, ENT-STMT-TRAN) and the customer's SSN, government ID, date of birth and EFT account (ENT-CUSTOMER). The key field holds a customer or account number.

## ENT-DATE-CONV-PARM · Date conversion parameter block

- **Copybook:** `app/cpy/CODATECN.cpy:17-52` (`CODATECN-REC`, 80 bytes). The assembler maps the same bytes with the DSECT `COCDATFT` (`app/maclib/COCDATFT.mac:17-23`: `COINTYPE` CL1, `COINPDT` CL20, `COOUTYPE` CL1, `COOUTDT` CL20, `COERMSG` CL38), copied into `app/asm/COBDATFT.asm:66`.
- **Dataset:** none. It is the in-storage parameter passed by reference to `COBDATFT` (`CALL 'COBDATFT' USING CODATECN-REC`, `app/cbl/CBACT01C.cbl:232`). Not written to any file.
- **Keys:** none.
- **Used by:** CBACT01C (copybook, `docs/inventory.md:287`); COBDATFT (assembler, through the DSECT; locally the shim `tools/shims/COBDATFT.cbl` copies `CODATECN`).

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `CODATECN-IN-REC` (group) | 1 | 21 | - | group | Input type and date |
| `CODATECN-TYPE` | 1 | 1 | `X` | text(1) | Input layout: `1` = `YYYYMMDD` (88 `YYYYMMDD-IN`), `2` = `YYYY-MM-DD` (88 `YYYY-MM-DD-IN`); anything else is rejected by COBDATFT |
| `CODATECN-INP-DATE` | 2 | 20 | `X(20)` | text(20) | Input date, left-aligned |
| `CODATECN-1INP` (redefines, type 1 view) | 2 | 20 | - | group | |
| `CODATECN-1YYYY` | 2 | 4 | `XXXX` | text(4) | Year |
| `CODATECN-1MM` | 6 | 2 | `XX` | text(2) | Month |
| `CODATECN-1DD` | 8 | 2 | `XX` | text(2) | Day |
| `CODATECN-1FIL` | 10 | 12 | `X(12)` | text(12) | Unused |
| `CODATECN-2INP` (redefines, type 2 view) | 2 | 20 | - | group | |
| `CODATECN-1O-YYYY` | 2 | 4 | `XXXX` | text(4) | Year (same name as a field of `CODATECN-1OUT`) |
| `CODATECN-1I-S1` | 6 | 1 | `X` | text(1) | Separator, `-` expected; not checked on the type-2 path (`app/asm/COBDATFT.asm:47-48` are comments). The same byte is input byte 5, which the type-1 path tests: the shim rejects a type-1 request when it is `-` (`tools/shims/COBDATFT.cbl:18`); the assembler's 20-byte compare starting here (`app/asm/COBDATFT.asm:36`) never matches (DEF-DTFT-001) |
| `CODATECN-1MM` | 7 | 2 | `XX` | text(2) | Month (same name as the field at position 6) |
| `CODATECN-1I-S2` | 9 | 1 | `X` | text(1) | Separator, never checked |
| `CODATECN-2YY` | 10 | 2 | `XX` | text(2) | Day, despite the name |
| `CODATECN-2FIL` | 12 | 10 | `X(10)` | text(10) | Unused |
| `CODATECN-OUT-REC` (group) | 22 | 21 | - | group | Output type and date |
| `CODATECN-OUTTYPE` | 22 | 1 | `X` | text(1) | Requested output layout: `1` = `YYYY-MM-DD` (88 `YYYY-MM-DD-OP`), `2` = `YYYYMMDD` (88 `YYYYMMDD-OP`). COBDATFT only rejects the contradicting value (`2` with input type `1`, `1` with input type `2`); any other value is accepted |
| `CODATECN-0UT-DATE` | 23 | 20 | `X(20)` | text(20) | Output date (the name has a zero, not a letter O). COBDATFT writes bytes 1-10 (type 1) or 1-8 (type 2) and leaves the rest as the caller set it |
| `CODATECN-1OUT` (redefines, `YYYY-MM-DD` view) | 23 | 20 | - | group | |
| `CODATECN-1O-YYYY` | 23 | 4 | `XXXX` | text(4) | Year |
| `CODATECN-1O-S1` | 27 | 1 | `X` | text(1) | `-` |
| `CODATECN-1O-MM` | 28 | 2 | `XX` | text(2) | Month |
| `CODATECN-1O-S2` | 30 | 1 | `X` | text(1) | `-` |
| `CODATECN-1O-DD` | 31 | 2 | `XX` | text(2) | Day |
| `CODATECN-1OFIl` | 33 | 10 | `X(10)` | text(10) | Not written by COBDATFT |
| `CODATECN-2OUT` (redefines, `YYYYMMDD` view) | 23 | 20 | - | group | |
| `CODATECN-2O-YYYY` | 23 | 4 | `XXXX` | text(4) | Year |
| `CODATECN-2O-MM` | 27 | 2 | `XX` | text(2) | Month |
| `CODATECN-2O-DD` | 29 | 2 | `XX` | text(2) | Day |
| `CODATECN-2OFIl` | 31 | 12 | `X(12)` | text(12) | Not written by COBDATFT |
| `CODATECN-ERROR-MSG` | 43 | 38 | `X(38)` | text(38) | Set to `INVALID INPUT` on a rejected request (the assembler fills bytes 14-38 from storage after its literal, the local shim with spaces); never cleared by COBDATFT |

Total 80 bytes: 1 + 20 + 1 + 20 + 38, matching the DSECT offsets 0, 1, 21, 22, 42.

Naming notes: `CODATECN-1MM` and `CODATECN-1O-YYYY` are each declared twice, so a program must qualify them (`OF CODATECN-1INP`, `OF CODATECN-2INP`, `OF CODATECN-1OUT`); CBACT01C uses only `CODATECN-REC`, `CODATECN-TYPE`, `CODATECN-INP-DATE`, `CODATECN-OUTTYPE` and `CODATECN-0UT-DATE` (`app/cbl/CBACT01C.cbl:223-234`), none of the redefined subfields. The DSECT comment for `COINTYPE` says `CODATECN-INP-DATE` instead of `CODATECN-TYPE` (`app/maclib/COCDATFT.mac:19`).

Sample observations: the only caller, CBACT01C, always sends input type `2`, output type `2`, the 10-character `ACCT-REISSUE-DATE` of ENT-ACCOUNT in input bytes 1-10 (bytes 11-20 spaces) and an output date of 20 spaces (`app/cbl/CBACT01C.cbl:223-227`). In the local READACCT run all 50 sample accounts came back with `YYYYMMDD` and 2 spaces in `OUT-ACCT-REISSUE-DATE` (output date bytes 1-10, the only ones written to the file); bytes 11-20 stay the spaces CBACT01C set, since COBDATFT writes only bytes 1-8 for type 2.

Sensitive fields: none.
