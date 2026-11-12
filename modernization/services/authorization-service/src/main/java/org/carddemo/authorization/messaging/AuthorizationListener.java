package org.carddemo.authorization.messaging;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;
import org.carddemo.authorization.processing.AlreadyProcessedException;
import org.carddemo.authorization.processing.AuthorizationProcessor;
import org.carddemo.authorization.processing.AuthorizationRepository;
import org.carddemo.authorization.processing.AuthorizationRepository.StoredReply;
import org.carddemo.authorization.processing.AuthorizationStoreException;
import org.carddemo.authorization.processing.FailurePoint;
import org.carddemo.authorization.processing.Outcome;
import org.carddemo.authorization.processing.ReplyRoute;
import org.carddemo.authorization.reply.AuthorizationReply;
import org.carddemo.authorization.request.AuthorizationRequest;
import org.carddemo.authorization.request.MessageKey;
import org.carddemo.authorization.request.RequestField;
import org.carddemo.authorization.request.RequestParser;
import org.carddemo.authorization.request.UnparsableRequestException;
import org.carddemo.platform.web.CorrelationIdFilter;
import org.carddemo.platform.web.Pii;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;

/**
 * The listener on {@code carddemo.pauth.request} (contract §4, §6): parse, duplicate check,
 * transaction, then publish the committed reply with a confirm and only then acknowledge
 * (D-35). Every outcome settles the delivery here; the container never acknowledges for it.
 * No log line holds a request body, a full card number or a credential.
 */
public class AuthorizationListener implements ChannelAwareMessageListener {

    static final String MESSAGE_ID_MDC_KEY = "messageId";

    private static final Logger log = LoggerFactory.getLogger(AuthorizationListener.class);

    private final AuthorizationProcessor processor;
    private final AuthorizationRepository repository;
    private final OutboxRepository outbox;
    private final ReplyPublisher publisher;

    public AuthorizationListener(AuthorizationProcessor processor, AuthorizationRepository repository,
            OutboxRepository outbox, ReplyPublisher publisher) {
        this.processor = processor;
        this.repository = repository;
        this.outbox = outbox;
        this.publisher = publisher;
    }

    /** One delivery, as the listener sees it. */
    record Delivery(Channel channel, long tag, String messageId, boolean redelivered) {
    }

    @Override
    public void onMessage(Message message, Channel channel) {
        MessageProperties p = message.getMessageProperties();
        String messageId = emptyToNull(p.getMessageId());
        String correlationId = emptyToNull(p.getCorrelationId()) != null ? p.getCorrelationId() : messageId;
        Delivery delivery = new Delivery(channel, p.getDeliveryTag(), messageId, Boolean.TRUE.equals(p.getRedelivered()));
        MDC.put(CorrelationIdFilter.MDC_KEY, String.valueOf(correlationId));
        MDC.put(MESSAGE_ID_MDC_KEY, String.valueOf(messageId));
        try {
            handle(message, p, correlationId, delivery);
        } catch (RuntimeException e) {
            log.error("authorization request failed, requeued: message ID {}: {}", messageId, e.getClass().getSimpleName());
            requeue(delivery);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
            MDC.remove(MESSAGE_ID_MDC_KEY);
        }
    }

    private void handle(Message message, MessageProperties p, String correlationId, Delivery delivery) {
        AuthorizationRequest request;
        try {
            request = RequestParser.parse(message.getBody(), p.getContentType());
        } catch (UnparsableRequestException e) {
            log.warn("M003 unparsable request dead-lettered: message ID {}, reason {}", delivery.messageId(), e.reason());
            settle(delivery, "reject", () -> delivery.channel().basicReject(delivery.tag(), false));
            return;
        }
        if (!request.replacedFields().isEmpty()) {
            log.info("characters above U+00FF replaced by '?': message ID {}, fields {}", delivery.messageId(),
                    request.replacedFields().stream().map(RequestField::jsonName).toList());
        }
        String replyTo = emptyToNull(p.getReplyTo());
        ReplyRoute route = replyTo == null
                ? new ReplyRoute(AuthorizationTopology.EXCHANGE, AuthorizationTopology.REPLY_ROUTING_KEY, correlationId)
                : new ReplyRoute("", replyTo, correlationId);
        MessageKey key = MessageKey.of(delivery.messageId(), request);
        Object card = Pii.cardNumber(request.stored(RequestField.CARD_NUM));
        try {
            Optional<StoredReply> stored = processor.findProcessed(key);
            if (stored.isPresent()) {
                duplicate(request, stored.get(), route, delivery);
                return;
            }
            Outcome outcome;
            try {
                outcome = processor.process(request, key, route);
            } catch (AlreadyProcessedException e) {
                duplicate(request, processor.findProcessed(key).orElseThrow(() -> new IllegalStateException(
                        "the duplicate key vanished after its insert failed")), route, delivery);
                return;
            }
            log.info("authorization decided: message ID {}, auth ID {}, card {}, response {}, reason {}",
                    delivery.messageId(), outcome.authId(), card, outcome.reply().respCode(), outcome.reply().reason());
            publishAndSettle(outcome.outboxId(), route, AuthorizationReply.contentType(request.form()), outcome.body(),
                    delivery);
        } catch (AuthorizationStoreException e) {
            String location = e.point().legacyLocation();
            log.error("{}authorization transaction failed, requeued: message ID {}, card {}: {}",
                    location == null ? "" : location + " ", delivery.messageId(), card, e.getMessage());
            requeue(delivery);
        }
    }

    /**
     * §6.3. A redelivery whose reply is still unsent publishes that row; anything else gets a new row
     * with the stored reply in this request's form and route. No summary or detail changes.
     */
    private void duplicate(AuthorizationRequest request, StoredReply stored, ReplyRoute route, Delivery delivery) {
        String contentType = AuthorizationReply.contentType(request.form());
        String body = AuthorizationReply.fromCsv(stored.replyBody()).body(request.form());
        if (delivery.redelivered()) {
            OptionalLong unsent = at(FailurePoint.OUTBOX_WRITE, () -> outbox.findUnsent(route, body));
            if (unsent.isPresent()) {
                log.info("redelivered request: publishing its unsent reply: message ID {}, auth ID {}",
                        delivery.messageId(), stored.authId());
                publishAndSettle(unsent.getAsLong(), route, contentType, body, delivery);
                return;
            }
        }
        log.info("duplicate request: replying with the stored reply: message ID {}, auth ID {}", delivery.messageId(),
                stored.authId());
        long outboxId = at(FailurePoint.OUTBOX_WRITE, () -> repository.insertOutbox(route, contentType,
                AuthorizationProcessor.REPLY_EXPIRATION_MS, body));
        publishAndSettle(outboxId, route, contentType, body, delivery);
    }

    /** Steps 10 to 12: confirmed (or returned) replies are marked sent and the request is acknowledged. */
    private void publishAndSettle(long outboxId, ReplyRoute route, String contentType, String body, Delivery delivery) {
        ReplyPublisher.Result result = publisher.publish(outboxId, route, contentType, body);
        if (result == ReplyPublisher.Result.NOT_CONFIRMED) {
            log.error("M004 reply not confirmed, request requeued: message ID {}, outbox ID {}", delivery.messageId(),
                    outboxId);
            try {
                outbox.markAttempt(outboxId);
            } catch (DataAccessException e) {
                log.error("outbox row {} not updated: SQL Server error {}", outboxId, sqlError(e));
            }
            requeue(delivery);
            return;
        }
        if (result == ReplyPublisher.Result.RETURNED) {
            log.warn("reply returned unroutable, counted as sent: correlation ID {}, outbox ID {}",
                    route.correlationId(), outboxId);
        }
        // The reply is out: acknowledging even when the mark fails keeps the requester at one
        // reply. The purge never deletes an unsent row, so pauth_outbox_unsent stays raised until
        // an operator sets sent_at after finding this warning; nothing retries the mark.
        try {
            outbox.markSent(outboxId);
        } catch (DataAccessException e) {
            log.warn("reply confirmed but not marked sent; set sent_at by hand: message ID {}, outbox_id {}, "
                    + "SQL Server error {}", delivery.messageId(), outboxId, sqlError(e));
        }
        settle(delivery, "ack", () -> delivery.channel().basicAck(delivery.tag(), false));
    }

    /**
     * A counted retry. RabbitMQ 4.3 and later count only {@code basic.reject} (not a requeued
     * {@code basic.nack}) toward {@code x-delivery-limit}, so a nack would retry forever and never
     * reach the dead-letter queue. The request queue holds each rejected delivery before it is
     * redelivered (delayed retry type {@code failed}); the listener itself never waits.
     */
    private void requeue(Delivery delivery) {
        settle(delivery, "reject", () -> delivery.channel().basicReject(delivery.tag(), true));
    }

    /** A settle that fails leaves the delivery unacknowledged; the broker redelivers it when the channel closes. */
    private static void settle(Delivery delivery, String action, ChannelAction work) {
        try {
            work.run();
        } catch (IOException | RuntimeException e) {
            log.error("{} failed: message ID {}: {}", action, delivery.messageId(), e.getClass().getSimpleName());
        }
    }

    interface ChannelAction {
        void run() throws IOException;
    }

    private static <T> T at(FailurePoint point, Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException e) {
            throw new AuthorizationStoreException(point, e);
        }
    }

    private static int sqlError(DataAccessException e) {
        return NestedExceptionUtils.getMostSpecificCause(e) instanceof SQLException s ? s.getErrorCode() : 0;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
