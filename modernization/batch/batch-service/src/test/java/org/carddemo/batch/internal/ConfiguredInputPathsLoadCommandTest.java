package org.carddemo.batch.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.carddemo.batch.BatchServiceApplication;
import org.carddemo.batch.PrivateVarDir;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Configured input paths (contract section 4.10) in make load's output: with the load folder or
 * the pending-authorization unload set below a CARDDEMO_VAR_DIR whose name holds
 * {@link PrivateVarDir#SENTINEL}, and the file missing, the command exits 1 and its output names
 * the setting and the file's own name, never the sentinel.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConfiguredInputPathsLoadCommandTest {

    static final TestDatabase DB = TestDatabase.shared();
    static final Path SAMPLE_DIR = Path.of(System.getProperty("carddemo.repo.root", "../../.."), "app", "data", "ASCII")
            .toAbsolutePath().normalize();

    static Path varDir;

    @BeforeAll
    static void createVarDir() throws IOException {
        varDir = Files.createTempDirectory("carddemo-var-" + PrivateVarDir.SENTINEL + "-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    @AfterAll
    static void removeVarDir() throws IOException {
        try (Stream<Path> paths = Files.walk(varDir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    @BeforeEach
    void clean() {
        DB.admin().sql("UPDATE ops.batch_window SET closed_at = SYSUTCDATETIME() WHERE closed_at IS NULL").update();
        DB.admin().sql("DELETE FROM core.daily_reject").update();
        DB.admin().sql("DELETE FROM core.daily_tran").update();
        DB.fixtures().deleteAll();
    }

    private static int runOnce(String... settings) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=demo",
                "--spring.datasource.url=" + DB.jdbcUrl(),
                "--spring.datasource.username=" + DB.loginName(ServiceLogin.BATCH),
                "--spring.datasource.password=" + DB.password(ServiceLogin.BATCH),
                "--carddemo.var-dir=" + varDir));
        args.addAll(List.of(settings));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BatchServiceApplication.class)
                .run(args.toArray(String[]::new));
        return SpringApplication.exit(context);
    }

    private static String lastCliLoadStatus() {
        return DB.admin().sql("SELECT TOP 1 e.STATUS FROM batch.BATCH_JOB_EXECUTION e "
                + "JOIN batch.BATCH_JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID "
                + "JOIN batch.BATCH_JOB_EXECUTION_PARAMS p ON p.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID "
                + "AND p.PARAMETER_NAME = 'runKey' WHERE i.JOB_NAME = 'dataLoadJob' AND p.PARAMETER_VALUE LIKE 'cli/%' "
                + "ORDER BY e.JOB_EXECUTION_ID DESC").query(String.class).single();
    }

    @Test
    void aMissingLoadFolderBelowTheVarDirExitsOneNamingTheSetting(CapturedOutput output) {
        int exit = runOnce("--carddemo.data-load.source-dir=" + varDir.resolve("inbound").resolve("load"));

        assertThat(exit).isOne();
        assertThat(lastCliLoadStatus()).isEqualTo("FAILED");
        assertThat(output.getAll()).contains("acctdata.txt not found in carddemo.data-load.source-dir")
                .contains("ended FAILED / FAILED").doesNotContain(PrivateVarDir.SENTINEL);
    }

    @Test
    void aMissingUnloadBelowTheVarDirExitsOneNamingTheSetting(CapturedOutput output) {
        int exit = runOnce("--carddemo.data-load.source-dir=" + SAMPLE_DIR,
                "--carddemo.batch.pauth.source-file=" + varDir.resolve("inbound").resolve("DBPAUTP0.dat"));

        assertThat(exit).isOne();
        assertThat(lastCliLoadStatus()).isEqualTo("FAILED");
        assertThat(output.getAll()).contains("DBPAUTP0.dat not found (carddemo.batch.pauth.source-file)")
                .contains("ended FAILED / FAILED").doesNotContain(PrivateVarDir.SENTINEL);
    }
}
