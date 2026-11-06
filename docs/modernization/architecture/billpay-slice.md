# Bill Pay slice: build contract

This is the build contract for step 3a ([overview.md](overview.md#phasing)): sign-on, the main menu, Bill Pay and the shared pieces they need. It applies the user's answers to the decision catalog (`docs/modernization/decisions/README.md`) and fixes every name the build agents share: modules, Flyway versions, tables, endpoints, token format, fidelity switches, messages and tests. Where this document and an older architecture page differ, this one wins until that page is updated.

Units in scope: **BILL** (COBIL00C), **SGN** (COSGN00C), **MENU** (COMEN01C). The admin menu (COADM01C) is a stub so that an `ADMIN` user lands somewhere; it is not in the trace scope.

## 1. Decisions applied

| Topic | Decision (catalog / architecture ID) | Effect in this slice |
| :-- | :-- | :-- |
| Access | C1 (D-01, D-02, D-03, D-SEC-01) | Every route and API needs a session; roles `USER` and `ADMIN`; any `USER` may pay any account; the paying user is recorded. |
| Passwords | D-04, D-SEC-03, D-SEC-07 | bcrypt hashes; fixed mode: case-sensitive passwords, case-insensitive user IDs, forced change for migrated users. |
| Lockout and session | D-05, D-SEC-04 | Fixed mode: 5 failures lock the user for 15 minutes; one neutral failure message; 15-minute idle timeout; explicit sign-off; sign-on audit. |
| Seed and bootstrap users | D-06 | Demo seed users only in the `demo` profile; elsewhere one bootstrap admin with a forced change. |
| Unknown user types | D-02 (DEF-SGN-005) | Fixed mode refuses them. |
| Masking | D-09, D-SEC-05 | Bill Pay shows no PII; responses never carry a card number. |
| Bill Pay posting | D-28 (DEF-BILL-010, Q-BILL-002) | Fixed mode: payment stored as a negative amount and applied by the same posting primitive as POSTTRAN (balance, cycle total, category balance). |
| All or nothing | D-29 (DEF-BILL-001) | One database transaction; architecture-dependent, no legacy mode. |
| Amount and confirmation | D-30 (DEF-BILL-014, Q-BILL-001) | Full-balance payment and one-step payment stay; fixed mode locks the account ID once the balance is shown. |
| Transaction IDs | D-59 | Fixed mode: SQL Server sequence `core.tran_id_seq`, 16 digits, no seed row; `tran_pk` surrogate kept (D-DATA-07). |
| Timestamps | D-60 | `orig_ts` and `proc_ts` are `DATETIME2(6)`; legacy text layouts are produced only by the exporter. |
| Audit | D-56, D-NFR-10 | `iam.audit_event` for sign-on events; `billpay.payment` for payments. |
| Batch window | D-BAT-05 = `WRITES_BLOCKED` | Bill Pay previews work during a window; payments return 503. Sign-on and refresh stay available. |
| Options of modules not installed | D-70, D-WEB-04 | Admin options 5 and 6 are hidden. Main menu option 11 keeps its must-preserve "not installed" message (BR-MENU-004), because no catalog item makes it defect-decide. |
| Ledger default | D-ARCH-13 | Code defaults to `legacy`; `ledger/fidelity.toml` sets each decided switch to `fixed`. Both modes are built and tested. |

## 2. Modules and work packages

Maven `groupId` `com.carddemo.modern`, Java base package `com.carddemo`. Every module lives where [repo-build-ci.md](repo-build-ci.md#layout) puts it.

| Package | Modules and files | Provides to the others |
| :-- | :-- | :-- |
| P1 platform and database | `pom.xml` (parent, Spring Boot 4.1.x BOM, Java 21), `mvnw`; `platform/fidelity`, `platform/legacy-format`, `platform/web-support`, `platform/test-support`; `db/migrations` ranges 0001-0099 and 0700-0799 plus the repeatable grants; `db/db-migrate` | Switches, number edits, security and error model, Testcontainers fixtures, the empty schemas and logins |
| P2 identity | `services/identity-service`; migrations 0300-0399 | Sign-on, sessions, tokens, JWKS, features, user seeding |
| P3 Bill Pay and ledger | `platform/ledger-core`; `services/billpay-service`; migrations 0100-0199 and 0400-0499 | Core tables, ID allocation, posting primitive, window guard, the Bill Pay API |
| P4 web | `web/` (shell, sign-on, password change, main menu, admin stub, Bill Pay page, Vitest, Playwright) | The screens |
| P5 load, make and CI | `batch/batch-service` (only `dataLoadJob` for the core tables), the Makefile targets of section 11, `ledger/*.toml`, `verify/trace`, `.github/workflows/modernization.yml`, `.env.example` additions | `make` targets, ledger, trace gate, CI |

Dependencies: P2, P3 and P5 need P1's parent POM and `platform/*` interfaces first; P1 publishes the interfaces of section 5 as stubs on day one so the others compile. P4 works from the OpenAPI snapshots of section 8 and needs no running backend for Vitest.

Rules: a service depends only on `platform/*`; only `ledger-core` writes `core.transaction` or changes balances; SQL through `JdbcClient` with explicit SQL; every service injects a `java.time.Clock` bean (tests fix it) and uses `carddemo.zone` (default the system zone).

## 3. Configuration, ports and secrets

| Process | Port (127.0.0.1) | Spring profile in `make up` and CI |
| :-- | --: | :-- |
| `identity-service` | 28081 | `demo` |
| `billpay-service` | 28082 | `demo` |
| Vite dev server | 25173 | proxies `/api/auth` and `/api/features` to 28081, `/api/billpay` to 28082 |
| `batch-service` | not started by `make up` in this slice | `make load` runs it as a one-shot job |

`.env.example` gains these keys, empty, so `make setup` fills them (P5):

| Key | Value |
| :-- | :-- |
| `CARDDEMO_DB_IDENTITY_PASSWORD`, `CARDDEMO_DB_BILLPAY_PASSWORD`, `CARDDEMO_DB_BATCH_PASSWORD` | SQL login passwords, random |
| `CARDDEMO_SEED_USER_PASSWORD` | password of the demo seed users. `make setup` generates **8 upper-case letters and digits**, because the value must fit the 8-character legacy field and sign on in both modes. |

`make setup` also writes the RSA signing key `modernization/var/secrets/identity-jwt.pem` (PKCS#8, RSA 2048, mode 600) when it is missing. `identity-service` refuses to start without it (`carddemo.identity.jwt.private-key-path`).

Common properties (in `web-support` defaults): `carddemo.security.jwks-uri=http://127.0.0.1:28081/api/auth/.well-known/jwks.json`, `carddemo.security.issuer=carddemo-identity`, `carddemo.security.audience=carddemo-api`, `carddemo.security.cookie-secure` (default `true`; `false` in the `demo` profile, which serves plain http on 127.0.0.1).

Fidelity values: `platform/fidelity` loads `ledger/fidelity.toml` (path `CARDDEMO_LEDGER_DIR`, default `modernization/ledger`) into `carddemo.fidelity.<ID>` properties at startup; an explicit property wins, which is how tests pick a mode. A missing entry means `legacy`.

## 4. Database

### Creation and logins

`db-migrate` (P1) connects as `sa`, creates the database `carddemo` with collation `Latin1_General_100_CI_AS_SC_UTF8` if missing, sets `READ_COMMITTED_SNAPSHOT ON` and `ALLOW_SNAPSHOT_ISOLATION ON`, creates or re-passwords the logins `carddemo_identity`, `carddemo_billpay` and `carddemo_batch` from `.env`, then runs Flyway as `sa`. No service runs Flyway.

### Flyway version ranges

Files in `modernization/db/migrations/`, named `V<4 digits>__<schema>_<change>.sql`. Each package writes only in its range, so parallel work cannot collide. Gaps are allowed.

| Range | Schema | Owner | Slice files |
| :-- | :-- | :-- | :-- |
| 0001-0099 | database-wide | P1 | `V0001__schemas.sql` (`core`, `ref`, `iam`, `billpay`, `pauth`, `batch`, `ops`), `V0002__database_users.sql` (users for the three logins, roles `app_identity`, `app_billpay`, `app_batch`) |
| 0100-0199 | `core` | P3 | `V0100__core_account.sql`, `V0101__core_customer.sql`, `V0102__core_card.sql`, `V0103__core_card_xref.sql`, `V0104__core_transaction.sql`, `V0105__core_tran_id_seq.sql`, `V0106__core_tcat_bal.sql` |
| 0200-0299 | `ref` | later (reference data) | none |
| 0300-0399 | `iam` | P2 | `V0300__iam_app_user.sql`, `V0301__iam_session.sql`, `V0302__iam_audit_event.sql` |
| 0400-0499 | `billpay` | P3 | `V0400__billpay_payment.sql` |
| 0500-0599 | `pauth` | later | none |
| 0600-0699 | `batch` | later (batch) | none |
| 0700-0799 | `ops` | P1 | `V0700__ops_batch_window.sql` |
| repeatable | grants | each owner | `R__grants_identity.sql` (P2), `R__grants_billpay.sql` (P3), `R__grants_batch.sql` (P5) |

### Tables of the slice

Types follow [data-model.md](data-model.md); changes from the decisions are marked. Key and code columns use `Latin1_General_100_BIN2`.

- `core.account`: as in data-model.md (`acct_id CHAR(11)` PK with a digits check, the five `DECIMAL(12,2)` amounts, dates, `addr_zip`, `group_id`, `legacy_filler CHAR(178) NULL`, `row_version ROWVERSION`).
- `core.customer`, `core.card`, `core.card_xref`: as in data-model.md, including `IX_xref_acct (acct_id, card_num)`.
- `core.tcat_bal`: PK `(acct_id, type_cd, cat_cd)`, `balance DECIMAL(11,2)`, `legacy_filler CHAR(22) NULL`, `row_version`.
- `core.transaction` (**changed by D-60**): `tran_pk BIGINT IDENTITY` PK; `tran_id CHAR(16)` BIN2 with filtered unique index `UX_tran_master (tran_id) WHERE in_master = 1`; `type_cd CHAR(2)`, `cat_cd CHAR(4)`; `source VARCHAR(10)`; `description VARCHAR(100)`; `amount DECIMAL(11,2)`; `merchant_id CHAR(9)`; `merchant_name`, `merchant_city VARCHAR(50)`; `merchant_zip VARCHAR(10)`; `card_num CHAR(16)`; `orig_ts`, `proc_ts DATETIME2(6)`; `proc_date AS CAST(proc_ts AS DATE) PERSISTED` with index `(proc_date, card_num)`; `legacy_filler CHAR(20) NULL`; `origin VARCHAR(12)` (`LOADED`, `POSTED`, `INTEREST`, `ONLINE_ADD`, `BILL_PAY`); `cycle_id INT NULL` (the slice writes `NULL`; the batch range adds the foreign key and the stamping rule); `in_master BIT NOT NULL DEFAULT 1`; `created_by CHAR(8) NULL`.
- `core.tran_id_seq` (**D-59**): `CREATE SEQUENCE core.tran_id_seq AS BIGINT START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 9999999999999999 NO CYCLE CACHE 50`. `make load` restarts it above the highest loaded `tran_id`.
- `iam.app_user`: `user_id CHAR(8)` BIN2 PK (stored upper case); `first_name`, `last_name VARCHAR(20)`; `password_hash VARCHAR(100)` (Spring `DelegatingPasswordEncoder` format, `{bcrypt}` with strength 12); `user_type CHAR(1)` (as loaded, no check constraint, because legacy mode accepts unknown types); `must_change_password BIT`; `failed_attempts SMALLINT`; `locked_until DATETIME2(3) NULL`; `last_sign_on_at DATETIME2(3) NULL`; `ebcdic_sort_key BINARY(8)`; `created_at`, `created_by`, `updated_at`, `updated_by`; `row_version`.
- `iam.session`: `session_id UNIQUEIDENTIFIER` PK; `user_id`; `created_at`, `last_activity_at`, `absolute_expiry_at`, `ended_at NULL`, `end_reason VARCHAR(20) NULL` (`SIGN_OFF`, `IDLE`, `EXPIRED`, `REUSE_DETECTED`); `refresh_hash BINARY(32)` (SHA-256 of the current refresh token); `previous_refresh_hash BINARY(32) NULL`.
- `iam.audit_event`: `event_id BIGINT IDENTITY` PK; `occurred_at DATETIME2(3)`; `actor_user_id CHAR(8) NULL`; `event_type VARCHAR(40)` (`SIGN_ON_OK`, `SIGN_ON_FAILED`, `USER_LOCKED`, `SIGN_OFF`, `SESSION_EXPIRED`, `PASSWORD_CHANGED`, `REFRESH_REUSE`); `subject_key VARCHAR(40) NULL`; `detail VARCHAR(200) NULL` (reason codes only, never a password or PII value); `client_ip VARCHAR(45) NULL`.
- `billpay.payment`: `payment_id BIGINT IDENTITY` PK; `tran_pk BIGINT` unique (FK `core.transaction`); `tran_id CHAR(16)`; `acct_id CHAR(11)`; `paid_by CHAR(8)`; `idempotency_key UNIQUEIDENTIFIER` unique; `amount DECIMAL(11,2)` (as stored on the transaction); `balance_before DECIMAL(12,2)`; `created_at DATETIME2(6)`.
- `ops.batch_window`: `window_id INT IDENTITY` PK; `cycle_id INT NULL`; `mode VARCHAR(16)` (`WRITES_BLOCKED`); `opened_at DATETIME2(3)`; `closed_at DATETIME2(3) NULL`. At most one open row (filtered unique index on a constant `WHERE closed_at IS NULL`).

### Grants

| Login | Grants |
| :-- | :-- |
| `carddemo_identity` | `SELECT, INSERT, UPDATE` on `iam.app_user`, `iam.session`; `INSERT` on `iam.audit_event`; nothing else. |
| `carddemo_billpay` | `SELECT` on `core.account`, `core.card_xref`, `core.transaction`, `core.tcat_bal`, `ops.batch_window`; `UPDATE (curr_bal, curr_cyc_credit, curr_cyc_debit)` on `core.account`; `INSERT` on `core.transaction`; `INSERT, UPDATE (balance)` on `core.tcat_bal`; `UPDATE` on `core.tran_id_seq`; `SELECT, INSERT` on `billpay.payment`; `EXECUTE` on `sp_getapplock` (implicit). |
| `carddemo_batch` | `SELECT, INSERT, DELETE` on the core tables; `ALTER` on `core.tran_id_seq` (restart after load). |

`make db-reset` passes only when each login is refused one table outside its grants (for example `carddemo_billpay` reading `core.customer`).

## 5. Shared library contracts

Signatures are the contract; packages may add helpers.

**platform/fidelity**

```java
public enum Mode { LEGACY, FIXED }
public interface FidelitySwitches { Mode mode(String itemId); boolean fixed(String itemId); }
@Retention(RUNTIME) @Target({TYPE, METHOD}) public @interface SpecItem { String[] value(); }
```

**platform/legacy-format**

```java
LegacyNumbers.editSigned(BigDecimal v, int intDigits, int scale)   // "+0000000194.00" for (194.00, 10, 2)
LegacyNumbers.truncate(BigDecimal v, int intDigits)                // drops high-order digits, keeps sign
LegacyTimestamps.online(LocalDateTime t)                            // "YYYY-MM-DD HH:MM:SS.000000"
LegacyTimestamps.posting(LocalDateTime t)                           // "YYYY-MM-DD-HH.MM.SS.hh0000"
```

**platform/ledger-core** (every method requires an active transaction: `@Transactional(propagation = MANDATORY)`)

```java
public interface TransactionIds { String next(); }                 // 16 digits, leading zeros
public interface TransactionWriter { long insert(NewTransaction t); } // returns tran_pk; DuplicateTranIdException
public record NewTransaction(String tranId, String typeCd, String catCd, String source, String description,
    BigDecimal amount, String cardNum, String merchantId, String merchantName, String merchantCity,
    String merchantZip, LocalDateTime origTs, LocalDateTime procTs, Origin origin, String createdBy) {}
public interface BalancePosting {
  void balanceOnly(String acctId, BigDecimal delta);                 // curr_bal += delta
  void post(String acctId, String typeCd, String catCd, BigDecimal amount); // BR-POST-004 and BR-POST-005
}
public interface BatchWindowGuard { void requireWritesAllowed(); }   // BatchWindowException
```

- `TransactionIds.next()`, switch `BR-TRNA-003` (D-59). Fixed: `NEXT VALUE FOR core.tran_id_seq`, formatted `%016d`. Legacy: `sp_getapplock 'carddemo.tran_id', Exclusive, Transaction`, then `MAX(tran_id) WHERE in_master = 1` plus 1 (0 when empty), so the result equals the reference "highest + 1" (BR-BILL-004) and two writers cannot get the same ID.
- `BalancePosting.post` applies the rule POSTTRAN applies: `tcat_bal(acct, type, cat).balance += amount`, inserting the row when missing; `curr_bal += amount`; `curr_cyc_credit += amount` when `amount >= 0`, else `curr_cyc_debit += amount`. Sums go through `LegacyNumbers.truncate` to the column's digits, as POSTTRAN has no size check (NUM-POST-003). `batch-service` posting must call the same method later; it is the "same posting service" of D-27 and D-28. It does **not** run posting's validations (over-limit, expiry); callers do their own.
- `BatchWindowGuard.requireWritesAllowed()` takes `sp_getapplock 'carddemo.batch_window', Shared, Transaction`, then fails when `ops.batch_window` has an open row. Opening a window (batch, later) takes the same lock exclusively, so no payment is half-way when the window opens.

**platform/web-support**

- Resource-server security: JWT validation against the JWKS URI with issuer and audience checks; `role` claim to `ROLE_USER` or `ROLE_ADMIN`. A cookie-aware `BearerTokenResolver` reads cookie `cd_at` first, then `Authorization: Bearer` (scripts and tests).
- CSRF for the SPA: `CookieCsrfTokenRepository.withHttpOnlyFalse()` (cookie `XSRF-TOKEN`, `Path=/`), header `X-XSRF-TOKEN`, the plain `CsrfTokenRequestAttributeHandler`. Required on every unsafe method except `POST /api/auth/sign-on` and `POST /api/auth/password` (no session yet; `SameSite=Strict` covers them).
- Error model: RFC 9457 problem details with `code`, `message` (exact catalogue text), `field` (focus target or `null`) and `severity` (`error` = red, `success` = green, `info` = neutral). 401 without a valid token: `code: UNAUTHENTICATED`; 403 for a wrong role: `code: ACCESS_DENIED`, message `You are not authorized to use this function ...`.
- `MessageCatalog`: every text of section 9, keyed by code; the TypeScript copy in `web/src/messages.ts` is generated from it at build time (`web-support` writes `target/messages.json`) and committed, with a contract test that they agree.

**platform/test-support**: a SQL Server Testcontainer using the compose image (`mcr.microsoft.com/mssql/server:2025-CU10-ubuntu-24.04` by digest, `MSSQL_MEMORY_LIMIT_MB=2048`), started once per JVM and migrated with the real migrations; fixture builders for accounts, cards, cross-references, category balances and users (seeded with the public demo password `PASSWORD`); a test RSA key and `TestTokens.user("USER0001")` / `.admin(...)` so Bill Pay tests need no identity-service; a fixed `Clock`; a JUnit extension that runs a test class in both modes (`@BothModes("DEF-BILL-010")`).

## 6. Fidelity switches touched

| Switch (item) | Decision | Legacy | Fixed | Ledger value |
| :-- | :-- | :-- | :-- | :-- |
| `DEF-BILL-010` | D-28 | Amount stored positive; `balanceOnly(acct, -amount)`; cycle totals and category balance untouched | Amount stored negative; `post(acct, "02", "0002", -amount)`: balance to zero, `curr_cyc_debit += -amount`, category balance `02/0002 += -amount` | fixed |
| `DEF-BILL-014` | D-30 | Account ID stays editable on the confirmation step | Account ID read-only once the balance is shown, until Clear or a finished payment | fixed |
| `BR-TRNA-003` | D-59 | "Highest master ID + 1" under an application lock | Sequence `core.tran_id_seq` | fixed |
| `DEF-SGN-011` | D-04 | Typed password upper-cased before the check | Case-sensitive check; migrated users must change their password | fixed |
| `DEF-SGN-002` | D-05 | No attempt counting | 5 failures lock for 15 minutes | fixed |
| `DEF-SGN-003` | D-05 | `User not found. Try again ...` / `Wrong Password. Try again ...` | One neutral message for unknown user, wrong password and locked user | fixed |
| `DEF-SGN-005` | D-02 | Any type other than `A` signs on as `USER` | Types other than `A` and `U` are refused | fixed |

Decided items with no code switch (ledger entry with `switch = false`): `DEF-BILL-009` and `Q-BILL-004` stay legacy (D-03, staff model: any user pays any account, the payer is recorded); `Q-BILL-001` legacy (D-30: full balance, one-step payment kept); `Q-BILL-006` fixed by the schema (D-60); `Q-MENU-003` fixed (D-05: Back on a menu signs off).

`NUM-BILL-002` and `DEF-BILL-002` are not decision items, so they keep the COBOL behaviour with no switch (this supersedes the switch table in [overview.md](overview.md#the-bill-pay-first-slice)): a balance of 1,000,000,000.00 or more is paid cut to 9 integer digits, and the card charged is the lowest card number of the account, the payment completing.

## 7. Identity contract

### Tokens and cookies

| Item | Value |
| :-- | :-- |
| Access token | JWT, RS256, header `{alg: RS256, typ: "at+jwt", kid}` (`kid` = base64url SHA-256 thumbprint of the public key). Claims: `iss` = `carddemo-identity`, `aud` = `carddemo-api`, `sub` = user ID (upper case), `sid` = session ID, `role` = `USER` or `ADMIN`, `utype` = the stored legacy type, `iat`, `nbf`, `exp` = `iat` + **300 s**, `jti`. |
| Access cookie | `cd_at`; `HttpOnly; SameSite=Strict; Path=/api; Max-Age=300`; `Secure` per `carddemo.security.cookie-secure`. The SPA never reads tokens. |
| Refresh token | 32 random bytes, base64url; only its SHA-256 is stored (`iam.session.refresh_hash`); rotated on every refresh. |
| Refresh cookie | `cd_rt`; `HttpOnly; SameSite=Strict; Path=/api/auth; Max-Age=28800`; `Secure` as above. |
| CSRF cookie | `XSRF-TOKEN` (readable by the SPA), set on sign-on, password change and refresh. |
| Idle timeout (D-05) | A refresh is refused when `now - last_activity_at > 15 min` (`last_activity_at` moves on each refresh); absolute limit 8 hours. The web app also signs off after 15 minutes without user input. A revoked session's access token stays valid for at most 5 minutes; Bill Pay accepts that. |
| Reuse detection | Presenting `previous_refresh_hash` ends the session with `REUSE_DETECTED` and audits `REFRESH_REUSE`. |

### Endpoints (identity-service)

| Method and path | Auth | Request | Success | Errors |
| :-- | :-- | :-- | :-- | :-- |
| `POST /api/auth/sign-on` | none | `{userId, password}` | `200 SessionView` + cookies | see the sign-on rules below |
| `POST /api/auth/password` | none, or a session | `{userId?, currentPassword, newPassword}` | `200 SessionView` + cookies: the user is signed in | sign-on rules for the current password, then the new-password rules |
| `POST /api/auth/refresh` | `cd_rt` + CSRF | - | `200 SessionView` + new cookies | `401 SESSION_EXPIRED` (cookies cleared) |
| `POST /api/auth/sign-off` | session + CSRF | - | `200 {message: "Thank you for using CardDemo application...", severity: "info"}`, cookies cleared, session ended | - |
| `GET /api/auth/me` | session | - | `200 SessionView` | 401 |
| `GET /api/auth/.well-known/jwks.json` | none | - | JWKS with the current key | - |
| `GET /api/features` | session | - | `200 {modules: {authorization: false, transactionTypes: false}, converted: ["BILL"], uiSwitches: {"DEF-BILL-014": "fixed"}}` | 401 |

`SessionView` = `{userId, userType, role, firstName, lastName, home: "/" | "/admin", idleTimeoutSeconds: 900}`.

`userId` in the password request is required without a session; with a session it may be omitted, and if present it must equal the session's user.

### Sign-on rules, in order

1. `userId` blank (spaces or empty): `400 USER_ID_REQUIRED`, `Please enter User ID ...`, field `userId` (VAL-SGN-001).
2. `password` blank: `400 PASSWORD_REQUIRED`, `Please enter Password ...`, field `password` (VAL-SGN-002).
3. Longer than 8 (`userId`) or 64 (`password`) characters: `400 INVALID_INPUT`, `Please enter User ID ...` or `Please enter Password ...` on the field. The web form caps the fields, so only scripts see this.
4. The user ID is upper-cased (BR-SGN-001) and read. A database failure: `503 UNABLE_TO_VERIFY`, `Unable to verify the User ...`, field `userId` (ERR-SGN-001).
5. Not found: legacy `401 USER_NOT_FOUND`, `User not found. Try again ...`, field `userId` (VAL-SGN-003). Fixed (`DEF-SGN-003`): `401 SIGN_ON_FAILED`, `Wrong User ID or Password. Try again ...`, field `password`.
6. Fixed `DEF-SGN-002` only: `locked_until > now` gives the same neutral `401 SIGN_ON_FAILED`, whatever the password; nothing else is checked.
7. Password check: legacy `DEF-SGN-011` compares `upper(password)`, fixed compares the password as typed, against the bcrypt hash; trailing spaces are trimmed first, as the 3270 field pads them. Wrong: legacy `401 WRONG_PASSWORD`, `Wrong Password. Try again ...`, field `password` (VAL-SGN-004); fixed: the neutral message. Fixed `DEF-SGN-002`: `failed_attempts += 1`; at 5, `locked_until = now + 15 min`, the counter resets and `USER_LOCKED` is audited. Unknown user IDs count nothing.
8. Fixed `DEF-SGN-005`: `user_type` not `A` or `U` gives `403 USER_TYPE_NOT_ALLOWED`, `User not authorized. Contact your administrator ...`, field `userId`. Legacy: any type other than `A` gets role `USER` (BR-SGN-004).
9. Fixed `DEF-SGN-011` and `must_change_password = 1`: `403 PASSWORD_CHANGE_REQUIRED`, `Password change required. Enter a new Password ...`, field `newPassword`, `severity: info`; no session.
10. Success: counter reset, `last_sign_on_at`, a new session, cookies, `SIGN_ON_OK` audited. `home` is `/admin` for type `A`, else `/` (BR-SGN-004).

Every failure writes `SIGN_ON_FAILED` with the reason code. Sign-on, password change and refresh do not consult the batch window (WRITES_BLOCKED covers business tables, not session bookkeeping).

### New-password rules (D-SEC-07)

After the current password passes steps 1 to 9 (step 9 does not apply):

| Check | Legacy (`DEF-SGN-011` legacy) | Fixed | Code and message (field `newPassword`) |
| :-- | :-- | :-- | :-- |
| Present | required | required | `NEW_PASSWORD_REQUIRED`, `Please enter new Password ...` |
| Length | 1 to 8 | 8 to 64, any printable character including spaces | `NEW_PASSWORD_LENGTH`, `New Password must be 1 to 8 characters ...` / `New Password must be 8 to 64 characters ...` |
| Different from current | yes (after upper-casing in legacy) | yes | `NEW_PASSWORD_SAME`, `New Password must differ from the current one ...` |
| Not the user ID | - | not equal to the user ID, ignoring case | `NEW_PASSWORD_IS_USER_ID`, `New Password must not be the User ID ...` |

Legacy stores the hash of the upper-cased value, so the result can always sign on under BR-SGN-003. Success clears `must_change_password`, audits `PASSWORD_CHANGED` and starts a session. The user-admin screens (D-53) apply the same rule when they are converted.

### Seeding (D-06)

`identity-service` seeds users at startup when `iam.app_user` is empty; `dataLoadJob` does not touch users.

- Profile `demo` (local and CI): the 10 DUSRSECJ users (IDs, names and types from the COSGN00C and COUSR specs; `ADMIN001` type `A`, `USER0001` type `U` among them), password `CARDDEMO_SEED_USER_PASSWORD`, `must_change_password = 0`.
- Any other profile: one bootstrap admin, `ADMIN001`, password from `CARDDEMO_BOOTSTRAP_ADMIN_PASSWORD` (startup fails if it is missing), `must_change_password = 1`.
- Users migrated from a legacy `USRSEC` file (not in this slice) are hashed once as stored, with `must_change_password = 1`.

## 8. Bill Pay contract

### Endpoints (billpay-service, role `USER`)

| Method and path | Request | Success |
| :-- | :-- | :-- |
| `GET /api/billpay/accounts/{acctId}` | - | `200 Preview` |
| `POST /api/billpay/accounts/{acctId}/payments` | headers `Idempotency-Key: <UUID>`, `X-XSRF-TOKEN`; body `{confirm: "Y"}` | `201 PaymentResult` |

```yaml
Preview:          { acctId: "00000000001", currentBalance: "194.00", currentBalanceDisplay: "+0000000194.00",
                    payable: true, message: "Confirm to make a bill payment...", severity: error, field: confirm }
                  # balance <= 0: payable false, message "You have nothing to pay...", severity error, field acctId
PaymentResult:    { tranId: "0000000000000001", amount: "194.00", acctId: "00000000001",
                    message: "Payment successful.  Your Transaction ID is 0000000000000001.", severity: success, field: acctId }
                  # amount is the amount paid, always positive here, whatever the stored sign
```

`acctId` is used exactly as received after URL decoding: a value of only spaces counts as blank; anything else is compared with the 11-character key as is, so `123` and `ABC` are not found (DEF-BILL-004 stays). More than 11 characters: `404 ACCOUNT_NOT_FOUND`.

### Preview, in order

1. Blank: `400 ACCT_ID_REQUIRED`, `Acct ID can NOT be empty...`, field `acctId` (VAL-BILL-001).
2. Read the account (no lock). Not found: `404 ACCOUNT_NOT_FOUND`, `Account ID NOT found...`, field `acctId` (VAL-BILL-003). Database failure: `500 ACCOUNT_LOOKUP_FAILED`, `Unable to lookup Account...`.
3. `200 Preview` (VAL-BILL-004, VAL-BILL-005). Allowed during a batch window.

### Payment, in order

1. Blank `acctId`: `400 ACCT_ID_REQUIRED` as above.
2. `confirm` not `Y` or `y`: `400 INVALID_CONFIRM`, `Invalid value. Valid values are (Y/N)...`, field `confirm`. The web client handles `N`, `n` and blank itself and never posts them.
3. `Idempotency-Key` missing or not a UUID: `400 IDEMPOTENCY_KEY_REQUIRED`, `Idempotency-Key header is required`.
4. Key already in `billpay.payment`: same account and same user, return the stored `201 PaymentResult` with header `Idempotent-Replayed: true` and write nothing; otherwise `422 IDEMPOTENCY_KEY_REUSED`, `Idempotency-Key was used for another payment`.
5. Begin one transaction (READ COMMITTED). `BatchWindowGuard.requireWritesAllowed()`: an open window gives `503 BATCH_WINDOW` with `Retry-After: 300`, `Bill payment is not available while the batch cycle runs. Try again later...`, field `acctId`.
6. `SELECT ... FROM core.account WITH (UPDLOCK, ROWLOCK) WHERE acct_id = ?`. Not found: `404 ACCOUNT_NOT_FOUND`. Failure: `500 ACCOUNT_LOOKUP_FAILED`.
7. `curr_bal <= 0`: `422 NOTHING_TO_PAY`, `You have nothing to pay...`, field `acctId`, with `currentBalance` and `currentBalanceDisplay` in the body (BR-BILL-002).
8. Card: `SELECT TOP 1 card_num FROM core.card_xref WHERE acct_id = ? ORDER BY card_num` (BR-BILL-005). None: `404 ACCOUNT_NOT_FOUND`, `Account ID NOT found...` (the legacy text for a cross-reference NOTFND). Failure: `500 XREF_LOOKUP_FAILED`, `Unable to lookup XREF AIX file...`.
9. Amount = `LegacyNumbers.truncate(curr_bal, 9)` (NUM-BILL-002). ID = `TransactionIds.next()`. Failure in legacy ID mode: `500 TRAN_LOOKUP_FAILED`, `Unable to lookup Transaction...`.
10. `TransactionWriter.insert` with BR-BILL-003's values: type `02`, category `0002`, source `POS TERM`, description `BILL PAYMENT - ONLINE`, amount `+amount` (legacy) or `-amount` (fixed `DEF-BILL-010`), the card, merchant `999999999` / `BILL PAYMENT` / `N/A` / `N/A`, `orig_ts` = `proc_ts` = now truncated to whole seconds (NUM-BILL-003's zero fraction), origin `BILL_PAY`, `in_master = 1`, `legacy_filler` `NULL`, `created_by` = the user. Duplicate ID: `409 TRAN_ID_EXISTS`, `Tran ID already exist...`. Other failure: `500 TRAN_ADD_FAILED`, `Unable to Add Bill pay Transaction...`.
11. Balance: legacy `balanceOnly(acct, -amount)`; fixed `post(acct, "02", "0002", -amount)`. Zero rows: `404 ACCOUNT_NOT_FOUND`. Failure: `500 ACCOUNT_UPDATE_FAILED`, `Unable to Update Account...`.
12. Insert `billpay.payment` (`paid_by` = the token's `sub`). A unique violation on the key means a concurrent retry: roll back and answer as step 4.
13. Commit; `201 PaymentResult`.

Any error rolls back everything (D-29). Errors are logged with the correlation ID and the technical cause; the response carries only the catalogue text (D-58). A second payment on the same account waits on the update lock and then gets `NOTHING_TO_PAY`.

### OpenAPI snapshots

Each service exports its OpenAPI document in a test (`OpenApiExportTest`) to `target/openapi/<service>.json`. The committed snapshots `modernization/web/openapi/identity-service.json` and `billpay-service.json` are what the web client is generated from (`openapi-typescript`); the backend test fails when the export differs from the snapshot, so a contract change is a reviewed diff and `web-build` needs no Maven run. Operation IDs: `signOn`, `changePassword`, `refresh`, `signOff`, `me`, `jwks`, `features`, `previewBillPayment`, `payBill`.

## 9. Web contract

| Route | Role | Content |
| :-- | :-- | :-- |
| `/sign-on` | public | User ID (max 8), Password (max 8 in legacy mode, 64 in fixed; masked), Sign on (Enter), Exit (F3). |
| `/sign-on/change-password` | public | Reached on `PASSWORD_CHANGE_REQUIRED` with the user ID in route state: current password, new password, repeat new password (client check: `New Password and confirmation do not match ...`), Change (Enter), Back. |
| `/signed-off` | public | `Thank you for using CardDemo application...` and a link to sign on (FLOW-SGN-003). |
| `/` | USER | Main menu. |
| `/admin` | ADMIN | Admin menu stub: options 1 to 4 show `This option <name> is coming soon ...` (green); options 5 and 6 hidden (D-70). |
| `/bill-pay` | USER | Bill Pay. |

A route without a session goes to `/sign-on` (no message); a route for the other role goes to that user's `home`. Every page header shows the two legacy titles, the date `mm/dd/yy` and time `hh:mm:ss`, and the screen's legacy IDs (`CB00 · COBIL00C`, `CC00 · COSGN00C`, `CM00 · COMEN01C`) (FLOW-BILL-009, FLOW-SGN-007, FLOW-MENU-007). The message line is red for `error`, green for `success`, neutral for `info`; focus moves to `field`. An API `401` triggers one refresh and a retry; a failed refresh goes to `/sign-on`.

**Function keys (D-WEB-03).** A header toggle (stored in `localStorage` as `carddemo.fkeys`) turns on 3270 shortcuts: F3 = Back or Exit, F4 = Clear; any other function key shows `Invalid key pressed. Please see below...` and keeps the input (VAL-BILL-006, VAL-SGN-005, VAL-MENU-002). With the toggle off, other function keys do nothing. Tests of S12 (BILL), S9 (SGN) and S9 (MENU) switch it on.

**Main menu.** Lines `01. Account View` to `11. Pending Authorization View` (BR-MENU-001), clickable, plus the option field (2 characters) and Enter. Option `10` opens `/bill-pay`. Options 1 to 9: `This option <name> is coming soon ...` in green (the option name as listed). Option 11 with `modules.authorization` false: `This option Pending Authorization View is not installed...` in red (BR-MENU-004). Invalid (NUM-MENU-001 normalisation, then not 1 to 11): `Please enter a valid option number...`. Back (F3) calls sign-off and shows an empty `/sign-on` with no message (FLOW-MENU-004, D-05).

**Bill Pay page, on Enter:**

1. Account ID blank: `Acct ID can NOT be empty...`, focus Account ID, no call.
2. Confirm `N` or `n`: clear Account ID, balance, Confirm and message; focus Account ID; no call (VAL-BILL-002).
3. Confirm other than `Y`, `y`, `N`, `n` or blank: `Invalid value. Valid values are (Y/N)...`, focus Confirm, no call.
4. Confirm blank: preview. Show the balance display and the response message and focus. In fixed `DEF-BILL-014` mode with `payable` true, the Account ID becomes read-only.
5. Confirm `Y` or `y`: pay, with a new `Idempotency-Key` per Enter (reused only for an automatic retry after a network error). Success: clear the fields, green message, focus Account ID, Account ID editable again. Errors: the response message, red, focus `field`; `NOTHING_TO_PAY` also shows the balance.

Back (F3) returns to the main menu (FLOW-BILL-004); Clear (F4) clears the fields and message, focus Account ID, Account ID editable (FLOW-BILL-005).

## 10. Scenarios

Each row is one backend integration test (API) and, where it involves the screen, one Playwright test, named after the unit and number (`BILL-S9`) and tagged with the items. Rows with two expectations run in both modes.

### BILL (COBIL00C section 15)

Start data: the sample account `00000000001` with balance 194.00, its card from the cross-reference, and its category balances, from test-support fixtures.

| # | Input | Legacy | Fixed (ledger) |
| --: | :-- | :-- | :-- |
| S1 | `/bill-pay` and both APIs without a session | redirect to `/sign-on`; API `401 UNAUTHENTICATED` | same |
| S2 | open `/bill-pay` from menu option 10 | empty form, focus Account ID | same |
| S3 | Enter, Account ID blank | `Acct ID can NOT be empty...`; API `400 ACCT_ID_REQUIRED` for blank on both endpoints | same |
| S4 | Enter, `99999999999`, Confirm blank | `Account ID NOT found...`, focus Account ID | same |
| S5 | Enter, `00000000001`, Confirm blank | balance `+0000000194.00`, red `Confirm to make a bill payment...`, focus Confirm, no data change; Account ID editable | same, Account ID read-only (`DEF-BILL-014`) |
| S6 | balance 0.00 and -25.50; Confirm blank, then `Y` | balance shown (`+0000000000.00`, `-0000000025.50`), `You have nothing to pay...`; `422 NOTHING_TO_PAY` for `Y`; no writes | same |
| S7 | Enter, Confirm `N` | fields and message cleared, focus Account ID, no call, no writes | same |
| S8 | Enter, Confirm `X` | `Invalid value. Valid values are (Y/N)...`, focus Confirm; API `400 INVALID_CONFIRM` | same |
| S9 | Enter, `00000000001`, `Y`, highest master ID `n` | one transaction `n+1` with BR-BILL-003's values and amount **+194.00**; `curr_bal` 0.00; cycle totals, category balances and every other account column unchanged; one `billpay.payment` row; fields cleared; green `Payment successful.  Your Transaction ID is <n+1>.` | amount **-194.00**; `curr_bal` 0.00; `curr_cyc_debit` decreased by 194.00; `curr_cyc_credit` unchanged; `tcat_bal (00000000001, 02, 0002)` decreased by 194.00 (created at -194.00 if missing); ID from the sequence (the fixture restarts it at `n+1`, so the ID is again `n+1`) |
| S10 | Back (F3) | main menu | same |
| S11 | Clear (F4) | fields and message cleared | same, Account ID editable again |
| S12 | F5 with shortcuts on | `Invalid key pressed. Please see below...`, input kept | same |

Added scenarios (the target's own guarantees):

| # | Case | Expected (both modes unless noted) |
| --: | :-- | :-- |
| S13 | Open batch window | preview `200`; payment `503 BATCH_WINDOW`, no writes |
| S14 | Same `Idempotency-Key` twice | second call returns the same `201` with `Idempotent-Replayed: true`, one transaction only; same key for another account: `422` |
| S15 | Two payments on one account at once, different keys | one `201`, one `422 NOTHING_TO_PAY`; one transaction |
| S16 | One-step payment: `Y` on the empty form with a valid account (BR-BILL-007) | paid without a preview |
| S17 | Balance 1,234,567,890.12 (NUM-BILL-002) | paid 234,567,890.12 (stored `+` legacy, `-` fixed); balance left 1,000,000,000.00 |
| S18 | Account with cards `...0002` and `...0001` | charged to the lower card number, success |
| S19 | Account with no cross-reference row | `404 ACCOUNT_NOT_FOUND`, no writes |
| S20 | `ADMIN` token | `403 ACCESS_DENIED` |
| S21 | Database failure injected at each step 6 to 12 | the step's message, nothing committed (DEF-BILL-001 fixed by architecture) |
| S22 | Legacy ID mode, two writers at once (payment and a direct `TransactionIds.next()`) | distinct IDs (DEF-BILL-003) |

### SGN (COSGN00C section 15)

Seed: the demo users with password `PASSWORD`; `TEST0001` stored with password `Secret12` where noted.

| # | Input | Legacy | Fixed (ledger) |
| --: | :-- | :-- | :-- |
| S1 | open the app | `/sign-on`, focus User ID | same |
| S2 | both blank | `Please enter User ID ...`, focus User ID | same |
| S3 | `ADMIN001`, password blank | `Please enter Password ...`, focus Password | same |
| S4 | `ADMIN001` / `PASSWORD` | `/admin`; `me` = `ADMIN001`, type `A`, role `ADMIN` | same |
| S5 | `user0001` / `password` | `/`; user `USER0001`, type `U` | `Wrong User ID or Password. Try again ...` (case-sensitive) |
| S5b | `user0001` / `PASSWORD` | `/` | `/` (IDs stay case-insensitive) |
| S6 | `NOUSER01` / `PASSWORD` | `User not found. Try again ...`, focus User ID | neutral message, focus Password |
| S7 | `ADMIN001` / `WRONGPWD` | `Wrong Password. Try again ...`, focus Password | neutral message |
| S8 | Exit (F3) on sign-on; Back on a menu | `/signed-off` with `Thank you for using CardDemo application...`; from a menu, session ended and empty `/sign-on` | same |
| S9 | F5 with shortcuts on | `Invalid key pressed. Please see below...` | same |
| S10 | sign-on during an open batch window | succeeds (deviation: the window does not cover sign-on) | same |
| S11 | `TEST0001` / `Secret12` | `Wrong Password. Try again ...` (DEF-SGN-011) | `/` |

Added: S12 five wrong passwords, then the right one: legacy signs on; fixed gives the neutral message until the clock passes 15 minutes, then signs on. S13 user type `X`: legacy `/` as `USER`; fixed `403 USER_TYPE_NOT_ALLOWED`. S14 `must_change_password = 1`: fixed sign-on `403 PASSWORD_CHANGE_REQUIRED`, the change screen, each new-password rule, success lands on `home`; legacy ignores the flag. S15 refresh after 16 idle minutes: `401 SESSION_EXPIRED`. S16 sign-off, then refresh with the old cookie: `401`. S17 an old refresh token reused: session ended, `REFRESH_REUSE` audited. S18 every sign-on outcome writes one audit row, never with a password.

### MENU (COMEN01C section 15)

| # | Input | Expected |
| --: | :-- | :-- |
| S1 | `/` without a session | `/sign-on` |
| S2 | signed on as `USER0001` | lines `01. Account View` to `11. Pending Authorization View` |
| S3 | option `10` | `/bill-pay` |
| S4 | option `5` | green `This option Credit Card Update is coming soon ...` (not converted; scope exclusion for BR-MENU-002 options 1 to 9) |
| S5 | option blank, `00`, `12`, `99` | `Please enter a valid option number...` |
| S6 | option `11`, module not installed | red `This option Pending Authorization View is not installed...` |
| S7 | option `11`, module installed | out of scope (no authorization module in this slice) |
| S8 | Back (F3) | session ended, empty `/sign-on` |
| S9 | F7 with shortcuts on | `Invalid key pressed. Please see below...` |
| S10 | `ADMIN001` opens `/` | redirected to `/admin` |

## 11. Item treatment for unit BILL

`test-billpay` requires a passing test for every row marked with a layer.

| Item | Treatment | Test |
| :-- | :-- | :-- |
| BR-BILL-001 | balance re-read under `UPDLOCK` at payment | API S9, S15 |
| BR-BILL-002 | step 7 | API S6 |
| BR-BILL-003 | step 10 | API S9 (every column checked) |
| BR-BILL-004 | `TransactionIds`; fixed mode by D-59 (deviation entry) | API S9, S22 |
| BR-BILL-005 | lowest card number | API S9, S18 |
| BR-BILL-006 | step 11, legacy mode | API S9 |
| BR-BILL-007 | one-step payment allowed | API and Playwright S16 |
| VAL-BILL-001 to 005 | sections 8 and 9 | API S3 to S8, Vitest, Playwright |
| VAL-BILL-006 | function-key toggle | Vitest, Playwright S12 |
| FLOW-BILL-001 | session required | API and Playwright S1 |
| FLOW-BILL-002 | empty form | Playwright S2 |
| FLOW-BILL-003 | Enter submits | Vitest |
| FLOW-BILL-004, 005 | Back, Clear | Playwright S10, S11 |
| FLOW-BILL-006 | VAL-BILL-006 | Playwright S12 |
| FLOW-BILL-007, 008 | pseudo-conversation and XCTL: replaced by routes (deviation, incidental) | - |
| FLOW-BILL-009 | page header | Vitest |
| SE-BILL-001 to 005 | steps 6, 8, 9, 10, 11 | API S9, S21 |
| ERR-BILL-001 | the step messages | API S21 |
| ERR-BILL-002, 003, 004 | no screen receive, no browse, no abend: deviation (architecture-dependent) | - |
| NUM-BILL-001 | `editSigned(…, 10, 2)` | unit test (positive, zero, negative), API S5, S6 |
| NUM-BILL-002 | `truncate(…, 9)` | API S17 |
| NUM-BILL-003 | whole-second `DATETIME2`; legacy layout from `LegacyTimestamps.online` in the exporter | unit test, API S9 |
| NUM-BILL-004 | header date and time | Vitest |
| NUM-BILL-005 | sequence `MAXVALUE` 9999999999999999 `NO CYCLE` (fixed); legacy wrap is not reachable with 16-digit data | unit test |
| DEF-BILL-001, 003 | architecture-dependent (D-29), deviation | API S21, S22 |
| DEF-BILL-002 | first card, completes | API S18 |
| DEF-BILL-004 | key used as typed | API (`123`, `ABC`) |
| DEF-BILL-005, 011, 012, 013 | cannot occur (one response, one transaction), deviation | - |
| DEF-BILL-006 | `legacy_filler` NULL (incidental, deviation) | - |
| DEF-BILL-007 | gone with D-60 | - |
| DEF-BILL-008, 015 | commarea artefacts, dropped (incidental) | - |
| DEF-BILL-009 | legacy kept (D-03); payer recorded | API S9 (`paid_by`) |
| DEF-BILL-010 | switch, both modes | API S9 both modes |
| DEF-BILL-014 | switch, both modes | Vitest and Playwright S5 both modes |
| Q-BILL-001 to 004, 006 | ledger entries (section 6) | - |
| Q-BILL-005 | class c, no entry | - |

SGN and MENU follow the same rule: every must-preserve item and every switch is cited by a test of section 10.

## 12. Ledger files (P5)

`ledger/fidelity.toml`, one table per item:

```toml
[switch."DEF-BILL-010"]
value = "fixed"          # legacy | fixed
code_switch = true       # false: decided, no branch in code
decision = "D-28"
approved_by = "user (decision catalog answers)"
approved_on = "2026-11-06"
```

Entries for this slice: the seven switches of section 6 (`fixed`), and `DEF-BILL-009`, `Q-BILL-001`, `Q-BILL-002`, `Q-BILL-003`, `Q-BILL-004`, `Q-BILL-006`, `Q-MENU-003`, `Q-SGN-001` to `Q-SGN-005` with their decisions.

`ledger/deviations.toml`, one table per item, `kind` one of `architecture-dependent`, `security-c1`, `incidental-ui`, `decision`:

```toml
[deviation."DEF-BILL-001"]
kind = "architecture-dependent"
decision = "D-29"
target = "One database transaction: all or nothing"
approved_by = "user (decision catalog answers)"
approved_on = "2026-11-06"
```

Entries for this slice: DEF-BILL-001, 003, 005, 006, 007, 008, 011, 012, 013, 015; ERR-BILL-002, 003, 004; FLOW-BILL-007, 008; BR-BILL-004 (fixed by D-59); DEF-SGN-001 (always hashed, D-04), DEF-SGN-004 (password never returned), DEF-SGN-006, DEF-SGN-012, DEF-MENU-003 (C1); DEF-SGN-008 (no underscores in the web field); ERR-SGN-001 for scenario S10 (the window does not cover sign-on); DEF-MENU-004 (the coming-soon text is the target's own); Q-MENU-003 (Back signs off).

`ledger/scope.toml`:

```toml
[unit.BILL]
spec = "docs/modernization/spec/programs/COBIL00C.md"
[unit.SGN]
spec = "docs/modernization/spec/programs/COSGN00C.md"
[unit.MENU]
spec = "docs/modernization/spec/programs/COMEN01C.md"
excluded = { "BR-MENU-002" = "options 1-9 and 11 not converted", "FLOW-MENU-001" = "replaced by the route guard" }
```

## 13. Make targets: done means

Each target prints `<target>: OK` and exits 0 only when every condition holds. Heavy targets take `var/run/heavy.lock`.

| Target | Done when |
| :-- | :-- |
| `setup` | as today, plus the new `.env` keys filled (seed password 8 upper-case letters and digits) and `var/secrets/identity-jwt.pem` present. |
| `db-reset` | the database is recreated; every migration of section 4 is applied and `flyway validate` is clean; each login connects and is refused one table outside its grants. |
| `load` | `dataLoadJob` (batch-service, one-shot) loads accounts, customers, cards, cross-references and category balances with 50 rows each, `COMPLETED`; `core.transaction` is empty; `core.tran_id_seq` restarted at highest `tran_id` + 1 (1 on the sample). |
| `up` | infrastructure, `identity-service` (seeds users if none), `billpay-service` and Vite are healthy within `UP_TIMEOUT`; `make status` lists them. |
| `smoke` | in under 60 s and with no row count change: both health endpoints, sign-on as `USER0001`, `GET /api/auth/me`, `GET /api/features`, a preview of `00000000001`, the JWKS. Sign-on bookkeeping rows (`iam.session`, `iam.audit_event`) are the only writes allowed. |
| `billpay` | starts SQL Server, identity, Bill Pay and Vite; runs `db-reset` and `load` when `core.account` is empty; signs on `USER0001`, previews and pays `00000000001` (or the first account with a positive balance), and checks the success message, a new transaction and `curr_bal` 0.00; leaves the slice up at `http://127.0.0.1:25173/bill-pay`. |
| `test-billpay` | `ledger-core` and `billpay-service` tests (Testcontainers) including S1 to S22 in both modes where marked; the Bill Pay Vitest tests; the BILL Playwright tests against a stack it starts; the BILL item table of section 11 fully covered (checked by `trace` in scope `BILL`). |
| `test-backend` | `./mvnw verify` over every module of the slice: unit, integration, contract (OpenAPI snapshots, message catalogue) and ArchUnit tests; both modes; JUnit XML in `build/test-results/backend/`. |
| `web-build` | `npm ci`, client generated from the committed snapshots, `tsc --noEmit`, ESLint, Vitest, `vite build` within the bundle budget. |
| `test-e2e` | the SGN, MENU and BILL Playwright scenarios in Chromium on fresh `db-reset` and `load`, with no serious or critical axe finding. |
| `verify-ledger` | every `FidelitySwitches` key in code has a `code_switch = true` entry; every entry and deviation names an item the spec defines, with decision, approver and date; every architecture-dependent item of the in-scope units has a deviation. |
| `trace` | for BILL, SGN and MENU: every must-preserve item (minus `excluded`) has a passing test, every switch has passing tests in both modes, every deviation has an entry, and no test cites an unknown ID; writes `build/trace/matrix.md` and `.json`. |

CI (`.github/workflows/modernization.yml`) runs `backend` (`test-backend`), `billpay` (`test-billpay`), `web` (`web-build`), `e2e` (`up test-e2e`), `ledger` (`verify-ledger trace`) and the existing `spec` job, as in [repo-build-ci.md](repo-build-ci.md#ci-github-actions). `make check` at the repository root must stay green.

## 14. Left for later slices

- Batch: whether posting keeps the daily file's transaction IDs alongside `core.tran_id_seq` (D-59), and how the sequence is kept above them; the `cycle_id` stamping rule for online writes; `batch-service` posting must call `BalancePosting.post`.
- D-28 consequence to watch in the batch slice: with posting's over-limit formula kept (D-19), a payment's negative amount in `curr_cyc_debit` raises `curr_cyc_credit - curr_cyc_debit`, so later charges in the same cycle are more likely to be rejected as over limit (DEF-POST-005). The sample disclosure groups give type 02 a zero rate, so interest does not change (CBACT04C BR-INTC-001).
- Online transaction add (D-27) writes the posting input, not `core.transaction`.
