# inquiry-service: architect rulings on the contract

Status: **approved with the rulings below**, 2026-11-11, amended the same day on two points: a request without a `message_id` (section 1.4, section 2), and the crash bound of the error record, which is stated as a limit and a deviation rather than an absolute guarantee (sections 1.1, 1.5, 6, 7). The contract reviewed is the inquiry-service agent's contract v4. It covers two units in one service: MQAC (COACCT01, CDRA, account inquiry) and MQDT (CODATE01, CDRD, date inquiry).

Coding may start once the orchestrator relays this approval. The agent writes these rulings into the contract as v5 before the first unit merges. A ruling here overrides the contract where the two differ. The check against the specs, the decision catalog and switches.md found three code switches (DEF-MQAC-002, DEF-MQDT-002, DEF-MQDT-003), no new switch, and one gap in messaging.md: its fixed column for DEF-MQAC-007 left out the error reply that D-74 decided. That gap is closed here and in [messaging.md](messaging.md#inquiry-reply-routing-and-errors).

Everything in the contract that is not named below is approved as drafted: the topology names, permissions, body forms, the 1000-byte layouts and the overpunch, the read and its cast parameter, the date replies, metrics, privacy rules and the implementation sequence.

## 1. Failed requests, error records and the error reply (Q3, D-74)

The source requirement stands as stated in the contract: a failed account read leaves one error record on the error queue (ERR-MQAC-005, scenario S7, SE-MQAC-004, must-preserve). D-74 adds an error reply to the requester and a retry before the dead-letter. The contract's limit (no record when the service dies on the last broker delivery) is **not** accepted as drafted. The mechanism below makes the handled give-up deterministic: a confirmed record, then the error reply, then an explicit dead-letter. It also narrows the crash case to a finite, stated limit (section 1.5). That remaining limit is an intentional architecture choice. It is recorded as a deviation with its reasoning, for the product delegation to ratify. It is not presented as "no choice".

### 1.1 The service gives up, the broker is only a backstop

- **The service decides the last attempt.** Every failure path reads `x-delivery-count` (absent on the first delivery, so 0). When the count is below 5, the service retries with `basic.reject(requeue = true)`. When it is 5 or more, the service runs the give-up path of that failure (1.2) and ends with `basic.reject(requeue = false)`. In a run without a crash, a failing request therefore still gets exactly 6 deliveries, spaced 200, 400, 600, 800 and 1000 ms, and then reaches its `.dlq`, as messaging.md describes.
- **The broker limit is raised on the two inquiry request queues.** `carddemo.inquiry.account.request` and `carddemo.inquiry.date.request` are declared with `x-delivery-limit 10` instead of 5. All other arguments are unchanged, as are the reply queues and `carddemo.pauth.request`. The broker's own dead-lettering is now only a backstop for deliveries that keep dying before the service can act. It is reached only after 11 deliveries, and the delayed retry holds each of returns 6 to 10 for 1000 ms.
- **Why this narrows the limit.** In the contract's design, a death on the sixth delivery was the sixth counted return, so the broker dead-lettered the request without another delivery and no record was written. Now that death is followed by a seventh delivery, which still has a count of 5 or more, so it runs the give-up path again. A request ends in its `.dlq` with no error record only if every delivery from the sixth to the eleventh dies, or fails to get its record confirmed, before the reject. In that case the broker log, the `.dlq` and the `M006` log lines show the cause. The backstop is finite on purpose: without it, a message that crashes the service on every delivery would be redelivered forever and block nothing else, but would never leave the queue.
- **No counter of the service's own and no store.** The count comes from the broker header. The service stays read-only (section 10 of the contract).

### 1.2 The give-up path for each failure

| Failure | Give-up path, in this order |
| :-- | :-- |
| Database failure on the account read (ERR-MQAC-005, DEF-MQAC-007) | 1. Publish the error record `ERROR WHILE READING ACCTF` and wait for its confirm. 2. Publish the error reply (1.3) and wait for its confirm. 3. `basic.reject(requeue = false)`. |
| Reply not confirmed (ERR-MQAC-006, ERR-MQDT-005) | 1. Publish the error record `MQPUT ERR` and wait for its confirm. 2. `basic.reject(requeue = false)`. No error reply, because the reply path is the thing that failed. |
| Any other exception (ERR-MQAC-009, ERR-MQDT-008) | `basic.reject(requeue = false)`. No record and no reply, as drafted. |
| Oversized request (ERR-MQAC-004, ERR-MQDT-004, DEF-MQAC-012, DEF-MQDT-009) | On whichever delivery finds it oversized, without waiting for the count: publish the error record `INP MQGET ERR:` with a confirm, then `basic.reject(requeue = false)`. No reply. |

- **The error record is confirmed before the request goes.** When a record is refused, not confirmed within 5 s, or its channel is lost (ERR-MQAC-007, ERR-MQDT-006), the service logs `M006` and rejects with `requeue = true`. It does not dead-letter the request. The next delivery runs the give-up path again, until the broker backstop applies. This replaces the contract's "logged; the request is still dead-lettered".
- **An error reply that is not confirmed** is logged as `M004` and does not stop the path. The record has already been confirmed, and requeueing at this point would only add a second record.

### 1.3 The error reply (D-74)

D-74 decided "error reply plus dead-letter/retry". The reply is sent only for the account-read failure, once per give-up, to the same destination and with the same properties as a normal reply (DEF-MQAC-002 routing and correlation, section 3; `expiration` 5000; `delivery_mode` 1).

- **Compatibility form**, 50 characters padded with spaces to 1000 bytes: `ERROR WHILE READING ACCTFILE ACCT ID : ` followed by the 11 key bytes as received. The text is the untruncated legacy literal, followed by the key label of the other replies. A text requester tells it apart from the other replies by its first characters: `ACCOUNT ID : `, `INVALID REQUEST PARAMETERS ACCT ID : `, or this one.
- **JSON form**: `{"status":"ERROR","acctId":"00000000001"}`. `ERROR` is added to the JSON status set of D-72 (`FOUND`, `NOT_FOUND`, `INVALID_REQUEST`, `ERROR`). It means that the request was not answered and is now in the dead-letter queue. It carries no cause (D-58).
- The date unit has no file read, so it never sends an error reply.

### 1.4 Error record identity

Each record keeps the contract's fields and codes (`00`/`00000`; `01`/`02080` for an oversized request) and the header `x-carddemo-unit`. Two more properties are added:

- `correlation_id` is the failed request's `message_id`. When the request has none, it is the request's `correlation_id`. When the request has neither, it is absent. This is the same fallback as the fixed-mode reply (section 3).
- Header `x-carddemo-delivery-count` is the request's `x-delivery-count` on the delivery that wrote the record (0 for the first delivery).

A second record for the same request can appear only after a death between the record's confirm and the reject. That second record carries the same `correlation_id` and a higher delivery count, so operations can count failed requests by `correlation_id`. A request with neither ID cannot be told apart from another one. Legacy records carry no request identity either.

### 1.5 What is guaranteed

**Handled give-up (no crash on deliveries 6 to 11).** This is guaranteed and tested:
- The request reaches its `.dlq` through the service's own `basic.reject(requeue = false)`.
- Exactly one confirmed error record is published first.
- For a database failure, exactly one error reply is then attempted (D-74), before the reject.

The same holds when the record is refused or not confirmed on some deliveries, as long as one of deliveries 6 to 11 gets it confirmed. Each refused delivery requeues the request; it does not dead-letter it.

**Crash path (a stated limit, not a guarantee).**

| Output | What can differ from the handled give-up |
| :-- | :-- |
| Error record for a database or reply failure | A death between the record's confirm and the reject adds a second record, with the same identity (1.4) and a higher delivery count. **Zero records** when every delivery from the sixth to the eleventh ends (crash, lost channel, or record not confirmed) before a record is confirmed. Then the broker backstop dead-letters the request. |
| Error record for an oversized request | A death between the confirm and the reject adds a record. Zero records when every delivery up to the eleventh ends before a record is confirmed. |
| Error reply | At most one per give-up, so a second one only after a crash that follows it. It can be missing when it is unconfirmed (`M004`) or expires unread. |

**Deviation, with its reasoning (ledger, B6).** The legacy kept the get, the read and the puts in one MQ unit of work, so ERR-MQAC-005 and SE-MQAC-004 gave exactly one record per failed request. AMQP has no transaction across a consume, a database read and a publish to another queue. A stored outbox would need a write grant on a service that is read-only by design. So the design gives at least one record with stable identity. There is one finite exception: all six final deliveries end before a record is confirmed. The exception is kept finite so that a message that crashes the service on every delivery still leaves the queue. This is an intentional architecture choice, and the product delegation may ratify or reject it. Raising the backstop narrows the exception further but never removes it.

Nothing is exactly once, and no test or document may claim it. The tests in section 6 prove each row above against the real pinned broker, not a mock.

## 2. Duplicate replies and BR-MQAC-007 (Q4)

BR-MQAC-007 and BR-MQDT-005 (exactly one reply per request, in arrival order, must-preserve) are kept for every run without a crash: one confirmed reply, then the acknowledgement. Tests I6 and D4 prove it. On the crash path, between a confirmed reply and its acknowledgement, the redelivery is answered again. No duplicate store is added, because the service is read-only and a second answer changes no data.

To make a duplicate recognisable without a store, it carries the **same identity** as the first reply:

- **Legacy mode:** the reply's `message_id` and `correlation_id` are copied from the request (D-73 legacy). A redelivered request has the same properties, so the duplicate carries the same IDs.
- **Fixed mode:** the reply's `correlation_id` is the request's `message_id`, as drafted. The reply's `message_id` is no longer random. It is the name-based UUID `UUID.nameUUIDFromBytes(("MQAC:" + requestMessageId).getBytes(UTF_8))`, or `"MQDT:"` for the date unit. A duplicate therefore repeats both IDs. The requester keeps the first reply for an ID.
- **A request without a `message_id`.** AMQP makes `message_id` optional. The legacy never met this case, because the MQ queue manager assigns a message ID to every put that does not set one. No existing contract requires the property, and none is added. Such a request is valid: it is answered normally and is never rejected or dead-lettered for the missing property. Its reply follows the faithful fallback:
  - Legacy mode copies the IDs as they are, so an absent `message_id` stays absent.
  - Fixed mode sets `correlation_id` to the request's `correlation_id`, or to none, and gives the reply a random `message_id`.
  - Its error record follows 1.4.
  
  The service cannot give such a request a stable identity without reading its body, and the body holds the account ID. So a duplicate reply or a second error record for it cannot be matched to the first by ID. That is a stated limit for requests without IDs, not a proven property. The demo requester and every test requester set `message_id`, and the service README tells requesters to set it.
- A duplicate account reply is read again from the current row, so its body can differ from the first one when the account changed in between. The requester keeps the first reply, so it keeps the earlier read.

This is an architecture-dependent deviation from BR-MQAC-007 and BR-MQDT-005 on the crash path only (at least once instead of the legacy unit of work). The ledger owner records it as one deviation entry for each item (section 7, B6). It follows from the approved replacement of MQ by RabbitMQ: D-72 to D-74 do not decide delivery guarantees. It is still an intentional choice (no duplicate store on a read-only service), recorded with this reasoning so that the product delegation can ratify it.

## 3. Account correlation (Q1)

Approved as proposed. The account reply's correlation (BR-MQAC-005, and the correlation part of DEF-MQAC-003 under D-73) is **covered by switch DEF-MQAC-002**. In legacy mode the account reply goes to the fixed reply queue and copies the request's `message_id` and `correlation_id`. In fixed mode it goes to `reply_to` (falling back to the fixed queue), with `correlation_id` = request `message_id` (falling back to the request's `correlation_id`, then none), and the `message_id` of section 2. D-73 decided DEF-MQAC-002 and DEF-MQAC-003 together, so one switch on the account side moves both. The date side keeps its two switches, as switches.md lists them. No fourth switch is added.

"Covered by this switch" is applied as switches.md defines it. The ledger owner adds a note to the `DEF-MQAC-002` switch entry naming BR-MQAC-005 and DEF-MQAC-003 (correlation). The tests W1, W2 and W6 cite all three IDs. The DEF-MQAC-003 deviation (DEV-ARCH-10, properties set explicitly) stays as it is.

## 4. Account key that is not 11 digits (Q2, DEF-MQAC-005)

DEF-MQAC-005 has no ledger entry, so the general rule of switches.md applies: it keeps the COBOL behaviour, with no switch. The COBOL behaviour has two outcomes:

- When the comparison passes, the raw bytes are used as the key. No account can match them, so the result is not found.
- When the comparison raises a data exception, the task abends and is backed out, and the same request comes back with the next trigger.

The first outcome is the one that can be built: **the not-found reply**, `INVALID REQUEST PARAMETERS ACCT ID : ` followed by the 11 key bytes as received (48 characters), or JSON `{"status":"NOT_FOUND","acctId":"<as received>"}`. No read is made, because `acct_id` holds 11 digits only (its `CHECK`), so the result is the one a read would give. The abend outcome is not reproduced, for the same reason that a blocked queue is not kept (DEV-ARCH-09): a listener does not loop on one message.

- **Which keys this covers.** Bytes 5 to 15, or the JSON `acctId` string, that are not exactly 11 ASCII digits: letters, spaces, a short request padded with spaces, `0xE9`. The key `00000000000` stays the invalid-request reply (BR-MQAC-004, S3). A function other than `INQA` stays the invalid-request reply whatever the key is, because the function is tested first.
- **This replaces the contract's proposal** (the invalid-request reply). It also replaces messaging.md's fixed-column entry for DEF-MQAC-005, which named that reply although DEF-MQAC-005 has no decision. messaging.md now says the same as switches.md.
- Test I10 expects the 48-character not-found reply. I9 (`INQA123`, a key of `123` and 8 spaces) also expects it.
- The ledger owner adds a deviation entry for DEF-MQAC-005: the abend outcome is not reproduced (DEV-ARCH-09) and the not-found outcome is kept (B6).

## 5. The other questions

- **Q5. Order:** approved. Concurrency 1 per listener by default. A request held by the delayed retry is overtaken by later ones, which the legacy could not do because it lost the request.
- **Q6. Reply lifetime:** approved. `expiration` 5000 and `delivery_mode` 1 for normal and error replies. Error records stay persistent, with no expiration.
- **Q7. JSON request and unknown content types:** approved. The media type is compared without case and without parameters (`Application/JSON; charset=utf-8` is JSON).
- **Q8. DEF-MQDT-004 fixed:** approved as the topology and per-requester permissions only. The body is still not examined (BR-MQDT-001).
- **Q9. Account ID in logs:** approved. The ID is masked to its last four characters through `Pii`.
- **Q10. Timeouts:** approved. Query timeout 1 s and pool connection timeout 1 s. One failed attempt can take up to about 2 s, so a request that fails on all six deliveries reaches its `.dlq` within about 15 s. The contract's "about 9 s" is corrected.

## 6. Scenario changes

Every test in this table runs against the real pinned RabbitMQ image in a private virtual host, never a mock. Broker refusals are real publisher nacks from the target queue itself. Every refusal target is a quorum queue, and a quorum queue with `overflow reject-publish` accepts the publish that takes it over its limit. So the test first publishes a marked sentinel to the target and waits for its confirm, then applies a policy in its virtual host, `max-length 0` and `overflow reject-publish`, to `carddemo.error` (or a reply queue) by exact name, then publishes marked probes from new channels until one is nacked. The queue has then sent its other publishers an asynchronous notice to reject; that the service's next publish is nacked is what the fixture pilot shows on the pinned image, not a guarantee of the broker. Lifting deletes the policy and waits for a confirmed probe. While a target is armed, nothing consumes, reads or expires its sentinel. The test never takes a message off a refused queue and never redeclares a service queue; its setup may purge queues of its own private virtual host by exact name while no refusal is armed and no work is in flight. Checks of service outputs skip the marked messages. The fixture pilot R0 of the contract (v12, section 12), which also sends an existing channel's first publish right after the nacked probe and right after the confirmed lift probe, must pass in the same heavy slot before any refusal row runs. Crashes are real: the test resets the service's own broker connection at the stated point, so its channels close and the broker returns the unacknowledged delivery. Each test asserts the delivery count, the headers (`x-carddemo-unit`, `x-carddemo-delivery-count`, `correlation_id`) and the order on the service's channel: record confirmed, then error reply published, then `basic.reject(requeue = false)`.

| # | Change |
| :-- | :-- |
| I9, I10 | not-found reply of 48 characters, no read (section 4) |
| I17 | as drafted (6 deliveries with the gaps, then the `.dlq`, exactly one record), plus: exactly one error reply (section 1.3) in the form of the request, to the switch's destination; the record carries `correlation_id` = request `message_id` and `x-carddemo-delivery-count` 5 |
| I24 | replaced: the service's connection is reset on the sixth delivery after the record's confirm and before the reject. Expected: a seventh delivery, a second record with the same `correlation_id` and delivery count 6, then the `.dlq`. Two records, both matched to the request by `correlation_id` |
| I25 | new: the service's connection is reset on the sixth delivery before the record is published. Expected: a seventh delivery writes exactly one record and one error reply, then the `.dlq` |
| I26 | new: the record's publish is nacked by the broker on every delivery from the sixth on. Expected: 11 deliveries in all, `M006` on deliveries 6 to 11, then the broker's backstop dead-letters the request; no record. This proves the finite limit of section 1.5 |
| I26b | new: the record is nacked on deliveries 6 and 7 and accepted on 8. Expected: exactly one record (delivery count 7), one error reply, then the `.dlq` after 8 deliveries |
| I27 | new: JSON request with a database failure on every delivery. Expected: `{"status":"ERROR","acctId":"..."}` once, then the `.dlq` |
| I28 | new: fixed mode, the same request delivered twice (the service's connection is reset after the reply's confirm). Expected: two replies with the same `message_id` (the name-based UUID) and the same `correlation_id` |
| I29 | new: a request without `message_id`, with `correlation_id` C1, then one with neither, both in fixed mode and in legacy mode. Expected: each is answered and never dead-lettered. Fixed mode: reply `correlation_id` C1 or none, with a random `message_id`. Legacy mode: the IDs are copied, and absent stays absent. With a database failure on every delivery, the record carries C1 or no `correlation_id` |
| I30 | new: the error reply is nacked by the broker on the sixth delivery. Expected: `M004`, exactly one record, then the `.dlq`; no requeue and no second record |
| D8 | as drafted, with the give-up rule: 6 deliveries, one `MQPUT ERR` record, the `.dlq`, no error reply |
| W7 | the request queues' arguments are read back with `x-delivery-limit` 10 and the three delayed-retry arguments; the reply queues keep 5 |

## 7. Dependencies (for the orchestrator to route)

- **B1 (backend). Grant file:** approved as drafted. `R__grants_inquiry.sql` holds the one column-level `SELECT` on `core.account` (the 11 columns, not `addr_zip` or `legacy_filler`).
- **B2 (backend). Start probes, corrected 2026-11-11:** `ServiceLogin` probes are statements the login must be refused, not statements it must be allowed. The earlier text, which replaced the probe with `SELECT TOP 0 acct_id FROM core.account`, had this the wrong way round and is withdrawn. The grantee is `inquiry_svc`. `ServiceLogin.INQUIRY` keeps the refused `SELECT TOP 0 cust_id FROM core.customer` and adds refused probes for `addr_zip` and `legacy_filler` on `core.account` and for an `UPDATE` on `core.account`; after `V418` it also adds a refused probe for `legacy_zip_group`. The contract's sections 10 and 15 carry the same correction.
- **B3 (architecture). data-model.md:** done in this change. Inquiry is no longer listed as a reader of `core.transaction`, and the `inquiry` login has its own row in [data-model.md](data-model.md#ownership-and-grants).
- **B4 (infra). RabbitMQ user `inquiry_svc`:** approved as drafted, including the demo requester user.
- **B5 (identity, infra). Module flag:** as drafted.
- **B6 (ledger owner).** The following entries:
  - A note on the `DEF-MQAC-002` switch entry naming BR-MQAC-005 and DEF-MQAC-003 as covered (section 3).
  - Deviation entries for BR-MQAC-007 and BR-MQDT-005: at least once on the crash path, with the same reply identity (section 2).
  - A deviation entry for DEF-MQAC-005: not-found reply, no abend (section 4).
  - The `fixed` entries DEF-MQAC-007 and ERR-MQAC-005 (D-74) get a note naming the error reply and the service-owned give-up with a broker backstop of 10.
  - A deviation entry for SE-MQAC-004 and ERR-MQAC-005 (and SE-MQDT-003 for `MQPUT ERR`): exactly one record on the handled give-up; on the crash path a possible second record with the same identity, and zero records when deliveries 6 to 11 all end before a record is confirmed. The entry carries the reasoning of section 1.5 and is marked for ratification by the product delegation.
  - The listener-replaced FLOW, SE and ERR items of the contract's section 13 that are not yet in `deviations.toml`.
