package org.carddemo.platform.web;

import java.util.Collection;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes {@code ops.audit} rows (D-56, D-NFR-10): who did what to which record, and which fields,
 * never their values. Call it inside the transaction of the change or the unmasked read, so the row
 * exists exactly when the action happened. A service login needs {@code INSERT} on the table only.
 */
public final class AuditLog {

    public enum Action { UNMASK, CREATE, UPDATE, DELETE }

    private static final int FIELD_NAMES_MAX = 400;

    private final JdbcClient jdbc;
    private final String service;

    public AuditLog(JdbcClient jdbc, String service) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * @param subjectKey the record's business key; for a card its {@code card_id}, never the PAN
     * @param fieldNames API field names, written sorted and comma-separated; may be empty
     */
    public void record(String actorUserId, Action action, String subjectType, String subjectKey,
            Collection<String> fieldNames) {
        String names = fieldNames == null || fieldNames.isEmpty() ? null : Unmask.join(fieldNames);
        if (names != null && names.length() > FIELD_NAMES_MAX) {
            throw new IllegalArgumentException("field names longer than " + FIELD_NAMES_MAX);
        }
        jdbc.sql("""
                INSERT INTO ops.audit (service, actor_user_id, action, subject_type, subject_key, field_names, correlation_id)
                VALUES (:service, :actor, :action, :subjectType, :subjectKey, :fieldNames, :correlationId)""")
                .param("service", service)
                .param("actor", Objects.requireNonNull(actorUserId, "actorUserId"))
                .param("action", action.name())
                .param("subjectType", Objects.requireNonNull(subjectType, "subjectType"))
                .param("subjectKey", Objects.requireNonNull(subjectKey, "subjectKey"))
                .param("fieldNames", names)
                .param("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY))
                .update();
    }
}
