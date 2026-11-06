# Messaging (RabbitMQ 4.4)

RabbitMQ replaces the IBM MQ queues of the two optional modules: card authorization (CP00, COPAUA0C) and account and date inquiry (CDRA, COACCT01; CDRD, CODATE01). The MQ objects themselves (queue manager, initiation queue, process and trigger definitions) are not in the repository (DEF-JCSDM-004, Q-JCSD2-001), so the topology below is new. Report submission does not use RabbitMQ: it goes through the Airflow REST API ([batch.md](batch.md#dags)).

## Topology

Virtual host `carddemo`. All queues are durable quorum queues (the broker default in `modernization/infra`), with `x-delivery-limit: 5` and dead-lettering to `carddemo.dlx`.

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

**Reply** (ENT-AUTH-RESPONSE): six comma-separated values with a trailing comma, 63 characters: card number (16), transaction ID (15), authorization ID code (6), response `00` or `05`, reason (4), amount edited `-zzzzzzzzz9.99`. Sent to the request's `reply_to`, or `carddemo.pauth.reply` when absent.

**Decision**: available credit is the summary's limit minus its balance, or the account's when no summary exists; approve in full when the amount fits, else decline (`4100`); unknown card, account or customer gives `3100`. Decision, summary update (with an update lock on the account's summary row) and the detail insert run in one SQL transaction.

| Concern | Legacy mode (default) | Fixed mode |
| :-- | :-- | :-- |
| Correlation (DEF-AUTH-015, Q-AUTH-001) | reply `correlation_id` = request `correlation_id` | same, falling back to the request `message_id` when the correlation ID is empty |
| Order of reply and commit (DEF-AUTH-008, Q-AUTH-005) | the request is acknowledged on receipt, the reply is published, then the database commits: at most once | decision, summary, detail and an outbox row commit together; the outbox publisher sends the reply with publisher confirms; then the request is acknowledged: effectively once |
| Duplicates (DEF-AUTH-006) | none detected | `pauth.processed_message` keyed by `message_id`, or by card, transaction ID, date and time; a duplicate gets the stored reply |
| Reply lifetime | per-message TTL 5000 ms (`expiration`) | same |
| Unparsable or oversized request (ERR-AUTH-003) | rejected without requeue to `carddemo.pauth.request.dlq` (a blocked queue cannot be kept: a listener does not stop on one message) | same, or a decline reply if Q-AUTH-004 says so |

The legacy reply is non-persistent. In RabbitMQ a reply to Direct Reply-To (`amq.rabbitmq.reply-to`) is transient; a reply to a quorum queue is always stored. Either is within the equivalence contract, which does not cover persistence.

The defects that come from state left over between messages in one CICS task (DEF-AUTH-001 replies that repeat the first decision, DEF-AUTH-002, 003, 004, 009, 013) have no legacy mode: a listener handles each message with fresh state. They are architecture-dependent items ([decisions.md](decisions.md#architecture-dependent-fidelity-items)).

## Account inquiry (CDRA)

**Request** (ENT-MQ-ACCT-REQUEST): bytes 1-4 `INQA`, bytes 5-15 the 11-digit account ID.

**Reply** (ENT-MQ-ACCT-REPLY), compatibility format: a 1000-byte text body with the labelled layout of the spec, amounts unedited with the sign overpunched in the last digit; a 48-byte not-found text or a 63-byte invalid-request text, padded. A JSON format with an explicit status (`FOUND`, `NOT_FOUND`, `INVALID_REQUEST`) and decimal strings adds the status field the legacy reply lacks (DEF-MQAC-014, Q-MQAC-007, Q-MQAC-008); the requester chooses it with `content_type`.

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
| Account table unavailable (DEF-MQAC-007) | request acknowledged, no reply, error record to `carddemo.error` | negative acknowledgement with requeue; after 5 deliveries to the dead-letter queue |
| Account key not 11 digits (DEF-MQAC-005) | the reply the spec describes for it today | invalid-request reply |
| Oversized or unparsable request (DEF-MQAC-012, DEF-MQDT-009) | dead-lettered (the legacy blocked queue cannot be kept) | same |

**Error records** (ENT-MQ-ERROR-MSG) go to `carddemo.error`: in compatibility format the 113-byte text with paragraph, message, condition, reason and queue; in JSON the same five fields. Error and dead-letter queues never carry a full card number; the authorization listener masks it in its error log (DEF-AUTH-011).

## Contract tests

Each contract has a golden-file test per body form (found, not found, invalid; approve, decline, unknown card) taken from the spec's examples, tagged with the item IDs, run against a Testcontainers RabbitMQ. The tests run in both fidelity modes.
