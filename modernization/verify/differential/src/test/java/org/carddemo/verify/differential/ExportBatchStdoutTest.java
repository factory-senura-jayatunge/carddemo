package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.carddemo.db.ServiceLogin;
import org.junit.jupiter.api.Test;

/**
 * The driver parses {@code --load-report}'s stdout whole: it holds the one JSON object or
 * nothing, and the failure line and any log line go to stderr. Each case runs the exporter in its
 * own JVM, so the logging is configured as in a real run.
 */
class ExportBatchStdoutTest {

    private record Run(int exit, String stdout, String stderr) {
        List<String> stderrLines() {
            return stderr.lines().toList();
        }
    }

    private static Run run(Map<String, String> env, String mainClass, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), mainClass));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(env);
        Process process = builder.start();
        CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getErrorStream().readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        byte[] stdout = process.getInputStream().readAllBytes();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(process.exitValue(), new String(stdout, StandardCharsets.UTF_8),
                new String(stderr.join(), StandardCharsets.UTF_8));
    }

    /** Every setting DbSettings requires, with dummy passwords, and a server nobody listens on. */
    private static Map<String, String> deadDatabase() {
        Map<String, String> env = new java.util.HashMap<>();
        env.put("CARDDEMO_DB_SERVER_URL", "jdbc:sqlserver://127.0.0.1:9;loginTimeout=1");
        env.put("MSSQL_SA_PASSWORD", "dummy");
        for (ServiceLogin login : ServiceLogin.values()) {
            env.put(login.passwordVariable(), "dummy");
        }
        return env;
    }

    @Test
    void aLoadReportThatCannotReadTheDatabasePrintsNothingOnStdoutAndOneLineOnStderr() throws Exception {
        Run run = run(deadDatabase(), ExportBatch.class.getName(), "--load-report", "1");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stdout()).isEmpty();
        assertThat(run.stderrLines()).singleElement().asString()
                .startsWith("verify-differential: FAILED: the database cannot be read (");
    }

    /** Execution 0 is a valid ID, so the report reaches the database; a malformed ID is a usage error. */
    @Test
    void aLoadReportOfExecutionZeroReachesTheDatabaseAndAMalformedIdIsAUsageError() throws Exception {
        Run zero = run(deadDatabase(), ExportBatch.class.getName(), "--load-report", "0");

        assertThat(zero.exit()).isEqualTo(1);
        assertThat(zero.stdout()).isEmpty();
        assertThat(zero.stderrLines()).singleElement().asString()
                .startsWith("verify-differential: FAILED: the database cannot be read (");
        for (String bad : new String[] {"-1", "01", "9223372036854775808"}) {
            Run run = run(deadDatabase(), ExportBatch.class.getName(), "--load-report", bad);

            assertThat(run.exit()).as(bad).isEqualTo(2);
            assertThat(run.stdout()).as(bad).isEmpty();
            assertThat(run.stderrLines()).as(bad).anySatisfy(line -> assertThat(line).contains("--load-report must be"));
        }
    }

    private static final String[][] EVERY_MODE = {
            {"--load-report", "1"},
            {"--export", "unused", "--job", "POSTTRAN", "--cycle-id", "1", "--fidelity", "DEF-ACUP-002=legacy"},
            {"--export", "unused", "--job", "INTCALC", "--cycle-id", "1", "--load-execution-id", "1"}};

    @Test
    void everyModeWithoutTheSettingsExitsTwoWithOneLineOnStderr() throws Exception {
        for (String[] args : EVERY_MODE) {
            Run run = run(Map.of(), ExportBatch.class.getName(), args);

            assertThat(run.exit()).as(args[0]).isEqualTo(2);
            assertThat(run.stdout()).as(args[0]).isEmpty();
            assertThat(run.stderrLines()).as(args[0]).singleElement().asString()
                    .startsWith("verify-differential: FAILED: not set: MSSQL_SA_PASSWORD");
        }
    }

    @Test
    void everyModeWithAnInvalidSettingNamesNoValue() throws Exception {
        Map<String, String> env = deadDatabase();
        env.put("CARDDEMO_DB_SERVER_URL", "jdbc:sqlserver://settings-probe-host:9;databaseName=x");
        for (String[] args : EVERY_MODE) {
            Run run = run(env, ExportBatch.class.getName(), args);

            assertThat(run.exit()).as(args[0]).isEqualTo(2);
            assertThat(run.stdout()).as(args[0]).isEmpty();
            assertThat(run.stderrLines()).as(args[0]).singleElement().asString()
                    .isEqualTo("verify-differential: FAILED: the database settings are invalid (IllegalArgumentException)");
        }
    }

    @Test
    void aPrintedReportIsAloneOnStdoutAndLogLinesGoToStderrFromWarnUp() throws Exception {
        Run run = run(Map.of(), StdoutProbe.class.getName());

        assertThat(run.exit()).isZero();
        assertThat(run.stdout()).isEqualTo(new String(LoadReport.of(LoadReportTest.load(1), 1).json(),
                StandardCharsets.UTF_8));
        assertThat(run.stdout()).startsWith("{\n").endsWith("\n}\n");
        assertThat(run.stderr()).contains("probe-error", "probe-warn").doesNotContain("probe-info", "probe-debug");
    }
}
