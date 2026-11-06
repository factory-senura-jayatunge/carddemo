# Decisions

This list has four parts:

1. **Architecture decisions** (D-ARCH). This proposal takes them. Approving the proposal approves them; any of them can be objected to on its own.
2. **Defaults** (D-SEC, D-DATA, D-BAT, D-MSG, D-WEB, D-NFR). Each one says what is built if nobody answers and what this proposal recommends. The "Catalog" column names the matching entry of the user decision catalog (D-01 to D-75), so one answer can settle both.
3. **Open questions that change the architecture.** Only the Q-items whose answer changes a table, a service, a DAG or a queue. Every other Q-item and defect-decide item is a fidelity switch or a ledger entry and leaves the architecture as it is.
4. **Architecture-dependent fidelity items.** Defects that the new platform cannot reproduce, so they need an explicit "accept the fix".

Until a default is answered, the build keeps the COBOL behaviour (legacy mode). Security is the exception described in D-SEC-01.

## Decided values

The user approved this proposal (D10) and answered the decision catalog (`docs/modernization/decisions/README.md`) on 2026-11-06: every catalog recommendation is accepted, including the bulk fix D-75 and the architecture-dependent fixes below, and every default here takes its recommended value, except D-SEC-01 (C1) and D-BAT-05 (`WRITES_BLOCKED`). Where the catalog and these answers differ, these answers win. The code still defaults each switch to `legacy`; `ledger/fidelity.toml` records the decided value, and both modes stay tested.

| ID | Decided value |
| :-- | :-- |
| D-ARCH-01 to D-ARCH-16 | As proposed. |
| D-SEC-01 | **C1**: sign-on for every screen and API, roles `ADMIN` and `USER`, staff model (D-01, D-02, D-03). Unknown user types are refused (DEF-SGN-005 fixed). Masking applies under C1 as in D-SEC-05. |
| D-SEC-02 | Local password check in `identity-service`; OIDC later only changes that service. |
| D-SEC-03 | Salted bcrypt hashes; case-sensitive passwords, case-insensitive user IDs; legacy passwords hashed once at migration and changed at first sign-on (D-04, DEF-SGN-011 fixed). |
| D-SEC-04 | On: 5 failures lock the user for 15 minutes; one neutral failure message for unknown user, wrong password and locked user; 15-minute idle timeout; explicit sign-off; sign-on audit (D-05). |
| D-SEC-05 | PII (SSN, government ID, date of birth, EFT account, card number) masked on screens and in API responses; `ADMIN` may unmask; every unmasked view is audited (D-09 under C1). There is no `PII_VIEWER` role under C1. Mechanism (ruled 2026-11-06): masking is done in the application, with one SQL login per service. A service gets column-level `GRANT UNMASK` only on the masked columns it must use or reveal; every other login sees SQL Server dynamic data masking. No second unmask login: it would run in the same process with the same secrets, so it would not contain a bug in the service, and it would double the connection setup. Details in [data-model.md](data-model.md#ownership-and-grants). |
| D-SEC-06 | As proposed (D-07). |
| D-SEC-07 | New-password rule as in the security table above. |
| D-28 (catalog) | Payments post like batch postings (user answer): negative amount, POSTTRAN rule, so the amount lands in `curr_cyc_debit` and category balance `02/0002`. With D-19 kept, a payment raises the over-limit figure for later charges in the cycle (DEF-POST-005). |
| D-DATA-01 | Group ID moved to `group_id` at migration; DEF-ACUP-002 fixed (D-11). |
| D-DATA-02 | `card_xref` owns the card-to-account link; `card.acct_id` follows it (D-13, D-61). |
| D-DATA-03 | `legacy_filler` kept until cut-over for the differential checks, then dropped. |
| D-DATA-04 | **Dropped** (ruled 2026-11-06 on the recommended value): no converted function reads the CVV. Card list and card view read it but neither show nor use it, card update keeps the stored value (D-12) and checks concurrency with `If-Match` (D-17), and export and reports never output it (D-10). `cvv_cd` is in no API, DTO, log or export. The column stays, masked, with no login holding `UNMASK` on it, until cut-over; then a migration drops it and the loader field. |
| D-DATA-05 | **Now**, not after cut-over: one SQL Server sequence `core.tran_id_seq`, 16 digits, no seed record (D-59), switch `BR-TRNA-003`. Legacy mode ("highest + 1" under a lock) stays for parity tests. |
| D-DATA-06 | **Now**: `orig_ts` and `proc_ts` are `DATETIME2(6)`; legacy layouts are produced by the exporter (D-60). |
| D-DATA-07 | `tran_pk` kept. |
| D-BAT-01 | Interest monthly on a chosen day (D-24). |
| D-BAT-02 | Statements once per statement period, on the interest day (D-38). |
| D-BAT-03 | Purge after the cycle (D-39). |
| D-BAT-04 | All history kept, retention per data class from the data-handling policy (D-40). |
| D-BAT-05 | **`WRITES_BLOCKED`** (the user's answer, which overrides catalog D-18): reads work during the window, writes return 503. Sign-on, refresh and sign-off stay available. |
| D-BAT-06 to D-BAT-10 | As proposed (D-57, D-66, D-41, D-62, D-23). |
| D-MSG-01 | Structured JSON replies once no requester parses the text (D-72). |
| D-MSG-02 | As proposed. |
| D-MSG-03 | Outbox: commit, reply, acknowledge (D-35). |
| D-MSG-04 | Replies to the request's `reply_to` (D-73). |
| D-WEB-01 to D-WEB-03 | As proposed. |
| D-WEB-04 | Hidden: admin options 5 and 6 when the transaction type module is absent (D-70). Main menu option 11 keeps its must-preserve "not installed" message (BR-MENU-004), because no catalog item makes it defect-decide. |
| D-NFR-01 to D-NFR-10 | As proposed; D-NFR-10 audit follows D-56. |

Answers to the open questions of the table further down:

| Question | Answer |
| :-- | :-- |
| Q-BILL-002 | Yes, confirmed by the user on 2026-11-06 as "same as batch posting", with the over-limit formula of D-19 kept. A payment is stored as a negative amount and applied by `ledger-core`'s posting primitive, the one POSTTRAN uses: balance, cycle total (`curr_cyc_debit`, where posting puts negative amounts) and category balance `02/0002` (D-28). |
| Q-TRNA-001 | Online adds feed the posting input (D-27): `transaction-service` writes a `core.daily_tran` row, and the next cycle's posting applies POSTTRAN's checks and updates to it, exactly once. Nothing is written to `core.transaction` until then. Path and consequences in [batch.md](batch.md#online-writes-between-cycles). |
| Q-BILL-004 | No ownership check (C1, D-03); the paying user is recorded. |
| Q-JINTC-002, Q-JCTLM-002 | Monthly (D-24). |
| Q-JSTMT-001 | Once per statement period (D-38). |
| Q-JCA7-003 | After the cycle (D-39). |
| Q-JGDGB-001, Q-JREPT-001 | Keep all history, retention by data class (D-40). |
| Q-JEXPT-001, Q-JIMPT-001 | Database export and load instead, unless a receiver is named (D-62). |
| Q-JPDF-001 | One PDF per statement from a template (D-45). |
| Q-AUTH-005 | Outbox (D-35). |
| Q-AUTH-004 | Requests are validated and declined with the existing reason codes (D-32). |
| Q-MQAC-002 | `reply_to` (D-73). |
| Q-ENT-001 | Moved at migration (D-11). |
| Q-ENT-002 | Surrogate key kept (D-59, D-DATA-07). |
| Q-USRL-002 | Standard server-side paging and order (D-54): the `ebcdic_sort_key` column stays only for the legacy mode. |
| Q-SGN-003, Q-SGN-004 | Lockout and neutral message (D-05). |
| Q-JTTYP-001 | One reference store (D-63). |
| Q-JCLOSE-001 | The window covers the same tables, with `WRITES_BLOCKED` (D-BAT-05). |
| Q-RPTS-001 | Asynchronous run with status and download (D-48). |

The Bill Pay slice applies these values in [billpay-slice.md](billpay-slice.md).

## Architecture decisions

| ID | Decision | Choice | Not chosen, and why |
| :-- | :-- | :-- | :-- |
| D-ARCH-01 | Deployable units | One Spring Boot service per bounded context (seven) plus one `batch-service` for every Spring Batch job ([overview.md](overview.md#bounded-contexts)) | A modular monolith would save about 2 GiB of memory, but the brief asks for microservices and the contexts are small enough to stay thin. |
| D-ARCH-02 | Database | One SQL Server database, one schema per context, the core tables shared, each service with its own login and column-level grants ([data-model.md](data-model.md#ownership-and-grants)) | A database per service: Bill Pay, posting, interest and account update must change account, transaction and category balance in one local transaction (INV-001, INV-004, INV-030, INV-043). |
| D-ARCH-03 | Data access | Spring `JdbcClient` with explicit SQL | JPA: lock hints, keyset paging and fixed column lists are part of the behaviour and must stay visible. |
| D-ARCH-04 | Shared rules | In-process libraries under `platform/` (ledger, fidelity, legacy formats, validation, web support) | A shared "ledger service": its calls could not join the caller's database transaction. |
| D-ARCH-05 | The transaction master | All transactions in one table; the legacy master is the rows with `in_master = 1`; TRANBKP and COMBTRAN become a flag update and an `ORDER BY` ([batch.md](batch.md#the-transaction-master-and-cycles)) | Copy tables per generation: they rebuild the GDG design and its relative-generation hazard (INV-052). |
| D-ARCH-06 | Batch cycle | Option F of area G, adapted ([batch.md](batch.md#batch-cycle-option)) | A to E: see the option table in batch.md. |
| D-ARCH-07 | Batch window | A row in `ops.batch_window`, opened and closed by DAG tasks, checked by the services and `ledger-core` | Stopping the services: it would also stop sign-on and the read-only screens with no way to choose the mode of D-BAT-05. |
| D-ARCH-08 | Batch launch | Airflow calls `batch-service`'s internal REST API and waits with a deferrable trigger; Airflow holds no rule | One `java -jar` per task: a JVM start per step and restart state spread over processes. |
| D-ARCH-09 | Report request | `transaction-service` triggers the report DAG through the Airflow REST API; status and download through `transaction-service` | A RabbitMQ queue read by a DAG sensor: more moving parts for one request type. |
| D-ARCH-10 | Messaging | Direct exchanges, durable quorum queues, dead-letter exchange, one RabbitMQ user per requester; the legacy text bodies are the default contract ([messaging.md](messaging.md)) | Streams or topic routing: no consumer needs replay or fan-out. |
| D-ARCH-11 | Sessions | `identity-service` issues RS256 access tokens and rotating refresh tokens in `HttpOnly` cookies; services validate offline against its JWKS | Server-side sessions: they would bring back state between calls. |
| D-ARCH-12 | Screen state | The pseudo-conversation becomes client state plus REST calls; `If-Match` on `row_version`; keyset paging; card numbers never in URLs | Server-held conversation state: it does not survive a restart or a second instance. |
| D-ARCH-13 | Fidelity | One switch per item ID, read through `FidelitySwitches`, recorded in `ledger/fidelity.toml`, checked by `verify-ledger` ([overview.md](overview.md#fidelity)) | Global "legacy" or "fixed" profiles: one decision would change many unrelated behaviours. |
| D-ARCH-14 | Validators | One rule table generates the Java validators and their TypeScript mirror; the server repeats every check | Hand-written twins: they drift. |
| D-ARCH-15 | Traceability | Item IDs as test tags, a generated matrix, a CI gate ([repo-build-ci.md](repo-build-ci.md#traceability)) | A hand-kept matrix: it goes stale. |
| D-ARCH-16 | Local runtime | Infrastructure in compose project `carddemo-modern`; JVMs, Airflow and Vite on the host; ports 28081 to 28090, 28080 and 25173 on 127.0.0.1 | Everything in compose: slower edit-run cycle and more VM memory. |

## Defaults

### Security

| ID | Decision | Default (built if not answered) | Recommended | Catalog | Spec |
| :-- | :-- | :-- | :-- | :-- | :-- |
| D-SEC-01 | Access model | C1: sign-on for every screen and API, roles `ADMIN` and `USER` from the user type, any `USER` works on any account ([api-security.md](api-security.md#the-no-sign-on-defect-inv-006-under-each-choice)) | C2 (linked accounts, extra roles, masking) for production once the business supplies the user-to-account link | D-01, D-02, D-03 (same: sign-on, two roles, staff model for parity) | INV-006, DEF-BILL-009, Q-BILL-004 |
| D-SEC-02 | Identity provider | Local password check in `identity-service` | An OIDC provider when one exists; only `identity-service` changes | - | - |
| D-SEC-03 | Password storage and case | bcrypt hash of the upper-cased password; user IDs and passwords case-insensitive, as today | Case-sensitive passwords with a forced reset at migration | D-04 (same) | DEF-SGN-001, INV-007, BR-SGN-001 |
| D-SEC-04 | Lockout, rate limit, failure message | Off; the two legacy messages stay | On, with a neutral message | D-05 (same) | Q-SGN-003, Q-SGN-004 |
| D-SEC-05 | PII on screens | Shown as today, every view audited | Masked unless the user holds `PII_VIEWER` | D-09 (same) | DEF-ACVW-010, DEF-CRDL-002 |
| D-SEC-07 | New-password rule | Legacy mode: 1 to 8 characters, upper-cased before hashing, different from the current one | Fixed mode (with D-04): 8 to 64 characters, case-sensitive, any printable character, different from the current one, not the user ID; the user-admin screens use the same rule ([billpay-slice.md](billpay-slice.md#new-password-rules-d-sec-07)) | D-04, D-53 (decided: fixed) | DEF-SGN-011 |
| D-SEC-06 | Least privilege for services and jobs | Always on: per-service SQL logins and RabbitMQ users (no legacy mode) | same | D-07 (same) | DEF-JCRDB-003, DEF-MQAC-004 |

### Data

| ID | Decision | Default | Recommended | Catalog | Spec |
| :-- | :-- | :-- | :-- | :-- | :-- |
| D-DATA-01 | Group ID stored at the ZIP position | Loaded as it is; switch DEF-ACUP-002 legacy | Move the value to `group_id` at migration and set the switch to fixed | D-11 (same) | INV-039, Q-ENT-001, DEF-ACUP-002 |
| D-DATA-02 | Card-to-account link held twice | Both copies kept; `verify-data` reports differences | `card_xref` owns the link, `card.acct_id` follows it | D-13, D-61 (same direction) | INV-030, DEF-CRDU-001 |
| D-DATA-03 | `legacy_filler` columns | Kept, for byte-exact export in the differential tests | Dropped after cut-over | - | DEF-POST-010, DEF-BILL-006 |
| D-DATA-04 | CVV | Stored, masked, never returned by an API | Tokenised, or dropped if no function reads it | D-10, D-12 (same direction) | DEF-CRDU-002, DEF-CDRD-001 |
| D-DATA-05 | Transaction ID allocation | Highest master ID plus one, allocated under an application lock inside the inserting transaction, so IDs equal the reference run and duplicates cannot occur | A SQL Server sequence formatted to 16 digits, after cut-over | D-59 (same after cut-over) | DEF-TRNA-003, DEF-BILL-003 |
| D-DATA-06 | Timestamp layouts | Each writer's 26-character layout kept as written | One typed `DATETIME2` column added at cut-over | D-60 (same) | Q-BILL-006 |
| D-DATA-07 | Surrogate key for transactions | `tran_pk`, because archived rows may repeat a legacy ID | same | D-59 adds one only if the data model needs it: it does here | Q-ENT-002 |

### Batch

| ID | Decision | Default | Recommended | Catalog | Spec |
| :-- | :-- | :-- | :-- | :-- | :-- |
| D-BAT-01 | Interest frequency | Every cycle (`interest=always`, the runbook order) | `month_end`: the rate is monthly and Control-M files interest in a monthly folder, which nothing orders (DEF-JCTLM-002) | D-24 (same) | Q-JINTC-002, Q-JCTLM-002 |
| D-BAT-02 | Statement timing | Every cycle, after interest | Once per statement period, on the interest day | D-38 (same) | Q-JSTMT-001, DEF-JSTMT-006 |
| D-BAT-03 | Authorization purge position | After the cycle, never gating posting | same | D-39 (same) | Q-JCA7-003, DEF-JCA7-007 |
| D-BAT-04 | History retention | The master holds one cycle; archived rows kept for five cycles, as the GDG limit | Keep all history visible, with a retention period per data class from the data-handling policy | D-40 (same) | INV-053, Q-JGDGB-001, Q-JREPT-001 |
| D-BAT-05 | Batch window mode | `CLOSED_FILES`: online use of the covered tables fails while the window is open, as today | `WRITES_BLOCKED`: reads keep working, writes return 503 with `Retry-After` | D-18 **differs**: no window at all | INV-002, BR-POST-007 |
| D-BAT-06 | Failure policy | Stop the chain, keep the window open, alert; no automatic retry of a business step | same | D-57 (same) | ERR-JCA7-001, DEF-JAUPG-003 |
| D-BAT-07 | Batch transaction type maintenance (MNTTRDB2) | Built only if a producer of its input file is named; otherwise types are kept online only | same | D-66 (same) | Q-TTBU-003 |
| D-BAT-08 | Master-data reloads | Seed load for new environments only; `carddemo_data_load` refuses to run on non-empty tables outside a local environment | same | D-41 (same) | Q-JACCT-001, DEF-JTCAT-002 |
| D-BAT-09 | Export and import jobs | Not built until a receiver of the file is named | same | D-62 (same) | Q-JEXPT-001, Q-JIMPT-001 |
| D-BAT-10 | Double posting and double interest | Refused: a completed cycle is a completed job instance | same | D-23 (same) | DEF-INTC-005 |

On D-BAT-05: the catalog recommends no window, with snapshot reads and optimistic locking. This proposal keeps blocking writes for the first release, for two reasons. Posting's over-limit check reads cycle totals that earlier records changed (BR-POST-007), so online writes during the run would make the result depend on timing. And the differential and cycle checks need a stable input. `NONE` becomes safe once account update can no longer edit balances (catalog D-08) and online transactions and payments go through posting (catalog D-27, D-28).

### Messaging

| ID | Decision | Default | Recommended | Catalog | Spec |
| :-- | :-- | :-- | :-- | :-- | :-- |
| D-MSG-01 | Inquiry reply format | Legacy 1000-byte text | JSON with an explicit status, once no requester parses the text | D-72 (same) | Q-MQAC-007, DEF-MQAC-014 |
| D-MSG-02 | Authorization request body | Legacy 18-value CSV; JSON accepted as well | same | - | ENT-AUTH-REQUEST |
| D-MSG-03 | Authorization reply and commit order | Legacy: acknowledge, reply, then commit (at most once) | Outbox: commit, then reply, then acknowledge | D-35 (same) | Q-AUTH-005, DEF-AUTH-008 |
| D-MSG-04 | Inquiry reply routing | The fixed reply queue | The request's `reply_to` | D-73 (same) | Q-MQAC-002 |

### Web

| ID | Decision | Default | Recommended | Catalog | Spec |
| :-- | :-- | :-- | :-- | :-- | :-- |
| D-WEB-01 | Component library | Mantine with the app's own design tokens | same | - | - |
| D-WEB-02 | Error display | One message at a time, in the legacy edit order, cursor on the field | same for the first release | - | the equivalence contracts |
| D-WEB-03 | 3270 function-key shortcuts | Off; each user can switch them on | same | - | - |
| D-WEB-04 | Options of modules that are not installed | Shown, with the legacy "coming soon" message | Hidden | D-70 (same) | BR-MENU-005 |

### Non-functional requirements

| ID | Decision | Default |
| :-- | :-- | :-- |
| D-NFR-01 | Online response time | 95th percentile under 300 ms per API call on the laptop with the sample data |
| D-NFR-02 | Batch duration | The daily cycle on the sample data in under 2 minutes; posting the stress data in under 1 minute |
| D-NFR-03 | Availability | One instance of each process locally; in a deployment two instances of each online service and one `batch-service` |
| D-NFR-04 | Observability | JSON logs with a correlation ID, Actuator health and Micrometer metrics; no PII in logs (catalog D-10) |
| D-NFR-05 | Encryption at rest | None locally; Transparent Data Encryption in every deployed environment, Always Encrypted for SSN and card number if compliance requires it |
| D-NFR-06 | Time zone and business date | The business date comes from the cycle; timestamps in the configured zone (`carddemo.zone`, default the system zone), as today |
| D-NFR-07 | Browsers | Current and previous major versions of Chrome, Edge, Firefox and Safari |
| D-NFR-08 | Accessibility | WCAG 2.2 AA |
| D-NFR-09 | File storage | `modernization/var/` locally; object storage in a deployment |
| D-NFR-10 | Audit | Changes and views of unmasked PII are audited (the legacy system has none; catalog D-56) |

## Open questions that change the architecture

| Question | Default assumed here | What changes with another answer |
| :-- | :-- | :-- |
| Q-BILL-002 (DEF-BILL-010) | A payment changes only the balance | Bill Pay also updates cycle totals and the category balance: a grant on `core.tcat_bal` and a posting primitive in `ledger-core` (catalog D-28). |
| Q-TRNA-001 | An online transaction is only inserted | Online adds feed posting: `transaction-service` writes to the posting input instead (catalog D-27). |
| Q-BILL-004 | Any user may pay any account (C1) | Ownership check: table `iam.user_account` and a data feed for it (C2). |
| Q-JINTC-002, Q-JCTLM-002 | Interest every cycle | The `interest_due` branch and its calendar. |
| Q-JSTMT-001 | Statements every cycle | The statement task moves to the statement-period branch. |
| Q-JCA7-003 | Purge after the cycle | The purge task moves before posting (`purge_position`). |
| Q-JGDGB-001, Q-JREPT-001 | Five generations | The retention task and the run-folder clean-up. |
| Q-JEXPT-001, Q-JIMPT-001 | No file receiver | Two jobs and two DAGs are built. |
| Q-JPDF-001 | No PDF | The PDF writer of `statementJob` and its layout. |
| Q-AUTH-005 | At most once | The outbox and processed-message tables. |
| Q-AUTH-004 | Unparsable request dead-lettered | A decline reply path. |
| Q-MQAC-002 | Fixed reply queues | Reply routing by `reply_to`, and per-requester reply queues. |
| Q-ENT-001 | Group ID left at the ZIP position | A migration step that moves it. |
| Q-ENT-002 | Surrogate transaction key | Without it, the master and archive need separate tables. |
| Q-USRL-002 | EBCDIC list order | The `ebcdic_sort_key` column can go. |
| Q-SGN-003, Q-SGN-004 | No lockout | The lockout columns and a rate limiter in `identity-service`. |
| Q-JTTYP-001 | One reference store | A VSAM-shaped export for coexistence (TRANEXTR) would be needed. |
| Q-JCLOSE-001 | The window covers the five files CLOSEFIL closes | More or fewer tables under the window guard. |
| Q-RPTS-001 | Report downloaded from the web app | Delivery by mail or a file share. |

## Architecture-dependent fidelity items

The legacy behaviour comes from a mechanism the target does not have. No legacy mode exists; the business accepts the fix by approving this list, and each item gets a deviation entry in `ledger/deviations.toml`.

| Item | Legacy behaviour | Why it cannot be kept | Target |
| :-- | :-- | :-- | :-- |
| DEF-BILL-001 | Balance changed without a payment record when a write fails | One database transaction | All or nothing |
| DEF-BILL-003, DEF-TRNA-003 | Two concurrent adds get the same transaction ID | ID allocation under a lock in the inserting transaction | Unique IDs |
| DEF-ACUP-005 | Account saved, customer not | One database transaction | Both or neither |
| DEF-USRU-007 | Lost update between two administrators | Stateless REST: the version the user saw travels as `If-Match` | `409` with the legacy "Record changed" text |
| DEF-POST-008 | A record half applied after an abend | Chunk transaction with the reader position | Restart resumes cleanly |
| DEF-INTC-009 | Interest rows and the account update not atomic | Same | Committed together |
| DEF-INTC-005 | A rerun charges interest twice | Job instance identity | Rerun refused (D-BAT-10) |
| DEF-AUTH-001, 002, 003, 004, 009, 013 | Later messages in one CICS task reuse the state of earlier ones | A listener handles each message with fresh state | Each decision uses its own request |
| ERR-AUTH-003, DEF-MQAC-012, DEF-MQDT-009 | A bad message blocks the queue | A listener does not stop on one message | Dead-lettered |
| DEF-MQAC-003 | Reply inherits MQ descriptor fields | AMQP has no descriptor inheritance | Properties set explicitly |
| DEF-TRPT-002 | Selection and heading dates can differ | One date pair feeds both | Cannot occur |
| INV-052, Q-JCOMB-001 | A report between TRANBKP and COMBTRAN loses the day | No generations; one table | Cannot occur |
| DEF-JSTMT-006 | CA-7 runs statements in parallel with posting | The DAG orders statements after posting and interest | Statements see the posted day |
| DEF-JAUPG-003 | Purge RC 16 may count as normal completion | Exit statuses replace return codes | `FAILED` |
| DEF-JCA7-005 | CA-7 triggers loop | DAGs are acyclic | One run per cycle |
