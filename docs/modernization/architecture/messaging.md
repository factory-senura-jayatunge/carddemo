# Messaging (RabbitMQ 4.4)

RabbitMQ replaces the IBM MQ queues of the two optional modules: card authorization (CP00, COPAUA0C) and account and date inquiry (CDRA, COACCT01; CDRD, CODATE01). The MQ objects themselves (queue manager, initiation queue, process and trigger definitions) are not in the repository (DEF-JCSDM-004, Q-JCSD2-001), so the topology below is new. Report submission does not use RabbitMQ: it goes through the Airflow REST API ([batch.md](batch.md#dags)).

## Topology

Virtual host `carddemo`. All queues are durable quorum queues (the broker default in `modernization/infra`). Each request and reply queue has `x-delivery-limit: 5`, except the two inquiry request queues, which have 10 as a backstop because the inquiry listener gives up on the sixth delivery itself ([inquiry-service.md](inquiry-service.md#1-failed-requests-error-records-and-the-error-reply-q3-d-74)). Request queues also have the delayed-retry arguments below, dead-letter exchange `carddemo.dlx` and a dead-letter routing key equal to its own name; without that key the broker would use the message's routing key, which no binding matches. Only request queues have a `<queue>.dlq` bound under that key. A reply queue's dead letters (expired, or returned more than 5 times) match no binding and are dropped, which is the reply's lifetime. A `.dlq` is a durable quorum queue with no arguments.

**Counted retries use reject.** On RabbitMQ 4.3 and later (the stack pins 4.4.0), only `basic.reject`, a lost channel or connection, or a crash counts a delivery toward `x-delivery-limit`; `basic.nack` with requeue does not, so a listener that nacks would retry forever and never reach its DLQ. Listeners retry with `basic.reject(requeue = true)` and dead-letter with `basic.reject(requeue = false)`; they never use `basic.nack`. The limit counts returns, not deliveries: the broker dead-letters a message once it has been returned more than 5 times, so a poison message is delivered 6 times (the first delivery plus 5 returns) and then goes to its DLQ. The limit stays 5 on `carddemo.pauth.request`. The inquiry request queues keep the same 6 deliveries in a run without a crash, but the service decides the last one from `x-delivery-count`, and the broker limit of 10 only catches deliveries that keep dying ([inquiry-service.md](inquiry-service.md#11-the-service-gives-up-the-broker-is-only-a-backstop)). The broker test on the pinned image asserts 6 deliveries and then the DLQ (corrected 2026-11-10).

**Counted retries are spaced by the broker (ruled 2026-11-10).** A requeued reject is redelivered at once, so without a delay a fast-failing fault (a database that refuses connections, a failover in progress) would use all 6 deliveries within milliseconds and dead-letter requests that a short wait would have answered. Every request queue (`carddemo.pauth.request` and the two inquiry request queues) is therefore declared with the quorum-queue delayed retry of RabbitMQ 4.3 and later, as three more queue arguments:

| Argument | Value |
| :-- | :-- |
| `x-delayed-retry-type` | `failed` (only returns that count a delivery: `basic.reject`, a lost channel or connection; this is every return the listeners make) |
| `x-delayed-retry-min` | `200` (ms) |
| `x-delayed-retry-max` | `1000` (ms) |

The broker holds a returned message for `min(200 ms × delivery count, 1000 ms)` before it can be delivered again: 200, 400, 600, 800 and 1000 ms after the five returns, about 3 s in total plus processing time. A request that succeeds on its last attempt is still answered well inside the 5 s that a reply is kept, and a fault that clears within about 3 s loses no request. The sixth reject dead-letters the message as before. A held message does not block the queue: other messages are delivered while it waits, so one failing request does not stall its neighbours. Reply queues and `.dlq` queues get no delayed retry; the requester owns what happens to its replies.

Queue arguments, not a policy: the service already declares the topology itself through `RabbitAdmin`, configure permission on its own queues is all it needs, and the values are tested with the code. A policy would need the policymaker tag or a separate infra step, and only one policy applies to a queue, so it would compete with any operator policy. Listeners do not pause before a reject: a sleep on the consumer thread blocks the other prefetched messages, and a pause off that thread would need its own acknowledgement handling for no gain over the broker's delay.

A request queue that already exists without these arguments must be replaced once: the operator drains it, deletes it and lets the service declare it again. The service itself still never deletes or redeclares an existing object. The broker may not compare these arguments when a queue is redeclared, so an old queue could otherwise keep running without the delay; the provisioning test therefore reads each request queue's arguments through the management API and checks all three.

The broker test on the pinned image (extending M24) uses a listener that always rejects with requeue and records when each delivery arrives. It asserts 6 deliveries, gaps of at least 200, 400, 600, 800 and 1000 ms (lower bounds only, minus 50 ms for timer jitter), then the message in the DLQ, with the whole run under 5 s. A second message published while the first is held must be delivered without waiting for it. If the pinned image does not space the deliveries, the builder reports back to the architect; no listener-side pause is added in its place.

**Service user permissions (corrected 2026-11-10 from broker tests).** Each service has its own user on vhost `carddemo`, with permissions limited to its own names. A queue declared with a dead-letter exchange needs read on the queue and write on that exchange, and `queue.bind` needs write on the queue and read on the exchange. The earlier set for `authorization_svc` (write on the exchanges only, read on the request queue only) therefore failed at start. The tested set for `authorization_svc` is:

| Permission | Regular expression |
| :-- | :-- |
| configure | `^(carddemo\.pauth\|carddemo\.pauth\.request\|carddemo\.pauth\.reply\|carddemo\.pauth\.request\.dlq\|carddemo\.dlx)$` |
| write | `^(amq\.default\|carddemo\.pauth\|carddemo\.dlx\|carddemo\.pauth\.request\|carddemo\.pauth\.reply\|carddemo\.pauth\.request\.dlq)$` |
| read | `^(carddemo\.pauth\.request\|carddemo\.pauth\.reply\|carddemo\.pauth\|carddemo\.dlx)$` |

Two residuals are accepted, because RabbitMQ has no finer permission. The user can `basic.get` from the reply queue, and configure lets it delete its own objects. The service never does either: it never deletes, purges or redeclares an existing object with other arguments. The provisioning test declares the topology with this user on a fresh vhost and checks that a declare outside these names is refused. The inquiry services get the same pattern on their own names when they are built.

| Exchange | Type | Routing key | Queue | Consumer |
| :-- | :-- | :-- | :-- | :-- |
| `carddemo.pauth` | direct | `request` | `carddemo.pauth.request` | `authorization-service` |
| `carddemo.pauth` | direct | `reply` | `carddemo.pauth.reply` (for requesters without their own reply queue) | the requester |
| `carddemo.inquiry` | direct | `account.request` | `carddemo.inquiry.account.request` | `inquiry-service` |
| `carddemo.inquiry` | direct | `account.reply` | `carddemo.inquiry.account.reply` (mirrors `CARD.DEMO.REPLY.ACCT`) | the requester |
| `carddemo.inquiry` | direct | `date.request` | `carddemo.inquiry.date.request` | `inquiry-service` |
| `carddemo.inquiry` | direct | `date.reply` | `carddemo.inquiry.date.reply` (mirrors `CARD.DEMO.REPLY.DATE`) | the requester |
| `carddemo.error` | fanout | - | `carddemo.error` (mirrors `CARD.DEMO.ERROR`) | operations |
| `carddemo.dlx` | direct | original queue name | `<queue>.dlq` for each request queue | operations |

The MQ trigger, CKTI and the "drain up to 501 messages, wait 5 seconds" loops become Spring AMQP listeners with manual acknowledgement, prefetch 10 and a concurrency setting; the loop mechanics are incidental in the equivalence contracts.

Each requester gets its own RabbitMQ user, with write access to the request exchange and read access only to its reply queue, which closes the shared-reply-queue exposure (DEF-MQAC-004, DEF-JCSDM-003).

## Authorization request and reply (CP00)

**Request** (ENT-AUTH-REQUEST): body is the legacy string of 18 comma-separated values, at most 500 bytes, in the field order of the spec. Properties: `reply_to`, `correlation_id`, `message_id`, `content_type: text/csv`. A JSON body (`content_type: application/json`) with the same 18 named fields is accepted as an option (D-MSG-02).

**Reply** (ENT-AUTH-RESPONSE): six comma-separated values with a trailing comma, 63 characters: card number (16), transaction ID (15), authorization ID code (6), response `00` or `05`, reason (4), amount edited `-zzzzzzzzz9.99`. A JSON request gets the same six values as a JSON object (`content_type: application/json`, amount as a decimal string). Sent to the request's `reply_to`, or `carddemo.pauth.reply` when absent.

**Decision** (D-31, D-32, D-33): available credit is the account's credit limit minus its posted balance minus the summary's pending approvals (`credit_balance`, 0 without a summary). The request is approved in full when no decline condition holds. Otherwise the first condition that holds gives the reason, in the order `3100` (unknown card, account or customer), `4100` (amount above available credit), `4200` (card not active or expired), `4300` (account not active), `5100` (confirmed fraud on the card), `5200` (reserved, never set until the business defines merchant fraud) and `9000` (invalid amount). Decision, summary update (with an update lock on the account's summary row), detail insert, outbox row and duplicate key run in one SQL transaction. The rulings on the authorization-service contract are in [authorization-service.md](authorization-service.md).

Every row below is built in its fixed form only (switches.md, D-31, D-35). The legacy column documents what is not reproduced.

| Concern | Legacy (not built) | Built |
| :-- | :-- | :-- |
| Correlation (DEF-AUTH-015, Q-AUTH-001) | reply `correlation_id` = request `correlation_id` | same, falling back to the request `message_id` when the correlation ID is empty |
| Order of reply and commit (DEF-AUTH-008, Q-AUTH-005) | the request is acknowledged on receipt, the reply is published, then the database commits: at most once | decision, summary, detail and an outbox row commit together; the listener then publishes the reply with publisher confirms and acknowledges the request. An unconfirmed reply is sent again only when its own request is redelivered; there is no timer relay, so two publishers never send one row: effectively once |
| Duplicates (DEF-AUTH-006) | none detected | `pauth.processed_message` keyed by `message_id`, or by card, transaction ID, date and time; a duplicate gets the stored reply |
| Reply lifetime | per-message TTL 5000 ms (`expiration`) | same |
| Unparsable or oversized request (ERR-AUTH-003) | rejected without requeue to `carddemo.pauth.request.dlq` (a blocked queue cannot be kept: a listener does not stop on one message) | same: dead-lettered with no reply and no write (fewer than 18 CSV values, invalid JSON, unknown `content_type`, oversized body); a parsable request with an invalid amount is declined `9000` |

The legacy reply is non-persistent. In RabbitMQ a reply to Direct Reply-To (`amq.rabbitmq.reply-to`) is transient; a reply to a quorum queue is always stored. Either is within the equivalence contract, which does not cover persistence.

The defects that come from state left over between messages in one CICS task (DEF-AUTH-001 replies that repeat the first decision, DEF-AUTH-002, 003, 004, 009, 013) have no legacy mode: a listener handles each message with fresh state. They are architecture-dependent items ([decisions.md](decisions.md#architecture-dependent-fidelity-items)).

## Account inquiry (CDRA)

**Request** (ENT-MQ-ACCT-REQUEST): bytes 1-4 `INQA`, bytes 5-15 the 11-digit account ID.

**Reply** (ENT-MQ-ACCT-REPLY), compatibility format: a 1000-byte text body with the labelled layout of the spec, amounts unedited with the sign overpunched in the last digit; a 48-byte not-found text or a 63-byte invalid-request text, padded. A JSON format with an explicit status (`FOUND`, `NOT_FOUND`, `INVALID_REQUEST`, and `ERROR` for the D-74 error reply) and decimal strings adds the status field the legacy reply lacks (DEF-MQAC-014, Q-MQAC-007, Q-MQAC-008); the requester chooses it with `content_type`.

The listener reads `core.account` without a lock. It is not covered by the batch window, because CDRA reads the account file only.

## Date inquiry (CDRD)

Any request body gets `SYSTEM DATE : MM-DD-YYYYSYSTEM TIME : HH:MM:SS` padded to 1000 bytes (ENT-MQ-DATE-REPLY). The JSON format sends an ISO-8601 timestamp with its offset, which answers the missing time zone (Q-MQDT-002).

## Inquiry reply routing and errors

| Concern | Legacy mode (default) | Fixed mode |
| :-- | :-- | :-- |
| Where the reply goes (Q-MQAC-002) | the fixed reply queue (`...account.reply`, `...date.reply`), ignoring `reply_to` | the request's `reply_to`, falling back to the fixed queue |
| Correlation (BR-MQAC-005, BR-MQDT-003) | reply `message_id` and `correlation_id` copied from the request | reply `correlation_id` = request `message_id` |
| Other properties (DEF-MQAC-003) | not copied: AMQP has no descriptor inheritance; `delivery_mode` set explicitly | same |
| Acknowledgement (SE-MQAC-005) | after the reply is published and confirmed | same |
| Account table unavailable (DEF-MQAC-007) | request acknowledged, no reply, error record to `carddemo.error` | rejected with requeue (`basic.reject`, which counts toward the limit); on the sixth delivery (spaced by the delayed retry above) the service writes the error record, sends the error reply (D-74) and dead-letters the request: exactly one confirmed record on this handled path, with a stated crash limit ([inquiry-service.md](inquiry-service.md#1-failed-requests-error-records-and-the-error-reply-q3-d-74)) |
| Account key not 11 digits (DEF-MQAC-005) | not-found reply without a read (the COBOL outcome when the key comparison does not abend) | same: DEF-MQAC-005 has no decision, so it is not a switch ([inquiry-service.md](inquiry-service.md#4-account-key-that-is-not-11-digits-q2-def-mqac-005)) |
| Oversized or unparsable request (DEF-MQAC-012, DEF-MQDT-009) | dead-lettered (the legacy blocked queue cannot be kept) | same |

**Error records** (ENT-MQ-ERROR-MSG) go to `carddemo.error`: in compatibility format the 113-byte text with paragraph, message, condition, reason and queue; in JSON the same five fields. An inquiry error record carries `correlation_id` = the failed request's `message_id` and the headers `x-carddemo-unit` and `x-carddemo-delivery-count`, so a repeated record can be matched to its request. Error and dead-letter queues never carry a full card number; the authorization listener masks it in its error log (DEF-AUTH-011).

## Contract tests

Each contract has a golden-file test per body form (found, not found, invalid; approve, decline, unknown card) taken from the spec's examples, tagged with the item IDs, run against a Testcontainers RabbitMQ. Under the targeted policy ([switches.md](switches.md)) only DEF-MQAC-002, DEF-MQDT-002 and DEF-MQDT-003 keep a legacy mode, and their tests run in both modes. Every other row in the mode tables above is built in its fixed form only. DEF-MQAC-005 is not a decision item and keeps the COBOL outcome that can be built: the not-found reply without a read.
