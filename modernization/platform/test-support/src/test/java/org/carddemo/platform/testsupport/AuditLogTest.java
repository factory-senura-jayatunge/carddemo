package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.carddemo.platform.web.AuditLog;
import org.carddemo.platform.web.CorrelationIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AuditLogTest {

    private final TestDatabase db = TestDatabase.shared();
    private final AuditLog audit = new AuditLog(db.admin(), "account-service");

    @BeforeEach
    void empty() {
        db.fixtures().deleteAll();
    }

    @Test
    void writesFieldNamesSortedWithTheCorrelationId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-1");
        try {
            audit.record("ADMIN001", AuditLog.Action.UNMASK, "ACCOUNT", "00000000001", List.of("ssn", "dob"));
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
        Map<String, Object> row = db.admin().sql("""
                SELECT service, actor_user_id, action, subject_type, subject_key, field_names, correlation_id
                FROM ops.audit""").query().singleRow();
        assertThat(row).containsEntry("service", "account-service").containsEntry("actor_user_id", "ADMIN001")
                .containsEntry("action", "UNMASK").containsEntry("subject_type", "ACCOUNT")
                .containsEntry("subject_key", "00000000001").containsEntry("field_names", "dob,ssn")
                .containsEntry("correlation_id", "corr-1");
    }

    @Test
    void noFieldsIsNullAndOverlongListsAreRefused() {
        audit.record("USER0001", AuditLog.Action.UPDATE, "CARD", "17", List.of());
        assertThat(db.admin().sql("SELECT field_names FROM ops.audit").query(String.class).optional()).isEmpty();
        assertThatThrownBy(() -> audit.record("USER0001", AuditLog.Action.UPDATE, "CARD", "17",
                List.of("x".repeat(401)))).isInstanceOf(IllegalArgumentException.class);
    }
}
