# REST APIs and security

## API conventions

- **Paths**: `/api/<context>/...`, JSON bodies, UTF-8. Every service publishes an OpenAPI document at `/v3/api-docs`; the React client is generated from it.
- **Errors**: RFC 9457 problem details with three extra fields: `code` (stable, for example `ACCOUNT_NOT_FOUND`), `message` (the exact legacy text from the spec, such as `Account ID NOT found...`) and `field` (where the screen puts the cursor). Successful responses that the legacy screen shows as a green or neutral message carry `{message, severity}`. Only the first failing check is returned, in the spec's edit order, because the first message and the cursor field are part of each equivalence contract.
- **Amounts** travel as decimal strings (`"194.00"`), with the legacy display string next to them where the screen shows an edited value (`"+0000000194.00"`).
- **Concurrency**: reads return an `ETag` from `row_version`; updates and deletes require `If-Match`. A mismatch returns `409` with the legacy text `Record changed by some one else. Please review` and the fresh values.
- **Paging**: keyset cursors (`start`, `after`, `before`, `size`) with a look-ahead `hasNext`, matching the legacy read-one-more browse. Page numbers are display state.
- **PANs** never appear in a URL. Card rows carry a surrogate `cardId`. Responses mask card numbers, SSN, DOB, government ID and EFT account to the last four characters; an `ADMIN` may ask for the unmasked value, and every unmasked view is audited (D-09 under C1, D-SEC-05). Masking is done in the service. The unmask is an explicit request that returns only the fields asked for, with `Cache-Control: no-store`. Each service keeps one SQL login with column-level `UNMASK` only where it needs the clear value ([data-model.md](data-model.md#ownership-and-grants)). The CVV is never returned (D-DATA-04).
- **Batch window** (`WRITES_BLOCKED`, D-BAT-05): reads keep working while a window is open; a write to a covered table returns `503` with `Retry-After` and `code: BATCH_WINDOW`, with a message per screen (for Bill Pay, `Bill payment is not available while the batch cycle runs. Try again later...`).

## Endpoints per service

### identity-service

| Method and path | Purpose | Spec |
| :-- | :-- | :-- |
| `POST /api/auth/sign-on` `{userId, password}` | Upper-case the user ID, check the hash, apply lockout, start a session; returns the session view with `home: "/" or "/admin"` | COSGN00C BR-SGN-001 to 005, VAL-SGN-001 to 005 |
| `POST /api/auth/password` `{userId?, currentPassword, newPassword}` | Change the password (forced at first sign-on for migrated users); success signs the user in | D-04, D-SEC-07 |
| `POST /api/auth/sign-off` | End the session | PF3 on sign-on and the menus |
| `POST /api/auth/refresh` | Rotate the access token | - |
| `GET /api/auth/me` | User ID, type, roles, installed features | the commarea identity (ENT-COMMAREA) |
| `GET /api/auth/.well-known/jwks.json` | Public keys for the other services | - |
| `GET /api/users?start=&startKey=&after=&before=&size=10` | User list page; `start` is a prefix filter, `startKey` the same prefix in key form | COUSR00C |
| `POST /api/users` | Add | COUSR01C |
| `GET /api/users/{userId}`, `PUT /api/users/{userId}`, and the same at `/api/users/key/{userKey}` | Fetch and update; the password is write-only | COUSR02C |
| `DELETE /api/users/{userId}`, `DELETE /api/users/key/{userKey}` | Delete (logical) | COUSR03C |
| `GET /api/features` | Which optional modules are installed (main menu option 11, admin options 5 and 6) | BR-MENU-004 |

The user endpoints' rules are in [identity-service.md](identity-service.md); the key form, which carries any user ID through the URL without relaxing the HTTP firewall, is in [its section 5](identity-service.md#5-user-ids-in-urls).

### billpay-service

| Method and path | Purpose | Spec |
| :-- | :-- | :-- |
| `GET /api/billpay/accounts/{acctId}` | Balance preview; `VAL-BILL-004` message when the balance is not positive | VAL-BILL-001, 003 to 005 |
| `POST /api/billpay/accounts/{acctId}/payments` `{confirm}` + `Idempotency-Key` | Pay the full balance; `201 {tranId, message: "Payment successful.  Your Transaction ID is 0000000000000301."}` | BR-BILL-001 to 007, SE-BILL-001 to 005 |

A retried POST with the same idempotency key returns the first result and writes nothing. The key is new: the legacy screen has no duplicate protection, but a browser retry must not pay twice.

### account-service

| Method and path | Purpose | Spec |
| :-- | :-- | :-- |
| `GET /api/accounts/{acctId}` (`USER`, `ADMIN`) | Account, customer and card number | COACTVWC |
| `GET /api/accounts/{acctId}/update-form` | Account and customer for update, with `ETag` | COACTUPC |
| `POST /api/accounts/{acctId}/validate` | Run the edits of the changed fields in order without saving (the Enter step, D-49) | COACTUPC VAL-ACUP-001 to 034 |
| `PUT /api/accounts/{acctId}` + `If-Match` | Save account and customer in one transaction (the F5 step) | COACTUPC BR-ACUP-005 |
| `POST /api/cards/search` `{acctId?, cardNum?, start?, after?, before?, size?}` (`USER`, `ADMIN`) | Card list; a `POST` because the filter can be a PAN | COCRDLIC |
| `POST /api/cards/lookup` `{acctId, cardNum}` (`USER`, `ADMIN`) | Card detail from typed keys (no PAN in the URL) | COCRDSLC |
| `GET /api/cards/{cardId}` (`USER`, `ADMIN`) | Card detail from the list | COCRDSLC |
| `POST /api/cards/update-lookup`, `GET /api/cards/{cardId}/update-form`, `POST /api/cards/{cardId}/validate` | Card for update, and the Enter step | COCRDUPC |
| `PUT /api/cards/{cardId}` + `If-Match` | Update name, status, expiry month and year | COCRDUPC |
| `POST /api/accounts/{acctId}/unmask`, `POST /api/cards/{cardId}/unmask` `{fields}` (`ADMIN`) | The requested fields in clear, audited in the same transaction | D-09, D-SEC-05 |
| `POST /api/transactions/{tranId}/unmask` `{fields: ["cardNum"]}` (`ADMIN`) | The transaction's card number in clear, audited in the same transaction ([transaction-service.md](transaction-service.md#access-and-the-card-number-reveal-d-09)) | D-09, DEF-TRNV-004 |

Roles are `USER` unless the row says otherwise. The full contract and its rulings are in [account-service.md](account-service.md).

### transaction-service

| Method and path | Purpose | Spec |
| :-- | :-- | :-- |
| `GET /api/transactions?start=&after=&before=&size=10` | Transaction list | COTRN00C |
| `GET /api/transactions/{tranId}` | One transaction | COTRN01C |
| `POST /api/transactions/validate`, `POST /api/transactions` | Add (validate, then confirm `Y`); the add requires `Idempotency-Key`, as Bill Pay | COTRN02C |
| `GET /api/transactions/latest` | Copy the last transaction into the form (PF5) | COTRN02C |
| `POST /api/reports/transactions` with the six date parts as typed and `confirm` | `202 ReportRun` with `Location`; triggers the report DAG ([transaction-service.md](transaction-service.md#14-contract-v7-2026-11-10)) | CORPT00C |
| `GET /api/reports/transactions/{runId}`, `GET .../{runId}/file` | Status and download of the 133-column report | CORPT00C, Q-RPTS-001 |

### reference-service

| Method and path | Purpose | Spec |
| :-- | :-- | :-- |
| `GET /api/transaction-types?typeCode=&descriptionContains=&after=&before=&size=7` | List with filters | COTRTLIC |
| `GET`, `POST`, `PUT` (+ `If-Match`), `DELETE /api/transaction-types/{code}` | Maintain one type; delete returns `409 Please delete associated child records first` while categories exist | COTRTUPC, COTRTLIC |
| `GET /api/reference/lookups/{states\|state-zip-prefixes\|area-codes}` | Shared validator lists. **Deferred** until a consumer is named; validation uses the lists bundled in `platform/validation` ([reference-service.md](reference-service.md#1-the-lookup-and-category-reads-are-not-built-now-question-1)) | COACTUPC |
| `GET /api/reference/transaction-categories` | Read-only categories. **Deferred**, as above | entities |

### authorization-service

| Method and path | Purpose | Spec |
| :-- | :-- | :-- |
| `GET /api/accounts/{acctId}/authorizations/summary` | Header and totals | COPAUS0C |
| `GET /api/accounts/{acctId}/authorizations?after=&size=5` | Newest first | COPAUS0C |
| `GET /api/authorizations/{authId}` | Detail with "next older" link | COPAUS1C |
| `POST /api/authorizations/{authId}/fraud` `{action: "F"\|"R"}` | Mark or remove fraud; detail and fraud row in one transaction | COPAUS1C, COPAUS2C |

The listener for authorization requests is in [messaging.md](messaging.md).

### batch-service (internal)

| Method and path | Caller | Purpose |
| :-- | :-- | :-- |
| `POST /internal/cycles` `{businessDate, interest}` | Airflow | Create the cycle, or return the existing one for that date (idempotent per date); returns the `INT` `cycleId` and `attempt`; `409 CYCLE_COMPLETED`, `409 CYCLE_PARAMETERS_DIFFER` |
| `POST /internal/jobs/{jobName}/executions` `{params}` | Airflow | Launch a job named in [batch.md](batch.md#jobs-per-jcl-job-and-step): `202` with the execution ID; `409 JOB_INSTANCE_COMPLETE`, `JOB_EXECUTION_RUNNING`, `JOB_INSTANCE_NOT_RESTARTABLE`, `BATCH_WINDOW_REQUIRED`, `CYCLE_COMPLETED`, `CYCLE_ATTEMPT_CHANGED` (cycle jobs, [contract](batch-execution-contract.md#21-identity-and-parameters) section 2.1); `400 INVALID_JOB_PARAMETERS`; `404` for an unknown job |
| `GET /internal/jobs/executions/{id}` | Airflow (polls every 10 s) | Status, exit status (`COMPLETED`, `COMPLETED_WITH_REJECTS`, `FAILED`), lease state and outputs; never changes the execution |
| `POST /internal/batch-window/open` `{cycleId \| windowRunKey}`, `.../close` `{..., complete}` | Airflow; the close also from `make batch-window-close` (`complete: false`) | Open (idempotent per holder; returns once online writes have drained, `503 WINDOW_DRAIN_TIMEOUT` with the window left closed, or `503 WINDOW_BUSY` while another holder has it) and close the one global window ([batch.md](batch.md#batch-window)) |

The full contract, including ownership leases and recovery, is [batch-execution-contract.md](batch-execution-contract.md). `batch-service` owns the window: only it writes `ops.batch_window`. Services never call `/internal/*`; they check the window through `ledger-core`'s `BatchWindowGuard`, which takes the shared side of the same application lock. No request carries a file path; inbound and outbound paths come from configuration.

`/internal/*` is bound to 127.0.0.1 and requires `Authorization: Bearer <CARDDEMO_BATCH_INTERNAL_TOKEN>`, a static random token that `make setup` writes to the git-ignored `.env`. It is compared in constant time and never logged. A static token is enough for one host where only Airflow calls the API. A deployment replaces it with an OAuth2 client-credentials token from the identity provider (D-SEC-02), with no change to the operator, which sends a bearer header either way.

## Security model

### Users and roles

The user table replaces `USRSEC`. The legacy user type maps to roles:

| Legacy type | Role | Can use |
| :-- | :-- | :-- |
| `A` | `ADMIN` | Admin menu functions: user administration, transaction types; read-only account view, card list, card view, transaction list and transaction view with unmask (admin option 7, D-09) |
| `U` | `USER` | Main menu functions 1 to 11 |
| any other value (Q-SGN-005) | `USER` in legacy mode; refused in fixed mode (DEF-SGN-005, decided, D-02) | - |

Under choice C2 three more roles are added: `OPERATOR` (reports, batch status), `FRAUD_ANALYST` (fraud marking) and `PII_VIEWER` (unmasked customer data).

### Authentication

The exact contract is in [billpay-slice.md](billpay-slice.md#7-identity-contract). In short:

- `identity-service` upper-cases the user ID (BR-SGN-001), changing only `a` to `z`, as `FUNCTION UPPER-CASE` does ([data-model.md](data-model.md#inventory)) and checks the password against a salted hash: Argon2id for every new hash, bcrypt for hashes stored before, replaced by Argon2id at the user's next successful sign-on so that response time does not show who has an old hash ([identity-service.md](identity-service.md#6-password-hashing)). Fixed mode of DEF-SGN-011 (decided, D-04) compares the password as typed; legacy mode upper-cases it first, as the COBOL does, with the same `a` to `z` helper. Users migrated from `USRSEC` are hashed once and must change their password at first sign-on (`403 PASSWORD_CHANGE_REQUIRED`, then `POST /api/auth/password`, which signs the user in).
- It issues an RS256 access token valid for 5 minutes and a rotating refresh token, in `HttpOnly`, `SameSite=Strict` cookies (`cd_at` for `/api`, `cd_rt` for `/api/auth`); `Secure` is a configuration switch, off only for plain http on 127.0.0.1. The SPA never reads tokens; the other services read the `cd_at` cookie (or an `Authorization: Bearer` header from scripts) and validate it offline against the JWKS endpoint. Unsafe methods need the `X-XSRF-TOKEN` header matching the `XSRF-TOKEN` cookie.
- **CSRF in every service** (rule of 2026-11-10). Spring Security's resource-server configurer exempts from `http.csrf()` every request that its `BearerTokenResolver` accepts. Our resolver accepts the `cd_at` cookie, so `http.csrf()` would skip the check for exactly the cookie-authenticated requests it exists to protect; identity-service's own chain had this gap and has fixed it. Every service therefore builds its chain with `platform/web-support`'s `CarddemoHttpSecurity.apply(http, exemptPosts...)` or uses web-support's default chain. It disables `http.csrf()` and adds its own `CsrfFilter`, which skips only safe methods, requests that carry `Authorization: Bearer` and no `cd_at` cookie, and the listed session-less POSTs (`/api/auth/sign-on`, `/api/auth/password`). No service calls `http.csrf()` or adds another exemption. **One exception, decided 2026-11-10:** identity-service builds its own chain with its own `CsrfFilter`, using the same matcher and no `http.csrf()`. `CarddemoHttpSecurity` hard-wires a resolver that reads `cd_at` on every path, so a refresh or sign-off authenticated by `cd_rt` and missing its header would get `401` instead of `403 CSRF_REJECTED`. Making identity use the shared chain later needs a resolver and handler parameter on `apply()` in the platform. Each service with an unsafe endpoint has a test through its real filter chain: an unsafe request authenticated by the `cd_at` cookie and without `X-XSRF-TOKEN` gets `403`, the same request with the header succeeds, and a request with both the cookie and a bearer header still needs the token.
- A refresh is refused after 15 idle minutes (D-05); the web app also signs off after 15 minutes without input. Back on a menu signs off.
- Fixed mode of DEF-SGN-002 and DEF-SGN-003 (decided, D-05): 5 failures lock the user for 15 minutes, and unknown user, wrong password and locked user get one neutral message, `Wrong User ID or Password. Try again ...`. In the same mode an in-memory limit on failed attempts per client address (default 30 per minute) answers `429 TOO_MANY_ATTEMPTS`; successes never count, and the lockout covers each user, so there is no per-user limit. Legacy mode keeps the two COBOL messages and has no limit. Every sign-on outcome is audited.
- Seed users exist only in the `demo` profile, with the password from `.env` (`CARDDEMO_SEED_USER_PASSWORD`); other environments get one bootstrap admin with a forced change (D-06). `identity-service` seeds them, not the data load.
- Sign-on, refresh and sign-off stay available during a batch window.
- An external identity provider (OIDC) can replace the local password check later without changing the other services, because they only validate tokens (D-SEC-02).
### Authorization

Every endpoint declares its role with Spring Security method security. No endpoint creates a session or accepts a user type from the client. Account and card endpoints never trust the account ID from a previous screen; they take it from the request and check it under the chosen rule.

### Audit

Every change and every view of unmasked PII writes an `iam.audit_event` or `ops.audit` row: who, when, what, the subject's key and the changed field names, never the values. Bill payments also record the paying user in `billpay.payment`.

### Service and messaging credentials

Each service has its own SQL login (grants in [data-model.md](data-model.md#ownership-and-grants)) and its own RabbitMQ user limited to its queues. All secrets come from the git-ignored `.env` that `make setup` generates.

## The no-sign-on defect (INV-006) under each choice

Today the five account and card transactions, the two Db2 transaction-type transactions and the two pending-authorization screens run without sign-on, and PF3 from them opens a menu, the Db2 ones the admin menu. Any signed-on user reaches every main-menu function for any account. INV-006 says authorization must be added, not preserved.

| What happens | C0 as-is (not offered) | **C1 sign-on required, legacy roles (default)** | C2 least privilege |
| :-- | :-- | :-- | :-- |
| Open an account, card, transaction-type or authorization screen without signing on | Works, as on CICS | Refused: the web app sends the user to `/sign-on`; the API returns 401 | Refused |
| Reach the admin menu from `CTLI`/`CTTU` + PF3 (DEF-TTLS-017, DEF-TTUP-017) | Works | Refused: admin routes need `ADMIN` | Refused |
| Reach the main menu with a blank user type (DEF-AUSM-012, DEF-AUDT-009, DEF-MENU-003) | Works | Refused | Refused |
| A `USER` views or changes any account, card or transaction (DEF-ACVW-009, DEF-CRDL-001, DEF-TRNL-007) | Works | Works, as for signed-on CICS users | Only accounts linked to the user in `iam.user_account` |
| A `USER` pays any account (DEF-BILL-009) | Works | Works; the paying user is recorded | Only linked accounts |
| A `USER` marks fraud on any authorization (DEF-AUDT-006) | Works | Works | Needs `FRAUD_ANALYST` |
| Full SSN, DOB and PAN on screen (DEF-ACVW-010, DEF-CRDL-002) | Shown | Masked; `ADMIN` may unmask, every unmasked view audited (decided, D-09) | Masked unless `PII_VIEWER` |
| Data needed | none | none | a user-to-account link, which no legacy file holds: the business must provide it |

C0 is listed only to show the gap: a web API without authentication cannot be deployed. The equivalence scenarios that start a transaction without sign-on are recorded in the decision ledger as accepted deviations. C1 is the decided model (D-SEC-01) because it changes only the paths INV-006 calls defects and needs no new data. C2 is recommended for production once the business supplies the user-to-account link (D-SEC-01).
