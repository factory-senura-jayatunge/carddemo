package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.carddemo.platform.outputroot.OutputRootException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;

/**
 * The service started as its jar starts, with application.yml and nothing else: no
 * CARDDEMO_VAR_DIR, and none of the developer's environment or system properties. The check
 * comes before any bean, so no database is needed; reaching the data source would fail with
 * another error.
 */
@ExtendWith(OutputCaptureExtension.class)
class TransactionStartCheckTest {

    @TempDir
    Path temp;

    static void start(String... args) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        new SpringApplicationBuilder(TransactionApplication.class).environment(environment).run(args).close();
    }

    static OutputRootException refusal(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof OutputRootException e) {
                return e;
            }
        }
        throw new AssertionError("no OutputRootException in " + failure, failure);
    }

    @Test
    void noVarDirFailsTheStart() {
        assertThatThrownBy(() -> start("--server.port=0")).satisfies(e -> assertThat(refusal(e))
                .hasMessage("OUTPUT_ROOT_INVALID: CARDDEMO_VAR_DIR is unset")
                .extracting(OutputRootException::code).isEqualTo(OutputRootException.Code.OUTPUT_ROOT_INVALID));
    }

    @Test
    void aRelativeVarDirFailsTheStart() {
        assertThatThrownBy(() -> start("--server.port=0", "--carddemo.var-dir=var"))
                .satisfies(e -> assertThat(refusal(e).code()).isEqualTo(OutputRootException.Code.OUTPUT_ROOT_INVALID));
    }

    @Test
    void theOldReportRootFailsTheStartByNameOnly(CapturedOutput output) {
        for (String name : new String[] {"CARDDEMO_TRANREPT_DIR", "carddemo.transaction.report.root"}) {
            assertThatThrownBy(() -> start("--server.port=0", "--carddemo.var-dir=" + temp,
                    "--" + name + "=/secret/old/tranrept")).satisfies(e -> assertThat(refusal(e))
                    .hasMessage("OUTPUT_ROOT_LEGACY_SETTING: " + name
                            + " is no longer read; remove it (outputs are under CARDDEMO_VAR_DIR/out/)")
                    .extracting(OutputRootException::code)
                    .isEqualTo(OutputRootException.Code.OUTPUT_ROOT_LEGACY_SETTING));
        }
        assertThat(output.getAll()).doesNotContain("/secret/old");
    }

    static IllegalArgumentException stagingRefusal(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IllegalArgumentException e && e.getMessage() != null
                    && e.getMessage().startsWith(ReportStaging.PROPERTY + " ")) {
                return e;
            }
        }
        throw new AssertionError("no staging refusal in " + failure, failure);
    }

    @Test
    void aWrongStagingFolderFailsTheStartByNameOnly(CapturedOutput output) throws Exception {
        Path out = Files.createDirectories(temp.resolve("out"));
        String[][] cases = {
            {"secret-staging/relative", ReportStaging.PROPERTY + " must be an absolute path"},
            {out.resolve("secret-staging").toString(),
                ReportStaging.PROPERTY + " must not be CARDDEMO_VAR_DIR/out, inside it, or contain it"},
        };
        for (String[] c : cases) {
            assertThatThrownBy(() -> start("--server.port=0", "--carddemo.var-dir=" + temp,
                    "--" + ReportStaging.PROPERTY + "=" + c[0]))
                    .satisfies(e -> assertThat(stagingRefusal(e)).hasMessage(c[1]));
        }
        assertThat(output.getAll()).doesNotContain("secret-staging");
    }
}
