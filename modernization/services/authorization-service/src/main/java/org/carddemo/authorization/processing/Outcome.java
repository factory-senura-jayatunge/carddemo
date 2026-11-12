package org.carddemo.authorization.processing;

import org.carddemo.authorization.decision.Decision;
import org.carddemo.authorization.reply.AuthorizationReply;

/**
 * A committed request: its decision and reply, the detail written ({@code null} when the card or
 * the account was not found), and the outbox row to publish with its body.
 */
public record Outcome(Decision decision, AuthorizationReply reply, Long authId, long outboxId, String body) {
}
