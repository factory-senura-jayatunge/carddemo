package org.carddemo.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

/**
 * The start check runs before any database setting is read: these runs have no database at all,
 * so reaching DbSettings or a connection would fail with a different message.
 */
@ExtendWith(OutputCaptureExtension.class)
class DbMigrateStartCheckTest {

    static final String SENTINEL = "sentinel-7c1e0b";

    @TempDir
    Path temp;

    @Test
    void noVarDirFailsTheRunBeforeTheDatabase(CapturedOutput output) throws Exception {
        DbMigrateApplication.Runner runner = new DbMigrateApplication.Runner(new MockEnvironment());
        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
        assertThat(output.getOut()).contains("db-migrate: FAILED (OUTPUT_ROOT_INVALID: ").doesNotContain("db-migrate: OK");
    }

    @Test
    void aLegacySettingFailsTheRunByNameOnly(CapturedOutput output) throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("carddemo.var-dir", temp.toString())
                .withProperty("CARDDEMO_REPORTS_DIR", "/secret/old/reports");
        DbMigrateApplication.Runner runner = new DbMigrateApplication.Runner(environment);
        runner.run(new DefaultApplicationArguments("--new-instance"));

        assertThat(runner.getExitCode()).isEqualTo(1);
        assertThat(output.getOut()).contains("db-migrate: FAILED (OUTPUT_ROOT_LEGACY_SETTING: CARDDEMO_REPORTS_DIR is no longer read;"
                + " remove it (outputs are under CARDDEMO_VAR_DIR/out/))");
        assertThat(output.getAll()).doesNotContain("/secret/old/reports");
    }

    @Test
    void aRelativeVarDirFailsTheRun(CapturedOutput output) throws Exception {
        DbMigrateApplication.Runner runner = new DbMigrateApplication.Runner(
                new MockEnvironment().withProperty("CARDDEMO_VAR_DIR", "var"));
        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
        assertThat(output.getOut()).contains("db-migrate: FAILED (OUTPUT_ROOT_INVALID: ");
    }

    /** Contract 4.10, Messages outside the root: the failure line has the code and a fixed reason, never the path. */
    @Test
    void aVarDirThatIsAFileFailsWithoutItsPath(CapturedOutput output) throws Exception {
        Path file = Files.writeString(temp.resolve("var-" + SENTINEL), "x");
        DbMigrateApplication.Runner runner = new DbMigrateApplication.Runner(
                new MockEnvironment().withProperty("carddemo.var-dir", file.toString()));
        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
        assertThat(output.getOut()).contains("db-migrate: FAILED (OUTPUT_ROOT_INVALID: CARDDEMO_VAR_DIR check)");
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }

    @Test
    void aRelativeVarDirFailsWithoutItsValue(CapturedOutput output) throws Exception {
        DbMigrateApplication.Runner runner = new DbMigrateApplication.Runner(
                new MockEnvironment().withProperty("CARDDEMO_VAR_DIR", "var-" + SENTINEL));
        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
        assertThat(output.getOut()).contains("db-migrate: FAILED (OUTPUT_ROOT_INVALID: CARDDEMO_VAR_DIR check)");
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }
}
