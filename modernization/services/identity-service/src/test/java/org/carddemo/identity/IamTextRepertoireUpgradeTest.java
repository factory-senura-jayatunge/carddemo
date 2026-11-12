package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.sql.DataSource;

import org.carddemo.db.CarddemoMigrations;
import org.carddemo.db.DatabaseProvisioner;
import org.carddemo.db.DbSettings;
import org.carddemo.db.ServiceLogin;
import org.carddemo.identity.user.PasswordHasher;
import org.carddemo.platform.testsupport.TestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * V203 to V206 on a database that already runs every other migration: a scratch database is
 * migrated with all files except identity's (the integrated head before them), gets users with
 * bcrypt hashes, sessions and audit rows, and is then migrated to V205, so V203 to V205 run out of
 * order as they do on an upgraded database. With a deleted and a locked user added, the last
 * migration runs V206 and the grants. Values, password hashes, every user's sign-on state, the
 * foreign keys and the grants survive, the columns end as on a fresh database, and an Argon2id
 * hash fits.
 */
class IamTextRepertoireUpgradeTest {

    private static final List<String> IDENTITY_FILES = List.of("V203__", "V204__", "V205__", "V206__");
    /** Names V206's table; on an upgrade Flyway runs it after every versioned migration. */
    private static final String IAM_GRANTS = "R__grants_iam.sql";
    private static final PasswordHasher HASHER = new PasswordHasher(4);
    /** Hashes stored before V205 are bcrypt, as the service wrote them then. */
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(4);

    @Test
    void existingUsersSessionsAndAuditRowsSurviveAndStillSignOn() throws Exception {
        DbSettings shared = TestDatabase.shared().settings();
        String name = shared.databaseName() + "_v203";
        DbSettings settings = new DbSettings(shared.serverUrl(), shared.adminUser(), shared.adminPassword(), name,
                name + "_", shared.passwords());
        DatabaseProvisioner provisioner = new DatabaseProvisioner(settings);
        provisioner.recreateDatabase();
        provisioner.provisionLogins();
        Path before = Files.createTempDirectory("iam-before-v203");
        try {
            DataSource admin = provisioner.databaseAdmin();
            copyMigrations(before, file -> IDENTITY_FILES.stream().noneMatch(file::startsWith)
                    && !file.equals(IAM_GRANTS));
            Flyway.configure().configuration(CarddemoMigrations.flyway(admin).getConfiguration())
                    .locations("filesystem:" + before).load().migrate();
            JdbcClient jdbc = JdbcClient.create(admin);
            assertThat(jdbc.sql("SELECT TYPE_NAME(system_type_id) FROM sys.columns"
                    + " WHERE object_id = OBJECT_ID('iam.app_user') AND name = 'user_id'").query(String.class).single())
                    .isEqualTo("char");

            String hash = "{bcrypt}" + BCRYPT.encode("PASSWORD");
            jdbc.sql("""
                    INSERT INTO iam.app_user (user_id, first_name, last_name, password_hash, user_type,
                        must_change_password, failed_attempts, ebcdic_sort_key, created_by)
                    VALUES ('ADMIN001', 'MARGARET', 'GOLD', :hash, 'A', 0, 0, 0xC1C4D4C9D5F0F0F1, NULL),
                           ('USER0001', 'José', 'Ñúñez', :hash, 'U', 1, 2, 0xE4E2C5D9F0F0F0F1, 'ADMIN001'),
                           ('LEGACY01', 'L', 'X', :hash, 'X', 0, 0, 0xD3C5C7C1C3E8F0F1, 'ADMIN001')""")
                    .param("hash", hash).update();
            jdbc.sql("""
                    INSERT INTO iam.session (session_id, user_id, created_at, last_activity_at, absolute_expiry_at,
                        refresh_hash)
                    VALUES (NEWID(), 'USER0001', '2026-03-02T09:00:00', '2026-03-02T09:00:00', '2026-03-02T17:00:00',
                        HASHBYTES('SHA2_256', 'r1'))""").update();
            jdbc.sql("""
                    INSERT INTO iam.audit_event (occurred_at, actor_user_id, event_type, subject_key, detail)
                    VALUES ('2026-03-02T09:00:00', 'USER0001', 'SIGN_ON_OK', 'USER0001', NULL)""").update();
            // Statistics on the columns, as queries on a live database create them.
            jdbc.sql("SELECT COUNT(*) FROM iam.app_user WHERE first_name > 'A' AND user_type = 'U' AND created_by > 'A'")
                    .query(Integer.class).single();
            jdbc.sql("SELECT COUNT(*) FROM iam.audit_event WHERE actor_user_id > 'A'").query(Integer.class).single();
            List<Map<String, Object>> users = users(jdbc);
            List<Map<String, Object>> sessions = sessions(jdbc);
            List<Map<String, Object>> audit = jdbc.sql("SELECT * FROM iam.audit_event").query().listOfRows();

            copyMigrations(before, file -> IDENTITY_FILES.subList(0, 3).stream().anyMatch(file::startsWith));
            Flyway.configure().configuration(CarddemoMigrations.flyway(admin).getConfiguration())
                    .locations("filesystem:" + before).load().migrate();
            assertThat(users(jdbc)).isEqualTo(users);
            jdbc.sql("""
                    UPDATE iam.app_user SET deleted_at = '2026-03-02T10:00:00', deleted_by = N'ADMIN001'
                    WHERE user_id = N'LEGACY01'""").update();
            jdbc.sql("""
                    UPDATE iam.app_user SET locked_until = '2026-03-02T09:15:00', last_sign_on_at = '2026-03-01T08:00:00'
                    WHERE user_id = N'USER0001'""").update();
            List<Map<String, Object>> state = jdbc.sql("""
                    SELECT user_id, failed_attempts, locked_until, last_sign_on_at FROM iam.app_user ORDER BY user_id""")
                    .query().listOfRows();
            assertThat(state).hasSize(3);
            users = users(jdbc);

            CarddemoMigrations.migrate(admin);

            assertThat(users(jdbc)).isEqualTo(users);
            assertThat(jdbc.sql("""
                    SELECT user_id, failed_attempts, locked_until, last_sign_on_at FROM iam.user_signon_state
                    ORDER BY user_id""").query().listOfRows()).as("every user's state, the deleted one included")
                    .isEqualTo(state);
            assertThat(sessions(jdbc)).isEqualTo(sessions);
            assertThat(jdbc.sql("SELECT * FROM iam.audit_event").query().listOfRows()).isEqualTo(audit);
            for (Map<String, Object> u : users(jdbc)) {
                assertThat(HASHER.matches("PASSWORD", (String) u.get("password_hash"))).as("%s", u.get("user_id"))
                        .isTrue();
            }
            assertThat(IamSchemaTest.columns(jdbc)).containsAllEntriesOf(IamSchemaTest.expectedColumns())
                    .doesNotContainKeys("iam.app_user.ebcdic_sort_key", "iam.app_user.failed_attempts",
                            "iam.app_user.locked_until", "iam.app_user.last_sign_on_at");
            assertThat(IamSchemaTest.foreignKey(jdbc, "FK_signon_state_user"))
                    .isEqualTo("user_signon_state.user_id>app_user.user_id|0|CASCADE");
            assertThat(jdbc.sql("""
                    SELECT version FROM flyway_schema_history
                    WHERE version IN ('203', '204', '205', '206') AND success = 1""")
                    .query(String.class).list()).containsExactlyInAnyOrder("203", "204", "205", "206");
            assertThatThrownBy(() -> jdbc.sql("""
                    INSERT INTO iam.session (session_id, user_id, created_at, last_activity_at, absolute_expiry_at,
                        refresh_hash)
                    VALUES (NEWID(), 'NOBODY', '2026-03-02T09:00:00', '2026-03-02T09:00:00', '2026-03-02T17:00:00',
                        HASHBYTES('SHA2_256', 'r2'))""").update()).hasMessageContaining("FK_session_user");

            // The service login keeps its grants and can now store the whole legacy range.
            JdbcClient identity = JdbcClient.create(provisioner.service(ServiceLogin.IDENTITY));
            String id = "\u00c9\u0085\u0080\u009fA\u00a0\u00ff1";
            String argon2 = HASHER.hash("PASSWORD");
            assertThat(argon2).hasSize(105);
            String first = "\u0080\u0081\u008d\u008f\u0090\u009d\u009f\u00e9\u00df\u00ff" + "\u00c0".repeat(10);
            identity.sql("""
                    INSERT INTO iam.app_user (user_id, first_name, last_name, password_hash, user_type, created_by)
                    VALUES (:id, :first, :last, :hash, 'U', 'ADMIN001')""")
                    .param("id", id).param("first", first).param("last", "\u0000\u0001\u007f").param("hash", argon2)
                    .update();
            assertThat(identity.sql("SELECT first_name FROM iam.app_user WHERE user_id = :id").param("id", id)
                    .query(String.class).single()).isEqualTo(first).hasSize(20);
            assertThat(identity.sql("SELECT last_name FROM iam.app_user WHERE user_id = :id").param("id", id)
                    .query(String.class).single()).isEqualTo("\u0000\u0001\u007f");
            assertThat(identity.sql("SELECT password_hash FROM iam.app_user WHERE user_id = :id").param("id", id)
                    .query(String.class).single()).isEqualTo(argon2);
            identity.sql("INSERT INTO iam.user_signon_state (user_id) VALUES (:id)").param("id", id).update();
            identity.sql("UPDATE iam.user_signon_state SET failed_attempts = 1 WHERE user_id = :id").param("id", id)
                    .update();
        } finally {
            try (Stream<Path> files = Files.walk(before)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
            drop(provisioner, settings);
        }
    }

    /** The user columns every stage has; the sign-on state is compared on its own. */
    private static List<Map<String, Object>> users(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT user_id, first_name, last_name, password_hash, user_type, must_change_password,
                       created_at, created_by, updated_at, updated_by
                FROM iam.app_user ORDER BY user_id""").query().listOfRows();
    }

    private static List<Map<String, Object>> sessions(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT CAST(session_id AS VARCHAR(36)) AS session_id, user_id, created_at, last_activity_at,
                       absolute_expiry_at, ended_at, end_reason, CONVERT(VARCHAR(64), refresh_hash, 2) AS refresh_hash
                FROM iam.session ORDER BY session_id""").query().listOfRows();
    }

    /** The files of modernization/db/migrations that {@code wanted} names, found from the module directory. */
    private static void copyMigrations(Path target, Predicate<String> wanted) throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("db/migrations"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("db/migrations not found above " + Path.of("").toAbsolutePath());
        }
        try (Stream<Path> files = Files.list(dir.resolve("db/migrations"))) {
            for (Path f : files.toList()) {
                String file = f.getFileName().toString();
                if (file.endsWith(".sql") && wanted.test(file)) {
                    Files.copy(f, target.resolve(file));
                }
            }
        }
    }

    private static void drop(DatabaseProvisioner provisioner, DbSettings settings) throws Exception {
        try (Connection c = provisioner.serverAdmin().getConnection(); Statement s = c.createStatement()) {
            String db = "[" + settings.databaseName() + "]";
            s.execute("IF DB_ID(N'" + settings.databaseName() + "') IS NOT NULL BEGIN ALTER DATABASE " + db
                    + " SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE " + db + "; END");
            for (ServiceLogin login : ServiceLogin.values()) {
                String loginName = settings.loginName(login);
                s.execute("IF SUSER_ID(N'" + loginName + "') IS NOT NULL DROP LOGIN [" + loginName + "]");
            }
        }
    }
}
