# REST APIs and security

## API conventions

- **Paths**: `/api/<context>/...`, JSON bodies, UTF-8. Every service publishes an OpenAPI document at `/v3/api-docs`; the React client is generated from it.
- **Errors**: RFC 9457 problem details with three extra fields: `code` (stable, for example `ACCOUNT_NOT_FOUND`), `message` (the exact legacy text from the spec, such as `Account ID NOT found...`) and `field` (where the screen puts the cursor). Successful responses that the legacy screen shows as a green or neutral message carry `{message, severity}`. Only the first failing check is returned, in the spec's edit order, because the first message and the cursor field are part of each equivalence contract.
- **Amounts** travel as decimal strings (`"194.00"`), with the legacy display string next to them where the screen shows an edited value (`"+0000000194.00"`).
- **Concurrency**: reads return an `ETag` from `row_version`; updates and deletes require `If-Match`. A mismatch returns `409` with the legacy text `Record changed by some one else. Please review` and the fresh values.
- **Paging**: keyset cursors (`start`, `after`, `before`, `size`) with a look-ahead `hasNext`, matching the legacy read-one-more browse. Page numbers are display state.
- **PANs** never appear in a URL. Card rows carry a surrogate `cardId`. Responses mask card numbers, SSN, DOB, government ID and EFT account unless the caller holds the unmask permission (choice C2) or the legacy screen shows them in full (choice C1, as today).
- **Batch window**: when a call needs a table the open window covers, the service returns `503` with `Retry-After` and `code: BATCH_WINDOW`. In legacy mode the `message` is the text the screen shows for an unavailable file (for Bill Pay, `Unable to lookup Account...`).

## Endpoints per service

### identity-service

| Method and path | Purpose | Spec |
| :-- | :-- | :-- |
| `POST /api/auth/sign-on` `{userId, password}` | Upper-case both, check the hash, start a session; returns `{userId, userType, home: "/" or "/admin"}` | COSGN00C BR-SGN-001 to 005, VAL-SGN-* |
| `POST /api/auth/sign-off` | End the session | PF3 on sign-on and the menus |
| `POST /api/auth/refresh` | Rotate the access token | - |
| `GET /api/auth/me` | User ID, type, roles, installed features | the commarea identity (ENT-COMMAREA) |
| `GET /api/auth/.well-known/jwks.json` | Public keys for the other services | - |
| `GET /api/users?start=&after=&before=&size=10` | User list page | COUSR00C |
| `POST /api/users` | Add | COUSR01C |
| `GET /api/users/{userId}`, `PUT /api/users/{userId}` | Fetch and update; the password is write-only | COUSR02C |
| `DELETE /api/users/{userId}` | Delete | COUSR03C |
| `GET /api/features` | Which optional modules are installed (main menu option 11, admin options 5 and 6) | BR-MENU-004 |

### billpay-service

| Method and path | Purpose | Spec |
| :-- | :-- | :-- |
| `GET /api/billpay/accounts/{acctId}` | Balance preview; `VAL-BILL-004` message when the balance is not positive | VAL-BILL-001, 003 to 005 |
| `POST /api/billpay/accounts/{acctId}/payments` `{confirm}` + `Idempotency-Key` | Pay the full balance; `201 {tranId, message: "Payment successful.  Your Transaction ID is 0000000000000301."}` | BR-BILL-001 to 007, SE-BILL-001 to 005 |

A retried POST with the same idempotency key returns the first result and writes nothing. The key is new: the legacy screen has no duplicate protection, but a browser retry must not pay twice.

### account-service

| Method and path | Purpose | Spec |
| :-- | :-- | :-- |
| `GET /api/accounts/{acctId}` | Account, customer and card number through the cross-reference | COACTVWC |
| `POST /api/accounts/{acctId}/validate` | Run the 34 edits in order without saving (the Enter step) | COACTUPC VAL-ACUP-001 to 034 |
| `PUT /api/accounts/{acctId}` + `If-Match` | Save account and customer in one transaction (the F5 step) | COACTUPC BR-ACUP-005 |
| `GET /api/cards?acctId=&cardNum=&after=&before=&size=7` | Card list | COCRDLIC |
| `POST /api/cards/lookup` `{acctId, cardNum}` | Card detail from typed keys (no PAN in the URL) | COCRDSLC |
| `GET /api/cards/{cardId}` | Card detail from the list | COCRDSLC |
| `PUT /api/cards/{cardId}` + `If-Match` | Update name, status, expiry month and year | COCRDUPC |

### transaction-service

| Method and path | Purpose | Spec |
| :-- | :-- | :-- |
| `GET /api/transactions?start=&after=&before=&size=10` | Transaction list | COTRN00C |
| `GET /api/transactions/{tranId}` | One transaction | COTRN01C |
| `POST /api/transactions/validate`, `POST /api/transactions` | Add (validate, then confirm `Y`) | COTRN02C |
| `GET /api/transactions/latest` | Copy the last transaction into the form (PF5) | COTRN02C |
| `POST /api/reports/transactions` `{type: MONTHLY\|YEARLY\|CUSTOM, startDate?, endDate?}` | `202 {runId}`; triggers the report DAG | CORPT00C |
| `GET /api/reports/transactions/{runId}`, `GET .../{runId}/file` | Status and download of the 133-column report | CORPT00C, Q-RPTS-001 |

### reference-service

| Method and path | Purpose | Spec |
| :-- | :-- | :-- |
| `GET /api/transaction-types?typeCode=&descriptionContains=&after=&before=&size=7` | List with filters | COTRTLIC |
| `GET`, `POST`, `PUT` (+ `If-Match`), `DELETE /api/transaction-types/{code}` | Maintain one type; delete returns `409 Please delete associated child records first` while categories exist | COTRTUPC, COTRTLIC |
| `GET /api/reference/lookups/{states\|state-zip-prefixes\|area-codes}` | Shared validator lists | COACTUPC |
| `GET /api/reference/transaction-categories` | Read-only categories | entities |

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
| `POST /internal/jobs/{jobName}/executions` `{params}` | Airflow | Launch a job; returns the execution ID |
| `GET /internal/jobs/executions/{id}` | Airflow | Status and exit status (`COMPLETED`, `COMPLETED_WITH_REJECTS`, `FAILED`) |
| `POST /internal/batch-window/open`, `.../close` | Airflow | Open and close the window ([batch.md](batch.md#batch-window)) |
| `GET /internal/batch-window` | services | Current window (services cache it for 5 seconds) |

`/internal/*` is bound to 127.0.0.1 and requires a client-credentials token from `.env`.

## Security model

### Users and roles

The user table replaces `USRSEC`. The legacy user type maps to roles:

| Legacy type | Role | Can use |
| :-- | :-- | :-- |
| `A` | `ADMIN` | Admin menu functions: user administration, transaction types |
| `U` and any other value (Q-SGN-005) | `USER` | Main menu functions 1 to 11 |

Under choice C2 three more roles are added: `OPERATOR` (reports, batch status), `FRAUD_ANALYST` (fraud marking) and `PII_VIEWER` (unmasked customer data).

### Authentication

- `identity-service` checks the password against a bcrypt hash of the upper-cased password. Sign-on upper-cases what the user types, so the behaviour the user sees is unchanged (BR-SGN-001, INV-007); only the storage changes (DEF-SGN-001).
- It issues a short-lived signed access token (15 minutes, RS256) and a rotating refresh token, both in `HttpOnly`, `Secure`, `SameSite=Strict` cookies. Unsafe methods also need a CSRF header. The other services validate the token offline against the JWKS endpoint.
- Seed users come from the DUSRSECJ list; their demo password is taken from `.env`, never from a committed file.
- Lockout, rate limiting and a neutral failure message are switches (Q-SGN-003, Q-SGN-004). Legacy mode keeps the two distinct messages that reveal whether a user ID exists.
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
| Full SSN, DOB and PAN on screen (DEF-ACVW-010, DEF-CRDL-002) | Shown | Shown, every view audited | Masked unless `PII_VIEWER` |
| Data needed | none | none | a user-to-account link, which no legacy file holds: the business must provide it |

C0 is listed only to show the gap: a web API without authentication cannot be deployed. The equivalence scenarios that start a transaction without sign-on are recorded in the decision ledger as accepted deviations. C1 is the default because it changes only the paths INV-006 calls defects and needs no new data. C2 is recommended for production once the business supplies the user-to-account link (D-SEC-01).
