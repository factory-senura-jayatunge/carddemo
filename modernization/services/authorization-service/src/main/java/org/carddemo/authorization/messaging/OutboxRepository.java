package org.carddemo.authorization.messaging;

import java.time.Duration;
import java.util.OptionalLong;
import org.carddemo.authorization.processing.ReplyRoute;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The outbox rows after their commit: the duplicate path's lookup and the publish bookkeeping (§6.2, §6.3). */
@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The unsent row a redelivered request left behind: same route, same correlation (NULL-safe)
     * and the same body, which is the stored reply in the request's form.
     */
    public OptionalLong findUnsent(ReplyRoute route, String body) {
        return jdbc.sql("""
                        SELECT TOP (1) outbox_id FROM pauth.outbox
                        WHERE sent_at IS NULL AND exchange_name = :exchange AND routing_key = :routingKey
                          AND (correlation_id = :correlationId OR (correlation_id IS NULL AND :correlationId IS NULL))
                          AND body = :body
                        ORDER BY outbox_id""")
                .param("exchange", route.exchange()).param("routingKey", route.routingKey())
                .param("correlationId", route.correlationId(), java.sql.Types.VARCHAR).param("body", body)
                .query(Long.class)
                .optional()
                .map(OptionalLong::of)
                .orElse(OptionalLong.empty());
    }

    public void markSent(long outboxId) {
        jdbc.sql("UPDATE pauth.outbox SET sent_at = SYSUTCDATETIME(), attempts = attempts + 1 WHERE outbox_id = :id")
                .param("id", outboxId).update();
    }

    public void markAttempt(long outboxId) {
        jdbc.sql("UPDATE pauth.outbox SET attempts = attempts + 1 WHERE outbox_id = :id").param("id", outboxId).update();
    }

    /** Rows still unsent after {@code age}: replies whose request went to the dead-letter queue, or stuck. */
    public long countUnsentOlderThan(Duration age) {
        return jdbc.sql("""
                        SELECT COUNT_BIG(*) FROM pauth.outbox
                        WHERE sent_at IS NULL AND created_at < DATEADD(MILLISECOND, -:ms, SYSUTCDATETIME())""")
                .param("ms", age.toMillis())
                .query(Long.class)
                .single();
    }
}
