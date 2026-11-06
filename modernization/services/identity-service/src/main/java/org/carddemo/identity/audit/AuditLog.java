package org.carddemo.identity.audit;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Writes iam.audit_event rows: who, when, what and about whom, never a password or other typed value. */
@Component
public class AuditLog {

    public enum EventType {
        SIGN_ON_OK, SIGN_ON_FAILED, USER_LOCKED, SIGN_OFF, SESSION_EXPIRED, PASSWORD_CHANGED, REFRESH_REUSE
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditLog(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @param actor the signed-on user who acted, or null
     * @param subject the user ID the event is about, as typed (cut to 40 characters)
     * @param detail a reason code, or null
     */
    public void record(EventType type, String actor, String subject, String detail, String clientIp) {
        jdbc.sql("""
                INSERT INTO iam.audit_event (occurred_at, actor_user_id, event_type, subject_key, detail, client_ip)
                VALUES (:at, :actor, :type, :subject, :detail, :ip)
                """)
                .param("at", LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC))
                .param("actor", actor)
                .param("type", type.name())
                .param("subject", cut(subject, 40))
                .param("detail", cut(detail, 200))
                .param("ip", cut(clientIp, 45))
                .update();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
