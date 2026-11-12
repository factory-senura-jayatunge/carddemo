package org.carddemo.identity;

import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.carddemo.identity.user.PasswordHasher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** iam rows for tests, written and read as the database owner. */
public final class IdentityFixtures {

    /** The public demo password of the DUSRSECJ users (README). */
    public static final String DEMO_PASSWORD = "PASSWORD";

    private static final PasswordHasher HASHER = new PasswordHasher(4);
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(4);
    private static final Map<String, String> HASHES = new ConcurrentHashMap<>();
    private static final Map<String, String> BCRYPT_HASHES = new ConcurrentHashMap<>();

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

    public record OpsAuditRow(String service, String actor, String action, String subjectType, String subjectKey,
            String fieldNames) {
    }

    /** The user-admin view of a row, deleted rows included. */
    public record AdminRow(String firstName, String lastName, String userType, String passwordHash,
            boolean mustChange, String createdBy, String updatedBy, LocalDateTime deletedAt, String deletedBy) {
    }

    /** The users of the demo seed (DUSRSECJ): ADMIN001 to ADMIN005 type A, USER0001 to USER0005 type U. */
    public static final List<String> DEMO_SEED = List.of("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
            "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    private static final List<String> RESET = List.of(
            "DELETE FROM ops.audit WHERE service = 'identity-service'",
            "DELETE FROM iam.audit_event",
            "DELETE FROM iam.session",
            "DELETE FROM iam.user_signon_state",
            "DELETE FROM iam.app_user",
            "DELETE FROM ops.batch_window");

    /**
     * Bounded: a session a test left in a transaction fails the reset with a lock timeout (1222)
     * within seconds instead of hanging it. One delete per call, with NOCOUNT ON: a row count
     * ahead of the error would let update() return without reading the timeout.
     */
    public void deleteAll() {
        for (String delete : RESET) {
            admin.sql("SET NOCOUNT ON; SET LOCK_TIMEOUT 5000; " + delete + "; SET LOCK_TIMEOUT -1;").update();
        }
    }

    /** ADMIN001 (type A) and USER0001 (type U) with password PASSWORD. */
    public IdentityFixtures demoUsers() {
        user("ADMIN001", "MARGARET", "GOLD", 'A', DEMO_PASSWORD, false);
        user("USER0001", "LAWRENCE", "THOMAS", 'U', DEMO_PASSWORD, false);
        user("USER0002", "AJITH", "KUMAR", 'U', DEMO_PASSWORD, false);
        return this;
    }

    /** The ten demo seed users with password PASSWORD; the names are the seed's. */
    public IdentityFixtures demoSeed() {
        String[][] names = {{"MARGARET", "GOLD"}, {"RUSSELL", "RUSSELL"}, {"RAYMOND", "WHITMORE"},
                {"EMMANUEL", "CASGRAIN"}, {"GRANVILLE", "LACHAPELLE"}, {"LAWRENCE", "THOMAS"}, {"AJITH", "KUMAR"},
                {"LAURITZ", "ALME"}, {"AVERARDO", "MAZZI"}, {"LEE", "TING"}};
        for (int i = 0; i < DEMO_SEED.size(); i++) {
            String id = DEMO_SEED.get(i);
            user(id, names[i][0], names[i][1], id.startsWith("ADMIN") ? 'A' : 'U', DEMO_PASSWORD, false);
        }
        return this;
    }

    /** A user whose stored hash is an Argon2id hash of {@code password} exactly as given, as the service writes. */
    public IdentityFixtures user(String userId, String first, String last, char type, String password,
            boolean mustChange) {
        return insertUser(userId, first, last, type, HASHES.computeIfAbsent(password, HASHER::hash), mustChange);
    }

    /** A user whose stored hash is a {@code {bcrypt}} hash of {@code password}, as stored before Argon2id. */
    public IdentityFixtures bcryptUser(String userId, String first, String last, char type, String password,
            boolean mustChange) {
        return insertUser(userId, first, last, type,
                BCRYPT_HASHES.computeIfAbsent(password, p -> "{bcrypt}" + BCRYPT.encode(p)), mustChange);
    }

    private IdentityFixtures insertUser(String userId, String first, String last, char type, String hash,
            boolean mustChange) {
        admin.sql("""
                INSERT INTO iam.app_user (user_id, first_name, last_name, password_hash, user_type,
                                          must_change_password)
                VALUES (:id, :first, :last, :hash, :type, :mustChange)
                """)
                .param("id", userId).param("first", first).param("last", last).param("hash", hash)
                .param("type", String.valueOf(type)).param("mustChange", mustChange)
                .update();
        // As every add of the service does.
        admin.sql("INSERT INTO iam.user_signon_state (user_id) VALUES (:id)").param("id", userId).update();
        return this;
    }

    public IdentityFixtures openBatchWindow() {
        // A window must name exactly one holder, a cycle or a run key, once V406 adds run_key. The
        // insert naming run_key is dynamic SQL because a batch that names a missing column does not
        // compile, so this works on databases with and without V406.
        admin.sql("""
                IF COL_LENGTH('ops.batch_window', 'run_key') IS NULL
                    INSERT INTO ops.batch_window (mode, opened_by) VALUES ('WRITES_BLOCKED', 'test');
                ELSE
                    EXEC sp_executesql
                        N'INSERT INTO ops.batch_window (mode, opened_by, run_key) VALUES (''WRITES_BLOCKED'', ''test'', @runKey)',
                        N'@runKey VARCHAR(250)', @runKey = :runKey;""")
                .param("runKey", "test/" + UUID.randomUUID())
                .update();
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

    public List<OpsAuditRow> opsAudit() {
        return admin.sql("""
                SELECT service, RTRIM(actor_user_id), action, subject_type, subject_key, field_names
                FROM ops.audit WHERE service = 'identity-service' ORDER BY audit_id
                """)
                .query((rs, n) -> new OpsAuditRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)))
                .list();
    }

    public AdminRow adminRow(String userId) {
        return admin.sql("""
                SELECT first_name, last_name, user_type, password_hash, must_change_password,
                       RTRIM(created_by), RTRIM(updated_by), deleted_at, RTRIM(deleted_by)
                FROM iam.app_user WHERE user_id = :id
                """)
                .param("id", userId)
                .query((rs, n) -> new AdminRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), rs.getString(6), rs.getString(7), rs.getObject(8, LocalDateTime.class),
                        rs.getString(9)))
                .single();
    }

    /** Sets the sign-on state of an existing user, as sign-on would have left it. */
    public IdentityFixtures signOnState(String userId, int failedAttempts, LocalDateTime lockedUntil,
            LocalDateTime lastSignOnAt) {
        admin.sql("""
                MERGE iam.user_signon_state AS s USING (SELECT :id AS user_id) AS v ON s.user_id = v.user_id
                WHEN MATCHED THEN UPDATE SET failed_attempts = :failed, locked_until = :locked, last_sign_on_at = :last
                WHEN NOT MATCHED THEN INSERT (user_id, failed_attempts, locked_until, last_sign_on_at)
                    VALUES (:id, :failed, :locked, :last);
                """)
                .param("id", userId).param("failed", failedAttempts).param("locked", lockedUntil)
                .param("last", lastSignOnAt)
                .update();
        return this;
    }

    /** Stores an Argon2id hash of {@code password}, as a password change in another transaction would. */
    public IdentityFixtures changeHash(String userId, String password) {
        admin.sql("UPDATE iam.app_user SET password_hash = :hash WHERE user_id = :id")
                .param("hash", HASHER.hash(password)).param("id", userId).update();
        return this;
    }

    /** Stores {@code hash} as the user's password hash, as written. */
    public IdentityFixtures storeHash(String userId, String hash) {
        admin.sql("UPDATE iam.app_user SET password_hash = :hash WHERE user_id = :id")
                .param("hash", hash).param("id", userId).update();
        return this;
    }

    /** Applies {@code assignments} (a SET list written by the test) to the user's row. */
    public IdentityFixtures setUser(String userId, String assignments) {
        admin.sql("UPDATE iam.app_user SET " + assignments + " WHERE user_id = :id").param("id", userId).update();
        return this;
    }

    public IdentityFixtures withoutSignOnState(String userId) {
        admin.sql("DELETE FROM iam.user_signon_state WHERE user_id = :id").param("id", userId).update();
        return this;
    }

    public boolean hasSignOnState(String userId) {
        return admin.sql("SELECT COUNT(*) FROM iam.user_signon_state WHERE user_id = :id").param("id", userId)
                .query(Integer.class).single() == 1;
    }

    /** The row version user administration saves against, as lower-case hex. */
    public String rowVersion(String userId) {
        // Read as bytes: LOWER(CONVERT(VARCHAR(16), row_version, 2)) came back as the same blanks for
        // every version, so comparing it proved nothing.
        byte[] version = admin.sql("SELECT row_version FROM iam.app_user WHERE user_id = :id")
                .param("id", userId).query((rs, n) -> rs.getBytes(1)).single();
        return HexFormat.of().formatHex(version);
    }

    public List<SessionRow> sessions() {
        return admin.sql("SELECT CAST(session_id AS VARCHAR(36)), RTRIM(user_id), end_reason FROM iam.session")
                .query((rs, n) -> new SessionRow(rs.getString(1).toLowerCase(), rs.getString(2), rs.getString(3)))
                .list();
    }

    /** The user's hash and forced change, with its sign-on state (none yet reads as 0, null, null). */
    public UserRow user(String userId) {
        return admin.sql("""
                SELECT u.password_hash, u.must_change_password, COALESCE(s.failed_attempts, 0), s.locked_until,
                       s.last_sign_on_at
                FROM iam.app_user u LEFT JOIN iam.user_signon_state s ON s.user_id = u.user_id
                WHERE u.user_id = :id
                """)
                .param("id", userId)
                .query((rs, n) -> new UserRow(rs.getString(1), rs.getBoolean(2), rs.getInt(3),
                        rs.getObject(4, LocalDateTime.class), rs.getObject(5, LocalDateTime.class)))
                .single();
    }
}
