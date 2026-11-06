package org.carddemo.identity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.carddemo.identity.user.EbcdicSortKey;
import org.carddemo.identity.user.PasswordHasher;
import org.springframework.jdbc.core.simple.JdbcClient;

/** iam rows for tests, written and read as the database owner. */
public final class IdentityFixtures {

    /** The public demo password of the DUSRSECJ users (README). */
    public static final String DEMO_PASSWORD = "PASSWORD";

    private static final PasswordHasher HASHER = new PasswordHasher(4);
    private static final Map<String, String> HASHES = new ConcurrentHashMap<>();

    public record AuditRow(String eventType, String actor, String subject, String detail, String clientIp) {
    }

    public record SessionRow(String sessionId, String userId, String endReason) {
    }

    public record UserRow(String passwordHash, boolean mustChange, int failedAttempts, LocalDateTime lockedUntil,
            LocalDateTime lastSignOnAt) {
    }

    private final JdbcClient admin;

    public IdentityFixtures(JdbcClient admin) {
        this.admin = admin;
    }

    public void deleteAll() {
        admin.sql("DELETE FROM iam.audit_event").update();
        admin.sql("DELETE FROM iam.session").update();
        admin.sql("DELETE FROM iam.app_user").update();
        admin.sql("DELETE FROM ops.batch_window").update();
    }

    /** ADMIN001 (type A) and USER0001 (type U) with password PASSWORD. */
    public IdentityFixtures demoUsers() {
        user("ADMIN001", "MARGARET", "GOLD", 'A', DEMO_PASSWORD, false);
        user("USER0001", "LAWRENCE", "THOMAS", 'U', DEMO_PASSWORD, false);
        user("USER0002", "AJITH", "KUMAR", 'U', DEMO_PASSWORD, false);
        return this;
    }

    /** A user whose stored hash is of {@code password} exactly as given. */
    public IdentityFixtures user(String userId, String first, String last, char type, String password,
            boolean mustChange) {
        String hash = HASHES.computeIfAbsent(password, HASHER::hash);
        admin.sql("""
                INSERT INTO iam.app_user (user_id, first_name, last_name, password_hash, user_type,
                                          must_change_password, ebcdic_sort_key)
                VALUES (:id, :first, :last, :hash, :type, :mustChange, :key)
                """)
                .param("id", userId).param("first", first).param("last", last).param("hash", hash)
                .param("type", String.valueOf(type)).param("mustChange", mustChange)
                .param("key", EbcdicSortKey.of(userId))
                .update();
        return this;
    }

    public IdentityFixtures openBatchWindow() {
        admin.sql("INSERT INTO ops.batch_window (mode, opened_by) VALUES ('WRITES_BLOCKED', 'test')").update();
        return this;
    }

    public List<AuditRow> audit() {
        return admin.sql("""
                SELECT event_type, RTRIM(actor_user_id) AS actor, subject_key, detail, client_ip
                FROM iam.audit_event ORDER BY event_id
                """)
                .query((rs, n) -> new AuditRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)))
                .list();
    }

    /** Every audit value as one text, to prove a password never reached the table. */
    public String auditText() {
        return String.join("|", admin.sql("""
                SELECT event_type, actor_user_id, subject_key, detail, client_ip
                FROM iam.audit_event
                """).query((rs, n) -> String.join("|", rs.getString(1), String.valueOf(rs.getString(2)),
                        String.valueOf(rs.getString(3)), String.valueOf(rs.getString(4)),
                        String.valueOf(rs.getString(5)))).list());
    }

    public List<SessionRow> sessions() {
        return admin.sql("SELECT CAST(session_id AS VARCHAR(36)), RTRIM(user_id), end_reason FROM iam.session")
                .query((rs, n) -> new SessionRow(rs.getString(1).toLowerCase(), rs.getString(2), rs.getString(3)))
                .list();
    }

    public UserRow user(String userId) {
        return admin.sql("""
                SELECT password_hash, must_change_password, failed_attempts, locked_until, last_sign_on_at
                FROM iam.app_user WHERE user_id = :id
                """)
                .param("id", userId)
                .query((rs, n) -> new UserRow(rs.getString(1), rs.getBoolean(2), rs.getInt(3),
                        rs.getObject(4, LocalDateTime.class), rs.getObject(5, LocalDateTime.class)))
                .single();
    }
}
