package org.carddemo.batch.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.carddemo.batch.BatchServiceApplication;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The default mode, as make load starts it: the application runs dataLoadJob once in a CLI window
 * and its exit code says whether the job completed.
 */
class LoadCommandTest {

    static final TestDatabase DB = TestDatabase.shared();
    static final Path SAMPLE_DIR = Path.of(System.getProperty("carddemo.repo.root", "../../.."), "app", "data", "ASCII")
            .toAbsolutePath().normalize();

    /** The command's private CARDDEMO_VAR_DIR (O8), mode 0700, removed after the class. */
    @TempDir
    static Path varDir;

    @BeforeEach
    void clean() {
        DB.admin().sql("UPDATE ops.batch_window SET closed_at = SYSUTCDATETIME() WHERE closed_at IS NULL").update();
        DB.admin().sql("DELETE FROM core.daily_reject").update();
        DB.admin().sql("DELETE FROM core.daily_tran").update();
        DB.fixtures().deleteAll();
    }

    private static int runOnce(String... more) {
        return runOnce(SAMPLE_DIR, more);
    }

    private static int runOnce(Path sourceDir, String... more) {
        List<String> args = new java.util.ArrayList<>(List.of(
                "--spring.profiles.active=demo",
                "--spring.datasource.url=" + DB.jdbcUrl(),
                "--spring.datasource.username=" + DB.loginName(ServiceLogin.BATCH),
                "--spring.datasource.password=" + DB.password(ServiceLogin.BATCH),
                "--carddemo.data-load.source-dir=" + sourceDir,
                "--carddemo.var-dir=" + varDir));
        args.addAll(List.of(more));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BatchServiceApplication.class)
                .run(args.toArray(String[]::new));
        return SpringApplication.exit(context);
    }

    private static List<Map<String, Object>> cliLoads() {
        return DB.admin().sql("SELECT e.JOB_EXECUTION_ID AS id, e.STATUS AS status, p.PARAMETER_VALUE AS run_key "
                        + "FROM batch.BATCH_JOB_EXECUTION e JOIN batch.BATCH_JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID "
                        + "JOIN batch.BATCH_JOB_EXECUTION_PARAMS p ON p.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID "
                        + "AND p.PARAMETER_NAME = 'runKey' WHERE i.JOB_NAME = 'dataLoadJob' AND p.PARAMETER_VALUE LIKE 'cli/%' "
                        + "ORDER BY e.JOB_EXECUTION_ID DESC").query().listOfRows();
    }

    @Test
    void theLoadRunsInItsOwnWindowAndExitsZeroWhenItCompletes() {
        int before = cliLoads().size();
        assertThat(runOnce()).isZero();

        List<Map<String, Object>> loads = cliLoads();
        assertThat(loads).hasSize(before + 1);
        Map<String, Object> load = loads.getFirst();
        assertThat(load.get("status")).isEqualTo("COMPLETED");
        String key = (String) load.get("run_key");
        assertThat(key).matches("cli/[^/]{1,185}/\\d{8}T\\d{6}\\.\\d{6}Z/[0-9a-f-]{36}");
        assertThat(DB.admin().sql("SELECT COUNT(*) FROM ops.batch_window WHERE run_key = :key AND closed_at IS NOT NULL")
                .param("key", key).query(Integer.class).single()).isOne();
        assertThat(DB.admin().sql("SELECT COUNT(*) FROM ops.batch_window WHERE closed_at IS NULL")
                .query(Integer.class).single()).isZero();
        assertThat(DB.admin().sql("SELECT COUNT(*) FROM core.account").query(Integer.class).single()).isEqualTo(50);
    }

    /** The real job's listener writes the file; a failed load names its execution too. */
    @Test
    void theExecutionIdFileNamesTheLoadsExecution(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("load.id");
        assertThat(runOnce("--carddemo.batch.load.execution-id-file=" + file)).isZero();
        assertThat(Files.readString(file)).isEqualTo(cliLoads().getFirst().get("id") + "\n");

        Path failedFile = dir.resolve("failed.id");
        assertThat(runOnce(dir.resolve("no-such-dir"), "--carddemo.batch.load.execution-id-file=" + failedFile))
                .isOne();
        Map<String, Object> failed = cliLoads().getFirst();
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(Files.readString(failedFile)).isEqualTo(failed.get("id") + "\n");
    }

    @Test
    void aWindowHeldByAnotherRunStopsTheLoadWithExitCodeOne() {
        int before = cliLoads().size();
        DB.admin().sql("INSERT INTO ops.batch_window (run_key, mode, opened_by) VALUES ('carddemo_test/held', "
                + "'WRITES_BLOCKED', 'LoadCommandTest')").update();
        try {
            assertThat(runOnce()).isOne();
        } finally {
            DB.admin().sql("UPDATE ops.batch_window SET closed_at = SYSUTCDATETIME() WHERE closed_at IS NULL").update();
        }
        assertThat(cliLoads()).hasSize(before);
        assertThat(DB.admin().sql("SELECT COUNT(*) FROM core.account").query(Integer.class).single()).isZero();
    }
}
