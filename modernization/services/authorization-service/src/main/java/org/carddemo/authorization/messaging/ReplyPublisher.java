package org.carddemo.authorization.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.carddemo.authorization.processing.AuthorizationProcessor;
import org.carddemo.authorization.processing.ReplyRoute;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Publishes one outbox row and waits for the broker's confirm (§4.4, §6.2 step 10). The template
 * is mandatory, so a reply whose queue is gone comes back as returned: it counts as sent.
 */
public class ReplyPublisher {

    /** The reply's lifetime: a confirm that takes longer is of no use to the requester. */
    static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);
    static final String EXPIRATION_MS = String.valueOf(AuthorizationProcessor.REPLY_EXPIRATION_MS);

    public enum Result {
        CONFIRMED,
        /** Confirmed, but unroutable: the requester's queue no longer exists. */
        RETURNED,
        /** Nacked, timed out or the connection failed; the request is requeued. */
        NOT_CONFIRMED
    }

    private final RabbitTemplate template;

    public ReplyPublisher(RabbitTemplate template) {
        this.template = template;
    }

    public Result publish(long outboxId, ReplyRoute route, String contentType, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(contentType);
        properties.setCorrelationId(route.correlationId());
        properties.setExpiration(EXPIRATION_MS);
        properties.setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);
        Message message = new Message(body.getBytes(StandardCharsets.UTF_8), properties);
        CorrelationData correlation = new CorrelationData("outbox-" + outboxId);
        try {
            template.send(route.exchange(), route.routingKey(), message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                return Result.NOT_CONFIRMED;
            }
            return correlation.getReturned() != null ? Result.RETURNED : Result.CONFIRMED;
        } catch (AmqpException | ExecutionException | TimeoutException e) {
            return Result.NOT_CONFIRMED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.NOT_CONFIRMED;
        }
    }
}
