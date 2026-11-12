# Data-load mapping (appendix to the data model)

This appendix states, field by field, how every source file the target loads or stages becomes table rows: the source field, the column, the column's SQL type, the transform, and the value of every column that no source field fills. It also names the counters and execution-context keys that the spec-only validators read ([batch.md](batch.md#spec-only-validators-2026-11-11)). The rules come from [data-model.md](data-model.md); this appendix does not add rules, except the response-code rule in [`pauth.auth_detail`](#pauthauth_detail-pautdtl1-ent-auth-detail), which is ruled here (2026-11-11).

- **Positions and pictures** are those of the entity catalog (`docs/modernization/spec/entities.md`), 1-based within the record.
- **Types** are those the migrations give at integration head `6e52c75`. Where the [text repertoire](data-model.md#text-repertoire-and-width) stages a different type, the staged type follows "→".
- **Differences** between this document set and the code at `6e52c75` are listed in [Differences at `6e52c75`](#differences-at-6e52c75). They are not resolved here.

## Transforms

| Code | Rule |
| :-- | :-- |
| `digits` | `9(n)` identifier. Every byte must be `0` to `9`, or the step fails. Stored as text with its leading zeros. |
| `text` | `X(n)`. Each byte is decoded to one character U+0000 to U+00FF: ISO-8859-1 for the files of `app/data/ASCII`, code page 037 for the HD unload. Trailing U+0020 is removed. Leading spaces and every other character, NUL included, are kept. |
| `raw` | As `text`, but all `n` characters are kept and nothing is removed. Used for keys and codes. |
| `zoned` | Display numeric, sign overpunched in the last byte (`{` and `A` to `I` positive; `}` and `J` to `R` negative), decimals implied by the picture. A negative zero fails the step, because its re-export would differ. |
| `packed` | `COMP-3`. Sign nibble `A`, `C`, `E` or `F` positive, `B` or `D` negative; any other nibble, a non-digit nibble or a negative value for an unsigned picture fails the step. |
| `binary` | `COMP`, big-endian two's complement. |
| `date` | `X(10)`. All spaces is `NULL`. Anything else must be a strict `YYYY-MM-DD` calendar date, or the step fails. |
| `filler` | A FILLER no column keeps. It must hold its constant (spaces, or the zeros named), or the step fails. Nothing is stored. |
| `kept filler` | A FILLER stored as read in `legacy_filler`, with no constant check. The exporter writes it back. |

**Every file load** (the `dataLoadJob` steps and `disclosureGroupReloadJob`):

- Records have a fixed length. Line ends are LF or CRLF, mixed within a file too (`tcatbal.txt`). A cross-reference line of 36 bytes is padded with spaces to 50. Any other length fails the step.
- Keys are strictly ascending in byte order over the KSDS key length (VAL-JACCT-001 and its siblings). A duplicate or a key out of order fails the step.
- A text value that its column cannot hold unchanged fails the step, naming the file, line and column, never the value ([text repertoire](data-model.md#text-repertoire-and-width) rule 7).
- The step checks the whole file first, then deletes the table's rows and inserts every record, in one transaction. A failure leaves the table as it was.
- Messages name the file, line or record, and field, never content (D-NFR-04).

## `core.account` (`acctdata.txt`, ENT-ACCOUNT)

Step `loadAccounts`, 300-byte records, key length 11.

| Field | Pos | Column | Type | Transform |
| :-- | :-- | :-- | :-- | :-- |
| `ACCT-ID` | 1-11 | `acct_id` | `CHAR(11)` BIN2, PK, digits check | `digits` |
| `ACCT-ACTIVE-STATUS` | 12 | `active_status` | `CHAR(1)` → `NCHAR(1)` | `text` |
| `ACCT-CURR-BAL` | 13-24 | `curr_bal` | `DECIMAL(12,2)` | `zoned` `S9(10)V99` |
| `ACCT-CREDIT-LIMIT` | 25-36 | `credit_limit` | `DECIMAL(12,2)` | `zoned` |
| `ACCT-CASH-CREDIT-LIMIT` | 37-48 | `cash_credit_limit` | `DECIMAL(12,2)` | `zoned` |
| `ACCT-OPEN-DATE` | 49-58 | `open_date` | `DATE` NULL | `date` |
| `ACCT-EXPIRAION-DATE` | 59-68 | `expiration_date` | `DATE` NULL | `date` |
| `ACCT-REISSUE-DATE` | 69-78 | `reissue_date` | `DATE` NULL | `date` |
| `ACCT-CURR-CYC-CREDIT` | 79-90 | `curr_cyc_credit` | `DECIMAL(12,2)` | `zoned` |
| `ACCT-CURR-CYC-DEBIT` | 91-102 | `curr_cyc_debit` | `DECIMAL(12,2)` | `zoned` |
| `ACCT-ADDR-ZIP` | 103-112 | `addr_zip` | `NVARCHAR(10)`, default `N''` | `text`, then the ZIP and group rule below |
| `ACCT-GROUP-ID` | 113-122 | `group_id` | `NCHAR(10)` BIN2, default `N''` | `text`, then the ZIP and group rule below |
| positions 103-122 | 103-122 | `legacy_zip_group` | `NCHAR(20)` BIN2 NULL (V418) | `raw`, all 20 characters |
| - | - | `zip_group_origin` | `CHAR(1)` BIN2 NULL, `R` or `M` (V418) | `R` stored as read, `M` moved |
| FILLER | 123-300 | `legacy_filler` | `NCHAR(178)` BIN2 NULL | `kept filler` |
| - | - | `row_version` | `ROWVERSION` | generated |

**ZIP and group rule** (DEF-ACUP-002, D-11, [layout quirks](data-model.md#filler-and-layout-quirks)):

- Legacy mode, and fixed mode for a record no manifest declares: `addr_zip` = 103-112 and `group_id` = 113-122, each `text`. Origin `R`.
- Fixed mode, a record the [layout manifest](#the-built-in-account-layout-manifest) declares `ACUP_UPDATE`: `group_id` = 103-112 `text` (`''` when all spaces or all U+0000) and `addr_zip` = `''`. Positions 113-122 must be blank, or the step fails before any write. Origin `M`.
- On the sample, fixed mode gives `group_id` `A000000000` and `addr_zip` `''` for all 50 accounts; legacy mode gives `addr_zip` `A000000000` and `group_id` `''`.

## `core.customer` (`custdata.txt`, ENT-CUSTOMER)

Step `loadCustomers`, 500-byte records, key length 9.

| Field | Pos | Column | Type | Transform |
| :-- | :-- | :-- | :-- | :-- |
| `CUST-ID` | 1-9 | `cust_id` | `CHAR(9)` BIN2, PK, digits check | `digits` |
| `CUST-FIRST-NAME` | 10-34 | `first_name` | `VARCHAR(25)` → `NVARCHAR(25)` | `text` |
| `CUST-MIDDLE-NAME` | 35-59 | `middle_name` | `VARCHAR(25)` → `NVARCHAR(25)` | `text` |
| `CUST-LAST-NAME` | 60-84 | `last_name` | `VARCHAR(25)` → `NVARCHAR(25)` | `text` |
| `CUST-ADDR-LINE-1` | 85-134 | `addr_line_1` | `NVARCHAR(50)` | `text` |
| `CUST-ADDR-LINE-2` | 135-184 | `addr_line_2` | `NVARCHAR(50)` | `text` |
| `CUST-ADDR-LINE-3` | 185-234 | `addr_line_3` | `VARCHAR(50)` → `NVARCHAR(50)` | `text` |
| `CUST-ADDR-STATE-CD` | 235-236 | `state_cd` | `CHAR(2)` → `NCHAR(2)` | `text` |
| `CUST-ADDR-COUNTRY-CD` | 237-239 | `country_cd` | `CHAR(3)` → `NCHAR(3)` | `text` |
| `CUST-ADDR-ZIP` | 240-249 | `zip` | `VARCHAR(10)` → `NVARCHAR(10)` | `text` |
| `CUST-PHONE-NUM-1` | 250-264 | `phone_1` | `VARCHAR(15)` → `NVARCHAR(15)` | `text` |
| `CUST-PHONE-NUM-2` | 265-279 | `phone_2` | `VARCHAR(15)` → `NVARCHAR(15)` | `text` |
| `CUST-SSN` | 280-288 | `ssn` | `CHAR(9)`, masked | `digits` |
| `CUST-GOVT-ISSUED-ID` | 289-308 | `govt_issued_id` | `NVARCHAR(20)`, masked | `text` |
| `CUST-DOB-YYYY-MM-DD` | 309-318 | `dob` | `DATE` NULL, masked | `date` |
| `CUST-EFT-ACCOUNT-ID` | 319-328 | `eft_account_id` | `CHAR(10)` → `NCHAR(10)`, masked | `text` |
| `CUST-PRI-CARD-HOLDER-IND` | 329 | `pri_card_holder_ind` | `CHAR(1)` → `NCHAR(1)` | `text` |
| `CUST-FICO-CREDIT-SCORE` | 330-332 | `fico_score` | `SMALLINT` | `digits`, stored as its integer value |
| FILLER | 333-500 | none | - | Any content is accepted and nothing is stored. The step counts the records whose 333-500 is not all spaces (DEF-ACUP-020). The export writes 168 spaces. |
| - | - | `row_version` | `ROWVERSION` | generated |

## `core.card` (`carddata.txt`, ENT-CARD)

Step `loadCards`, 150-byte records, key length 16.

| Field | Pos | Column | Type | Transform |
| :-- | :-- | :-- | :-- | :-- |
| `CARD-NUM` | 1-16 | `card_num` | `CHAR(16)` BIN2, PK → `NCHAR(16)` | `raw` |
| `CARD-ACCT-ID` | 17-27 | `acct_id` | `CHAR(11)` BIN2, no FK | `digits` |
| `CARD-CVV-CD` | 28-30 | `cvv_cd` | `CHAR(3)`, masked, no login unmasks it (D-DATA-04) | `digits` |
| `CARD-EMBOSSED-NAME` | 31-80 | `embossed_name` | `VARCHAR(50)` → `NVARCHAR(50)` | `text` |
| `CARD-EXPIRAION-DATE` | 81-90 | `expiration_date` | `CHAR(10)`, pattern check `####-##-##` → `NCHAR(10)`, check dropped | `text`. Kept as text, not parsed (DEF-CRDU-009); at `6e52c75` the step fails a value that is not four digits, dash, two digits, dash, two digits. |
| `CARD-ACTIVE-STATUS` | 91 | `active_status` | `CHAR(1)` → `NCHAR(1)` | `text` |
| FILLER | 92-150 | `legacy_filler` | `NCHAR(59)` BIN2 NULL | `kept filler` |
| - | - | `card_id` | `BIGINT IDENTITY`, unique | generated |
| - | - | `row_version` | `ROWVERSION` | generated |

## `core.card_xref` (`cardxref.txt`, ENT-CARD-XREF)

Step `loadCardXref`, 50-byte records (the sample's 36-byte lines padded), key length 16.

| Field | Pos | Column | Type | Transform |
| :-- | :-- | :-- | :-- | :-- |
| `XREF-CARD-NUM` | 1-16 | `card_num` | `CHAR(16)` BIN2, PK → `NCHAR(16)` | `raw` |
| `XREF-CUST-ID` | 17-25 | `cust_id` | `CHAR(9)` BIN2, FK `core.customer` | `digits`; must name a loaded customer, or the step fails |
| `XREF-ACCT-ID` | 26-36 | `acct_id` | `CHAR(11)` BIN2, FK `core.account` | `digits`; must name a loaded account, or the step fails |
| FILLER | 37-50 | none | - | `filler`, spaces |

## `core.tcat_bal` (`tcatbal.txt`, ENT-TCAT-BAL)

Step `loadCategoryBalances`, 50-byte records, key length 17.

| Field | Pos | Column | Type | Transform |
| :-- | :-- | :-- | :-- | :-- |
| `TRANCAT-ACCT-ID` | 1-11 | `acct_id` | `CHAR(11)` BIN2, FK `core.account` | `digits`; must name a loaded account, or the step fails |
| `TRANCAT-TYPE-CD` | 12-13 | `type_cd` | `CHAR(2)` BIN2 → `NCHAR(2)` | `raw` |
| `TRANCAT-CD` | 14-17 | `cat_cd` | `CHAR(4)` BIN2 | `digits` |
| `TRAN-CAT-BAL` | 18-28 | `balance` | `DECIMAL(11,2)` | `zoned` `S9(09)V99` |
| FILLER | 29-50 | `legacy_filler` | `NCHAR(22)` BIN2 NULL | `kept filler` (22 zeros in the sample) |
| - | - | `row_version` | `ROWVERSION` | generated |

## Reference tables

`ref.tran_type` (`trantype.txt`, ENT-TRAN-TYPE), step `loadTransactionTypes`, 60-byte records, key length 2:

| Field | Pos | Column | Type | Transform |
| :-- | :-- | :-- | :-- | :-- |
| `TRAN-TYPE` | 1-2 | `type_cd` | `CHAR(2)` BIN2, PK → `NCHAR(2)` | `raw` |
| `TRAN-TYPE-DESC` | 3-52 | `description` | `VARCHAR(50)` → `NVARCHAR(50)` | `text` |
| FILLER | 53-60 | none | - | `filler`, `00000000` |
| - | - | `row_version` | `ROWVERSION` | generated |

`ref.tran_category` (`trancatg.txt`, ENT-TRAN-CATEGORY), step `loadCategories`, 60-byte records, key length 6:

| Field | Pos | Column | Type | Transform |
| :-- | :-- | :-- | :-- | :-- |
| `TRAN-TYPE-CD` | 1-2 | `type_cd` | `CHAR(2)` BIN2, FK `ref.tran_type` → `NCHAR(2)` | `raw`; must name a loaded type, or the step fails |
| `TRAN-CAT-CD` | 3-6 | `cat_cd` | `CHAR(4)` BIN2, digits check | `digits` |
| `TRAN-CAT-TYPE-DESC` | 7-56 | `description` | `VARCHAR(50)` → `NVARCHAR(50)` | `text` |
| FILLER | 57-60 | none | - | `filler`, `0000` |
| - | - | `row_version` | `ROWVERSION` | generated |

`ref.disclosure_group` (`discgrp.txt`, ENT-DISC-GROUP), step `loadDisclosureGroups` and the `reload` step of `disclosureGroupReloadJob` (same mapping, [batch.md](batch.md#discgrp-disclosuregroupreloadjob)), 50-byte records, key length 16. The file must hold the `DEFAULT` group, or the step fails.

| Field | Pos | Column | Type | Transform |
| :-- | :-- | :-- | :-- | :-- |
| `DIS-ACCT-GROUP-ID` | 1-10 | `group_id` | `CHAR(10)` BIN2 → `NCHAR(10)` | `text` |
| `DIS-TRAN-TYPE-CD` | 11-12 | `type_cd` | `CHAR(2)` BIN2 → `NCHAR(2)` | `raw` |
| `DIS-TRAN-CAT-CD` | 13-16 | `cat_cd` | `CHAR(4)` BIN2, digits check | `digits` |
| `DIS-INT-RATE` | 17-22 | `int_rate` | `DECIMAL(6,2)` | `zoned` `S9(04)V99` |
| FILLER | 23-50 | none | - | `filler`, 28 zeros |
| - | - | `row_version` | `ROWVERSION` | generated |

There is no foreign key from `ref.disclosure_group` to the type or category tables.

**Lookup lists** (step `loadLookups`). The source is not a legacy file. It is the validation library's `lookups.json`, the lists `usStateCodes` (pattern `[A-Z]{2}`), `usStateZipPrefixes` (`[A-Z]{2}[0-9]{2}`) and `nanpGeneralPurposeAreaCodes` (`[0-9]{3}`). A value that does not match, or is listed twice, fails the step. They go to `ref.us_state.state_cd`, `ref.us_state_zip_prefix` (`state_cd` = characters 1-2, `zip_prefix` = 3-4) and `ref.nanp_area_code.area_code`, all `CHAR` BIN2.

## `pauth.auth_summary` (`PAUTSUM0`, ENT-AUTH-SUMMARY)

Step `loadPendingAuthorizations`. The record structure of the HD unload is in [Pending authorizations](data-model.md#pending-authorizations-pauth): a field at entity position `p` is at record offset `39 + p - 1`. The root whose `PA-ACCT-ID` bytes are all `X'40'` is skipped with its details (BR-PDUL-002).

| Field | Pos | Column | Type | Transform |
| :-- | :-- | :-- | :-- | :-- |
| `PA-ACCT-ID` | 1-6 | `acct_id` | `CHAR(11)` BIN2, PK, FK `core.account` | `packed` `S9(11)`, written as 11 digits with leading zeros. A negative value fails the step. It must be above the previous summary's and name a loaded account. |
| `PA-CUST-ID` | 7-15 | `cust_id` | `CHAR(9)` BIN2, digits check | `digits` |
| `PA-AUTH-STATUS` | 16 | none | - | not stored, not checked |
| `PA-ACCOUNT-STATUS` (5) | 17-26 | none | - | not stored, not checked |
| `PA-CREDIT-LIMIT` | 27-32 | `credit_limit` | `DECIMAL(12,2)` (V504) | `packed` `S9(09)V99` |
| `PA-CASH-LIMIT` | 33-38 | `cash_limit` | `DECIMAL(12,2)` (V504) | `packed` |
| `PA-CREDIT-BALANCE` | 39-44 | `credit_balance` | `DECIMAL(15,2)` (V504) | `packed` |
| `PA-CASH-BALANCE` | 45-50 | `cash_balance` | `DECIMAL(15,2)` (V504) | `packed` |
| `PA-APPROVED-AUTH-CNT` | 51-52 | `approved_auth_cnt` | `INT` | `binary` `S9(04)` |
| `PA-DECLINED-AUTH-CNT` | 53-54 | `declined_auth_cnt` | `INT` | `binary` |
| `PA-APPROVED-AUTH-AMT` | 55-60 | `approved_auth_amt` | `DECIMAL(15,2)` | `packed` |
| `PA-DECLINED-AUTH-AMT` | 61-66 | `declined_auth_amt` | `DECIMAL(15,2)` | `packed` |
| FILLER | 67-100 | none | - | not stored, not checked |
| - | - | `row_version` | `ROWVERSION` | generated |

## `pauth.auth_detail` (`PAUTDTL1`, ENT-AUTH-DETAIL)

Same step and offsets. A detail belongs to the summary before it in the file.

| Field | Pos | Column | Type | Transform |
| :-- | :-- | :-- | :-- | :-- |
| (parent) | - | `acct_id` | `CHAR(11)` BIN2, FK `pauth.auth_summary` (`NO ACTION`, V507) | the parent summary's `acct_id` |
| `PA-AUTH-DATE-9C` | 1-3 | `auth_date_9c` | `INT` | `packed` `S9(05)` |
| `PA-AUTH-TIME-9C` | 4-8 | `auth_time_9c` | `INT` | `packed` `S9(09)`. The pair is unique under one summary (`UQ_auth_detail_9c`); a repeat fails the step. |
| both parts | 1-8 | `processed_at` | `DATETIME2(3)` | date = year `2000 + YY`, day `DDD` of `99999 - date9c`; time = `HHMMSSmmm` of `999999999 - time9c`. A value that is not a valid date or time fails the step. |
| `PA-AUTH-ORIG-DATE` | 9-14 | `orig_date` | `NCHAR(6)` | `raw` |
| `PA-AUTH-ORIG-TIME` | 15-20 | `orig_time` | `NCHAR(6)` | `raw` |
| `PA-CARD-NUM` | 21-36 | `card_num` | `CHAR(16)` BIN2, masked → `NCHAR(16)` (V506) | `raw` |
| `PA-AUTH-TYPE` | 37-40 | `auth_type` | `NCHAR(4)` | `raw` |
| `PA-CARD-EXPIRY-DATE` | 41-44 | `card_expiry_date` | `NCHAR(4)` | `raw` |
| `PA-MESSAGE-TYPE` | 45-50 | `message_type` | `NCHAR(6)` | `raw` |
| `PA-MESSAGE-SOURCE` | 51-56 | `message_source` | `NCHAR(6)` | `raw` |
| `PA-AUTH-ID-CODE` | 57-62 | `auth_id_code` | `CHAR(6)` → `NCHAR(6)` (V508) | `raw`; loaded rows keep the legacy code |
| `PA-AUTH-RESP-CODE` | 63-64 | `auth_resp_code` | `CHAR(2)` BIN2, `CHECK IN ('00','05')` | `raw`; must be `00` or `05` (below) |
| `PA-AUTH-RESP-REASON` | 65-68 | `auth_resp_reason` | `CHAR(4)` BIN2 → `NCHAR(4)` BIN2 (V508) | `raw` |
| `PA-PROCESSING-CODE` | 69-74 | `processing_code` | `NCHAR(6)` | `digits` |
| `PA-TRANSACTION-AMT` | 75-81 | `transaction_amt` | `DECIMAL(12,2)` | `packed` `S9(10)V99` |
| `PA-APPROVED-AMT` | 82-88 | `approved_amt` | `DECIMAL(12,2)` | `packed` |
| `PA-MERCHANT-CATAGORY-CODE` | 89-92 | `merchant_category_code` | `NCHAR(4)` | `raw` |
| `PA-ACQR-COUNTRY-CODE` | 93-95 | `acqr_country_code` | `NCHAR(3)` | `raw` |
| `PA-POS-ENTRY-MODE` | 96-97 | `pos_entry_mode` | `NCHAR(2)` | `digits` |
| `PA-MERCHANT-ID` | 98-112 | `merchant_id` | `NCHAR(15)` | `raw` |
| `PA-MERCHANT-NAME` | 113-134 | `merchant_name` | `NVARCHAR(22)` | `text` |
| `PA-MERCHANT-CITY` | 135-147 | `merchant_city` | `NVARCHAR(13)` | `text` |
| `PA-MERCHANT-STATE` | 148-149 | `merchant_state` | `NCHAR(2)` | `raw` |
| `PA-MERCHANT-ZIP` | 150-158 | `merchant_zip` | `NVARCHAR(9)` | `text` |
| `PA-TRANSACTION-ID` | 159-173 | `transaction_id` | `NCHAR(15)` | `raw` |
| `PA-MATCH-STATUS` | 174 | `match_status` | `CHAR(1)` BIN2, `CHECK IN ('P','D','E','M')` | `raw`; must be `P`, `D` or `E`. `M` fails the step, because an unload cannot carry the matched transaction columns (V503). |
| `PA-AUTH-FRAUD` | 175 | `auth_fraud` | `CHAR(1)` BIN2, default `' '`, `CHECK IN (' ','F','R')` | `raw`; must be a space, `F` or `R` |
| `PA-FRAUD-RPT-DATE` | 176-183 | `fraud_rpt_date` | `DATE` NULL | All spaces is `NULL`; otherwise `MM/DD/YY` with year `20YY`, a valid date, or the step fails. |
| FILLER | 184-200 | none | - | not stored, not checked |
| - | - | `auth_id` | `BIGINT IDENTITY`, PK | generated in file order; never reseeded, because fraud rows keep their `auth_id` |
| - | - | `fraud_changed_by` | `NCHAR(8)` BIN2 NULL | `NULL` |
| - | - | `matched_tran_pk`, `matched_tran_id`, `matched_cycle_id`, `matched_at` | V503, NULL | `NULL` |
| - | - | `row_version` | `ROWVERSION` | generated |

**Response code (ruled 2026-11-11).** A detail whose `PA-AUTH-RESP-CODE` is not `00` or `05`, blank included, fails `loadPendingAuthorizations` before anything is written. No CardDemo writer produces another code: the authorization program writes `00` for an approval and `05` for a decline (BR-AUTH-006), and the table refuses any other value (`CK_auth_detail_resp`), so a target writer cannot store one either. The bundled unload holds `00` in all 202 details. BR-AUPG-003 treats "any other code, including blanks" as a decline, but in the target no such row can exist, so the purge has no case for it ([batch.md](batch.md#authorization-purge-purgependingauthorizationsjob)).

`pauth.auth_fraud` is not loaded: the repository has no `AUTHFRDS` sample, and the load never touches the table.

## `core.daily_tran` (daily file, ENT-DAILY-TRAN)

Not part of `dataLoadJob`. The `stage` step of `ingestDailyFileJob` writes one row per record of the cycle's inbound file ([batch.md](batch.md#files-and-tables)), 350-byte records, with no key check. It stages fields as read without validating them, so it never refuses a record that posting must reject. Only a value that its column cannot hold unchanged fails it (`DAILY_FILE_INVALID`, record number and column).

| Field | Pos | Column | Type | Transform |
| :-- | :-- | :-- | :-- | :-- |
| `DALYTRAN-ID` | 1-16 | `tran_id` | `CHAR(16)` BIN2 → `NCHAR(16)` | `raw` |
| `DALYTRAN-TYPE-CD` | 17-18 | `type_cd` | `CHAR(2)` BIN2 → `NCHAR(2)` | `raw` |
| `DALYTRAN-CAT-CD` | 19-22 | `cat_cd` | `CHAR(4)` BIN2 | `raw` (not checked here) |
| `DALYTRAN-SOURCE` | 23-32 | `source` | `NVARCHAR(10)` | `text` |
| `DALYTRAN-DESC` | 33-132 | `description` | `NVARCHAR(100)` | `text` |
| `DALYTRAN-AMT` | 133-143 | `amount` | `DECIMAL(11,2)` NULL | `zoned` `S9(09)V99`; `NULL` when the field does not decode |
| `DALYTRAN-MERCHANT-ID` | 144-152 | `merchant_id` | `CHAR(9)` | `raw` (not checked here) |
| `DALYTRAN-MERCHANT-NAME` | 153-202 | `merchant_name` | `NVARCHAR(50)` | `text` |
| `DALYTRAN-MERCHANT-CITY` | 203-252 | `merchant_city` | `NVARCHAR(50)` | `text` |
| `DALYTRAN-MERCHANT-ZIP` | 253-262 | `merchant_zip` | `NVARCHAR(10)` | `text` |
| `DALYTRAN-CARD-NUM` | 263-278 | `card_num` | `CHAR(16)` BIN2, masked → `NCHAR(16)` | `raw` |
| `DALYTRAN-ORIG-TS` | 279-304 | `orig_ts` | `CHAR(26)` → `NCHAR(26)` | `raw`; parsed by posting |
| `DALYTRAN-PROC-TS` | 305-330 | `proc_ts` | `CHAR(26)` → `NCHAR(26)` | `raw` |
| FILLER | 331-350 | none | - | not stored; kept in `raw_record` |
| whole record | 1-350 | `raw_record` | `NCHAR(350)` BIN2 | `raw` |
| - | - | `daily_pk` | `BIGINT IDENTITY`, PK | generated |
| - | - | `cycle_id` | `INT` | the cycle |
| - | - | `seq` | `INT` | the record number, from 1 |
| - | - | `origin` | `VARCHAR(12)` | `DAILY_FILE` |
| - | - | `created_by` | `NCHAR(8)` BIN2 NULL | `NULL` |
| - | - | `created_at` | `DATETIME2(3)` | default `SYSUTCDATETIME()` |
| - | - | `idempotency_key` | `UNIQUEIDENTIFIER` NULL (V408) | `NULL` |

## `core.transaction` (`TRANSACT`, ENT-TRANSACTION)

No step loads `TRANSACT`. The first step of `dataLoadJob` empties `core.transaction`, and the load leaves it empty (TRANFILE's dummy row is not carried over). The origin `LOADED` is allowed by `CK_transaction_origin` but no job writes it at `6e52c75`; only test fixtures do. The record-to-column mapping below is the one the exporter and the differential checks use:

| Field | Pos | Column | Type | Rule |
| :-- | :-- | :-- | :-- | :-- |
| `TRAN-ID` | 1-16 | `tran_id` | `CHAR(16)` BIN2, length check → `NCHAR(16)` | `raw` |
| `TRAN-TYPE-CD` | 17-18 | `type_cd` | `CHAR(2)` BIN2 → `NCHAR(2)` | `raw` |
| `TRAN-CAT-CD` | 19-22 | `cat_cd` | `CHAR(4)` BIN2 | `digits` |
| `TRAN-SOURCE` | 23-32 | `source` | `NVARCHAR(10)` | `text` |
| `TRAN-DESC` | 33-132 | `description` | `NVARCHAR(100)` | `text` |
| `TRAN-AMT` | 133-143 | `amount` | `DECIMAL(11,2)` | `zoned` `S9(09)V99` |
| `TRAN-MERCHANT-ID` | 144-152 | `merchant_id` | `CHAR(9)` | `digits` |
| `TRAN-MERCHANT-NAME` | 153-202 | `merchant_name` | `NVARCHAR(50)` | `text` |
| `TRAN-MERCHANT-CITY` | 203-252 | `merchant_city` | `NVARCHAR(50)` | `text` |
| `TRAN-MERCHANT-ZIP` | 253-262 | `merchant_zip` | `NVARCHAR(10)` | `text` |
| `TRAN-CARD-NUM` | 263-278 | `card_num` | `CHAR(16)` BIN2, masked → `NCHAR(16)` | `raw` |
| `TRAN-ORIG-TS` | 279-304 | `orig_ts` | `DATETIME2(6)` | parsed from any known layout (ENT-TRANSACTION timestamp table); a date-only value is midnight. The export writes the origin's layout back (D-60). |
| `TRAN-PROC-TS` | 305-330 | `proc_ts` | `DATETIME2(6)` | as `orig_ts` |
| FILLER | 331-350 | `legacy_filler` | `NCHAR(20)` BIN2 NULL | `kept filler` |
| - | - | `tran_pk` | `BIGINT IDENTITY`, PK | generated |
| - | - | `proc_date` | computed, persisted | `CAST(proc_ts AS DATE)` |
| - | - | `origin` | `VARCHAR(12)` | the writer: `POSTED`, `INTEREST`, `ONLINE_ADD`, `BILL_PAY` |
| - | - | `cycle_id` | `INT` NULL, FK `batch.cycle` | the posting cycle |
| - | - | `in_master` | `BIT`, default 1 | 1 on insert; 0 after `archive` (TRANBKP) |
| - | - | `created_by` | `NCHAR(8)` BIN2 NULL | the user, for online writers |

## Counters and context keys

Every count below is read from the job repository: `BATCH_STEP_EXECUTION.READ_COUNT` and `WRITE_COUNT`, and the step's or job's execution context. Keys not listed here are internal restart state, not totals, and no validator may depend on them.

| Job and step | Where | Name | Value |
| :-- | :-- | :-- | :-- |
| `dataLoadJob`, each file step (`loadAccounts`, `loadCustomers`, `loadCards`, `loadCardXref`, `loadCategoryBalances`, `loadTransactionTypes`, `loadCategories`, `loadDisclosureGroups`) | step counts | `READ_COUNT`, `WRITE_COUNT` | records read; rows inserted (equal on success) |
| `dataLoadJob`, `loadLookups` | step counts | `WRITE_COUNT` | rows of the three lists together: 706 on the standard lists (56 + 240 + 410) |
| `dataLoadJob`, `loadPendingAuthorizations` | step counts | `READ_COUNT`; `WRITE_COUNT` | segment records read, the skipped root and its details included (224 on the sample); summaries plus details inserted (223) |
| `dataLoadJob`, `clearCoreTables`, `restartTranIdSequence` | - | none | no counts |
| `dataLoadJob`, `loadAccounts` | job context | `dataLoad.acctdataSha256`, `dataLoad.accountLayoutManifestSha256` | hex SHA-256 of the bytes the first attempt read; the second only when a manifest was supplied |
| `dataLoadJob`, `loadAccounts` | step context | `accountsAsRead`, `accountsMigrated` (long); `accountLayoutSource` (`builtin`, `supplied`, `none` or `ignored`); `accountLayoutWarnings` (long); `acctdataSha256`; `accountLayoutManifestSha256` (`""` when none); `groupIdMode` (`legacy` or `fixed`) | the load report |
| `dataLoadJob`, `loadCustomers` | step context | `customerFillerNormalised` (long); `customerFillerNormalisedLines` (`3, 7-9` form, at most 100 ranges) | the customer FILLER report |
| `archiveMasterJob`, `archive` | `batch.cycle.archived_count` (`INT` NULL) | - | rows moved out of the master by the first run of the cycle; a rerun keeps it. `NULL` until the step has run. The step's `WRITE_COUNT` is that run's own count (0 on a rerun). |
| `purgePendingAuthorizationsJob`, `purge` | step context | `summariesRead`, `summariesDeleted`, `detailsRead`, `detailsDeleted`, `summariesDeletedWithNonZeroTotals`, `fraudDetailsKept` (long); `creditReleased` (string) | the run totals (BR-AUPG-006) and two more, defined [below](#purge-keys) |
| `purgePendingAuthorizationsJob`, `purgeMessages` | step context | `processedMessagesDeleted`, `outboxRowsDeleted` (long); `messagesBefore` (string) | message cleanup, defined [below](#purge-keys) |
| `categoryBalanceListingJob`, `list` | step counts | `WRITE_COUNT` | lines written; must equal the line count of the published file |
| `disclosureGroupReloadJob`, `reload` and `checkDefaultGroup` | step counts | `reload`: `READ_COUNT`, `WRITE_COUNT` | records read and inserted; `checkDefaultGroup` has none |

The job parameters of the purge are `cycleId`, `businessDate`, `attempt`, `expiryDays` and `commitInterval`. The context keys of `loadAccounts` and `loadCustomers`, and the listing and DISCGRP steps, are not in `6e52c75` ([below](#differences-at-6e52c75)); their names are fixed here as the branches that build them use them. The listing and DISCGRP jobs need no context keys of their own.

### Purge keys

The rules are those of [authorization-service.md](authorization-service.md#3-purge-and-fraud-question-11-data-and-batch). `cutoff` is `businessDate − expiryDays`. A detail is expired when `CAST(processed_at AS DATE) <= cutoff`, which the job runs as `processed_at < cutoff + 1 day` at 00:00. Every count is summed over the summaries of the step's committed chunks. A restart carries the step context, so its values include the work committed before it. Concurrent purges split the counts between them (their sum equals one run's).

| Key | What it is | How it is computed | When zero |
| :-- | :-- | :-- | :-- |
| `summariesRead`, `detailsRead` | summaries, and their details, present when each summary is processed | per summary: 1 when it is found under its lock, and the count of all its details read under the lock before the delete | written as `0` |
| `summariesDeleted`, `detailsDeleted` | rows this run deleted | the rows of the `DELETE` statements' `OUTPUT` | written as `0` |
| `summariesDeletedWithNonZeroTotals` | deleted summaries that still held a non-zero stored value | a deleted summary counts when any of `approved_auth_cnt`, `approved_auth_amt`, `declined_auth_cnt`, `declined_auth_amt`, `credit_balance` or `cash_balance` is not zero after the subtraction (the values the delete removes) | written as `0` |
| `fraudDetailsKept` | expired details kept because they are flagged | per summary: the details read under the lock with `processed_at` before the detail bound and `auth_fraud = 'F'` | written as `0` |
| `creditReleased` | the pending credit released by deletion | the sum of `approved_amt` over the deleted details with `auth_resp_code = '00'` and `match_status = 'P'` (the deleted row's own state). It is the sum of the `credit_balance` subtractions. Plain decimal string with two fraction digits (`"125.40"`, `"0.00"`). When the step processed no summary it is `"0"`. Compare it as a decimal value. | written |
| `messagesBefore` | the exclusive upper bound for the message rows, a timestamp | the earlier of `cutoff` and the database's UTC date at the step's first call minus `expiryDays`, plus one day, at 00:00. The row timestamps are UTC, so the bound is compared with no time-zone conversion: a row is deleted when its timestamp is before the bound, that is, when its date is on or before the earlier day. Written once, before any delete, as ISO `YYYY-MM-DDT00:00` (Java `LocalDateTime.toString`, no seconds). A restart of the step keeps it. `none` when the earlier day is before 0001-01-01; then nothing is deleted. | always written |
| `processedMessagesDeleted` | `pauth.processed_message` rows deleted | rows with `received_at` before `messagesBefore` | written as `0` |
| `outboxRowsDeleted` | sent `pauth.outbox` rows deleted | rows with `sent_at IS NOT NULL` and `created_at` before `messagesBefore`; unsent rows are never deleted | written as `0` |

## The built-in account layout manifest

The manifest of DEF-ACUP-002 is a resource of batch-service, `modernization/batch/batch-service/src/main/resources/org/carddemo/batch/data/acctdata-ascii.layout.toml`, read through `AccountLayoutManifest` and `AccountLayouts` (the list `BUILT_IN`). It holds `schema = 1`, `sha256 = "c2a97b6a32dc4a87a7aafdf7f72e6712e560412d30b00c5526cca80fc9dfd260"` (the bytes of `app/data/ASCII/acctdata.txt`) and `default_layout = "ACUP_UPDATE"`, with no per-account overrides (`other_layout_accounts`). `AccountLayoutsTest` recomputes the hash from the file. A supplied manifest comes from the property `carddemo.data-load.account-layout-manifest`. The validators read the built-in manifest from that path in the source tree, never through batch-service.

It was added by data's `c95c8f6`, which `build/data-acup` (`0eade3f`) and `build/backend-grant-candidate` (`cffcd66`) both contain. It is not in `6e52c75`.

## Differences at `6e52c75`

These are differences between the architecture and the code at the integration head. Each is resolved by merging the branch named, or reported to its owner; none is decided here.

1. **Account ZIP and group.** At `6e52c75` the loader has no manifest. In fixed mode it moves 103-112 to `group_id` for every record and fails when `ACCT-GROUP-ID` is not blank; the V418 columns `legacy_zip_group` and `zip_group_origin` do not exist, and the `loadAccounts` context keys are not written. The ruling is built on `build/data-acup` and `build/backend-grant-candidate`.
2. **Customer 333-500.** At `6e52c75` that FILLER is an unmodelled FILLER, so a record whose 333-500 is not all spaces fails `loadCustomers`. The rule above (DEF-ACUP-020) is built on `build/data-acup`, with the `customerFillerNormalised` keys.
3. **Description types.** `ref.tran_type.description` and `ref.tran_category.description` are `VARCHAR(50)` at `6e52c75`. [data-model.md](data-model.md#reference-identity-and-module-tables) gives `NVARCHAR(50)`, which `V421` sets. `V421` comes from data's `4a1de14` (`build/data-refdesc`), which `build/backend-grant-candidate` carries.
4. **Stage 2 columns.** Every "→" type above waits for `V414` (proposed) or `V506`. Until then a value outside the column's repertoire fails the step, as rule 7 says.
5. **`auth_id_code` and `auth_resp_reason`.** At `6e52c75` neither is in the text repertoire, yet both carry a loaded `X(n)` value. `auth_id_code CHAR(6)` uses the database's UTF-8 collation, so a character from U+0080 up takes two bytes and a full-width value is refused. `auth_resp_reason CHAR(4)` with `Latin1_General_100_BIN2` uses code page 1252, which stores U+0080 to U+009F as `?`. `loadPendingAuthorizations` does not run the column-capacity check that the file loaders run, so a lossy substitution would not fail the step. Both halves are closed. The storage half by backend's `V508__pauth_auth_code_text_repertoire.sql` (`576e427`, on `build/backend-grant-candidate`): `NCHAR(6)` with the database collation, and `NCHAR(4)`, in `pauth.auth_detail` and `pauth.auth_fraud` ([data-model.md](data-model.md#migrations); the fraud copy's collation follows in `V509`). The loader half by data's `fa75495` (`build/data-pauth`): every summary and detail row is checked against its columns, in file order, before the first write.
6. **`batch_svc` and the CVV.** At `6e52c75`, `R__grants_batch.sql` grants `UNMASK` on all of `core.card` to `batch_svc`, `cvv_cd` included, against D-DATA-04. `build/backend-grant-candidate` narrows it to the customer, transaction and daily card columns and gives none on `core.card`. Until that merges, no validator reads `core.card` on `batch_svc`.
7. **Listing and DISCGRP jobs.** `categoryBalanceListingJob` and `disclosureGroupReloadJob` are in the job catalog at `6e52c75` but not built. They are built on `build/batch-catalogue-jobs` (`dc57394`), with the step names above.
