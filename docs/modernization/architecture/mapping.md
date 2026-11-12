# Mapping: every spec to its target

Each row is one spec: 46 program specs and 54 job, procedure, CSD and schedule specs. "Spec" gives the spec ID and its unit code, which prefixes its item IDs. Service, job and DAG names are defined in [api-security.md](api-security.md), [batch.md](batch.md) and [messaging.md](messaging.md).

Target kinds: **service** (endpoint), **route** (React), **job** (Spring Batch job and step), **DAG** (Airflow DAG and task), **queue** (RabbitMQ listener), **library** (shared code), **schema** (Flyway), **dropped** (with the reason).

## Programs (46)

### Online, core

| Spec | Target | Notes |
| :-- | :-- | :-- |
| COSGN00C · SGN (CC00) | service `identity-service` `POST /api/auth/sign-on`, `POST /api/auth/sign-off`, `GET /api/auth/me`; route `/sign-on` | Upper-cases ID and password before the check (BR-SGN-001, INV-007); type `A` to `/admin`, other types to `/` (BR-SGN-005). Password hash replaces plain text (DEF-SGN-001). |
| COMEN01C · MENU (CM00) | route `/` (main menu); `GET /api/features` for option 11 | 11 options in table order (BR-MENU-001); the admin-only check (BR-MENU-003) becomes a role check; INQUIRE PROGRAM (BR-MENU-004) becomes feature discovery. |
| COADM01C · ADMN (CA00) | route `/admin` | 6 options (BR-ADMN-001); route guard requires role ADMIN (DEF-ADMN-001). Options 5 and 6 appear when the reference module is installed. |
| COUSR00C · USRL (CU00) | service `identity-service` `GET /api/users?start=&startKey=&after=&before=&size=10`; route `/admin/users` | Keyset paging by `user_id` (`BIN2`); `start` is a case-insensitive prefix filter; no legacy order (D-54, Q-USRL-002); rules in [identity-service.md](identity-service.md). |
| COUSR01C · USRA (CU01) | `POST /api/users`; route `/admin/users/new` | ID stripped, checked and upper-cased `a` to `z` (D-53); type `A` or `U` (Q-USRA-001); password per D-SEC-07 in the `DEF-SGN-011` mode; a deleted ID can be added again ([identity-service.md](identity-service.md)). |
| COUSR02C · USRU (CU02) | `GET /api/users/{userId}`, `PUT /api/users/{userId}` with `If-Match`, also at `/api/users/key/{userKey}`; route `/admin/users/:userKey/edit` | Never returns the password (DEF-USRU-005); version check fixes the lost update (DEF-USRU-007); the key form carries any user ID in the URL ([identity-service.md](identity-service.md#5-user-ids-in-urls)). |
| COUSR03C · USRD (CU03) | `DELETE /api/users/{userId}` or `/api/users/key/{userKey}` with `If-Match`; route `/admin/users/:userKey/delete` | Confirmation step stays in the UI; logical delete (D-56); self-delete and the last admin refused (D-06) ([identity-service.md](identity-service.md)). |
| COBIL00C · BILL (CB00) | service `billpay-service` `GET /api/billpay/accounts/{acctId}`, `POST /api/billpay/accounts/{acctId}/payments`; route `/bill-pay` | First slice. One DB transaction (SE-BILL-001 to 005). See [overview.md](overview.md#the-bill-pay-first-slice). |
| COACTVWC · ACVW (CAVW) | service `account-service` `GET /api/accounts/{acctId}`; route `/accounts/view` | Lookup chain cross-reference, account, customer (SE-ACVW-*); multi-card policy DEF-ACVW-003. PII masked by default. |
| COACTUPC · ACUP (CAUP) | `POST /api/accounts/{acctId}/validate`, `PUT /api/accounts/{acctId}` with `If-Match`; route `/accounts/edit` | Validate then save (FLOW-ACUP-102 edit order); account and customer in one transaction (DEF-ACUP-005); group ID saved in its own field (DEF-ACUP-002 fixed-only here; the switch lives in the account load, [switches.md](switches.md)). |
| COCRDLIC · CRDL (CCLI) | `GET /api/cards?acctId=&cardNum=&after=&before=&size=7`; route `/cards` | Keyset paging, 7 rows (BR-CRDL-*); rows carry `cardId`, never the PAN in a URL. |
| COCRDSLC · CRDV (CCDL) | `POST /api/cards/lookup` (typed account and card), `GET /api/cards/{cardId}`; route `/cards/:cardId`, `/cards/lookup` | Reads by card number only (BR-CRDV-001); account check per Q-CRDV-*. |
| COCRDUPC · CRDU (CCUP) | `PUT /api/cards/{cardId}` with `If-Match`; route `/cards/:cardId/edit` | Four editable fields; the CVV is never written (DEF-CRDU-002) and the account never changes (DEF-CRDU-001), both fixed-only. |
| COTRN00C · TRNL (CT00) | service `transaction-service` `GET /api/transactions?start=&after=&before=&size=10`; route `/transactions` | "Equal or greater" start, look-ahead for next page. |
| COTRN01C · TRNV (CT01) | `GET /api/transactions/{tranId}`; route `/transactions/:tranId` | No lock (DEF-TRNV-001 is incidental). |
| COTRN02C · TRNA (CT02) | `POST /api/transactions/validate`, `POST /api/transactions`, `GET /api/transactions/latest`; route `/transactions/new` | ID from `ledger-core` (shared with Bill Pay, DEF-TRNA-003); writes only the transaction row (BR-TRNA-007) unless Q-TRNA-001 says post. |
| CORPT00C · RPTS (CR00) | `POST /api/reports/transactions` (202 with run ID), `GET /api/reports/transactions/{runId}`, `GET .../{runId}/file`; route `/reports/transactions` | Triggers DAG `carddemo_transaction_report`; replaces the 17-record JOBS stream (ENT-REPORT-JOB). |
| CSUTLDTC · DTCK | library `platform/validation` `LegacyDateCheck` (Java and TypeScript) | Returns severity and message number as CEEDAYS would (2507 to 2521); the callers treat 2513 as invalid (DEF-DTCK-004 fixed-only, D-52); `LegacyDateCheck` itself has no switch. |

### Batch, core

| Spec | Target | Notes |
| :-- | :-- | :-- |
| CBTRN02C · POST | jobs `ingestDailyFileJob` (steps `stage`, `claimOnlineAdds`) and `postDailyTransactionsJob` (steps `post`, `exportRejects`); DAG `carddemo_daily_cycle` tasks `ingest_daily_file`, `post_daily_transactions` | Single-threaded in input order (BR-POST-007); exit status `COMPLETED_WITH_REJECTS` for legacy RC 4. |
| CBACT04C · INTC | job `calculateInterestJob` step `interest`; DAG task `calculate_interest` | Item = one account with its category balances; truncation toward zero (BR-INTC-001); ID prefix from the cycle date (BR-INTC-005). |
| CBTRN03C · TRPT | job `transactionReportJob` steps `select`, `print`; DAG `carddemo_transaction_report` | 133-byte report writer with BR-TRPT-005 paging and NUM-TRPT-002 edits. |
| CBTRN01C · TVAL | dropped | No job and no output; its card and account checks are part of posting (BR-POST-*). |
| CBACT01C · ACRD | dropped | READACCT listing and three extracts with no consumer; replaced by `verify-data`. |
| CBACT02C · CDRD | dropped | Diagnostic listing that prints PAN and CVV (DEF-CDRD-001). |
| CBACT03C · XRRD | dropped | Diagnostic listing (DEF-XRRD-001). |
| CBCUS01C · CURD | dropped | Diagnostic listing that prints SSN and DOB (DEF-CURD-001). |
| CBSTM03A · STMT | job `statementJob` step `render`: legacy text and HTML renderer, fixed-mode PDF per account ([batch.md](batch.md#statements-statementjob-ruled-2026-11-11)); DAG task `generate_statements` | No 51 x 10 limit (DEF-STMT-001 is covered by DEF-STMT-005: the legacy renderer fails before output on overflow); no HTML in fixed mode (D-45). |
| CBSTM03B · STIO | absorbed into `statementJob` readers | The file-access request protocol is an implementation detail. |
| CBEXPORT · EXPT | job `exportMastersJob`; DAG `carddemo_export_masters` (manual) | Required. A snapshot export of the five masters in the 500-byte records, encrypted, with a manifest; no CVV (D-62, D-10, [batch.md](batch.md#export-and-import-ruled-2026-11-11)). Its codecs are `platform/legacy-format`'s. |
| CBIMPORT · IMPT | job `importMigrationJob`; DAG `carddemo_import_migration` (manual) | Required. Validates an export and loads it into empty master tables, all or nothing (D-62, [batch.md](batch.md#export-and-import-ruled-2026-11-11)). Not used to migrate the sample data. |
| COBSWAIT · WAIT | dropped | Fixed wait; Airflow dependencies replace it (Q-WAIT-002). |
| MVSWAIT · MWAT | dropped | STIMER wrapper for COBSWAIT. |
| COBDATFT · DTFT | dropped | Its only caller is dropped; `java.time` covers the conversion. |

### Transaction type module (Db2)

| Spec | Target | Notes |
| :-- | :-- | :-- |
| COTRTLIC · TTLS (CTLI) | service `reference-service` `GET /api/transaction-types`, `PUT /api/transaction-types/{code}`, `DELETE /api/transaction-types/{code}`; route `/admin/transaction-types` | 7-row keyset list with filters; delete refused while categories exist (BR-TTLS-009). |
| COTRTUPC · TTUP (CTTU) | `GET`, `POST`, `PUT`, `DELETE /api/transaction-types/{code}`; route `/admin/transaction-types/maintain` | Code normalisation `1` to `01` (BR-TTUP-001). |
| COBTUPDT · TTBU | job `transactionTypeMaintenanceJob`; DAG `carddemo_reference_refresh` task `maintain_transaction_types` | Built only if a producer of the 53-byte input file is named (D-BAT-07); commit scope per Q-TTBU-003. |

### VSAM-MQ module

| Spec | Target | Notes |
| :-- | :-- | :-- |
| COACCT01 · MQAC (CDRA) | queue listener in `inquiry-service` on `carddemo.inquiry.account.request` | 1000-byte reply in compatibility mode ([messaging.md](messaging.md#account-inquiry-cdra)). |
| CODATE01 · MQDT (CDRD) | queue listener in `inquiry-service` on `carddemo.inquiry.date.request` | `SYSTEM DATE : MM-DD-YYYYSYSTEM TIME : HH:MM:SS` reply. |

### Authorization module (IMS, Db2, MQ)

| Spec | Target | Notes |
| :-- | :-- | :-- |
| COPAUA0C · AUTH (CP00) | queue listener in `authorization-service` on `carddemo.pauth.request` | 18-field request, 6-field reply; decision, summary and detail in one transaction. |
| COPAUS0C · AUSM (CPVS) | `GET /api/accounts/{acctId}/authorizations/summary`, `GET /api/accounts/{acctId}/authorizations?after=&size=5`; route `/authorizations` | Main menu option 11; newest first. |
| COPAUS1C · AUDT (CPVD) | `GET /api/authorizations/{authId}`, `POST /api/authorizations/{authId}/fraud`; route `/authorizations/:authId` | Detail update and fraud row in one transaction (PINV-e-authorization-03). |
| COPAUS2C · AUFR | absorbed into `authorization-service` `FraudService` | Insert or update of `pauth.auth_fraud`; no XA needed. |
| CBPAUP0C · AUPG | job `purgePendingAuthorizationsJob`; DAG task `purge_pending_authorizations` | Expiry days, commit interval as job parameters; the decided AUPG fixes are built fixed-only (CBPAUP0C cannot run locally, so nothing compares with it). |
| DBUNLDGS · DBUL | dropped | IMS unload to GSAM; SQL Server backup replaces it. |
| PAUDBUNL · PDUL | dropped | IMS unload to flat files. Not a target input: the one-time loader step `loadPendingAuthorizations` reads the HD unload of DBPAUTP0 ([data-model.md](data-model.md#pending-authorizations-pauth), Loader source). |
| PAUDBLOD · PDLD | dropped | IMS reload; replaced by the loader step and SQL Server restore. |

## Jobs, procedures, CSDs and schedules (54)

### Batch cycle and reports

| Spec | Target | Notes |
| :-- | :-- | :-- |
| CLOSEFIL · JCLOSE | DAG `carddemo_daily_cycle` task `open_batch_window` | Batch-window row instead of `CEMT SET FIL CLO` (INV-002, Q-JCLOSE-001). |
| OPENFIL · JOPEN | DAG task `close_batch_window` | Runs only after success; on failure the window stays open as legacy (ERR-JCA7-001). |
| TRANBKP · JTBKP | DAG task `archive_master` (job `archiveMasterJob`) | The reset becomes "end the previous master window" on the rows; the backup copy is dropped because the rows stay in the table ([batch.md](batch.md#the-transaction-master-and-cycles)). |
| POSTTRAN · JPOST | DAG tasks `ingest_daily_file`, `post_daily_transactions` | Jobs `ingestDailyFileJob`, `postDailyTransactionsJob`; RC 4 maps to success with warnings. |
| INTCALC · JINTC | DAG task `calculate_interest` behind branch `interest_due` | PARM `2022071800` derived from the cycle date (Q-INTC-006); frequency per Q-JINTC-002. |
| COMBTRAN · JCOMB | dropped as a step | Posting and interest insert straight into the master tagged with the cycle; the sort is an `ORDER BY`. Removes the relative-generation hazard (INV-052, Q-JCOMB-001). |
| CREASTMT · JSTMT | DAG task `generate_statements` (job `statementJob`) | Work KSDS and re-key sort replaced by an `ORDER BY card_num, tran_id`. Runs after interest (INV-054). |
| TXT2PDF1 · JPDF | the fixed-mode PDF renderer of `statementJob` (Apache PDFBox), no flag | The external exec is not in the repository, so the PDF layout is new (D-45, Q-JPDF-001). |
| TRANIDX · JTIDX | dropped | SQL Server maintains the index on the processing date; nothing reads the AIX (DEF-JTIDX-004). |
| TRANREPT · JTRPT | DAG `carddemo_transaction_report` (job `transactionReportJob`) | Its backup step is dropped: the report reads the table (removes the BKUP hazard). |
| TRANREPT-PROC · JTRPTP | same DAG, triggered by `transaction-service` | The on-demand path of CORPT00C. |
| PRTCATBL · JPCAT | DAG `carddemo_category_balance_listing` (job `categoryBalanceListingJob`) | 40-byte listing in key order, balance edit `TTTTTTTTT.TT`; fixed mode adds a trailing minus for negative balances (DEF-JPCAT-001, D-75, [format](batch.md#prtcatbl-categorybalancelistingjob)). Its backup step becomes a snapshot row set (`batch.snapshot_tcat_bal`). |
| WAITSTEP · JWAIT | dropped | Fixed 36-second pause; DAG dependencies and the batch-window drain replace it. |
| REPROC · JRPROC | dropped | Generic copy procedure; each caller gets its own snapshot or export. |
| SCHED-CA7 · JCA7 | DAG dependencies of `carddemo_daily_cycle` | Only the ordering rules carry over; triggers loop as written (DEF-JCA7-005). Not carried over: statements in parallel with posting, and the purge before posting ([batch.md](batch.md#batch-cycle-option)). |
| SCHED-CONTROLM · JCTLM | DAG schedules: daily cycle, weekly reference refresh, month-end interest branch | Only the daily, weekly and monthly split carries over. `MAXRERUN=5` causes no automatic rerun; DAG tasks do not retry business steps either. |

### Reloads and set-up

| Spec | Target | Notes |
| :-- | :-- | :-- |
| ACCTFILE · JACCT | schema `core.account`; job `dataLoadJob` step `loadAccounts`; DAG `carddemo_data_load` | Load fails on duplicate or unsorted keys (VAL-JACCT-001). Reload only inside a batch window. |
| CARDFILE · JCARD | schema `core.card` with an index on `acct_id`; step `loadCards` | No 8-cards-per-account cap (Q-JCARD-001). CICS close and open dropped: the load runs in the `WRITES_BLOCKED` batch window, where reads keep working (D-BAT-05; deviation group `DEV-BATCH-WINDOW`, as for the other loads). |
| XREFFILE · JXREF | schema `core.card_xref` with an index on `acct_id`; step `loadCardXref` | No 2-per-account cap (Q-JXREF-001); pads 36-byte sample lines. |
| CUSTFILE · JCUST | schema `core.customer`; step `loadCustomers` | |
| TRANFILE · JTRAN | schema `core.transaction`, empty | Job dropped: no dummy seed row. |
| TRANTYPE · JTTYP | schema `ref.tran_type`; step `loadTransactionTypes` | No separate reload job: one store (Q-JTTYP-001). No migration seeds the table. |
| TRANCATG · JTCATG | schema `ref.tran_category` with foreign key; step `loadCategories` | No separate reload job: one store. No migration seeds the table. |
| DISCGRP · JDISC | schema `ref.disclosure_group`; step `loadDisclosureGroups`; job `disclosureGroupReloadJob` on DAG `carddemo_reference_refresh` | Weekly reload kept, with a check that the `DEFAULT` group exists. |
| TCATBALF · JTCAT | schema `core.tcat_bal`; step `loadCategoryBalances` for new environments only | Running balances, not reference data: no scheduled reload. |
| DUSRSECJ · JUSEC | schema `iam.app_user`; demo seed users from `.env` (`CARDDEMO_SEED_USER_PASSWORD`), seeded by `identity-service` in the `demo` profile only (D-06) | Clear-text passwords dropped. |
| ESDSRRDS · JESRR | dropped | ESDS and RRDS copies of the user file with no consumer in the repository (Q-JESRR-001). |
| DEFGDGB · JGDGB | dropped | GDG bases; replaced by cycle tags and per-run output folders, kept under the retention of their data class (D-BAT-04, D-40), not five generations. |
| DEFGDGD · JGDGD | dropped | Reference-data backup GDGs; SQL Server backups (or temporal tables if required). |
| DALYREJS · JREJS | dropped | `core.daily_reject` table with masked PAN view and a retention purge. |
| REPTFILE · JREPT | dropped | Report output kept under the retention of its data class (D-BAT-04, D-40; Q-JREPT-001). |
| DEFCUST · JDCUST | dropped | Obsolete and unreferenced. |
| CBADMCDJ · JCSDUP | dropped | Outdated CSD set-up. |
| CBEXPORT-JOB · JEXPT | DAG `carddemo_export_masters` | See CBEXPORT. |
| CBIMPORT-JOB · JIMPT | DAG `carddemo_import_migration` | See CBIMPORT. |
| READACCT · JRACCT | dropped | Diagnostic; `verify-data` replaces it. |
| READCARD · JRCARD | dropped | Diagnostic, prints PII. |
| READCUST · JRCUST | dropped | Diagnostic, prints PII. |
| READXREF · JRXREF | dropped | Diagnostic. |
| FTPJCL · JFTP | dropped | Demo FTP; an SFTP task only if a real partner appears. |
| INTRDRJ1 · JIRD1 | dropped | Demo of internal-reader chaining. |
| INTRDRJ2 · JIRD2 | dropped | Demo. |

### CSDs and module set-up

| Spec | Target | Notes |
| :-- | :-- | :-- |
| CARDDEMO-CSD · JCSD | React routes and service endpoints for the 18 transaction codes; 17 mapsets become screens; 8 files become tables | TDQ `JOBS` becomes the Airflow trigger. CDV1, libraries and CICS attributes dropped. |
| CRDDEMOD-CSD · JCSDD | routes `/admin/transaction-types*`, `reference-service` | Db2 entry and thread limit become a connection pool. The per-user Db2 sign-on (`AUTHTYPE(USERID)`) becomes the service's own SQL login and the server's ADMIN role check (D-07; FLOW-JCSDD-003 is a deviation). |
| CRDDEMOM-CSD · JCSDM | `inquiry-service` listeners | The MQ objects are not in the repository; the RabbitMQ topology is new. |
| CRDDEMO2-CSD · JCSD2 | `authorization-service` listener (CP00), routes `/authorizations*` (CPVS, CPVD) | Platform configuration only. The per-user Db2 sign-on of `CPVD` (`AUTHTYPE(USERID)`) becomes the service's own SQL login with the C1 session and role check (D-07; FLOW-JCSD2-004 is a deviation). |
| CREADB21 · JCRDB | schema migrations `ref.tran_type`, `ref.tran_category` with foreign key `NO ACTION`; rows loaded by `dataLoadJob` from `trantype.txt` and `trancatg.txt` | Grants to PUBLIC dropped (DEF-JCRDB-003); the text is the VSAM sample's, loaded once (Q-JCRDB-004, D-63). |
| MNTTRDB2 · JMNTT | DAG `carddemo_reference_refresh` task `maintain_transaction_types` | Runs only when an input file is present. |
| TRANEXTR · JTREX | dropped | One reference store removes the copy (INV-019); an export job only if a VSAM consumer must be fed during coexistence. |
| CBPAUP0J · JAUPG | DAG task `purge_pending_authorizations` | Runs after the cycle and never gates posting (Q-JCA7-003, DEF-JCA7-007); fails the DAG on error, including the legacy RC 16 (DEF-JAUPG-003). |
| DBPAUTP0 · JDBPA | dropped | IMS HD unload; SQL Server backup. Its existing output, the sample `AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat`, is the input of `loadPendingAuthorizations`. |
| UNLDGSAM · JULGS | dropped | IMS unload. |
| UNLDPADB · JULPA | dropped | IMS unload of PAUDBUNL; its files have no consumer in the target. |
| LOADPADB · JLDPA | dropped | IMS reload. No merge (DEF-JLDPA-002): `loadPendingAuthorizations` refuses tables that hold rows unless a reload is allowed (D-BAT-08, D-41), and a reload replaces the rows ([authorization-service.md](authorization-service.md), Loader). |

## Counts

Counted from the rows above.

| Target kind | Programs | Jobs |
| :-- | --: | --: |
| Service endpoint, route or queue listener | 24 | 0 |
| Spring Batch job (incl. the two optional ones) | 8 | 0 |
| Library, or absorbed into another unit | 3 | 0 |
| DAG, DAG task, schedule or job step | 0 | 16 |
| Platform configuration (the four CSDs) | 0 | 4 |
| Schema, seed or loader step | 0 | 11 |
| Dropped | 11 | 23 |
| **Total** | **46** | **54** |
