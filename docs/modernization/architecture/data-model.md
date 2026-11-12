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

**Collation.** Database default `Latin1_General_100_CI_AS_SC_UTF8`. Key and code columns (`acct_id`, `card_num`, `tran_id`, `type_cd`, `cat_cd`, `user_id`, `group_id`) use `Latin1_General_100_BIN2`, so comparison is exact and ordering is by code point, which is ISO-8859-1 (and ASCII) byte order. That equals the VSAM EBCDIC order only for keys made of digits alone, where byte order is numeric order in both code pages. No list follows EBCDIC order (D-54); a list that ever must would need a sort key built from the full code page 037 table. A free-text key such as `group_id` is `NCHAR(n)` with the same collation ([text repertoire](#text-repertoire-and-width)).

## Ownership and grants

Each service connects with its own SQL login. The grants are the enforced version of the coupling the spec describes.

| Table | Read | Insert | Update | Delete |
| :-- | :-- | :-- | :-- | :-- |
| `core.account` | batch (every column); account-service, billpay-service and authorization-service (the 14 account columns, [below](#ownership-and-grants)); inquiry-service (11 columns, below) | batch (load) | account-service (profile columns); billpay-service (`curr_bal`, `curr_cyc_credit`, `curr_cyc_debit`); batch (balances, cycle totals) | batch (reload) |
| `core.customer` | account, authorization, batch | batch (load) | account-service | batch (reload) |
| `core.card` | account, authorization (status and expiry columns, D-32), batch | batch (load) | account-service (name, status, expiry; account in legacy mode) | batch (reload) |
| `core.card_xref` | all services, batch | batch (load) | none | batch (reload) |
| `core.transaction` | transaction, billpay, batch | billpay-service, batch (online adds arrive through `core.daily_tran`, D-27) | batch (`in_master` only) | batch (retention purge) |
| `core.tcat_bal` | batch, billpay-service | batch, billpay-service | batch, billpay-service (`balance`; DEF-BILL-010 fixed, D-28) | batch (reload) |
| `core.daily_tran` | batch; transaction-service (waiting `ONLINE_ADD` rows, for legacy IDs and "copy latest"); billpay-service (waiting `ONLINE_ADD` IDs, for legacy IDs). The two services get column-scoped `SELECT` without `card_num` and `raw_record` | batch, transaction-service (`ONLINE_ADD` only, through `core.daily_tran_online_add`, D-27) | batch (the claim) | batch (retention) |
| `core.daily_reject` | batch, operations view | batch | none | batch (retention) |
| `ref.*` | all | reference-service, batch (load, maintenance job) | same | same |
| `pauth.*` | authorization-service, batch | authorization-service, batch (load) | authorization-service; batch (summary totals on purge) | batch (purge, reload); `auth_fraud` never |

Column-level `GRANT UPDATE (curr_bal, curr_cyc_credit, curr_cyc_debit)` to the Bill Pay login is what keeps Bill Pay standalone yet unable to change anything else in the account (INV-004). The cycle columns are needed because a payment is posted like any other transaction (D-28).

**Online reads of `core.account` by column.** `account_svc`, `billpay_svc` and `authorization_svc` each hold a column-level `SELECT` on exactly the 14 account columns: `acct_id`, `active_status`, `curr_bal`, `credit_limit`, `cash_credit_limit`, `open_date`, `expiration_date`, `reissue_date`, `curr_cyc_credit`, `curr_cyc_debit`, `addr_zip`, `group_id`, `legacy_filler`, `row_version`. They hold no table-level `SELECT`, because that grant would also cover any column added later. The DEF-ACUP-002 provenance columns `legacy_zip_group` and `zip_group_origin` (V418, [layout quirks](#filler-and-layout-quirks)) are batch-only, so no online login can read them. A column added to `core.account` later stays out of these logins until a grant names it. A failure test that must refuse an account read denies `SELECT` on `acct_id`, which every account read names, because a table-level `DENY` does not override a column-level `GRANT` in SQL Server. A schema test pins the column set of the three logins.

The `inquiry_svc` login (`ServiceLogin.INQUIRY`; inquiry-service, optional VSAM-MQ module) is read-only: one column-level `SELECT` on `core.account (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, group_id)` in `R__grants_inquiry.sql`, and nothing else. It gets no `addr_zip`, `legacy_filler`, other table or `UNMASK`. Its start probes are statements it must be refused: `SELECT` of `core.customer.cust_id`, of `addr_zip` and `legacy_filler` on `core.account` (and `legacy_zip_group` after `V418`), and an `UPDATE` on `core.account` ([inquiry-service.md](inquiry-service.md#7-dependencies-for-the-orchestrator-to-route), B2).

**Writers that must not set some columns (2026-11-10).** SQL Server allows a column list in `GRANT` only for `SELECT`, `UPDATE` and `REFERENCES`; `GRANT INSERT (...)` fails with error 1020. So a login that may add rows but must not choose some of their columns gets no `INSERT` on the table. It gets `EXECUTE` on a `dbo`-owned procedure in the table's schema, and ownership chaining needs no grant on the table. The procedure sets the protected columns itself and takes no parameter for them. It runs `SET NOCOUNT ON`, opens no transaction of its own and catches no error, so it joins the caller's transaction and a constraint violation reaches the caller unchanged. It returns its result as exactly one result set with one row, as `batch.report_run_trigger_failed` does. Its parameters use the column types of the [text repertoire's](#inventory) final stage (`NCHAR`/`NVARCHAR` where a column becomes Unicode), so a later width or repertoire migration of the table needs no change to the procedure; until then the values are narrowed implicitly, which is safe because the service has already checked them. A schema test reads `sys.parameters` and fails if the procedure has a parameter for a protected column. The procedures today are `batch.report_run_request` ([Report runs](#report-runs-batchreport_run)), `core.daily_tran_online_add` ([transaction-service.md](transaction-service.md#13-online-add-and-report-request-through-procedures-2026-11-10)), and `core.daily_tran_key_lookup`, which compares a retried add with the stored row, card number included, without a `SELECT` grant on it ([transaction-service.md](transaction-service.md#14-contract-v7-2026-11-10)).

Masking (D-SEC-05): the masked columns (`core.customer` SSN, government ID, DOB, EFT account; `core.card.cvv_cd`; `core.transaction.card_num`; `core.daily_tran.card_num`) carry dynamic data masking. `raw_record` in `core.daily_tran` and `core.daily_reject` holds the card number at positions 263 to 278 and has no mask. Its control is the column grant: only `batch_svc` reads it, and operations users read `core.v_daily_reject`. A role probe checks that `transaction_svc` and `billpay_svc` are refused `card_num` and `raw_record` on `core.daily_tran`. A service login gets column-level `GRANT UNMASK` only on the masked columns it must use or reveal, for example account_svc on the four customer columns and transaction_svc on `core.transaction.card_num`. batch_svc gets it on the same five columns for the encrypted masters export only ([batch.md](batch.md#export-and-import-ruled-2026-11-11)). No login gets it on `cvv_cd` (D-DATA-04). Dynamic data masking only protects ad-hoc readers; it is not the control for API responses. Each service masks PII in its responses to the last four characters by default. The value is revealed only through an explicit `ADMIN` unmask request, which returns only the fields asked for, with `Cache-Control: no-store`, and writes an `ops.audit` row (subject key and field names, never values). Each service has one login; there is no separate unmask login.

## Core tables

### `core.account` (ENT-ACCOUNT, `ACCTDATA`, 300 bytes)

| Column | Type | From | Notes |
| :-- | :-- | :-- | :-- |
| `acct_id` | `CHAR(11)` BIN2, PK, `CHECK` 11 digits | `ACCT-ID` 9(11) | Text, so `123` is not `00000000123` (VAL-BILL-003, DEF-BILL-004). |
| `active_status` | `CHAR(1)` | `ACCT-ACTIVE-STATUS` | |
| `curr_bal`, `credit_limit`, `cash_credit_limit`, `curr_cyc_credit`, `curr_cyc_debit` | `DECIMAL(12,2)` | `S9(10)V99` zoned | |
| `open_date`, `expiration_date`, `reissue_date` | `DATE` | `X(10)` `YYYY-MM-DD` | Account update writes only valid dates (VAL-ACUP-005 to 009); the loader rejects unparsable values. |
| `addr_zip` | `NVARCHAR(10)` | positions 103-112 | As read, except for a record whose layout a manifest declares as `ACUP_UPDATE` (D-11, [layout quirks](#filler-and-layout-quirks)): then `''`. The ASCII sample is declared, so the sample accounts have `''` in fixed mode and `A000000000` in legacy mode. Unicode, because a loaded value may hold any of the 256 Latin-1 characters, U+0000 included. No service writes it. |
| `group_id` | `NCHAR(10)` BIN2 | positions 113-122, or 103-112 for a declared `ACUP_UPDATE` record | Key into `ref.disclosure_group`, fallback `DEFAULT`. `A000000000` for the sample in fixed mode, blank in legacy mode. Free text in account update ([text repertoire](#text-repertoire-and-width)). |
| `legacy_zip_group` | same character type as `legacy_filler`, NULL | positions 103-122 | Migration V418 (data), with `zip_group_origin`. The 20 characters exactly as read, NULs and trailing spaces included. Batch-only grants; dropped at cut-over (D-DATA-03). Drives the export ([layout quirks](#filler-and-layout-quirks)). |
| `zip_group_origin` | `CHAR(1)` NULL, `CHECK IN ('R','M')` | the loader | `R` stored as read, `M` migrated per D-11. Batch-only grants; dropped at cut-over. |
| `legacy_filler` | `CHAR(178)` NULL | FILLER 123-300 | Kept only for byte-exact export; see below. |
| `row_version` | `ROWVERSION` | - | Optimistic check for account update (INV-042). |

### `core.customer` (ENT-CUSTOMER, `CUSTDATA`, 500 bytes)

`cust_id CHAR(9)` PK; `first_name`, `middle_name`, `last_name VARCHAR(25)`; `addr_line_1`, `addr_line_2 NVARCHAR(50)`; `addr_line_3 VARCHAR(50)` (the city; `NVARCHAR(50)` in stage 2 of [text repertoire](#text-repertoire-and-width)); `state_cd CHAR(2)`; `country_cd CHAR(3)`; `zip VARCHAR(10)`; `phone_1`, `phone_2 VARCHAR(15)`; `ssn CHAR(9)`; `govt_issued_id NVARCHAR(20)`; `dob DATE`; `eft_account_id CHAR(10)`; `pri_card_holder_ind CHAR(1)`; `fico_score SMALLINT`; `row_version`. SSN, government ID, DOB and EFT account carry SQL Server dynamic data masking; account_svc holds column-level `UNMASK` on them and masks responses itself ([grants](#ownership-and-grants)); encryption at rest is decision D-NFR-05.

### `core.card` (ENT-CARD, `CARDDATA`, 150 bytes)

`card_num CHAR(16)` PK; `card_id BIGINT IDENTITY` unique (used in URLs instead of the PAN); `acct_id CHAR(11)` with index `IX_card_acct` (replaces `CARDAIX`, no 8-per-account cap); `cvv_cd CHAR(3)` masked, in no API, DTO, log or export, and no login unmasks it; dropped at cut-over (D-DATA-04 decided: drop); `embossed_name VARCHAR(50)`; `expiration_date CHAR(10)` with a pattern check (kept as text because card update keeps whatever day it read, DEF-CRDU-009); `active_status CHAR(1)`; `legacy_filler CHAR(59)` NULL; `row_version`. No foreign key on `acct_id` yet. Card update never changes it (DEF-CRDU-001 fixed-only), so the accounts slice may add one once the loader confirms every card's account exists.

### `core.card_xref` (ENT-CARD-XREF, `CARDXREF`, 50 bytes)

`card_num CHAR(16)` PK; `cust_id CHAR(9)` FK to customer; `acct_id CHAR(11)` FK to account, index `IX_xref_acct (acct_id, card_num)` (replaces `CXACAIX`, no 2-per-account cap). "First card for an account" is the lowest card number, which is the order a VSAM `BLDINDEX` gives duplicates.

### `core.transaction` (ENT-TRANSACTION, `TRANSACT`, 350 bytes)

| Column | Type | Notes |
| :-- | :-- | :-- |
| `tran_pk` | `BIGINT IDENTITY` PK | Surrogate key (Q-ENT-002); the legacy ID stays the business key. |
| `tran_id` | `CHAR(16)` BIN2 | Unique among rows in the master: filtered unique index `UX_tran_master (tran_id) WHERE in_master = 1`. |
| `type_cd`, `cat_cd` | `CHAR(2)`, `CHAR(4)` BIN2 | No foreign key: posting does not check them (DEF-POST-009), and history rows keep the codes they were posted with. Transaction add checks them online (DEF-TRNA-002, D-52). |
| `source` | `VARCHAR(10)` | |
| `description` | `VARCHAR(100)` | |
| `amount` | `DECIMAL(11,2)` | Bill payments are stored negative (DEF-BILL-010 fixed, D-28); positive in legacy mode. |
| `merchant_id` | `CHAR(9)` | |
| `merchant_name`, `merchant_city` | `VARCHAR(50)` | |
| `merchant_zip` | `VARCHAR(10)` | |
| `card_num` | `CHAR(16)` | Masked for logins outside the core services. |
| `orig_ts`, `proc_ts` | `DATETIME2(6)` | One typed column (D-60, Q-BILL-006); the exporter writes each origin's legacy layout for the differential checks. |
| `proc_date` | `AS CAST(proc_ts AS DATE) PERSISTED`, indexed | The report selects on the date (INV-010); replaces the `TRAN-PROC-TS` AIX. |
| `legacy_filler` | `CHAR(20)` NULL | Inherited by online writers in legacy mode (DEF-BILL-006). |
| `origin` | `VARCHAR(12)` | `LOADED`, `POSTED`, `INTEREST`, `ONLINE_ADD`, `BILL_PAY`. |
| `cycle_id` | `INT` NULL, FK `batch.cycle` | The cycle that posted the row. NULL for bill payments until the batch slice settles the stamping rule. |
| `in_master` | `BIT` | 1 while the row is in the legacy "transaction master" ([batch.md](batch.md#the-transaction-master-and-cycles)). |

Indexes: `(card_num, tran_id) WHERE in_master = 1` for statements; `(proc_date, card_num)` for the report. New IDs come from the sequence `core.tran_id_seq` (16 digits, no seed row, D-59) through `ledger-core`, in the reserved range `9000000000000000` to `9999999999999999` (user approval 2026-11-10), which daily-file IDs may not use, with a claim check ([batch.md](batch.md#transaction-ids-from-the-file-and-from-the-services)); legacy mode allocates "highest + 1" under a lock.

### Other core tables

- `core.tcat_bal` (ENT-TCAT-BAL): PK `(acct_id, type_cd, cat_cd)`; `balance DECIMAL(11,2)`; `legacy_filler CHAR(22)` NULL; `row_version`.
- `core.daily_tran` (ENT-DAILY-TRAN): staging for the posting input, from the daily file and from online adds (D-27). PK `daily_pk BIGINT IDENTITY`; `cycle_id INT` NULL (NULL = an online add waiting for the next cycle) and `seq INT` NULL, unique `(cycle_id, seq)` once claimed; `origin VARCHAR(12)` (`DAILY_FILE`, `ONLINE_ADD`); `created_by` NULL (the user, for online adds); the parsed columns plus `raw_record CHAR(350)`, because a reject carries the input as read. Filtered unique index `UX_daily_waiting (tran_id) WHERE cycle_id IS NULL`. [batch.md](batch.md#online-writes-between-cycles) describes the claim.
- `core.daily_reject` (ENT-DAILY-REJECT): PK `(cycle_id, seq)`; `reason_cd CHAR(4)` (100 to 103); `reason_desc VARCHAR(76)`; `raw_record CHAR(350)`. View `core.v_daily_reject` masks the card number for operations users.

## Reference, identity and module tables

| Table | Key | Notes |
| :-- | :-- | :-- |
| `ref.tran_type` (ENT-TRAN-TYPE, ENT-DB2-TRAN-TYPE) | `type_cd CHAR(2)` | `description NVARCHAR(50)` ([inventory](#inventory); [reference-service.md](reference-service.md#6-description-column-type-question-6)). One store replaces the Db2 table and the VSAM copy (INV-019). |
| `ref.tran_category` (ENT-TRAN-CATEGORY, ENT-DB2-TRAN-TYPE-CAT) | `(type_cd, cat_cd CHAR(4))` | `description NVARCHAR(50)`. FK to `tran_type` `ON DELETE NO ACTION` (Db2 RESTRICT, BR-TTLS-009). |
| `ref.disclosure_group` (ENT-DISC-GROUP) | `(group_id CHAR(10), type_cd, cat_cd)` | `int_rate DECIMAL(6,2)`, annual percent; the `DEFAULT` group must exist. |
| `ref.us_state`, `ref.us_state_zip_prefix`, `ref.nanp_area_code` | code | The 56 states, 240 state and ZIP-prefix pairs and 410 area codes the account update edits use; exposed read-only so Java and TypeScript validators share them. |
| `iam.app_user` (ENT-USER-SECURITY) | `user_id CHAR(8)` BIN2 | `first_name`, `last_name VARCHAR(20)`, `password_hash VARCHAR(255)` (widened from 100 by `V205` for Argon2id, [identity-service.md](identity-service.md#6-password-hashing)), `user_type CHAR(1)`, `must_change_password` (forced change, D-04), `deleted_at`, `deleted_by` (logical delete, D-56, [identity-service.md](identity-service.md#versions)), audit columns, `row_version`. |
| `iam.user_signon_state` | `user_id NCHAR(8)` BIN2, FK to `iam.app_user` `ON DELETE CASCADE` | `failed_attempts`, `locked_until`, `last_sign_on_at` (lockout, D-05), moved out of `iam.app_user` by `V206` so that sign-on state never changes the user's `row_version` (only the one-time `{bcrypt}` upgrade does) ([identity-service.md](identity-service.md#7-sign-on-state)); no `row_version` of its own. |
| `iam.session`, `iam.audit_event` | id | Sessions hold the hash of the current and previous refresh token. Audit rows never hold PII values, only IDs and field names. |
| `billpay.payment` | `tran_pk` | `tran_id`, `acct_id`, `paid_by CHAR(8)`, `idempotency_key UNIQUEIDENTIFIER` unique, `amount`, `created_at`. |
| `pauth.auth_summary` (ENT-AUTH-SUMMARY, IMS root `PAUTSUM0`) | `acct_id CHAR(11)` | FK to `core.account`. Limits `DECIMAL(12,2)` and balances `DECIMAL(15,2)` (V504); approved and declined counts `INT` and amounts `DECIMAL(15,2)` ([below](#pending-authorizations-pauth)); no status fields; `row_version`. |
| `pauth.auth_detail` (ENT-AUTH-DETAIL, IMS child `PAUTDTL1`) | `auth_id BIGINT IDENTITY` | FK `acct_id` to the summary, `ON DELETE NO ACTION` since `V507` (`V500` created it with `CASCADE`, whose range locks caused purge deadlocks); every summary delete, by the purge or the reload, deletes the summary's details explicitly first ([authorization-service.md](authorization-service.md#3-purge-and-fraud-question-11-data-and-batch)). `processed_at DATETIME2(3)` indexed descending replaces the 9-complement key; `auth_date_9c` and `auth_time_9c` are kept as columns, unique per account, which reproduces the IMS duplicate-key rule; the loader carries them. IDs are never reused. |
| `pauth.auth_fraud` (ENT-AUTH-FRAUD, Db2 `AUTHFRDS`) | `auth_id` (D-36) | The 26 Db2 columns plus `reported_by` (DEF-AUDT-006 deviation); non-unique index `(card_num, auth_ts)`; no FK, so the row survives the purge. |
| `pauth.processed_message`, `pauth.outbox` | message key | Idempotency and reply outbox for the fixed mode of the authorization listener ([messaging.md](messaging.md)). |
| `batch.cycle` | `cycle_id` | `business_date DATE` unique, `attempt`, `interest_parm CHAR(10)`, status (`OPEN`, `COMPLETED`), the inbound file's name and SHA-256, timestamps. |
| `batch.report_run`, `batch.statement_run` | run ID | Parameters, requester, status; output folders are derived from the ID ([below](#report-runs-batchreport_run)). Statement runs, their captured account figures (`batch.statement_capture`) and their per-account rows (`batch.account_statement`, all V419) are in [batch.md](batch.md#statements-statementjob-ruled-2026-11-11). `previous_run_id` is set only on a `FIXED` run (`CK_statement_run_previous`); it names the latest `COMPLETED` run of an earlier cycle, and that referenced run may be of either mode ([batch.md](batch.md#which-accounts-and-transactions-fixed), 2026-11-11); V419's column comment, which says fixed runs only, is older and superseded. |
| `batch.BATCH_*` | Spring Batch | Job repository; Spring Batch's SQL Server schema. |
| `ops.batch_window` | `window_id` | `cycle_id` or `run_key` (exactly one), `mode`, `opened_at`, `closed_at`. |
| `ops.database_instance` | `id TINYINT`, `CHECK (id = 1)` | `instance_id UNIQUEIDENTIFIER NOT NULL`, set with `NEWID()` by the migration that creates the table (`V420`) and changed only by db-migrate's `--new-instance` rotation after a restore, and `created_at`. It names the database instance, so a dropped and recreated database gets a new output root ([batch-execution-contract.md](batch-execution-contract.md#410-output-root-ruled-2026-11-11) section 4.10). `SELECT` for `batch_svc` and `transaction_svc`; no login gets `INSERT`, `UPDATE` or `DELETE`. |
| `batch.execution_lease`, `batch.export_run` | job execution ID; `export_id` | Execution ownership and exports ([batch-execution-contract.md](batch-execution-contract.md)). |

### Pending authorizations (`pauth`)

Rulings on the V500 to V502 DDL review (2026-11-06):

- **Fraud key (D-36).** `pauth.auth_fraud` is keyed by `auth_id`, the detail's surrogate ID, one of the options D-36 accepted. Two authorizations can no longer share a fraud row (DEF-AUFR-001). `(card_num, auth_ts)` stays as a non-unique index in the Db2 order. There is no FK, so the fraud row outlives the detail.
- **Fraud copy collations (ruled 2026-11-11).** A fraud row copies its detail's columns exactly, so each copy has the detail column's type and collation. `V501` left three of them on the database collation (`Latin1_General_100_CI_AS_SC_UTF8`): `auth_resp_code CHAR(2)`, `auth_resp_reason` (`NCHAR(4)` since `V508`) and `match_status CHAR(1)`. In `pauth.auth_detail` they are `Latin1_General_100_BIN2`. A comparison across the two tables would then fail with error 468 or 451, and a filter on the fraud copy would ignore case and U+0000 where the detail does not. Backend aligns them in `V509__pauth_fraud_copy_collation.sql` ([Migrations](#migrations)): the same three columns become `CHAR(2)`, `NCHAR(4)` and `CHAR(1)` `COLLATE Latin1_General_100_BIN2`, still `NULL`. No index, constraint, default or column grant names them. Statistics can cover them, though, and SQL Server refuses a collation change on a column that statistics cover. `V509` therefore first drops the auto-created and user-created statistics on the three columns, and the server creates auto statistics again when a query needs them. The two `CHAR` columns change code page from UTF-8 to 1252. Their values are copies of 1252 detail values, so they convert exactly, and the migration first checks that every stored value converts back unchanged; if one does not, it fails before it changes anything. Trailing spaces do not count in that check, as in any `CHAR` comparison, so a one-character `auth_resp_code` that filled both UTF-8 bytes would read back padded with a space. That case is theoretical: a copied code is `00` or `05`. Until `V509` lands, a query that compares a fraud column with a detail column names `COLLATE Latin1_General_100_BIN2` on the fraud side. The schema test checks the collation of all three columns, and the upgrade test runs `V509` on a database that has fraud rows and compares their values before and after.
- **IDs are never reused.** Fraud rows keep `auth_id` after the detail is gone, so `pauth.auth_detail` is never truncated or reseeded. The loader deletes rows, which does not reset `IDENTITY`. `batch_svc` gets no `ALTER` on `pauth`.
- **Summary to account.** The FK `auth_summary.acct_id` → `core.account` stays. As in the other referential checks, a summary for a missing account fails `loadPendingAuthorizations`. A reload deletes `pauth` before `core.account` and loads it afterwards.
- **Unused summary fields.** `PA-AUTH-STATUS` and `PA-ACCOUNT-STATUS` get no columns. No program reads them, and online-created summaries hold leftover bytes in them (DEF-AUTH-013). The loader skips their bytes.
- **Authorization code (D-32, BR-AUTH-008).** The service sets the code to the six rightmost digits of `auth_id`, zero-padded. There is no sequence and no unique constraint. The code is unique among any 1,000,000 consecutive authorizations, so it is unique among retained details while fewer than that arrive within one purge expiry. Loaded rows keep their legacy code. Nothing looks a row up by its code.
- **Running totals.** The summary's approved and declined counts are `INT`, and its approved and declined amounts are `DECIMAL(15,2)`. The IMS domain (`S9(4) COMP`, `S9(9)V99`) is not kept for these totals. The declined totals grow with every decline. One declined request at the largest valid amount (`DECIMAL(12,2)`) already overflows `S9(9)V99`, and capping the count would decline every request of a busy account. Limits are `DECIMAL(12,2)` as in `core.account`, and `credit_balance` and `cash_balance` are `DECIMAL(15,2)` (backend `V504`, [authorization-service.md](authorization-service.md#1-listener-auth)), so a limit that account update accepts never fails the summary write. How the screen shows the counts and amounts (NUM-AUSM-001, NUM-AUSM-002) belongs to the web contract.
- **Purge (D-33, D-34).** Purging a detail subtracts it from the summary's totals: approved count and amount for an approval, plus `credit_balance` only if it is still `P` (a matched `M` approval was released already); declined count and amount for a decline (`05`). A summary is deleted only when it has no details left. Details flagged `F` are not purged, because fraud rows are kept with no expiry. `processed_message` rows and sent `outbox` rows older than the same expiry are deleted. The `DELETE` of a detail repeats `auth_fraud <> 'F'`, and the release uses the deleted row's own state (`OUTPUT deleted.match_status, deleted.approved_amt`), so a fraud mark or a match committed after the purge's read is neither lost nor released twice. The business date, the age rule, the lock order and the message-row cutoff are in [authorization-service.md](authorization-service.md#3-purge-and-fraud-question-11-data-and-batch).
- **Response code (ruled 2026-11-11).** A detail holds `00` (approved) or `05` (declined) and nothing else (`CK_auth_detail_resp`, BR-AUTH-006). A loaded detail with any other code, blank included, fails `loadPendingAuthorizations` before anything is written, so the purge never meets one ([data-load-mapping.md](data-load-mapping.md#pauthauth_detail-pautdtl1-ent-auth-detail)).
- **Matching (D-33), decided 2026-11-10.** A pending approval is also released when exactly one approval and one transaction posted by the cycle agree on card, amount, normalised merchant and date window. The rule, the columns `matched_tran_pk`, `matched_tran_id`, `matched_cycle_id` and `matched_at` (V503) and the tests are in [d33-matching-brief.md](d33-matching-brief.md).
- **Loader source.** The only sample is the EBCDIC IMS HD unload `AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat` (ENT-AUTH-HD-UNLOAD): `RECFM=VB`, 226 records, each with a 4-byte record descriptor. The first and last records (88 bytes) are utility header and trailer records and are skipped. The 22 records of 140 bytes are `PAUTSUM0` segments (an IMS prefix, then the 100-byte summary), and the 202 records of 240 bytes are `PAUTDTL1` segments (an IMS prefix, then the 200-byte detail). Offsets below count from the first byte of the record descriptor (offset 0). Each segment record has the segment data length (binary, 100 or 200) at offsets 8-9, the segment name in code page 037 at offsets 10-17, the segment data at offset 39 for that length, and one trailing `X'00'`, so the record length is 39 + length + 1. The segment data is not the last 100 or 200 bytes; that reading shifts every field by one byte. The loader decodes the data with the EBCDIC code page and packed decimal through `platform/legacy-format`. A record whose length, name and descriptor length disagree, or whose trailing byte is not `X'00'`, fails the step. A detail belongs to the summary before it in the file, because the HD unload writes each root followed by its children. The root whose key is all `X'40'` is skipped with its details, as PAUDBUNL BR-PDUL-002 does. Any other record length or segment name fails the step. The PAUDBUNL and DBUNLDGS file layouts are not loaded, because the repository contains no sample in those layouts.
- **Loader golden tests** assert fixed byte offsets in the sample, not only that the reader agrees with a test builder:
  - The first summary (record 2) has `X'00000000001C'` at offsets 39-44, which decodes to `PA-ACCT-ID` 1 (ENT-AUTH-SUMMARY bytes 1-6), followed by `PA-CUST-ID` in zoned digits. At offset 40 the same bytes are not valid packed decimal.
  - The first detail (record 3) has `X'76699C'` at offsets 39-41, `PA-AUTH-DATE-9C`. 99999 - 76699 = 23300 (`YYDDD`, 2023 day 300), which is the `PA-AUTH-ORIG-DATE` `231027` at offsets 47-52 (ENT-AUTH-DETAIL bytes 9-14).
  - The 22nd summary (record 225, just before the trailer) has an all-`X'40'` key and no details, so the load gives 21 summaries and 202 details.
  - Every one of the 224 segment records ends in `X'00'`.
  - Test names and logs carry only these offsets and synthetic field values, never a card number.
- **Loader mapping.** `processed_at` is derived from the 9-complement parts, with years `20YY`. `PA-FRAUD-RPT-DATE` is mapped to `DATE`: blank becomes NULL, and text that does not parse fails the step.

| Login | Grants on `pauth` and for authorization |
| :-- | :-- |
| `authorization_svc` | `SELECT` on `core.card_xref`. On `core.account`, column-level `SELECT` on the 14 account columns only, never the provenance columns ([grants](#ownership-and-grants)). On `core.customer`, column-level `SELECT` on the customer ID, the three name parts, the three address lines, state, ZIP and the first phone only, never `ssn`, `govt_issued_id`, `dob`, `eft_account_id` or `fico_score`. On `core.card`, column-level `SELECT` on the card number, account, active status and expiry only, never `cvv_cd` (D-DATA-04). `SELECT, INSERT, UPDATE` on `auth_summary` and `auth_fraud`. `SELECT, INSERT` on `auth_detail`, plus `UPDATE` on its fraud columns and on `auth_id_code` (set once, in the insert's transaction). `SELECT, INSERT` on `processed_message`. `SELECT, INSERT` on `outbox`, plus `UPDATE (attempts, sent_at)`. `INSERT` on `ops.audit`. `UNMASK` on `auth_detail.card_num` (the fraud copy), `processed_message.reply_body` and `outbox.body` (the resent replies). |
| `batch_svc` | `SELECT, INSERT, DELETE` on `auth_summary` and `auth_detail` (load and purge). `UPDATE` on `auth_summary (credit_balance, approved_auth_cnt, approved_auth_amt, declined_auth_cnt, declined_auth_amt)` and on `auth_detail (match_status, matched_tran_pk, matched_tran_id, matched_cycle_id, matched_at)` (D-33 matching). `SELECT, DELETE` on `processed_message` and `outbox`. Nothing on `auth_fraud`, and no `UNMASK`. |

### Report runs (`batch.report_run`)

Approved 2026-11-10. This consolidates the backend proposal v1 and the data review v1. Data owns V407. Backend adds the grants after V407 merges.

- **Table.** The DDL in section 2 of the data review stands, as backend agreed. The table is `TRANREPT` only: it has no `kind` and no `run_key`, and the request columns are `NOT NULL`. It has the FK `job_execution_id` → `BATCH_JOB_EXECUTION`, the status CHECK that ties each status to its columns, and `CK_report_run_trigger_failed`. In addition:
  - `CK_report_run_failure_code`: `failure_code IN ('TRIGGER_FAILED', 'JOB_FAILED', 'JOB_STOPPED', 'OWNER_LOST')`.
  - Index `(requested_by, requested_at DESC)`.
  - **`output_sha256 BINARY(32) NULL` (ruled 2026-11-11; data, `V424`, reserved by the orchestrator after `V423`; data confirms the number against a fresh inventory of its migrations).** The sha256 of the published `TRANREPT.txt`, the same value as the intent's sha256 in the job execution context, stored as 32 bytes so no hex case or format can differ. Two CHECKs: `CK_report_run_sha256_completed`, `output_sha256 IS NULL OR status = 'COMPLETED'`, trusted; and `CK_report_run_completed_sha256`, `status <> 'COMPLETED' OR output_sha256 IS NOT NULL`, added `WITH NOCHECK`, so the `COMPLETED` rows from before V424 keep `NULL`. Every later insert must satisfy it, and so must every later `UPDATE` that sets `status` or `output_sha256`: SQL Server checks a CHECK on an `UPDATE` only when the statement sets a column the CHECK names. That is sound because no writer changes a `COMPLETED` row (the failure listener and recovery change only `RUNNING` rows, I4 in [batch.md](batch.md#build-rulings-statement-and-transaction-report-2026-11-11)). A report run has exactly the four statuses of answer 3 below, with no `SUPERSEDED`, so the trusted CHECK names the one status that carries a hash. An `UPDATE` of a `COMPLETED` row from before V424 that sets `status` or `output_sha256` is checked, and fails the second CHECK if it leaves the row `COMPLETED` without a hash, so a producer that completes such a row again without the hash fails. An `UPDATE` that sets only other columns, such as `finished_at`, is not checked and leaves the row `COMPLETED` with `NULL`. The CHECK does not keep such a row unchanged; the rule that no writer may change a `COMPLETED` row does. Data confirms that against its code (producer, failure listener, recovery, cleanup), and that no schema check requires every constraint to be trusted; if one does, this constraint is its one named exception. No backfill, no inferred hash and no repair of the old rows: the download refuses them.
- **Answers to the proposal's questions.**
  1. The trigger failure is recorded through a procedure, not through a column `UPDATE` grant. CHECKs constrain the state a row ends in, not the change that put it there. Even with the CHECKs, a column grant on `(status, failure_code, finished_at)` allows three bad changes, each of which ends in a state the CHECKs accept:
     - a `RUNNING` or `COMPLETED` row can be set to `FAILED` with `JOB_FAILED`;
     - a `JOB_FAILED` row can be set to `COMPLETED`;
     - a `TRIGGER_FAILED` row can be set back to `REQUESTED`.
  2. `report_type` stays on the row, for display only.
  3. The four statuses stay: `REQUESTED`, `RUNNING`, `COMPLETED`, `FAILED`.
- **Objects in V407.** Data creates them. They are owned by `dbo` like the table, so ownership chaining needs no `UPDATE` grant and no grant on the job repository:
  - `batch.report_run_trigger_failed @report_run_id BIGINT` runs `SET NOCOUNT ON`, then `UPDATE ... SET status = 'FAILED', failure_code = 'TRIGGER_FAILED', finished_at = SYSUTCDATETIME() WHERE report_run_id = @id AND status = 'REQUESTED'`. It returns exactly one result set with one row and one `INT` column `changed` (1 when the row changed, else 0), and `RETURN`s the same value. Callers read `changed`, never the JDBC update count. An unknown ID or a row that is not `REQUESTED` is a no-op: `changed = 0`, and `row_version` does not change.
  - `batch.v_report_run_stalled (report_run_id, stalled)`. `stalled` (BIT) is 1 when the row is `RUNNING`, its execution is non-terminal, and its lease is recoverable by the [contract predicate](batch-execution-contract.md#41-lease), using the lease row's `recoverable_after_ms` (120 s when the execution has no lease row). That covers a crash after the task's retries are used up, when no launch remains to recover it.
- **Grants** (backend, `R__grants_transaction.sql`). Exactly these, and nothing else in `batch` (nothing on `batch.export_run`):
  - `EXECUTE` on `batch.report_run_request @report_type, @start_date DATE, @end_date DATE, @requested_by NCHAR(8)`. There is no `INSERT` on the table: a column list on `INSERT` cannot be granted ([the rule above](#ownership-and-grants)). The procedure inserts the row with `status = 'REQUESTED'` and `requested_at = SYSUTCDATETIME()`, and returns one row `(report_run_id BIGINT, requested_at)`. `status`, the timestamps, `failure_code` and `job_execution_id` have no parameter. Data creates it in a new migration, with the same `dbo` ownership as `report_run_trigger_failed`.
  - `SELECT` on `batch.report_run (report_run_id, report_type, start_date, end_date, requested_by, requested_at, status, started_at, finished_at, failure_code, row_version, output_sha256)`. `output_sha256` is added with V424. There is still no grant on the job repository or `BATCH_JOB_EXECUTION_CONTEXT`.
  - `EXECUTE` on `batch.report_run_trigger_failed`.
  - `SELECT` on `batch.v_report_run_stalled`.
  - No `INSERT`, no `UPDATE` and no `DELETE`. `job_execution_id` is not granted.

  Login probes check that each of the following is refused: any direct `INSERT`, `UPDATE` of any column (`output_sha256` included), `SELECT job_execution_id` and any `SELECT` on `BATCH_JOB_EXECUTION_CONTEXT`. A schema test checks that `report_run_request` has no parameter for `status`. They also check that `report_run_trigger_failed` on a `RUNNING`, `COMPLETED` or `FAILED` row, or on an unknown ID, returns `changed = 0` and leaves `row_version` unchanged.
- **The trigger.**
  - transaction-service inserts the row, then triggers the DAG with the Airflow `dag_run_id` `tranrept-<runId>`. Airflow's `409` for an existing run counts as success.
  - A connection error or timeout is retried, because Airflow may have created the run.
  - Only a definite failure calls `report_run_trigger_failed`. That is a `4xx` other than `409`, or retries used up.
    - `changed = 1`: the API answers `503`.
    - `changed = 0`: the job has already started, and the API answers `202 {runId}` from the row.
  - A row still `REQUESTED` after a crash between the insert and the trigger is shown as `NOT_STARTED` once it is older than a setting (default 15 minutes). The API derives this state and writes nothing.
- **batch-service writes.** These run in the job's fenced transactions and are compare-and-set:
  - **To `RUNNING`:** the first step writes `RUNNING`, `job_execution_id` and `started_at` `WHERE report_run_id = @id AND (status = 'REQUESTED' OR (status IN ('RUNNING', 'FAILED') AND job_execution_id IS NOT NULL))`. The second branch is a restart. With 0 rows the job fails with `REPORT_RUN_NOT_RUNNABLE` and writes nothing, with one exception (ruled 2026-11-11): a `COMPLETED` row whose `job_execution_id` is an earlier execution of the same job instance, whose finished publication the restart carries in its context. Then the step writes nothing, and the last step checks the published file against the committed sha256, the context's and, when set, the row's `output_sha256`, and completes without building (contract 4.8).
  - **The launch** refuses a `TRIGGER_FAILED` row up front with `400 INVALID_JOB_PARAMETERS` (`field: runId`).
  - **To `COMPLETED`:** written in the last step, with `finished_at` and `output_sha256` (the intent's sha256, which the publication verified the target against) in the same `UPDATE`, `WHERE job_execution_id = @this_execution`, in the transaction that records the publication: the publish transaction (contract 4.3) or the fenced recording transaction after a publication outside a transaction (contract 4.8). The report file is published and verified against the intent before that commit, so a `RUNNING` or `FAILED` row may already have its file; only `COMPLETED` makes it readable (contract 4.10).
  - **To `FAILED`:** a job listener writes `FAILED` with `JOB_FAILED`, or `JOB_STOPPED` for `STOPPED` and `ABANDONED`, in a fenced transaction, `WHERE job_execution_id = @this_execution AND status = 'RUNNING'`, so a `COMPLETED` row is never set back.
  - **Recovery** (contract 4.4) sets the row of the recovered execution, if it is `RUNNING`, to `FAILED` with `OWNER_LOST`. It does this in the same transaction as the revoke. The restart then sets `RUNNING` again.
- **Access in the API.** A `USER` sees only their own runs (`requested_by`), and an `ADMIN` sees all runs. Anything else gets `404`, so the API does not reveal that a run exists. The same rule covers the download.
- **Cleanup.** Because of the FK to `BATCH_JOB_EXECUTION`, any future job-repository cleanup deletes the `batch.report_run`, `batch.export_run` and `batch.statement_run` rows of an execution before the execution itself, or keeps executions that a row references. `batch.cycle.masters_replaced_by` (V423, [batch.md](batch.md#cycles-before-a-master-replacement-ruled-2026-11-11)) is different: cleanup keeps every execution that a cycle's marker references and never sets a marker back to NULL, because a NULL marker would allow the rerun that the marker refuses.
- **The listing** has no row. `categoryBalanceListingJob` stays run-keyed, and its folder is named by the Spring Batch job instance ID (contract 2.1 and 2.3 unchanged).
- **`batch.export_run`** (V407, data only): as in section 5 of the data review. `export_id` is an `IDENTITY` PK. `job_instance_id` is unique, with an FK to `BATCH_JOB_INSTANCE`, so a restart finds its row. The table has `business_date`, `RUNNING`/`COMPLETED`/`FAILED` with the same column CHECK pattern, the timestamps and `failure_code`. Recovery marks it `FAILED` with `OWNER_LOST` in the same way. transaction_svc gets no grants on it.
## How the legacy stores map

| Legacy store | Key / index | Target |
| :-- | :-- | :-- |
| `ACCTDATA` KSDS | `KEYS(11 0)` | `core.account` PK |
| `CARDDATA` KSDS + `CARDAIX` | `KEYS(16 0)`; AIX `KEYS(11 16)` non-unique | `core.card` PK + `IX_card_acct` |
| `CARDXREF` KSDS + `CXACAIX` | `KEYS(16 0)`; AIX `KEYS(11 25)` non-unique | `core.card_xref` PK + `IX_xref_acct` |
| `CUSTDATA` KSDS | `KEYS(9 0)` | `core.customer` PK |
| `TRANSACT` KSDS + timestamp AIX | `KEYS(16 0)`; AIX `KEYS(26 304)` non-unique | `core.transaction` filtered unique index + `proc_date` index |
| `TCATBALF` KSDS | `KEYS(17 0)` | `core.tcat_bal` composite PK |
| `TRANTYPE`, `TRANCATG` KSDS and Db2 `TRANSACTION_TYPE`, `TRANSACTION_TYPE_CATEGORY` | `KEYS(2 0)`, `KEYS(6 0)`; Db2 PK and FK | `ref.tran_type`, `ref.tran_category` |
| `DISCGRP` KSDS | `KEYS(16 0)` | `ref.disclosure_group` composite PK |
| `USRSEC` KSDS | `KEYS(8 0)` | `iam.app_user` |
| `DALYTRAN.PS` | sequential | inbound file, then `core.daily_tran` |
| `DALYREJS` GDG | sequential | `core.daily_reject` |
| `TRANSACT.BKUP`, `SYSTRAN`, `TRANSACT.COMBINED`, `TRANSACT.DALY` GDGs | relative generations | rows tagged by `cycle_id` and `in_master` |
| IMS `PAUTSUM0` / `PAUTDTL1` / `PAUTINDX` | packed account key; 9-complement date and time key | `pauth.auth_summary` / `pauth.auth_detail` / PK |
| Db2 `AUTHFRDS` | `(CARD_NUM, AUTH_TS)` | `pauth.auth_fraud` PK `auth_id`, index `(card_num, auth_ts)` |

## Numeric and text types

| Legacy | Target | Rule |
| :-- | :-- | :-- |
| `9(n)` used as an identifier | `CHAR(n)` BIN2 with a digits check | Keeps leading zeros, exact match and byte order. |
| `S9(10)V99`, `S9(09)V99`, `S9(04)V99` zoned | `DECIMAL(12,2)`, `DECIMAL(11,2)`, `DECIMAL(6,2)` | Java `BigDecimal`, never floating point. |
| `9(03)` score, `S9(4) COMP` counts | `SMALLINT` | Except the authorization summary's running totals ([above](#pending-authorizations-pauth)). |
| `COMP-3` (IMS segments, export file) | `DECIMAL(p,s)` | Decoded by `platform/legacy-format`. |
| Binary fields in the export file | `INT` / `BIGINT` | |
| `X(10)` dates | `DATE` where every writer guarantees a valid date, else `CHAR(10)` | Account dates and DOB are `DATE`; card expiry stays text. |
| `X(26)` timestamps | `DATETIME2(6)` | Parsed on load; legacy layouts written back only by the exporter (D-60). |
| `X(n)` text | `NVARCHAR(n)`, or `NCHAR(n)` BIN2 for keys and codes; trailing spaces trimmed | [Text repertoire and width](#text-repertoire-and-width). Exporters pad back to `n`; leading spaces are kept. |

**Overflow.** No COBOL program checks size errors, so high-order digits are lost silently (NUM-BILL-002, NUM-POST-003). `platform/legacy-format` offers `LegacyNumbers.truncate(value, intDigits)`, used only where the item's switch is `legacy`; the fixed mode raises a validation error. With `DECIMAL` columns, an overflow that reaches the database fails the statement instead of wrapping.

**Zoned signs.** The ASCII sample files keep mainframe overpunch signs (`{`, `A` to `I` positive; `}`, `J` to `R` negative). The codec decodes them on load and encodes a computed zero as `{` on export, as the GnuCOBOL build does.

## Text repertoire and width

This section (2026-11-10) replaces the earlier rule that every `X(n)` text column is `VARCHAR(n)`. Under the database's UTF-8 collation `VARCHAR(n)` holds `n` bytes, so it refused values of `n` accented characters that the forms accept. `Latin1_General_100_BIN2` on `CHAR` or `VARCHAR` uses code page 1252, which has no U+0080 to U+009F and stores them as `?`.

### Policy

1. **Repertoire.** A legacy `X(n)` field holds `n` single-byte characters. `platform/legacy-format` maps the 256 byte values one to one onto U+0000 to U+00FF (ISO-8859-1), and EBCDIC code page 037 maps the same 256 characters one to one. That range is therefore exactly what a legacy field can hold. The target keeps all of it: accented letters, U+00A0, and the control characters U+0000 to U+001F and U+007F to U+009F, NUL included. No character in the range is refused as unprintable, because the spec gives no such rule (BR-ACUP-108, VAL-TRNA-006). This holds for every input path, online and loaded, whether or not the bundled sample contains such a character.
2. **Length.** `n` characters. Each character of the range is one UTF-16 code unit and one byte of the legacy layout, so Java `String.length()`, the HTML `maxlength`, `NVARCHAR(n)` and the `n` bytes of an export all agree.
3. **Normalisation is unchanged.** This policy does not change how any unit trims input, and `FormRules` is not changed. Each unit keeps its approved behaviour (table below). Legacy-format records alone strip and pad U+0020 only, because that is the legacy pad.
4. **API check.** A free-text field, meaning one with no narrower content rule, gets a new `FormRules` rule kind, `latin1`, in the shared `rules.json`, so the page and the server agree. It runs after `required` and before `maxLength`, on the value the unit stores, after the unit's own normalisation. A character is a code point: a surrogate pair, such as an emoji, is one character above U+00FF, refused as a whole. A character above U+00FF gives `400 UNSUPPORTED_CHARACTER`, `<label> contains a character that is not supported.`, field = that field. The text never contains the character, and nothing is written. The longest text is `Government Issued Id contains a character that is not supported.` (64 characters), within 75 for the account units and 78 for TRNA. Each service that has a free-text field adds the key to its catalogue. Fields with a narrower rule (digits, letters, lists) are unchanged and refuse such characters through that rule.
5. **Storage.** A column that holds a legacy `X(n)` value is `NVARCHAR(n)`, or `NCHAR(n) COLLATE Latin1_General_100_BIN2` for a key, a code or a byte carrier. Its order is ISO-8859-1 byte order, not EBCDIC order, except for digits ([collation](#schemas)). `NVARCHAR(n)` holds exactly `n` characters of the range. Such a column is never `VARCHAR(n)` under the UTF-8 collation (a character from U+0080 up takes two bytes) or `CHAR`/`VARCHAR` with `Latin1_General_100_BIN2`. No SQL or Java code casts these columns to `CHAR` or `VARCHAR`. Strings are bound as Unicode: `sendStringParametersAsUnicode=true`, the mssql-jdbc default, is never turned off. Only these keep a non-Unicode type: values the target generates; legacy `9(n)` fields and keys with a digits `CHECK` (the reload specs' key checks fail a bad record); and `X(10)` dates and `X(26)` timestamps that are parsed into `DATE` or `DATETIME2` (an unparsable value fails the load, as the existing rules say).
6. **Loaders and exporters.** Both support every legacy-valid value, including bytes `0x80` to `0xFF`, in every `X(n)` field. A loaded value is stored unchanged except for the trailing pad; an exported field is the stored value padded with U+0020 to `n`, so load then export reproduces the file byte for byte. Exporters encode with an ISO-8859-1 (or code page 037) encoder that reports errors (`CodingErrorAction.REPORT`), never with `String.getBytes`, which replaces characters silently.
7. **Not yet complete.** Until the staged migrations below land, some loaded columns are still too narrow (rule 5). A load that meets a value such a column cannot hold fails the step with the record key and the column name, never the value, and never stores `?`. That is a safety net, not fidelity: legacy accepts the value. The fixture tests below cover these columns now and are tagged pending until their migration lands. No column is excluded from the promise.
8. **Display.** Accepted control characters, NUL included, are never a reason to refuse input. Each renderer makes them safe when it shows them: the web escapes or shows a visible placeholder, and HTML, PDF and CSV writers escape them as their format requires. The legacy-format records write them as the bytes they are.
9. **Logs.** A database error is logged with the SQL error number, the state and the correlation ID. The driver message is never logged, because the 2628 message contains the value it cut, and neither are SQL parameters or values (D-NFR-04).

### Normalisation by unit (unchanged)

| Where | What is removed | Approved by |
| :-- | :-- | :-- |
| `FormRules` checks other than `maxLength` and `alphabetic` | Java whitespace at both ends (`strip()`), before the check | platform |
| `FormRules` `maxLength` | trailing Java whitespace (`stripTrailing()`), for counting only | platform |
| `FormRules` `alphabetic` | nothing | platform |
| ACUP save and change detection, every field | Java whitespace at both ends; then `*` followed by spaces, or spaces only, is `''` | account-service contract §6, BR-ACUP-102 and BR-ACUP-105 (D-50) |
| CRDU | name: trailing Java whitespace; status, month and year: both ends | account-service contract §9 (D-51) |
| TRNA | pad fields trimmed; empty is spaces only; other text stored as typed, trailing U+0020 dropped by the column rule | transaction-service contract, TRNA rules (D-52) |
| Sign-on and password change | user ID upper-cased; password trailing spaces trimmed | [billpay-slice.md](billpay-slice.md) |
| Authorization request | none; text is cut or padded to its width | authorization-service contract §4.2 (BR-AUTH-001) |
| Legacy-format records (loaders and exporters) | trailing U+0020 only; padded back with U+0020 | `FixedRecord` |
| Reads of `CHAR` and `NCHAR` columns | the trailing pad (group ID, Bill Pay's keys) | each repository |

Java whitespace includes tab, line feed and U+001C to U+001F, but not NUL, U+0085 or U+00A0. A unit that strips therefore removes those control characters at the ends of a value, as it does today, and keeps them inside a value.

### Inventory

Every column that holds a legacy `X(n)` value or a byte carrier, with its writers. Typed columns (amounts, numbers, parsed dates and timestamps), generated values and digit-checked keys are left out under rule 5.

**Stage 1, now (online and message writers, byte carriers).**

| Column | Writer and rule | Target |
| :-- | :-- | :-- |
| `core.account.group_id` | ACUP `groupId`, free text; loader | `NCHAR(10)` BIN2, default `N''` |
| `core.account.addr_zip` | loader only (any Latin-1 value as read, U+0000 included); no service writes it | `NVARCHAR(10)`, default `N''` |
| `core.customer.addr_line_1`, `addr_line_2` | ACUP, free text; loader | `NVARCHAR(50)` |
| `core.customer.govt_issued_id` | ACUP, free text; loader | `NVARCHAR(20)`, mask `default()` kept |
| `core.daily_tran` and `core.transaction`: `source`, `description`, `merchant_name`, `merchant_city`, `merchant_zip` | TRNA, `required` and `maxLength` only; posting copies; loader | `NVARCHAR(10)`, `(100)`, `(50)`, `(50)`, `(10)` |
| `core.daily_tran.raw_record`, `core.daily_reject.raw_record` | TRNA builds it; the daily file; posting copies | `NCHAR(350)` BIN2 |
| `core.v_daily_reject.raw_record_masked` | view over the column above | cast to `NCHAR(350)` |
| `legacy_filler` on `core.account` (178), `core.card` (59), `core.transaction` (20), `core.tcat_bal` (22) | loader; written back by the exporter | `NCHAR(n)` BIN2 `NULL` |
| `pauth.auth_detail` and `pauth.auth_fraud`: `orig_date`, `orig_time`, `auth_type`, `card_expiry_date`, `message_type`, `message_source`, `processing_code`, `merchant_category_code`, `acqr_country_code`, `pos_entry_mode`, `merchant_id`, `merchant_name`, `merchant_city`, `merchant_state`, `merchant_zip`, `transaction_id` | authorization request, stored as received; IMS unload loader | same width, `CHAR` to `NCHAR` and `VARCHAR` to `NVARCHAR`, collation kept |
| `pauth.auth_detail` and `pauth.auth_fraud`: `auth_id_code`, `auth_resp_reason` | the authorization service (the code from `auth_id`, D-32; its reason codes); IMS unload loader | `NCHAR(6)` with the database collation; `NCHAR(4)` BIN2 (the fraud copy from `V509`) |
| `pauth.processed_message.reply_body` | the reply repeats the request's card value and transaction ID | `NCHAR(63)` BIN2, mask kept |
| `pauth.outbox.body` | the same reply | `NVARCHAR(1000)` BIN2, mask kept |

**Stage 2, staged (loaded columns; online writers, where there are any, accept a narrower repertoire).** Online writers keep their narrower rules; the loaders must still store any legacy value. A writer counts as ASCII-only when each of its checks is ASCII by construction (`alphabetic`, `numeric` and the digit checks) or folds with the helper and passes the case-fold vectors under Upper-casing below. Until backend's vectors pass on both implementations, the `yesNo` and list columns (`active_status`, `state_cd`, `pri_card_holder_ind`) are not called ASCII-validated: a character above U+007F that Unicode upper-casing folds into a listed letter can get through.

| Column | Writers | Target |
| :-- | :-- | :-- |
| `core.account.active_status` | ACUP `yesNo`; loader | `NCHAR(1)` |
| `core.customer.first_name`, `middle_name`, `last_name` | ACUP `alphabetic`; loader | `NVARCHAR(25)` |
| `core.customer.addr_line_3`, `zip`, `phone_1`, `phone_2` | ACUP `alphabetic`, `numeric`, `phone`; loader | `NVARCHAR(50)`, `(10)`, `(15)`, `(15)` |
| `core.customer.state_cd`, `country_cd`, `pri_card_holder_ind` | ACUP list, read-only, `yesNo`; loader | `NCHAR(2)`, `(3)`, `(1)` |
| `core.customer.eft_account_id` | ACUP `numeric`; loader | `NCHAR(10)`, mask kept |
| `core.card.embossed_name`, `active_status`, `expiration_date` | CRDU `alphabetic`, `yesNo`, built date; loader | `NVARCHAR(50)`, `NCHAR(1)`, `NCHAR(10)`; `CK_card_expiration_date` is dropped, because card expiry stays text precisely since not every writer guarantees a date |
| `core.daily_tran.orig_ts`, `proc_ts` | TRNA builds them; the daily file | `NCHAR(26)` |
| Card number family: `core.card.card_num`, `core.card_xref.card_num`, `core.transaction.card_num`, `core.daily_tran.card_num`, `pauth.auth_detail.card_num`, `pauth.auth_fraud.card_num` | `X(16)` keys; loaders | `NCHAR(16)` BIN2, masks kept |
| Transaction ID family: `core.transaction.tran_id`, `core.daily_tran.tran_id`, `billpay.payment.tran_id`, `pauth.auth_detail.matched_tran_id` | `X(16)` keys; loaders; generated online | `NCHAR(16)` BIN2 |
| Type code family: `type_cd` on `ref.tran_type`, `ref.tran_category`, `ref.disclosure_group`, `core.transaction`, `core.daily_tran`, `core.tcat_bal` | `X(02)` keys; loaders; TRNA `numeric` | `NCHAR(2)` BIN2 |
| `ref.tran_type.description`, `ref.tran_category.description` | TTUP letters, digits and spaces (VAL-TTUP-006); loader | `NVARCHAR(50)` |
| `ref.disclosure_group.group_id` | loader | `NCHAR(10)` BIN2, the same type as `core.account.group_id` |
| `iam.app_user.first_name`, `last_name` | USRA and USRU, `required` only; USRSEC loader | `NVARCHAR(20)` |
| `iam.app_user.user_type` | USRA, not validated; loader | `NCHAR(1)` BIN2 |
| User ID family: `iam.app_user.user_id`, `created_by`, `updated_by`, `deleted_by`; `iam.session.user_id`; `iam.audit_event.actor_user_id`; `ops.audit.actor_user_id`; `core.transaction.created_by`; `core.daily_tran.created_by`; `billpay.payment.paid_by`; `batch.report_run.requested_by`; `pauth.auth_detail.fraud_changed_by`; `pauth.auth_fraud.reported_by` | USRA ([D-53 rules](identity-service.md)); USRSEC loader | `NCHAR(8)` BIN2 |
| `ops.audit.subject_key`, `iam.audit_event.subject_key` | may hold a user ID | `NVARCHAR(40)` |

**Upper-casing.** COSGN00C upper-cases the typed user ID and password with `FUNCTION UPPER-CASE`, which changes only the letters `a` to `z` into `A` to `Z`, whatever the code page; `é`, `ÿ`, `µ` and `ß` stay as they are. Every target upper-casing of legacy text uses one helper in `platform/legacy-format` that does exactly that, and nothing else: the user ID at sign-on, the password in the legacy mode of DEF-SGN-011 before the hash check, and every other place a spec says a value is upper-cased. Java `toUpperCase` (`é` to `É`) and JavaScript `toUpperCase` (`ß` to `SS`, `ÿ` to U+0178) are not used for these values; the server upper-cases, and the page does not. Length and repertoire therefore never change.
**Case-insensitive checks.** D-50, D-51 and D-55 accept lower case where the legacy screens took only upper case: `y` and `n` (ACUP and CRDU `yesNo`), state codes (ACUP `inList` `usStateCodes` and `stateZip`) and the card list's `s` and `u`. The USRL `start` prefix (through the user ID canonical form) and the "not the user ID" password check of D-SEC-07 are case-insensitive too. Each of these folds with the same `a` to `z` helper, in Java and in the TypeScript mirror; `LegacyAmount`'s `CR` and `DB` match folds the same way. `toUpperCase` (with or without `Locale.ROOT`), `toLowerCase`, `equalsIgnoreCase`, `localeCompare` and case-insensitive regular expression flags are not used on legacy text. A value that neither a spec nor a decision makes case-insensitive is compared exactly. A value stored after a case-insensitive check is the helper's output, so its length never changes. The reason is that Unicode upper-casing accepts characters that are not the listed letters: U+017F `ſ` becomes `S` and U+0131 `ı` becomes `I`, so `ſc` and `ıl` pass as `SC` and `IL`, and `ß` becomes `SS`, so a one-character value turns into two. Backend adds vectors to `rule-vectors.json`, run against both implementations: `y`, `n`, `sc` and `Sc` pass; `ſc`, `ıl`, `ſ`, `ß`, `ÿ`, `µ`, `é` and U+FF39 `Ｙ` are refused with the field's own code (`YES_NO`, `NOT_IN_LIST`, `STATE_ZIP_MISMATCH`), and no value grows. A source test fails when `platform/validation` calls one of the excluded methods outside the helper.

### Migrations

Applied migrations are never edited. Data's versions `V410` (`batch.auth_match_pair`, [d33-matching-brief.md](d33-matching-brief.md)), `V412` (cycle snapshots) and `V413` (daily reject reasons) are taken and not reused. `V411` and `V415` are confirmed by data, and `V505`, `V506` and `V508` by backend. `V414`, `V509`, `V203` and `V204` are still proposed; each owner confirms its version through the orchestrator before the file is written. Identity's `V205` (password hash width) and `V206` (sign-on state table), and the authorization owner's `V507` (`FK_auth_detail_summary` without cascade), are outside the text repertoire work ([identity-service.md](identity-service.md#versions)). `outOfOrder(true)` makes a lower number safe ([billpay-slice.md](billpay-slice.md#flyway-version-ranges)).

| Version | Owner, agreed with | Columns | When |
| :-- | :-- | :-- | :-- |
| `V411__core_text_repertoire.sql` (confirmed) | data; backend for account-service and transaction-service | the `core` rows of stage 1 and `core.v_daily_reject` | now |
| `V505__pauth_text_repertoire.sql` (confirmed) | backend (authorization-service) | the `pauth` rows of stage 1, and the `pauth` members of the user ID family (`fraud_changed_by`, `reported_by`) | now |
| `V508__pauth_auth_code_text_repertoire.sql` (confirmed) | backend (authorization-service) | `auth_id_code` (`NCHAR(6)`, database collation) and `auth_resp_reason` (`NCHAR(4)`; BIN2 in `pauth.auth_detail`, the database collation in `pauth.auth_fraud` until `V509`) in both tables | now |
| `V509__pauth_fraud_copy_collation.sql` (proposed) | backend (authorization-service) | `pauth.auth_fraud`'s `auth_resp_code`, `auth_resp_reason` and `match_status` to `Latin1_General_100_BIN2`, as in `pauth.auth_detail` ([ruling](#pending-authorizations-pauth)) | after `V508`; before any query joins or unions the two tables |
| `V414__core_ref_text_repertoire.sql` (proposed) | data; backend for every service that reads the columns; the reference owner for `ref` | the `core`, `ref` and `billpay` rows of stage 2, except the user ID family | staged; required before load and export fidelity is claimed for non-ASCII input |
| `V506__pauth_key_text_repertoire.sql` (confirmed) | backend | the `pauth` members of the card number and transaction ID families (`card_num`, `matched_tran_id`) | staged, with `V414` |
| `V203__iam_user_text_repertoire.sql` (proposed) | identity | the `iam` rows of stage 2, the `iam` members of the user ID family and `iam.audit_event.subject_key` | before user add or update ships, and for USRSEC load fidelity |
| `V204__iam_user_admin.sql` (proposed) | identity | logical delete columns, `ebcdic_sort_key` dropped ([identity-service.md](identity-service.md#versions)) | with `V203` |
| `V415__user_id_family.sql` (confirmed) | data; backend for `ops.audit` and `billpay.payment` | the user ID family in `core`, `batch`, `ops` and `billpay`, and `ops.audit.subject_key` | before user add or update ships |

Each migration:

- Has a version above that of every migration that creates one of its columns or an object depending on one, so a fresh database and an upgraded one hold the same dependencies when it runs and no conditional DDL is needed. That is why a family whose members are created in several ranges is altered per range ([identity-service.md](identity-service.md#rule)); no foreign key joins family members in different ranges. A fresh migration and an upgrade from the current integrated head must give the same catalog ([pilot](identity-service.md#pilot-before-the-user-admin-code-depends-on-the-widths)).
- Drops what depends on an altered column, alters the column, and recreates the dependency with the same name and definition: default constraints (`DF_account_group_id`, `DF_account_addr_zip`), masks (drop, alter, add `MASKED WITH` the same function), primary keys, foreign keys, unique and other indexes (for example `UX_daily_idempotency`, `IX_report_run_requester`, `IX_tran_card_master`, `IX_auth_fraud_card_ts`), check constraints that name the column (for example `CK_daily_tran_idempotency`, `CK_transaction_tran_id`, `CK_app_user_upper`), and views (`CREATE OR ALTER VIEW core.v_daily_reject` with the `NCHAR(350)` cast).
- Keeps every grant. Column-level `GRANT` and `GRANT UNMASK` survive `ALTER COLUMN`; the owner also adds a comment naming the migration to its `R__grants_*` file, so the repeatable grants run again. The role probes of `make db-reset` prove the result.
- Converts existing values without loss. The bundled data is ASCII; a value already stored as `?` stays `?`, and development databases are reloaded.
- Has a schema test that checks each column's type, length, collation, nullability, default and mask.

### Fixture tests

Written now; the stage-2 parts are tagged pending until `V414`, `V506`, `V203` and `V415` land, and the tag is removed then.

- **Load and export round trip.** For every file a loader reads, a fixture whose records put, in every `X(n)` text field, a sequence that runs through all 256 byte values `0x00` to `0xFF` (wrapping at `n`, never ending in `0x20`), with valid digits in digit-checked keys and valid dates in parsed dates. The ASCII files are read as ISO-8859-1 and the EBCDIC files through code page 037. Load, then export: the output equals the fixture byte for byte, and every stored value equals the decoded field without its trailing pad.
- **Online.** The tests in [account-service.md](account-service.md#12-character-set-and-column-width-in-account-update-2026-11-10), [transaction-service.md](transaction-service.md#11-character-set-of-the-free-text-fields-2026-11-10) and [authorization-service.md](authorization-service.md#6-character-set-of-the-request-fields-2026-11-10).
- **Logs.** A forced 2628 in a test database logs the error number and the correlation ID, and no part of the value.
## FILLER and layout quirks

- **FILLER** is not modelled, with one exception: `legacy_filler` on account, card, transaction and category balance. Customer 333-500 is not kept either; it is exported as spaces (below). These are the records batch rewrites and the differential checks compare byte for byte (inherited FILLER, DEF-POST-010, DEF-BILL-006, DEF-CRDU-003). The columns are nullable and are dropped after cut-over (D-DATA-03).
- **Group ID at the ZIP position (INV-039, Q-ENT-001, DEF-ACUP-002, DEF-ACUP-020; approved 2026-11-11).** Every reader uses the `CVACT01Y` layout (ZIP at 103-112, group at 113-122). Only the COACTUPC save writes the update layout (group at 103, unset FILLER from 113). The bytes of one record cannot show which layout wrote it, so the loader never decides from content. The ruling (v4) sets these rules:
  - **Default, both modes: as read in `CVACT01Y`.** `addr_zip` = 103-112 and `group_id` = 113-122, each without trailing spaces. Every other character is data, U+0000 included, and NUL is never trimmed. A known or unknown group at 113 (`NOSUCHGRP`) is kept.
  - **D-11 move (fixed mode of switch DEF-ACUP-002) only for a declared layout.** A layout manifest (TOML: the file's SHA-256, a default layout `CVACT01Y` or `ACUP_UPDATE`, optional per-account overrides) declares it. The built-in manifest has one entry: `app/data/ASCII/acctdata.txt` by its SHA-256, all records `ACUP_UPDATE`; a test recomputes the hash. The EBCDIC copy has no entry (its account 49 holds `ZEROAPR`, outside the D-11 population). A caller may supply a manifest through `carddemo.data-load.account-layout-manifest`; its SHA-256 must equal the file's. A manifest is bound by content, never by name or directory. Legacy mode ignores every manifest.
  - **Hash of the bytes processed.** The step reads `acctdata.txt` and the manifest once, hashes exactly those bytes, and classifies and inserts from the same in-memory bytes; it never reopens either file, so a file changed while the step runs cannot be classified under another file's hash. Both hashes go into the job execution context at the first read. A restart of the same instance reads and hashes again and fails with `SOURCE_FILE_CHANGED`, before any write, when either hash differs. verify-data follows the same rule when it applies the manifest.
  - **Blank** means all spaces or all U+0000, and nothing else. A declared `ACUP_UPDATE` record gets `group_id` = 103-112 without trailing spaces (`''` when blank; a mix of spaces and NULs is data and keeps its NULs) and `addr_zip` = `''`. Its 113-122 must be blank; anything else, a space and NUL mix included, fails the step before any write with `acctdata.txt line N: declared ACUP_UPDATE but positions 113-122 are not blank`.
  - **Validation before any write.** A malformed manifest, an unknown layout, an override that is not 11 digits, is listed twice or is not in the file, a hash mismatch, or a non-blank 113 in a declared record fails the step before its first insert. The table and the provenance columns stay as they were.
  - **Diagnostics (D-NFR-04).** Counts per origin. In fixed mode, a warning with the count and the line numbers of undeclared records whose 113 is blank and 103 is not. No message names an account ID; a manifest problem names the entry's position.
  - **Export, per row and state-based.** When `legacy_zip_group` is set and the current `addr_zip` and `group_id` equal the load-time values derived from it and `zip_group_origin`, the exporter writes `legacy_zip_group` at 103-122 unchanged. Otherwise it writes `CVACT01Y`. A migrated row whose group is changed and then changed back exports its original update-layout bytes again. Edits of other columns do not affect 103-122.
  - **Online.** Account update writes `group_id` only, in every mode, and never `addr_zip` or the two provenance columns.
  - **Q-ACUP-001** is answered for the target: `CVACT01Y`. **Q-ACUP-010 part (b)**, what the target writes, is closed: account 113-122 by this rule, and account 123-300 and customer 333-500 by the next item. Part (a), what z/OS actually leaves in the unset FILLER, stays open as a mainframe fact; the target does not depend on it.
  - On the sample, the move changes no interest amount, because `A000000000` and `DEFAULT` differ only for 07/0001 (DEF-INTC-006). On the INTCALC stress data with the harness manifest, fixed INTCALC writes 128 interest records and changes accounts 4, 8, 9 and 10; legacy writes 124.
  - **NUL round trip (tests).** U+0000 survives the database unchanged in `group_id`, `addr_zip` and `legacy_zip_group`. The tests write records with NULs at 103 or 113 through the real loader, read the three columns back through JDBC, and compare code point by code point and by length, so a NUL that is dropped or turned into a space fails. If the column check, the text policy check or the driver alters U+0000, the loader is fixed; the cases are not relaxed.
  - **Harness manifests.** The verify-batch harness supplies a manifest for every account file it prepares or generates and loads in fixed mode: the INTCALC sample (`ACUP_UPDATE`), the INTCALC stress file (`ACUP_UPDATE`, with accounts 1, 2, 3, 5, 6 and 7 as `CVACT01Y`, taken from the generator's construction, never from the bytes) and the POSTTRAN stress input. The unedited sample relies on the built-in entry, and cases E4 and E17 load with no manifest on purpose. Each hash is computed after the file is written and is never pinned, and the load report's `migrated` count is asserted for every case.
- **Unset FILLER of the update layouts (Q-ACUP-010 part (b), DEF-ACUP-020; decided 2026-11-11 under the standing delegation, option O2 of the customer FILLER note v1).** A COACTUPC save rewrites the whole account and customer records from working storage whose FILLER it never sets: spaces under GnuCOBOL, undefined on z/OS (often X'00'). DEF-ACUP-020 is fixed-only, with no switch, so this rule holds in every mode and does not depend on the DEF-ACUP-002 mode. No new switch is added.
  - **Account 123-300: kept as stored.** It is the named FILLER, held in `legacy_filler` ([core tables](#core-tables)). The loader stores it as read, U+0000 included, with no constant check. Account update never writes it, and the exporter writes it back.
  - **Customer 333-500: spaces.** `core.customer` has no FILLER column, and none is added; z/OS residue is never reproduced. The loader accepts any content at 333-500 (all X'00', other residue or text) and stores nothing for it. Positions 1-332 are the named fields and follow the text policy: a U+0000 there is data and is kept. Every customer export writes 168 spaces there. This replaces, for this FILLER only, the loader's rule that an unmodelled FILLER must hold its constant; every other unmodelled FILLER keeps that check.
  - **Diagnostics (D-NFR-04).** The load report counts the customer records whose 333-500 was not all spaces, and a warning names their file lines (`custdata.txt line N`), in every mode. No message, log or report names a customer ID or shows the FILLER bytes or their text.
  - **Downstream.** Account update writes only named customer columns, so a save leaves nothing at 333-500 to write. An export followed by an import round trips spaces (BR-EXPT-009, BR-IMPT-004). verify-data's re-export check (check 4) compares positions 1-332 of each customer with its source line exactly and expects 168 spaces at 333-500. verify-data counts the source lines whose 333-500 is not all spaces on its own, and that count `K` and those line numbers must equal the load report's. A differential case that feeds such a file to a program printing the raw customer record (CBCUS01C) shows spaces in the target; that difference belongs to DEF-ACUP-020.
  - **Sample unchanged.** `app/data/ASCII/custdata.txt` and the EBCDIC copy hold spaces at 333-500 in all 50 records, so the count is 0 and the re-export stays byte-identical.
  - **Tests.** The note's C1 to C3 and C5. C4 now passes the load: one record with all X'00' and one with text at 333-500 load, the report counts 2 with their line numbers, each exports 168 spaces, verify-data passes, and a scan of the logs and the report finds neither customer ID nor the FILLER content. The cases run with DEF-ACUP-002 in both modes. An account with U+0000 in 123-300 loads, survives an account update save, and re-exports unchanged.
- **ZIP cut to 5 characters on save (INV-044, DEF-ACUP-003)**: fixed-only, ZIP+4 kept (D-14).
- **Card-to-account link held twice (INV-030)**: `card.acct_id` and `card_xref.acct_id` both stay, as the legacy programs read different copies. A `verify-data` check reports rows where they differ. Single ownership is decision D-DATA-02.
- **Several cards per account**: the schema allows it; each program's behaviour is in [switches.md](switches.md): DEF-TRPT-004 is a switch, DEF-ACVW-003 is fixed-only (D-61), and DEF-BILL-002 and DEF-INTC-007 keep the COBOL behaviour.
- **Two timestamp layouts in one column (DEF-BILL-007)**: gone with the typed column (D-60); processing-date order is now time order.
- **User list order (NUM-USRL-002)**: the list orders by `user_id` (D-54, no legacy mode), not in EBCDIC order (lower case, upper case, digits). `ebcdic_sort_key` is dropped by `V204`.
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
| `loadPendingAuthorizations` | `app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat`, the module's only sample data: an EBCDIC IMS HD unload (ENT-AUTH-HD-UNLOAD), not the PAUDBUNL files | `pauth.auth_summary`, `pauth.auth_detail` | 21 summaries / 202 details (one all-spaces root skipped) |
| `stageDailyFile` | `dailytran.txt` | `core.daily_tran` | 300 (in the cycle, not in `load`) |

Rules, all from the reload specs: a duplicate or out-of-order key fails the step (VAL-JACCT-001 and its siblings); referential checks (cross-reference to account and customer, category balance to account) fail the step; nothing is printed except counts and keys. The transaction table starts empty (TRANFILE's dummy row is dropped).

The field-by-field mapping of every loaded file (columns, types, transforms and defaults), the steps' counters and context keys, and the built-in account layout manifest are in the appendix [data-load-mapping.md](data-load-mapping.md).

## Reconciliation

`make verify-data` checks, per table:

1. Row count equals the source line count.
2. The key set equals the source key set.
3. Control totals: sums of every amount column.
4. Each row, exported back to its fixed layout by the same exporter the differential tests use, equals the source line byte for byte (after line-ending normalisation and the 36-to-50 padding). For customers, positions 333-500 of the source line are compared as spaces ([layout quirks](#filler-and-layout-quirks)).
5. Cross-table checks: every card has a cross-reference row, `card.acct_id = card_xref.acct_id`, every account in `tcat_bal` exists, the `DEFAULT` disclosure group exists.
6. Account ZIP and group (DEF-ACUP-002): an independent implementation applies the same layout manifest to the source bytes it hashed, and compares the expected `addr_zip`, `group_id` and `zip_group_origin` of every account with the table, with counts per origin. A wrongly migrated row shows here even when its re-export is byte-identical. Mismatches are reported by file line number, not by account ID.

The report lists mismatching keys only; card numbers are masked to the last four digits.

`verify-data` reads the demo database and changes nothing. Check 4 uses the same codec as the loader, so an independent oracle repeats these checks from the source files and raw SQL, with its own encoder. It runs in `make verify-batch-spec` on that target's private database, which it resets per case, never on the demo database ([batch.md](batch.md#data-load-dataloadjob)).
