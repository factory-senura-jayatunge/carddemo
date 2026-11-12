package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.carddemo.db.CarddemoMigrations;
import org.carddemo.db.DatabaseProvisioner;
import org.carddemo.db.DbSettings;
import org.carddemo.db.ServiceLogin;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V424 on a database that already holds a completed report run: a scratch database migrated up to
 * V421 gets a COMPLETED row, which has no hash because the column does not exist yet, then the
 * remaining migrations. The row is kept as it is, the check that needs a hash stays untrusted, the
 * service reads the missing hash (so the download refuses the run, NO_SHA256), and a new
 * completion without a hash is refused.
 */
class ReportRunSha256UpgradeTest {

    private static final long EXECUTION = -7;

    @Test
    void aRunCompletedBeforeTheHashKeepsItsNullAndIsReadAsHavingNone() throws Exception {
        DbSettings shared = TestDatabase.shared().settings();
        String name = shared.databaseName() + "_v424";
        DbSettings settings = new DbSettings(shared.serverUrl(), shared.adminUser(), shared.adminPassword(), name,
                name + "_", shared.passwords());
        DatabaseProvisioner provisioner = new DatabaseProvisioner(settings);
        provisioner.recreateDatabase();
        provisioner.provisionLogins();
        Path versioned = Files.createTempDirectory("report-run-before-v424");
        try {
            DataSource admin = provisioner.databaseAdmin();
            // Flyway runs every repeatable on each migrate whatever the target, and the grants name
            // objects that only later versions create (output_sha256 from V424, pauth from V500). So
            // the step to V421 runs the versioned files alone; the last migrate runs the repeatables
            // after every versioned migration, as on an upgraded database.
            List<String> repeatables = copyVersionedMigrations(versioned);
            assertThat(repeatables).isNotEmpty();
            Flyway.configure().configuration(CarddemoMigrations.flyway(admin).getConfiguration())
                    .locations("filesystem:" + versioned).target("421").load().migrate();
            JdbcClient jdbc = JdbcClient.create(admin);
            assertThat(jdbc.sql("SELECT COL_LENGTH('batch.report_run', 'output_sha256')").query(Integer.class)
                    .optional()).isEmpty();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NULL")
                    .query(Integer.class).single()).as("no repeatable before V424").isZero();

            jdbc.sql("INSERT INTO batch.BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) "
                    + "VALUES (:e, 0, 'reportUpgradeTest', 'reportUpgradeTest')").param("e", EXECUTION).update();
            jdbc.sql("INSERT INTO batch.BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, "
                    + "LAST_UPDATED, STATUS) VALUES (:e, 0, :e, GETUTCDATE(), GETUTCDATE(), 'COMPLETED')")
                    .param("e", EXECUTION).update();
            long old = jdbc.sql("""
                    INSERT INTO batch.report_run (report_type, start_date, end_date, requested_by, job_execution_id,
                        status, started_at, finished_at)
                    OUTPUT inserted.report_run_id
                    VALUES ('CUSTOM', '2022-07-01', '2022-07-06', 'UPGRADE1', :e, 'COMPLETED', SYSUTCDATETIME(),
                        SYSUTCDATETIME())""").param("e", EXECUTION).query(Long.class).single();
            Map<String, Object> before = jdbc.sql("""
                    SELECT status, started_at, finished_at, failure_code, row_version
                    FROM batch.report_run WHERE report_run_id = :id""").param("id", old).query().singleRow();

            CarddemoMigrations.migrate(admin);

            assertThat(jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '424'")
                    .query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("SELECT script FROM flyway_schema_history WHERE version IS NULL AND success = 1")
                    .query(String.class).list()).as("every repeatable after the upgrade")
                    .containsExactlyInAnyOrderElementsOf(repeatables);
            Map<String, Object> after = jdbc.sql("""
                    SELECT status, started_at, finished_at, failure_code, row_version
                    FROM batch.report_run WHERE report_run_id = :id""").param("id", old).query().singleRow();
            assertThat(after.keySet()).isEqualTo(before.keySet());
            assertThat(after.get("status")).isEqualTo("COMPLETED");
            assertThat(after.get("started_at")).isEqualTo(before.get("started_at"));
            assertThat(after.get("finished_at")).isEqualTo(before.get("finished_at"));
            assertThat((byte[]) after.get("row_version")).as("V424 adds the column without touching the row")
                    .isEqualTo((byte[]) before.get("row_version"));
            assertThat(jdbc.sql("""
                            SELECT is_disabled, is_not_trusted FROM sys.check_constraints
                            WHERE name = 'CK_report_run_completed_sha256'""").query().singleRow())
                    .containsEntry("is_disabled", false).containsEntry("is_not_trusted", true);

            JdbcClient transaction = JdbcClient.create(provisioner.service(ServiceLogin.TRANSACTION));
            assertThat(transaction.sql("""
                            SELECT COUNT(*) FROM batch.report_run
                            WHERE report_run_id = :id AND status = 'COMPLETED' AND output_sha256 IS NULL""")
                    .param("id", old).query(Integer.class).single()).isOne();

            assertThatThrownBy(() -> jdbc.sql("UPDATE batch.report_run SET finished_at = SYSUTCDATETIME()"
                    + " WHERE report_run_id = :id").param("id", old).update())
                    .as("a changed old row is checked like a new one")
                    .hasMessageContaining("CK_report_run_completed_sha256");
            assertThatThrownBy(() -> jdbc.sql("""
                    INSERT INTO batch.report_run (report_type, start_date, end_date, requested_by, job_execution_id,
                        status, started_at, finished_at)
                    VALUES ('CUSTOM', '2022-07-01', '2022-07-06', 'UPGRADE1', :e, 'COMPLETED', SYSUTCDATETIME(),
                        SYSUTCDATETIME())""").param("e", EXECUTION).update())
                    .hasMessageContaining("CK_report_run_completed_sha256");
            assertThat(jdbc.sql("SELECT row_version FROM batch.report_run WHERE report_run_id = :id").param("id", old)
                    .query(byte[].class).single()).isEqualTo((byte[]) before.get("row_version"));
        } finally {
            try (Stream<Path> files = Files.walk(versioned)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } finally {
                PauthSummaryUpgradeTest.drop(provisioner, settings);
            }
        }
    }

    /**
     * Copies the versioned files of modernization/db/migrations, found from the module directory,
     * into {@code target}, and returns the names of the repeatable files left out.
     */
    private static List<String> copyVersionedMigrations(Path target) throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("db/migrations"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("db/migrations not found above " + Path.of("").toAbsolutePath());
        }
        try (Stream<Path> files = Files.list(dir.resolve("db/migrations"))) {
            List<Path> sql = files.filter(f -> f.getFileName().toString().endsWith(".sql")).toList();
            for (Path f : sql) {
                if (f.getFileName().toString().startsWith("V")) {
                    Files.copy(f, target.resolve(f.getFileName()));
                }
            }
            return sql.stream().map(f -> f.getFileName().toString()).filter(n -> n.startsWith("R__")).toList();
        }
    }
}
