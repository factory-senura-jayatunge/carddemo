package org.carddemo.identity.user;

import java.time.LocalDateTime;
import java.util.Optional;

import org.carddemo.identity.signon.LegacyText;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final String SYSTEM = "SYSTEM";

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> find(String userId) {
        return jdbc.sql("""
                SELECT user_id, first_name, last_name, password_hash, user_type,
                       must_change_password, failed_attempts, locked_until
                FROM iam.app_user
                WHERE user_id = :id
                """)
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

    public long count() {
        return jdbc.sql("SELECT COUNT_BIG(*) FROM iam.app_user").query(Long.class).single();
    }

    /**
     * Counts one failed attempt. The attempt that reaches {@code maxFailures} sets
     * {@code locked_until} and restarts the count at zero; returns whether it did.
     */
    public boolean recordFailure(String userId, int maxFailures, LocalDateTime lockUntil) {
        return jdbc.sql("""
                UPDATE iam.app_user
                SET failed_attempts = CASE WHEN failed_attempts + 1 >= :max THEN 0 ELSE failed_attempts + 1 END,
                    locked_until = CASE WHEN failed_attempts + 1 >= :max THEN :until ELSE locked_until END
                OUTPUT CASE WHEN deleted.failed_attempts + 1 >= :max THEN 1 ELSE 0 END
                WHERE user_id = :id
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
        jdbc.sql("""
                UPDATE iam.app_user
                SET failed_attempts = 0, locked_until = NULL, last_sign_on_at = :now
                WHERE user_id = :id
                """)
                .param("now", now)
                .param("id", userId)
                .update();
    }

    public void changePassword(String userId, String hash, LocalDateTime now) {
        jdbc.sql("""
                UPDATE iam.app_user
                SET password_hash = :hash, must_change_password = 0, failed_attempts = 0,
                    locked_until = NULL, updated_at = :now, updated_by = :id
                WHERE user_id = :id
                """)
                .param("hash", hash)
                .param("now", now)
                .param("id", userId)
                .update();
    }

    /** Adds the user unless the ID exists; returns whether a row was inserted. */
    public boolean insertIfAbsent(AppUser user, LocalDateTime now) {
        return jdbc.sql("""
                INSERT INTO iam.app_user (user_id, first_name, last_name, password_hash, user_type,
                                          must_change_password, ebcdic_sort_key, created_at, created_by)
                SELECT :id, :first, :last, :hash, :type, :mustChange, :sortKey, :now, :by
                WHERE NOT EXISTS (SELECT 1 FROM iam.app_user WITH (UPDLOCK, HOLDLOCK) WHERE user_id = :id)
                """)
                .param("id", user.userId())
                .param("first", user.firstName())
                .param("last", user.lastName())
                .param("hash", user.passwordHash())
                .param("type", String.valueOf(user.userType()))
                .param("mustChange", user.mustChangePassword())
                .param("sortKey", EbcdicSortKey.of(user.userId()))
                .param("now", now)
                .param("by", SYSTEM)
                .update() == 1;
    }
}
