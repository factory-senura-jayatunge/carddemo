package org.carddemo.identity.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.carddemo.identity.signon.LegacyText;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * iam.app_user and its sign-on state in iam.user_signon_state. A logically deleted user
 * ({@code deleted_at} set) keeps its row and its ID, and every read here treats it as absent.
 *
 * <p>Sign-on writes only the state table, so a sign-on or a failed attempt never moves
 * {@code app_user.row_version}, the version user administration saves and deletes against. The
 * one exception is the upgrade of a {@code {bcrypt}} hash, once per user ({@link #upgradeHash}). Every
 * add writes the state row with the user row; a user without one (a row written outside the
 * service) has no failed attempts, no lock and no sign-on yet, and sign-on adds it.
 */
@Repository
public class UserRepository {

    private static final String SYSTEM = "SYSTEM";

    private static final String MANAGED_COLUMNS = """
            SELECT user_id, first_name, last_name, user_type, password_hash, row_version
            FROM iam.app_user
            """;

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> find(String userId) {
        return find(userId, "");
    }

    /**
     * The active user and its sign-on state, both rows update-locked until the end of the caller's
     * transaction: what a session is started from, so a change committed after the unlocked
     * {@link #find} cannot slip in before the session.
     */
    public Optional<AppUser> findLocked(String userId) {
        return find(userId, " WITH (UPDLOCK, ROWLOCK)");
    }

    private Optional<AppUser> find(String userId, String hint) {
        return jdbc.sql("""
                SELECT u.user_id, u.first_name, u.last_name, u.password_hash, u.user_type,
                       u.must_change_password, COALESCE(s.failed_attempts, 0) AS failed_attempts, s.locked_until
                FROM iam.app_user u%1$s
                LEFT JOIN iam.user_signon_state s%1$s ON s.user_id = u.user_id
                WHERE u.user_id = :id AND u.deleted_at IS NULL
                """.formatted(hint))
                .param("id", userId)
                .query((rs, n) -> new AppUser(
                        LegacyText.rtrim(rs.getString("user_id")),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("password_hash"),
                        rs.getString("user_type").charAt(0),
                        rs.getBoolean("must_change_password"),
                        rs.getInt("failed_attempts"),
                        rs.getObject("locked_until", LocalDateTime.class)))
                .optional();
    }

    /** Every row, deleted users included: the seed runs only on an empty table. */
    public long count() {
        return jdbc.sql("SELECT COUNT_BIG(*) FROM iam.app_user").query(Long.class).single();
    }

    /**
     * Counts one failed attempt. The attempt that reaches {@code maxFailures} sets
     * {@code locked_until} and restarts the count at zero; returns whether it did.
     */
    public boolean recordFailure(String userId, int maxFailures, LocalDateTime lockUntil) {
        ensureState(userId);
        return jdbc.sql("""
                UPDATE iam.user_signon_state
                SET failed_attempts = CASE WHEN failed_attempts + 1 >= :max THEN 0 ELSE failed_attempts + 1 END,
                    locked_until = CASE WHEN failed_attempts + 1 >= :max THEN :until ELSE locked_until END
                OUTPUT CASE WHEN deleted.failed_attempts + 1 >= :max THEN 1 ELSE 0 END
                WHERE user_id = :id
                  AND EXISTS (SELECT 1 FROM iam.app_user u WHERE u.user_id = :id AND u.deleted_at IS NULL)
                """)
                .param("max", maxFailures)
                .param("until", lockUntil)
                .param("id", userId)
                .query(Integer.class)
                .optional()
                .map(locked -> locked == 1)
                .orElse(false);
    }

    public void recordSignOn(String userId, LocalDateTime now) {
        ensureState(userId);
        jdbc.sql("""
                UPDATE iam.user_signon_state
                SET failed_attempts = 0, locked_until = NULL, last_sign_on_at = :now
                WHERE user_id = :id
                """)
                .param("now", now)
                .param("id", userId)
                .update();
    }

    /** The user's own password change: a write of the record, which also clears the lockout. */
    public void changePassword(String userId, String hash, LocalDateTime now) {
        jdbc.sql("""
                UPDATE iam.app_user
                SET password_hash = :hash, must_change_password = 0, updated_at = :now, updated_by = :id
                WHERE user_id = :id
                """)
                .param("hash", hash)
                .param("now", now)
                .param("id", userId)
                .update();
        clearLockout(userId);
    }

    /**
     * Replaces a stored {@code {bcrypt}} hash by {@code newHash}, inside the caller's transaction,
     * only while the row still holds {@code oldHash}; nothing else in the row changes. Returns
     * whether it did. Under BIN2 so that a hash differing only in case is not the old one.
     */
    public boolean upgradeHash(String userId, String oldHash, String newHash) {
        return jdbc.sql("""
                UPDATE iam.app_user SET password_hash = :new
                WHERE user_id = :id AND password_hash COLLATE Latin1_General_100_BIN2 = :old AND deleted_at IS NULL
                """)
                .param("new", newHash)
                .param("id", userId)
                .param("old", oldHash)
                .update() == 1;
    }

    /**
     * Adds the state row of an existing user unless it has one. One statement, so the key-range
     * lock of HOLDLOCK makes a concurrent add for the same user wait and then find this row.
     */
    private void ensureState(String userId) {
        jdbc.sql("""
                INSERT INTO iam.user_signon_state (user_id)
                SELECT u.user_id FROM iam.app_user u
                WHERE u.user_id = :id
                  AND NOT EXISTS (SELECT 1 FROM iam.user_signon_state WITH (UPDLOCK, HOLDLOCK) WHERE user_id = :id)
                """)
                .param("id", userId)
                .update();
    }

    private void clearLockout(String userId) {
        jdbc.sql("UPDATE iam.user_signon_state SET failed_attempts = 0, locked_until = NULL WHERE user_id = :id")
                .param("id", userId)
                .update();
    }

    /**
     * Adds the user and its state row unless the ID exists, inside the caller's transaction;
     * returns whether a row was inserted.
     */
    public boolean insertIfAbsent(AppUser user, LocalDateTime now) {
        return insert(user, now, SYSTEM).isPresent();
    }

    /**
     * Adds the user and its state row unless a row with the ID exists, deleted users included,
     * inside the caller's transaction. Returns the new row version, or empty when the ID exists.
     */
    public Optional<byte[]> insert(AppUser user, LocalDateTime now, String createdBy) {
        Optional<byte[]> version = jdbc.sql("""
                INSERT INTO iam.app_user (user_id, first_name, last_name, password_hash, user_type,
                                          must_change_password, created_at, created_by)
                OUTPUT inserted.row_version
                SELECT :id, :first, :last, :hash, :type, :mustChange, :now, :by
                WHERE NOT EXISTS (SELECT 1 FROM iam.app_user WITH (UPDLOCK, HOLDLOCK) WHERE user_id = :id)
                """)
                .param("id", user.userId())
                .param("first", user.firstName())
                .param("last", user.lastName())
                .param("hash", user.passwordHash())
                .param("type", String.valueOf(user.userType()))
                .param("mustChange", user.mustChangePassword())
                .param("now", now)
                .param("by", createdBy)
                .query((rs, n) -> rs.getBytes(1))
                .optional();
        if (version.isPresent()) {
            jdbc.sql("INSERT INTO iam.user_signon_state (user_id) VALUES (:id)")
                    .param("id", user.userId())
                    .update();
        }
        return version;
    }

    /**
     * The user add of user administration, inside the caller's transaction: a new ID is inserted,
     * and the row of a logically deleted user is reused as if new (the add of COUSR01C works on a
     * deleted ID). Returns the new row version, or empty when an active user has the ID.
     */
    public Optional<byte[]> addOrReuse(AppUser user, LocalDateTime now, String createdBy) {
        // UPDLOCK, HOLDLOCK keeps the key, or the gap where it would go, locked until commit, so a
        // concurrent add of the same ID waits and then finds this one.
        Optional<Boolean> deleted = jdbc.sql("""
                SELECT CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END
                FROM iam.app_user WITH (UPDLOCK, HOLDLOCK) WHERE user_id = :id
                """)
                .param("id", user.userId())
                .query(Integer.class)
                .optional()
                .map(d -> d == 1);
        if (deleted.isEmpty()) {
            return insert(user, now, createdBy);
        }
        if (!deleted.get()) {
            return Optional.empty();
        }
        // The re-added user starts with no failed attempts, no lock and no sign-on.
        ensureState(user.userId());
        jdbc.sql("""
                UPDATE iam.user_signon_state SET failed_attempts = 0, locked_until = NULL, last_sign_on_at = NULL
                WHERE user_id = :id
                """)
                .param("id", user.userId())
                .update();
        return jdbc.sql("""
                UPDATE iam.app_user
                SET first_name = :first, last_name = :last, password_hash = :hash, user_type = :type,
                    must_change_password = :mustChange, created_at = :now, created_by = :by, updated_at = NULL,
                    updated_by = NULL, deleted_at = NULL, deleted_by = NULL
                OUTPUT inserted.row_version
                WHERE user_id = :id AND deleted_at IS NOT NULL
                """)
                .param("first", user.firstName())
                .param("last", user.lastName())
                .param("hash", user.passwordHash())
                .param("type", String.valueOf(user.userType()))
                .param("mustChange", user.mustChangePassword())
                .param("now", now)
                .param("by", createdBy)
                .param("id", user.userId())
                .query((rs, n) -> rs.getBytes(1))
                .optional();
    }

    /**
     * One page of active users in {@code user_id} order (the column's BIN2 collation): those whose
     * ID matches {@code likePattern} (escape character {@code \}), after or before a key when one
     * is given, at most {@code limit} rows, descending for a page read backwards.
     */
    public List<ListedUser> list(String likePattern, String after, String before, int limit) {
        if (after != null && before != null) {
            throw new IllegalArgumentException("after and before");
        }
        String bound = after != null ? " AND user_id > :key" : before != null ? " AND user_id < :key" : "";
        String order = before != null ? "DESC" : "ASC";
        var spec = jdbc.sql("SELECT TOP (:limit) user_id, first_name, last_name, user_type FROM iam.app_user"
                        + " WHERE deleted_at IS NULL AND user_id LIKE :pattern ESCAPE '\\'" + bound
                        + " ORDER BY user_id " + order)
                .param("limit", limit)
                .param("pattern", likePattern);
        if (after != null || before != null) {
            spec = spec.param("key", after != null ? after : before);
        }
        return spec.query((rs, n) -> new ListedUser(
                        LegacyText.rtrim(rs.getString("user_id")),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        LegacyText.rtrim(rs.getString("user_type"))))
                .list();
    }

    /** The active user, read without a lock. */
    public Optional<ManagedUser> fetch(String userId) {
        return jdbc.sql(MANAGED_COLUMNS + "WHERE user_id = :id AND deleted_at IS NULL")
                .param("id", userId)
                .query(UserRepository::managed)
                .optional();
    }

    /** The active user, update-locked until the end of the caller's transaction. */
    public Optional<ManagedUser> fetchForUpdate(String userId) {
        return jdbc.sql(MANAGED_COLUMNS + "WITH (UPDLOCK, ROWLOCK) WHERE user_id = :id AND deleted_at IS NULL")
                .param("id", userId)
                .query(UserRepository::managed)
                .optional();
    }

    /**
     * Saves names and type, and the password when {@code passwordHash} is not null (which also
     * clears the forced change and the lockout). Returns the new row version, or empty when the
     * row is no longer at {@code expectedVersion}.
     */
    public Optional<byte[]> update(String userId, byte[] expectedVersion, String firstName, String lastName,
            String userType, String passwordHash, String actor, LocalDateTime now) {
        // Two statements rather than a CASE on a nullable parameter: the driver cannot type a
        // parameter that is only compared with NULL.
        String password = passwordHash == null ? "" : """
                password_hash = :hash, must_change_password = 0,
                """;
        var spec = jdbc.sql("""
                UPDATE iam.app_user
                SET first_name = :first, last_name = :last, user_type = :type,
                """ + password + """
                    updated_at = :now, updated_by = :actor
                OUTPUT inserted.row_version
                WHERE user_id = :id AND row_version = :version AND deleted_at IS NULL
                """)
                .param("first", firstName)
                .param("last", lastName)
                .param("type", userType)
                .param("now", now)
                .param("actor", actor)
                .param("id", userId)
                .param("version", expectedVersion);
        if (passwordHash != null) {
            spec = spec.param("hash", passwordHash);
        }
        Optional<byte[]> version = spec.query((rs, n) -> rs.getBytes(1)).optional();
        if (version.isPresent() && passwordHash != null) {
            clearLockout(userId);
        }
        return version;
    }

    /** Marks the user deleted; false when the row is no longer at {@code expectedVersion}. */
    public boolean markDeleted(String userId, byte[] expectedVersion, String actor, LocalDateTime now) {
        return jdbc.sql("""
                UPDATE iam.app_user SET deleted_at = :now, deleted_by = :actor
                WHERE user_id = :id AND row_version = :version AND deleted_at IS NULL
                """)
                .param("now", now)
                .param("actor", actor)
                .param("id", userId)
                .param("version", expectedVersion)
                .update() == 1;
    }

    /** Active type-A users other than {@code userId}. */
    public int otherActiveAdmins(String userId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM iam.app_user
                WHERE user_type = 'A' AND deleted_at IS NULL AND user_id <> :id
                """)
                .param("id", userId)
                .query(Integer.class)
                .single();
    }

    private static ManagedUser managed(ResultSet rs, int n) throws SQLException {
        return new ManagedUser(
                LegacyText.rtrim(rs.getString("user_id")),
                rs.getString("first_name"),
                rs.getString("last_name"),
                LegacyText.rtrim(rs.getString("user_type")),
                rs.getString("password_hash"),
                rs.getBytes("row_version"));
    }
}
