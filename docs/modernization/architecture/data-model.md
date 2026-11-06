# Data model (SQL Server 2025)

One database, `carddemo`, with one schema per bounded context. Flyway migrations in `modernization/db/` create everything; no service runs migrations itself.

## Schemas

| Schema | Holds | Owner (DDL and main writer) |
| :-- | :-- | :-- |
| `core` | account, customer, card, card cross-reference, transaction, category balance, daily input staging, daily rejects | shared: see [grants](#ownership-and-grants) |
| `ref` | transaction types and categories, disclosure groups, state, ZIP-prefix and area-code lists | `reference-service` |
| `iam` | users, refresh tokens, audit events | `identity-service` |
| `billpay` | payment audit and idempotency | `billpay-service` |
| `pauth` | pending authorization summary and detail, fraud reports, processed messages, reply outbox | `authorization-service` |
| `batch` | Spring Batch metadata, cycles, report and statement runs | `batch-service` |
| `ops` | batch window, application audit | `batch-service` (window), all services (audit inserts) |

**Collation.** Database default `Latin1_General_100_CI_AS_SC_UTF8`. Key and code columns (`acct_id`, `card_num`, `tran_id`, `type_cd`, `cat_cd`, `user_id`, `group_id`) use `Latin1_General_100_BIN2`, so comparison and ordering are byte order, as in VSAM. With fixed-width digit keys, byte order is numeric order, and ASCII and EBCDIC agree.

## Ownership and grants

Each service connects with its own SQL login. The grants are the enforced version of the coupling the spec describes.

| Table | Read | Insert | Update | Delete |
| :-- | :-- | :-- | :-- | :-- |
| `core.account` | all services, batch | batch (load) | account-service (profile columns); billpay-service (`curr_bal` only); batch (balances, cycle totals) | batch (reload) |
| `core.customer` | account, authorization, batch | batch (load) | account-service | batch (reload) |
| `core.card` | account, batch | batch (load) | account-service (name, status, expiry; account in legacy mode) | batch (reload) |
| `core.card_xref` | all services, batch | batch (load) | none | batch (reload) |
| `core.transaction` | transaction, billpay, inquiry, batch | transaction-service, billpay-service, batch | batch (`in_master` only) | batch (retention purge) |
| `core.tcat_bal` | batch | batch | batch (and billpay-service if DEF-BILL-010 is fixed) | batch (reload) |
| `core.daily_tran`, `core.daily_reject` | batch, operations view | batch | none | batch (retention) |
| `ref.*` | all | reference-service, batch (load, maintenance job) | same | same |

Column-level `GRANT UPDATE (curr_bal)` to the Bill Pay login is what keeps Bill Pay standalone yet unable to change anything else in the account (INV-004).

## Core tables

### `core.account` (ENT-ACCOUNT, `ACCTDATA`, 300 bytes)

| Column | Type | From | Notes |
| :-- | :-- | :-- | :-- |
| `acct_id` | `CHAR(11)` BIN2, PK, `CHECK` 11 digits | `ACCT-ID` 9(11) | Text, so `123` is not `00000000123` (VAL-BILL-003, DEF-BILL-004). |
| `active_status` | `CHAR(1)` | `ACCT-ACTIVE-STATUS` | |
| `curr_bal`, `credit_limit`, `cash_credit_limit`, `curr_cyc_credit`, `curr_cyc_debit` | `DECIMAL(12,2)` | `S9(10)V99` zoned | |
| `open_date`, `expiration_date`, `reissue_date` | `DATE` | `X(10)` `YYYY-MM-DD` | Account update writes only valid dates (VAL-ACUP-005 to 009); the loader rejects unparsable values. |
| `addr_zip` | `VARCHAR(10)` | positions 103-112 | Holds `A000000000` in every sample account ([layout quirks](#filler-and-layout-quirks)). |
| `group_id` | `CHAR(10)` BIN2 | positions 113-122 | Blank in the sample; key into `ref.disclosure_group`, fallback `DEFAULT`. |
| `legacy_filler` | `CHAR(178)` NULL | FILLER 123-300 | Kept only for byte-exact export; see below. |
| `row_version` | `ROWVERSION` | - | Optimistic check for account update (INV-042). |

### `core.customer` (ENT-CUSTOMER, `CUSTDATA`, 500 bytes)

`cust_id CHAR(9)` PK; `first_name`, `middle_name`, `last_name VARCHAR(25)`; `addr_line_1` to `_3 VARCHAR(50)` (line 3 is the city); `state_cd CHAR(2)`; `country_cd CHAR(3)`; `zip VARCHAR(10)`; `phone_1`, `phone_2 VARCHAR(15)`; `ssn CHAR(9)`; `govt_issued_id VARCHAR(20)`; `dob DATE`; `eft_account_id CHAR(10)`; `pri_card_holder_ind CHAR(1)`; `fico_score SMALLINT`; `row_version`. SSN, government ID, DOB and EFT account carry SQL Server dynamic data masking for every login except the account-service unmask role; encryption at rest is decision D-NFR-05.

### `core.card` (ENT-CARD, `CARDDATA`, 150 bytes)

`card_num CHAR(16)` PK; `card_id BIGINT IDENTITY` unique (used in URLs instead of the PAN); `acct_id CHAR(11)` with index `IX_card_acct` (replaces `CARDAIX`, no 8-per-account cap); `cvv_cd CHAR(3)` masked, never returned by an API (decision D-DATA-04 recommends dropping it); `embossed_name VARCHAR(50)`; `expiration_date CHAR(10)` with a pattern check (kept as text because card update keeps whatever day it read, DEF-CRDU-009); `active_status CHAR(1)`; `legacy_filler CHAR(59)` NULL; `row_version`. No foreign key on `acct_id`, because card update in legacy mode may move a card to any account (DEF-CRDU-001).

### `core.card_xref` (ENT-CARD-XREF, `CARDXREF`, 50 bytes)

`card_num CHAR(16)` PK; `cust_id CHAR(9)` FK to customer; `acct_id CHAR(11)` FK to account, index `IX_xref_acct (acct_id, card_num)` (replaces `CXACAIX`, no 2-per-account cap). "First card for an account" is the lowest card number, which is the order a VSAM `BLDINDEX` gives duplicates.

### `core.transaction` (ENT-TRANSACTION, `TRANSACT`, 350 bytes)

| Column | Type | Notes |
| :-- | :-- | :-- |
| `tran_pk` | `BIGINT IDENTITY` PK | Surrogate key (Q-ENT-002); the legacy ID stays the business key. |
| `tran_id` | `CHAR(16)` BIN2 | Unique among rows in the master: filtered unique index `UX_tran_master (tran_id) WHERE in_master = 1`. |
| `type_cd`, `cat_cd` | `CHAR(2)`, `CHAR(4)` BIN2 | No foreign key: transaction add does not check them (BR-TRNA-007). |
| `source` | `VARCHAR(10)` | |
| `description` | `VARCHAR(100)` | |
| `amount` | `DECIMAL(11,2)` | Bill payments are stored positive (DEF-BILL-010). |
| `merchant_id` | `CHAR(9)` | |
| `merchant_name`, `merchant_city` | `VARCHAR(50)` | |
| `merchant_zip` | `VARCHAR(10)` | |
| `card_num` | `CHAR(16)` | Masked for logins outside the core services. |
| `orig_ts`, `proc_ts` | `CHAR(26)` | Stored as each writer writes them: five layouts today (Q-BILL-006). |
| `proc_date_text` | `AS CAST(LEFT(proc_ts,10) AS CHAR(10)) PERSISTED`, indexed | The report compares this text (INV-010); replaces the `TRAN-PROC-TS` AIX. |
| `legacy_filler` | `CHAR(20)` NULL | Inherited by online writers in legacy mode (DEF-BILL-006). |
| `origin` | `VARCHAR(12)` | `LOADED`, `POSTED`, `INTEREST`, `ONLINE_ADD`, `BILL_PAY`. |
| `cycle_id` | `INT` NULL, FK `batch.cycle` | The cycle that posted the row, or the open cycle for online writes. |
| `in_master` | `BIT` | 1 while the row is in the legacy "transaction master" ([batch.md](batch.md#the-transaction-master-and-cycles)). |

Indexes: `(card_num, tran_id) WHERE in_master = 1` for statements; `(proc_date_text, card_num)` for the report.

### Other core tables

- `core.tcat_bal` (ENT-TCAT-BAL): PK `(acct_id, type_cd, cat_cd)`; `balance DECIMAL(11,2)`; `legacy_filler CHAR(22)` NULL; `row_version`.
- `core.daily_tran` (ENT-DAILY-TRAN): staging for the posting input. PK `(cycle_id, seq)`; the parsed columns plus `raw_record CHAR(350)`, because a reject carries the input as read.
- `core.daily_reject` (ENT-DAILY-REJECT): PK `(cycle_id, seq)`; `reason_cd CHAR(4)` (100 to 103); `reason_desc VARCHAR(76)`; `raw_record CHAR(350)`. View `core.v_daily_reject` masks the card number for operations users.

## Reference, identity and module tables

| Table | Key | Notes |
| :-- | :-- | :-- |
| `ref.tran_type` (ENT-TRAN-TYPE, ENT-DB2-TRAN-TYPE) | `type_cd CHAR(2)` | `description VARCHAR(50)`. One store replaces the Db2 table and the VSAM copy (INV-019). |
| `ref.tran_category` (ENT-TRAN-CATEGORY, ENT-DB2-TRAN-TYPE-CAT) | `(type_cd, cat_cd CHAR(4))` | FK to `tran_type` `ON DELETE NO ACTION` (Db2 RESTRICT, BR-TTLS-009). |
| `ref.disclosure_group` (ENT-DISC-GROUP) | `(group_id CHAR(10), type_cd, cat_cd)` | `int_rate DECIMAL(6,2)`, annual percent; the `DEFAULT` group must exist. |
| `ref.us_state`, `ref.us_state_zip_prefix`, `ref.nanp_area_code` | code | The 56 states, 240 state and ZIP-prefix pairs and 410 area codes the account update edits use; exposed read-only so Java and TypeScript validators share them. |
| `iam.app_user` (ENT-USER-SECURITY) | `user_id CHAR(8)` BIN2 | `first_name`, `last_name VARCHAR(20)`, `password_hash VARCHAR(100)`, `user_type CHAR(1)`, `ebcdic_sort_key BINARY(8)` (list order, Q-USRL-002), audit columns, `row_version`. Lockout columns exist but stay unused in legacy mode (Q-SGN-003). |
| `iam.refresh_token`, `iam.audit_event` | id | Audit rows never hold PII values, only IDs and field names. |
| `billpay.payment` | `tran_pk` | `tran_id`, `acct_id`, `paid_by CHAR(8)`, `idempotency_key UNIQUEIDENTIFIER` unique, `amount`, `created_at`. |
| `pauth.auth_summary` (ENT-AUTH-SUMMARY, IMS root `PAUTSUM0`) | `acct_id CHAR(11)` | Limits, balances, approved and declined counts and amounts as `DECIMAL(11,2)` and `SMALLINT` (the IMS domain); `row_version`. |
| `pauth.auth_detail` (ENT-AUTH-DETAIL, IMS child `PAUTDTL1`) | `auth_id BIGINT IDENTITY` | FK `acct_id` to the summary `ON DELETE CASCADE` (the purge deletes a summary with its details). `processed_at DATETIME2(3)` indexed descending replaces the 9-complement key; `auth_date_9c` and `auth_time_9c` are kept as columns, unique per account, because the fraud key derives from them (Q-AUFR-001). |
| `pauth.auth_fraud` (ENT-AUTH-FRAUD, Db2 `AUTHFRDS`) | `(card_num, auth_ts)` | The 26 Db2 columns; nullable `auth_id` without FK, so the row survives the purge. |
| `pauth.processed_message`, `pauth.outbox` | message key | Idempotency and reply outbox for the fixed mode of the authorization listener ([messaging.md](messaging.md)). |
| `batch.cycle` | `cycle_id` | `business_date DATE` unique, `interest_parm CHAR(10)`, status, timestamps. |
| `batch.report_run`, `batch.statement_run` | run ID | Parameters, requester, status, output paths. |
| `batch.BATCH_*` | Spring Batch | Job repository; Spring Batch's SQL Server schema. |
| `ops.batch_window` | `window_id` | `cycle_id`, `mode`, `opened_at`, `closed_at`. |

## How the legacy stores map

| Legacy store | Key / index | Target |
| :-- | :-- | :-- |
| `ACCTDATA` KSDS | `KEYS(11 0)` | `core.account` PK |
| `CARDDATA` KSDS + `CARDAIX` | `KEYS(16 0)`; AIX `KEYS(11 16)` non-unique | `core.card` PK + `IX_card_acct` |
| `CARDXREF` KSDS + `CXACAIX` | `KEYS(16 0)`; AIX `KEYS(11 25)` non-unique | `core.card_xref` PK + `IX_xref_acct` |
| `CUSTDATA` KSDS | `KEYS(9 0)` | `core.customer` PK |
| `TRANSACT` KSDS + timestamp AIX | `KEYS(16 0)`; AIX `KEYS(26 304)` non-unique | `core.transaction` filtered unique index + `proc_date_text` index |
| `TCATBALF` KSDS | `KEYS(17 0)` | `core.tcat_bal` composite PK |
| `TRANTYPE`, `TRANCATG` KSDS and Db2 `TRANSACTION_TYPE`, `TRANSACTION_TYPE_CATEGORY` | `KEYS(2 0)`, `KEYS(6 0)`; Db2 PK and FK | `ref.tran_type`, `ref.tran_category` |
| `DISCGRP` KSDS | `KEYS(16 0)` | `ref.disclosure_group` composite PK |
| `USRSEC` KSDS | `KEYS(8 0)` | `iam.app_user` |
| `DALYTRAN.PS` | sequential | inbound file, then `core.daily_tran` |
| `DALYREJS` GDG | sequential | `core.daily_reject` |
| `TRANSACT.BKUP`, `SYSTRAN`, `TRANSACT.COMBINED`, `TRANSACT.DALY` GDGs | relative generations | rows tagged by `cycle_id` and `in_master` |
| IMS `PAUTSUM0` / `PAUTDTL1` / `PAUTINDX` | packed account key; 9-complement date and time key | `pauth.auth_summary` / `pauth.auth_detail` / PK |
| Db2 `AUTHFRDS` | `(CARD_NUM, AUTH_TS)` | `pauth.auth_fraud` |

## Numeric and text types

| Legacy | Target | Rule |
| :-- | :-- | :-- |
| `9(n)` used as an identifier | `CHAR(n)` BIN2 with a digits check | Keeps leading zeros, exact match and byte order. |
| `S9(10)V99`, `S9(09)V99`, `S9(04)V99` zoned | `DECIMAL(12,2)`, `DECIMAL(11,2)`, `DECIMAL(6,2)` | Java `BigDecimal`, never floating point. |
| `9(03)` score, `S9(4) COMP` counts | `SMALLINT` | |
| `COMP-3` (IMS segments, export file) | `DECIMAL(p,s)` | Decoded by `platform/legacy-format`. |
| Binary fields in the export file | `INT` / `BIGINT` | |
| `X(10)` dates | `DATE` where every writer guarantees a valid date, else `CHAR(10)` | Account dates and DOB are `DATE`; card expiry stays text. |
| `X(26)` timestamps | `CHAR(26)` | Five layouts, kept as written in legacy mode. |
| `X(n)` text | `VARCHAR(n)`, trailing spaces trimmed | Exporters pad back to `n`; leading spaces are kept. |

**Overflow.** No COBOL program checks size errors, so high-order digits are lost silently (NUM-BILL-002, NUM-POST-003). `platform/legacy-format` offers `LegacyNumbers.truncate(value, intDigits)`, used only where the item's switch is `legacy`; the fixed mode raises a validation error. With `DECIMAL` columns, an overflow that reaches the database fails the statement instead of wrapping.

**Zoned signs.** The ASCII sample files keep mainframe overpunch signs (`{`, `A` to `I` positive; `}`, `J` to `R` negative). The codec decodes them on load and encodes a computed zero as `{` on export, as the GnuCOBOL build does.

## FILLER and layout quirks

- **FILLER** is not modelled, with one exception: `legacy_filler` on account, card, transaction and category balance. These are the records batch rewrites and the differential checks compare byte for byte (inherited FILLER, DEF-POST-010, DEF-BILL-006, DEF-CRDU-003). The columns are nullable and are dropped after cut-over (D-DATA-03).
- **Group ID at the ZIP position (INV-039, Q-ENT-001, DEF-ACUP-002, DEF-ACUP-020).** Every sample account holds `A000000000` at positions 103-112 (`ACCT-ADDR-ZIP` in the main layout) and spaces at 113-122 (`ACCT-GROUP-ID`). The loader loads them as they are: `addr_zip = 'A000000000'`, `group_id` blank, so interest uses `DEFAULT`, as today. Switch `DEF-ACUP-002`: legacy writes the screen's group ID into `addr_zip` and blanks `group_id` (a fixed stand-in for the bytes the program never sets); fixed writes `group_id` and keeps `addr_zip`. Data decision D-DATA-01 chooses whether migration moves the value into `group_id`; on the sample this changes no interest amount, because the two groups differ only for 07/0001 (DEF-INTC-006).
- **ZIP cut to 5 characters on save (INV-044, DEF-ACUP-003)**: switch on account update.
- **Card-to-account link held twice (INV-030)**: `card.acct_id` and `card_xref.acct_id` both stay, as the legacy programs read different copies. A `verify-data` check reports rows where they differ. Single ownership is decision D-DATA-02.
- **Several cards per account**: the schema allows it; the behaviour of each program is a switch (DEF-BILL-002, DEF-ACVW-003, DEF-INTC-007, DEF-TRPT-004).
- **Two timestamp layouts in one column (DEF-BILL-007)**: kept, so on one day online payments still sort before batch postings in the processing-date order.
- **User list order (NUM-USRL-002)**: `ebcdic_sort_key` reproduces EBCDIC order (lower case, upper case, digits); fixed mode orders by `user_id`.
- **Sample-file quirks**: cross-reference lines are 36 bytes (padded to 50); `tcatbal.txt` mixes CRLF and LF; the loader accepts both.

## Sample-data migration

The loader is a Spring Batch job, `dataLoadJob`, run by `make load` (direct launch) or the DAG `carddemo_data_load` (inside a batch window). It has one step per file, each all-or-nothing:

| Step | Source | Target | Expected rows |
| :-- | :-- | :-- | --: |
| `loadAccounts` | `app/data/ASCII/acctdata.txt` | `core.account` | 50 |
| `loadCustomers` | `custdata.txt` | `core.customer` | 50 |
| `loadCards` | `carddata.txt` | `core.card` | 50 |
| `loadCardXref` | `cardxref.txt` | `core.card_xref` | 50 |
| `loadCategoryBalances` | `tcatbal.txt` | `core.tcat_bal` | 50 |
| `loadTransactionTypes` / `loadCategories` | `trantype.txt` / `trancatg.txt` | `ref.tran_type` / `ref.tran_category` | 7 / 18 |
| `loadDisclosureGroups` | `discgrp.txt` | `ref.disclosure_group` | 51 |
| `loadLookups` | the state, ZIP-prefix and area-code lists from the account update spec | `ref.us_*`, `ref.nanp_area_code` | 56 / 240 / 410 |
| `seedUsers` | the 10 DUSRSECJ seed users; passwords from `.env` | `iam.app_user` | 10 |
| `loadPendingAuthorizations` | the module's sample unload files (PAUDBUNL layout) | `pauth.*` | 21 summaries / 202 details (one all-spaces root skipped) |
| `stageDailyFile` | `dailytran.txt` | `core.daily_tran` | 300 (in the cycle, not in `load`) |

Rules, all from the reload specs: a duplicate or out-of-order key fails the step (VAL-JACCT-001 and its siblings); referential checks (cross-reference to account and customer, category balance to account) fail the step; nothing is printed except counts and keys. The transaction table starts empty (TRANFILE's dummy row is dropped).

## Reconciliation

`make verify-data` checks, per table:

1. Row count equals the source line count.
2. The key set equals the source key set.
3. Control totals: sums of every amount column.
4. Each row, exported back to its fixed layout by the same exporter the differential tests use, equals the source line byte for byte (after line-ending normalisation and the 36-to-50 padding).
5. Cross-table checks: every card has a cross-reference row, `card.acct_id = card_xref.acct_id`, every account in `tcat_bal` exists, the `DEFAULT` disclosure group exists.

The report lists mismatching keys only; card numbers are masked to the last four digits.
