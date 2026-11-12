# authorization-service: architect rulings on the contract

Status: **approved with the rulings below**, 2026-11-10; contract v2 approved in [section 7](#7-contract-v2-and-its-remaining-questions-2026-11-10). The contract first reviewed was the backend's authorization-service contract v1. It covers four units: AUTH (COPAUA0C, the listener), AUSM (COPAUS0C), AUDT (COPAUS1C) and AUFR (COPAUS2C).

Coding may start now. Backend writes these rulings into the contract as v2 before the first unit merges. A ruling here overrides the contract where the two differ. The check against the specs and the ledger found no code switch in these units and no conflict with D-31 to D-36, D-MSG-02, D-MSG-03 or switches.md.

## 1. Listener (AUTH)

- **Request fields (BR-AUTH-001, must-preserve).**
  - The processing code (6) and the POS entry mode (2) receive the text right-aligned as digits: padded with zeros on the left and cut on the left. For example, `1` becomes `01` and `123` becomes `23`.
  - Every other text field is padded with spaces and cut on the right, as the contract says.
  - The decision checks still run on the raw value.
- **Amount (BR-AUTH-002, D-32).**
  - An amount longer than 13 characters after trimming is invalid. So is one with more than two decimals, or any other text that fails the contract's pattern. Each of these is declined `9000` (question 1, approved).
  - The legacy truncated such amounts. D-32 decided "reject invalid amounts", and the ledger gets a `fixed` entry for BR-AUTH-002 under D-32.
  - An invalid amount is stored as `0.00`.
- **Decline order:** 3100, 4100, 4200, 4300, 5100, 5200, 9000 (BR-AUTH-007), first condition that holds. The 3100 conditions "card value not exactly 16 digits" and "no `core.card` row" are approved as part of "unknown card" (D-31, D-32).
- **5100 and 5200 (question 2):** approved. 5100 means a confirmed `F` fraud row for the card. 5200 stays reserved and is never set until the business defines merchant fraud.
- **Account expiry (question 3):** only `active_status` gives 4300. D-32 names account status, not account expiry.
- **Request expiry (question 4):** stored, not compared. The card's own `expiration_date` gives 4200.
- **JSON reply (question 5):** approved. A JSON request gets the JSON reply with trailing spaces trimmed, and a CSV request gets the 63-character reply ([messaging.md](messaging.md#authorization-request-and-reply-cp00)).
- **Code without a detail (question 6):** `000000`.
- **Authorization code (question 10):** approved as the column grant `UPDATE (auth_id_code)` on `pauth.auth_detail`. The code is set in the same transaction as the insert. A test checks that it equals the six rightmost digits of `auth_id`.
- **Commit, reply, acknowledge (D-35, D-MSG-03):** approved as drafted.
  - The listener publishes the reply after the commit, with confirms, then acknowledges the request.
  - An unconfirmed reply is sent again only by the redelivery of its own request. There is no timer relay.
  - A request dead-lettered after 6 deliveries (the first plus 5 returns, [messaging.md](messaging.md)) leaves its outbox row unsent, and `pauth_outbox_unsent` alerts. The reply's 5-second lifetime has passed by then anyway.
- **Unparsable requests:** dead-lettered with no reply and no write. The citation is messaging.md, not the open-questions default of decisions.md. ERR-AUTH-003 cites its deviation DEV-ARCH-09.
- **Limits wider than the summary (question 12).** The account-update ruling allows limits of up to 10 integer digits ([account-service.md](account-service.md#3-acup-account-update)). A summary write for such an account would fail, and the request would reach the dead-letter queue with no reply. So:
  - Backend migration `V504` widens `pauth.auth_summary.credit_limit` and `cash_limit` to `DECIMAL(12,2)`, as in `core.account`.
  - It widens `credit_balance` and `cash_balance` to `DECIMAL(15,2)`, as the running totals.
  - The 4100 condition `credit_balance + amount > 999999999.99` is dropped.
  - The loader is unchanged, because the old values fit.
  - V504 also adds `auth_detail.fraud_changed_by` and `auth_fraud.reported_at` if V500 to V503 do not already have them. Backend confirms which.

## 2. Screens (AUSM, AUDT, AUFR)

- **Card number on the detail (question 7):** masked, with no unmask endpoint. D-09's items do not include the authorization screens. An `ADMIN` reveals a card number on card view ([account-service.md](account-service.md#access-admin-reads-user-writes-user-decision-on-d-09)). The authorization endpoints stay `USER` only.
- **Detail not found (question 9):** `Authorization not found...` is approved.
- **Messages without codes.** D-58's question covers every screen message. Its item list names only account view, so the ledger gets `fixed` entries under D-58 for the ERR-AUSM and ERR-AUDT texts that drop codes. The ERR-AUFR texts are incidental and need no entry. The set, confirmed against the ledger on 2026-11-10:
  - **D-58 `fixed`:** ERR-AUSM-001 to ERR-AUSM-005, ERR-AUDT-002, ERR-AUDT-003, ERR-AUDT-004 and ERR-AUDT-006.
  - **ERR-AUSM-004** has two parts. Its summary-read part is reachable and drops the status code (D-58). Its PSB-scheduling part cannot occur, because there is no PSB; a connection failure takes the summary-read path. The note says both.
  - **ERR-AUDT-001 is architecture-dependent, not D-58.** It is only the PSB-scheduling failure (COPAUS1C §10). No PSB exists, so the path cannot occur. Its must-preserve outcome, an error message and no detail shown, is kept by ERR-AUDT-002's path: a connection or read failure gives `500 AUTH_DETAIL_READ_FAILED` and ` System error while reading Auth Details`.
  - **ERR-AUDT-004 is intended in the set.** Its must-preserve part is that a failed fraud write is reported and nothing is saved (COPAUS1C §10, `SYNCPOINT ROLLBACK`). The text it shows is COPAUS2C's, which is incidental (ERR-AUFR-001, ERR-AUFR-002, NUM-AUFR-003). The detail and the fraud row commit together now, so the one failure path shows ` System error while FRAUD Tagging, ROLLBACK` (the ERR-AUDT-006 text without the status) and rolls both back. That the stored status is shown afterwards is DEF-AUDT-003 (`fixed`, D-75). The note names the text shown, not only the dropped Db2 `CODE` and `STATE`.
- **Customer on the fraud row (BR-AUFR-002, must-preserve).** `cust_id` is the customer the screen shows: the lowest card's customer for the account, read from `core.card_xref` (D-61). It is not the summary's `cust_id`.
- **A fraud mark that conflicts** gets `409 RECORD_CHANGED`, and the user confirms again on the fresh view. This replaces the retry that the d33 brief allows for batch writers.
- **Backward paging and `If-Match` on the fraud POST:** approved.

## 3. Purge and fraud (question 11, data and batch)

- The purge's `DELETE` of a detail repeats `auth_fraud <> 'F'`.
- The purge releases credit from the state of the row it deleted (`OUTPUT deleted.match_status, deleted.approved_amt`), not from an earlier read. A fraud mark or a D-33 match committed between its read and its delete therefore can neither be lost nor released twice.
- This is recorded in [data-model.md](data-model.md#pending-authorizations-pauth).

**Purge job rulings (2026-11-10).** These confirm or correct the batch expert's assumptions for `purgePendingAuthorizationsJob`. They follow from D-33, D-34, D-10 and D-40, so no user decision is needed. Data owns the integration.

- **Today is the cycle's business date.** The age is counted from the `businessDate` of the `batch.cycle` the run belongs to, never from the wall clock. A purge that is cleared and rerun on a later day uses the same date.
- **Age in calendar days (D-34, DEF-AUPG-004).** A detail qualifies when `CAST(processed_at AS DATE) <= DATEADD(day, -expiryDays, businessDate)`, which is "age ≥ expiry days" (BR-AUPG-002) with a correct year end. `processed_at` is compared as stored, with no time-zone conversion, as in D-33 matching. A detail dated after the business date never qualifies.
- **`expiryDays`** is an integer of at least 1, with default 5 (D-34). 0 is rejected, because it is the defect D-34 fixes (DEF-AUPG-001). There is no maximum: no decision sets one, and the legacy 2-digit field is a card format. The job checks at start that `businessDate − expiryDays` is a valid date, and fails with an invalid-parameter error if it is not. The same value feeds the D-33 matching window.
- **Each summary locked before its details are read.** The database runs with `READ_COMMITTED_SNAPSHOT ON`, and the listener locks the summary row before it inserts a detail. For each summary, the purge runs these steps in order, inside the chunk's transaction:
  1. Lock the summary row (`UPDLOCK, HOLDLOCK`).
  2. Delete its expired details that are not flagged `F` (with `OUTPUT deleted.*`).
  3. Apply the deleted rows to the totals (data-model.md).
  4. Delete the summary if no detail is left, with the `NOT EXISTS` read under `READCOMMITTEDLOCK`.

  A detail that the listener committed before the lock is therefore seen. The summary delete never removes a detail: since `V507` the foreign key has no cascade (below). When the listener finds its summary gone after the purge commits, it creates a new one with `credit_balance` 0, as for an account that has no summary.
- **Chunks of `commitInterval` summaries (corrects "one summary per transaction").** The purge step is a chunk step. `commitInterval` is the number of summaries in one chunk transaction, as the batch execution contract already lists it. It is not a second setting.
  - **Property:** `carddemo.batch.pauth.commit-interval`, an integer of at least 1, with default 10. Data owns the default. A chunk holds the locks of all its summaries until it commits, and each one delays an authorization request on that account. The default is small for that reason. The count alone does not bound the delay, because a chunk's duration also depends on how many details its summaries have. No requirement sets an authorization latency during the purge. The only time limit is the reply's per-message TTL of 5000 ms ([messaging.md](messaging.md), the same as legacy). A reply that the purge delays past it expires unread, as a late legacy reply does. The data is still committed, and a resent copy of the same request gets the stored reply from `processed_message`. BR-AUPG-005 makes only "work is committed in units" must-preserve, and its frequency incidental.
  - **Measured, not claimed:** the expert measures chunk duration and the listener's added delay on a heavy fixture at the default interval, and records the volumes tested (summaries per chunk, details per summary) with the results in the job's test notes. The docs claim only what was measured. If the measured delay comes near the TTL, data lowers the default. No new timeout or latency target is added.
  - **Lock order:** the reader returns summary keys in ascending `acct_id` (keyset, no lock held while reading), and the steps run in that order, so the purge takes its summary locks in ascending order. A listener transaction holds one summary lock, so it cannot form a cycle with the purge.
  - **Concurrent purges are safe; no single-purge guarantee is claimed.** `max_active_runs=1` covers only one DAG. Purges of different cycles or attempts are different job instances, so the internal API or a second DAG run can start one while another runs. Correctness does not depend on preventing that, and no global lock is added:
    - Both purges take summary locks in ascending `acct_id`, so they cannot deadlock with each other. The second waits on the first's lock, then reads the committed result.
    - Each detail is subtracted only by the purge whose `DELETE` removed it (`OUTPUT deleted.*`). A detail can therefore not be released twice, and a summary is deleted once.
    - The message-row step deletes rows by age only, so two runs delete the same set between them.
    - The run totals split between the two runs. Their sum equals one run's totals.
  - **Transactions:** the chunk commits through the fencing transaction manager. There is no manual transaction, no `REQUIRES_NEW` and no commit inside the step.
  - **Failure:** a deadlock or lock failure fails the chunk, which rolls back, and the job is `FAILED` with no retry inside the step (D-57). A restart resumes after the last committed chunk ([batch.md](batch.md)).
  - **Message rows** are deleted in their own step after the summaries, never inside a summary chunk. That step uses the same `commitInterval`, counted in rows.
  - **Tests:**
    - commit interval 1 and an interval above 1;
    - the foreign-key assertion: a summary delete with a detail left fails and changes nothing;
    - a listener request on an account inside an open chunk, which waits for the chunk's commit and then sees the purge's result;
    - two purge executions (different job instances) started together on one fixture. They end with no deadlock, every expired detail is deleted and subtracted once, and the final state and the summed totals equal one run's;
    - the heavy-fixture measurement above.
- **An empty summary is deleted whatever its stored totals (D-34).** "Delete a summary only when it has no details left" is the whole rule. Non-zero totals on an empty summary come only from loaded legacy totals that were never written back (DEF-AUPG-003). Under D-33 a pending amount with no detail is released, and deleting the summary is that release. This is not a silent write: the run counts these deletes as "summaries deleted with non-zero totals", next to BR-AUPG-006's four totals. The count carries no account number (D-10). It neither fails the run nor alerts.
- **Message rows.** `processed_message` (by `received_at`) and sent `outbox` rows (by `created_at`, `sent_at IS NOT NULL`) are deleted with the same `expiryDays`. Unsent outbox rows are never deleted, and `pauth_outbox_unsent` alerts on them. These timestamps are UTC (`SYSUTCDATETIME()`), so their cutoff is the earlier of `businessDate − expiryDays` and the database's UTC date minus `expiryDays`. A business date ahead of the clock then never deletes a fresh idempotency row. The duplicate window, a few redeliveries of a 5-second request, is far shorter than one day, so the day granularity is safe. There is no separate retention contract for these rows. A separate retention period is a later configuration change if operations ask for one (D-40).
- **Loader.** Approved: the `pauth` load step runs fenced under the job's transaction manager, with all its writes in transactions. A reload removes rows with `DELETE`, each summary's details first and then the summary (no cascade since `V507`), never `TRUNCATE`. The `IDENTITY` is never reseeded (IDs are never reused), and `auth_fraud` is never deleted (`batch_svc` has no grant on it). The purge and the loader need no new grant: data-model.md's `batch_svc` grants cover them. The one migration is `V507`, below.
- **No cascade from summary to detail (decided 2026-11-10).** `FK_auth_detail_summary` becomes `ON DELETE NO ACTION` in `V507` (authorization owner, `pauth` range, above `V500` to `V506`). The evidence on the real `1205` deadlocks showed the cause: even when a summary had no detail left, the cascade's range scan on `pauth.auth_detail` took next-key `RangeS-U` locks that reached into other accounts' key ranges, so a purge chunk and a listener insert on a neighbouring account could deadlock despite the summary lock order. Every summary delete therefore deletes that summary's details explicitly first, in the same transaction: the purge in step 2 (and the summary is deleted in step 4 only when none is left), and the reload before each summary. The foreign key still stops a summary delete that would orphan a detail (error 547, which fails the chunk). Its check seeks the index that leads with `acct_id`, so it locks only that account's keys. Tests: a summary delete with a detail left fails and changes nothing; the concurrency test of a purge chunk and listener inserts on neighbouring accounts runs with no `1205`; and the catalog comparison of fresh and upgraded databases ([identity-service.md](identity-service.md#pilot-before-the-user-admin-code-depends-on-the-widths)) shows `NO ACTION` both ways.

## 4. Item table (§12)

- Add the 12 missing items:
  - NUM-AUTH-004 and SE-AUTH-003, SE-AUTH-004 and SE-AUTH-006
  - FLOW-AUSM-001 to FLOW-AUSM-003
  - FLOW-AUDT-001 to FLOW-AUDT-003 and VAL-AUDT-001
  - FLOW-AUFR-001
- Remove the duplicate rows of ERR-AUTH-009 and DEF-AUTH-006, and cite D-32 for DEF-AUTH-006.
- DEF-AUTH-009 and DEF-AUTH-013 cite DEV-ARCH-08 (decision none), not D-31.
- NUM-AUTH-002 is "kept, without the digit loss" (no decision). NUM-AUTH-001 follows BR-AUTH-002.
- FLOW-AUTH-001 and FLOW-AUTH-002 keep their must-preserve parts: one commit per request. The triggered start and the loop limits are architecture-dependent.

## 5. Ledger changes requested

An entry whose item keeps an outcome of its own gets `retained` and `requires_test = true`, so the kept part stays tested ([transaction-service.md](transaction-service.md#9-partial-deviations-what-stays-tested)). ERR-AUDT-001 is one.

The ledger owner makes these changes, routed by the orchestrator.

- **New `fixed` entries:**
  - BR-AUTH-002 (D-32)
  - the ERR-AUSM and ERR-AUDT texts that drop codes (D-58), the set listed in [section 2](#2-screens-ausm-audt-aufr)
- **Changed notes:** ERR-AUSM-004 (both parts) and ERR-AUDT-004 (the text shown), as in section 2.
- **New architecture-dependent entries:**
  - DEF-AUTH-010, DEF-AUSM-001 and DEF-AUDT-004 (state between requests)
  - DEF-AUSM-009 (no page-number table)
  - ERR-AUDT-001 (no PSB to schedule), replacing its D-58 entry
  - FLOW-AUTH-001 (triggered start)

## 6. Character set of the request fields (2026-11-10)

The text fields of a request follow the policy in [data-model.md](data-model.md#text-repertoire-and-width). This replaces the contract's sentence "Characters that the `Latin1_General_100` columns cannot hold are stored as `?`": under the database's UTF-8 collation those columns hold bytes, so an accented value at full width failed the insert instead.

- The body is still decoded as strict UTF-8, and undecodable bytes still make the request unparsable.
- Every character from U+0000 to U+00FF is stored as received. Each code point above U+00FF is replaced by one `?` in the service, before the field is cut to its width; an emoji (a surrogate pair) becomes one `?`, not two. The width then counts characters of the range. The request is never refused for its text, because the fields are stored as received (BR-AUTH-001). The decision checks still run on the raw value. A replacement is logged at info with the message ID and the field names, never the value.
- Backend migration `V505` (confirmed by backend) makes the request text columns of `pauth.auth_detail` and `pauth.auth_fraud` Unicode at the same widths, and `pauth.processed_message.reply_body` (`NCHAR(63)` BIN2) and `pauth.outbox.body` (`NVARCHAR(1000)` BIN2) too, because the reply repeats the card value and the transaction ID. Masks and grants stay. The two `pauth` user-ID columns (`fraud_changed_by`, `reported_by`) change in `V505` too; the card number and transaction ID columns change in stage 2, in backend's `V506` ([data-model.md](data-model.md#migrations)).
- Tests: a CSV request with a 22-character merchant name of `É` is processed and stored unchanged; a JSON request with `中` in the merchant city is stored with `?` there and logs only the field name; `😀` in the merchant name becomes one `?`; a transaction ID holding U+0085 comes back unchanged in the reply.

## 7. Contract v2 and its remaining questions (2026-11-10)

Contract v2 writes the rulings above into v1 and is **approved**, with the answers below. Its implementation sequence (§14, steps 1 to 8) is approved in that order, and step 1 may start now. The checks found nothing in v2 that departs from sections 1 to 6. Two points hold as written:

- The fraud audit row uses `action = 'UPDATE'`, which `CK_audit_action` allows. A `FRAUD_MARK` action would break the check.
- Account IDs and card numbers are cast to `CHAR(11)` and `CHAR(16)` in the parameter only, after the strict digit check, and `sendStringParametersAsUnicode` stays on. When `V506` makes `card_num` `NCHAR(16)`, the card cast in the same commit follows the new column type.

| # | Question | Ruling |
| :-- | :-- | :-- |
| B1 | Batch window | No `BatchWindowGuard` call in the listener or the fraud endpoint. [batch-execution-contract.md](batch-execution-contract.md) section 6 names the exception: `WRITES_BLOCKED` does not cover `pauth`, and where that rule and a general brief differ, the rule wins. The listener's reads of `core.card_xref`, `core.account`, `core.customer` and `core.card` and the fraud write's `ops.audit` row stay allowed during a window. Concurrency with the purge and the D-33 match uses row locks only (contract §6.5). |
| B2 | `UPDATE (auth_id_code)` grant | Backend adds `auth_id_code` to the existing column grant on `pauth.auth_detail` in `R__grants_authorization.sql`, in the `V505` commit, as [data-model.md](data-model.md#ownership-and-grants) lists. Only that one column is added. This service does not edit the file. |
| B3 | Tests that need `V505` | The service merges backend's `V505` commit once it exists. Until then M30 to M33, and any test that stores a full-width accented value, fail. They are never skipped or tagged pending, so the gap stays visible. |
| B4 | RabbitMQ provisioning | Infra creates the virtual host `carddemo` (named in [messaging.md](messaging.md); compose defines only the default user on `/`, so no vhost is derived from it) and the user `authorization_svc`. It works create-only: an existing vhost, user, password or `.env` value is kept and never reset, and this work stays separate from the JWT key rotation. Permissions are the narrowest that work, as tested on the broker: the configure, write and read expressions in [messaging.md](messaging.md#topology) (corrected 2026-11-10; the first set here lacked read on the queues being declared with a dead-letter exchange and write on the queues being bound). At start the service declares exactly the topology of messaging.md through `RabbitAdmin`, with declaration errors not ignored. A name that exists with other arguments fails the start. The service never deletes, purges or redeclares an existing object with other arguments. The listener tests' private vhosts are created and deleted with the admin login from `.env`, and nothing global is touched. |
| B5 | Reply TTL and the purge | Recorded, with no limit claimed on a purge chunk's duration. When a chunk holds the summary lock longer than 5 s, the reply expires unread and the committed decision goes back to a resent request from `processed_message` (contract §6.3). That is the normal path, not a failure. |
| B6 | OpenAPI snapshot | Approved: one new file, `modernization/web/openapi/authorization-service.json`, with no existing web file changed. The web owner generates its client from it when the screens are built. |
| B7 | Feature flag | `carddemo.identity.features.authorization` stays `false` in every profile until the service's API and its web screens are both merged. A backend merge alone does not enable it. Enabling it, and passing the RabbitMQ credentials to the service, are later identity and infra changes. |

## 8. Build rulings for steps 4 to 6 (2026-11-10)

These answer the points the build raised for the listener (step 4, commit `361b24e`), the summary and list (step 5) and the detail and fraud toggle (step 6). None is a user question: each follows from the specs, the contract or a decided catalog entry.

**Queue arguments: confirmed, with one correction.** The declared topology matches [messaging.md](messaging.md#topology). `carddemo.pauth.request` and `carddemo.pauth.reply` each have `x-delivery-limit: 5`, dead-letter exchange `carddemo.dlx` and a dead-letter routing key equal to their own name. That key must be set: without it the broker would use the message's routing key (`request`), which the DLQ binding does not match. Only the request queue has a DLQ binding, so a reply that expires after 5 seconds, or is returned more than 5 times, is dropped; that is the reply's lifetime. `carddemo.pauth.request.dlq` is a durable quorum queue with no arguments. The correction: on RabbitMQ 4.3 and later (the stack pins 4.4.0), `basic.nack` with requeue does not count toward `x-delivery-limit`; only `basic.reject`, a lost channel or connection, or a crash do. The listener's `basicNack(tag, false, true)` (SQL failure, reply not confirmed) would therefore retry forever and never dead-letter. It becomes `basicReject(tag, true)`. M22 and M24 must run on the broker before step 4 is called done, and M24 must show exactly 6 deliveries and then the DLQ: `x-delivery-limit: 5` dead-letters after more than 5 returns, so the first delivery plus 5 returns is 6 (corrected 2026-11-10; the limit stays 5).

**Retry back-off (ruled 2026-11-10).** `carddemo.pauth.request` also carries the delayed-retry arguments in [messaging.md](messaging.md#topology) (`x-delayed-retry-type: failed`, min 200 ms, max 1000 ms). The listener's `basicReject(tag, true)` on a SQL failure is unchanged; the broker now holds each return for 200 to 1000 ms, so the 6 deliveries span about 3 s instead of a few milliseconds and a short database outage no longer dead-letters requests. The listener does not sleep before rejecting. M24 additionally asserts the gaps between deliveries, the total under 5 s and that another message is delivered while one is held, and the provisioning test checks the three arguments on the declared queue.

**Sent mark failing after a confirmed reply: confirmed.** The listener still acknowledges. The broker has confirmed the reply, so the requester has its one answer; a requeue would make the redelivery send a second one. The row stays with `sent_at` null and `pauth_outbox_unsent` counts it. Because the purge never deletes unsent rows, the gauge stays raised until an operator acts, so the listener logs a warning event with the message ID and `outbox_id` (no body, no card number) saying the reply was confirmed but not marked. The operator sets `sent_at` after checking that log line. There is no automatic retry and no relay.

**Step 5.**

- **Name: confirmed.** `name` is BR-AUSM-001's value: the first word of the first name, a space, the first character of the middle name, a space, and the first word of the last name. A blank middle name has a space as its first character, so first and last name are then three spaces apart. The API does not cut the name at 25 characters. Under DEF-AUSM-004 the API returns the full value, and the screen width belongs to the web, whose test covers the cut at 25 and shows the spaces as they are. "Whole words" in the contract's §7 means that no word is cut by the API. It does not mean full names.
- **Address: corrected.** The separator is `,` with no space, as BR-AUSM-001 states for both lines: `addr_line_1,addr_line_2` and `addr_line_3,state,ZIP5`. No decision changes it, and BR-AUSM-001 is kept. Each value loses only its trailing spaces. The legacy cut at the first double space and at 25 characters is DEF-AUSM-004 display truncation, which the API does not apply. The `", "` in the contract's §7 example is corrected by the contract owner.
- **A failure before any read** (the read-only transaction or the connection cannot start) gets the endpoint's own read-failure code and text, as the contract's §7.2 says for the summary ("connection failure takes the summary-read path"). The summary reports `AUTH_SUMMARY_READ_FAILED`, the list and the detail `AUTH_DETAIL_READ_FAILED`, whichever table the endpoint reads first. A test covers it for each endpoint.
- **Fraud toggle.** A start failure comes before the step-2 locking read, so it is `500 AUTH_DETAIL_READ_FAILED`. A failure in the write steps, the commit included, stays `500 FRAUD_UPDATE_FAILED` with everything rolled back.

**Step 6.**

- **Locking read of the fraud toggle fails: `500 AUTH_DETAIL_READ_FAILED`.** Confirmed. Nothing has been written at that point, legacy PF5 reads the detail first (ERR-AUDT-002, without codes per D-58), and `FRAUD_UPDATE_FAILED` stays for the write steps 4 to 7. Every AUDT and AUFR problem has `field: null`, because the screen has no input field.
- **`reportDate` is an ISO-8601 date** (`2026-11-10`). Confirmed. The legacy `MM/DD/YY` form appears only in `display` (`F-11/10/26`, BR-AUDT-004).
- **The report date is `LocalDate.now(clock)` in `carddemo.zone`.** Confirmed (contract §1 Time, D-NFR-06). One value is used for the detail and the fraud row (DEF-AUFR-004 fixed). The test uses a fixed clock at a moment when the zone's date and the UTC date differ.

The contract owner records these in the contract's §4, §6, §7 and §9.
