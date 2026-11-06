package org.carddemo.identity.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.carddemo.identity.signon.LegacyText;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** iam.session. Session IDs travel as strings: SQL Server converts them to UNIQUEIDENTIFIER. */
@Repository
public class SessionRepository {

    public enum EndReason {
        SIGN_OFF, IDLE, EXPIRED, REUSE_DETECTED, PASSWORD_CHANGE, USER_REFUSED
    }

    public record SessionRow(UUID sessionId, String userId, LocalDateTime createdAt, LocalDateTime lastActivityAt,
            LocalDateTime absoluteExpiryAt, LocalDateTime endedAt, String endReason) {

        public boolean active() {
            return endedAt == null;
        }
    }

    private static final String COLUMNS = """
            SELECT session_id, user_id, created_at, last_activity_at, absolute_expiry_at, ended_at, end_reason
            FROM iam.session
            """;

    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID sessionId, String userId, LocalDateTime now, LocalDateTime absoluteExpiry,
            byte[] refreshHash) {
        jdbc.sql("""
                INSERT INTO iam.session (session_id, user_id, created_at, last_activity_at, absolute_expiry_at,
                                         refresh_hash)
                VALUES (:id, :user, :now, :now, :expiry, :hash)
                """)
                .param("id", sessionId.toString())
                .param("user", userId)
                .param("now", now)
                .param("expiry", absoluteExpiry)
                .param("hash", refreshHash)
                .update();
    }

    public Optional<SessionRow> find(UUID sessionId) {
        return jdbc.sql(COLUMNS + "WHERE session_id = :id").param("id", sessionId.toString())
                .query(SessionRepository::row).optional();
    }

    public Optional<SessionRow> findByRefreshHash(byte[] hash) {
        return jdbc.sql(COLUMNS + "WHERE refresh_hash = :hash").param("hash", hash)
                .query(SessionRepository::row).optional();
    }

    public Optional<SessionRow> findByPreviousRefreshHash(byte[] hash) {
        return jdbc.sql(COLUMNS + "WHERE previous_refresh_hash = :hash").param("hash", hash)
                .query(SessionRepository::row).optional();
    }

    /**
     * Replaces the refresh token of an active session, keeping the old hash to recognise reuse.
     * False when the session ended or another request rotated {@code expected} first.
     */
    public boolean rotate(UUID sessionId, byte[] expected, byte[] next, LocalDateTime now) {
        return jdbc.sql("""
                UPDATE iam.session
                SET previous_refresh_hash = refresh_hash, refresh_hash = :next, last_activity_at = :now
                WHERE session_id = :id AND refresh_hash = :expected AND ended_at IS NULL
                """)
                .param("next", next)
                .param("now", now)
                .param("id", sessionId.toString())
                .param("expected", expected)
                .update() == 1;
    }

    /** Ends an active session; false when it had ended already. */
    public boolean end(UUID sessionId, LocalDateTime now, EndReason reason) {
        return jdbc.sql("""
                UPDATE iam.session SET ended_at = :now, end_reason = :reason
                WHERE session_id = :id AND ended_at IS NULL
                """)
                .param("now", now)
                .param("reason", reason.name())
                .param("id", sessionId.toString())
                .update() == 1;
    }

    private static SessionRow row(ResultSet rs, int n) throws SQLException {
        return new SessionRow(
                UUID.fromString(rs.getString("session_id")),
                LegacyText.rtrim(rs.getString("user_id")),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("last_activity_at", LocalDateTime.class),
                rs.getObject("absolute_expiry_at", LocalDateTime.class),
                rs.getObject("ended_at", LocalDateTime.class),
                rs.getString("end_reason"));
    }
}
