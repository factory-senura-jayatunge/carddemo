package org.carddemo.authorization.processing;

/**
 * Where a reply is published, as the listener works it out from the request's {@code reply_to}
 * and correlation ID (contract §4.4). It is stored with the reply in {@code pauth.outbox}; the
 * content type follows the request's body form.
 *
 * @param correlationId {@code null} when neither the correlation ID nor the message ID is set
 */
public record ReplyRoute(String exchange, String routingKey, String correlationId) {
}
