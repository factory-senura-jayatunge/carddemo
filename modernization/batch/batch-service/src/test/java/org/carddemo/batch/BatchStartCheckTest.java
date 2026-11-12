package org.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.carddemo.batch.internal.BatchCommandCheck;
import org.carddemo.platform.outputroot.OutputRootException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;

/**
 * The service started as its jar starts, with its application.properties and nothing else: no
 * CARDDEMO_VAR_DIR, and none of the developer's environment or system properties. The check
 * comes before any bean, so no database is needed; reaching the data source would fail with
 * another error.
 */
@ExtendWith(OutputCaptureExtension.class)
class BatchStartCheckTest {

    @TempDir
    Path temp;

    static void start(String... args) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        new SpringApplicationBuilder(BatchServiceApplication.class).environment(environment)
                .listeners(new BatchCommandCheck()).run(args).close();
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
        assertThatThrownBy(() -> start()).satisfies(e -> assertThat(refusal(e))
                .hasMessage("OUTPUT_ROOT_INVALID: CARDDEMO_VAR_DIR is unset")
                .extracting(OutputRootException::code).isEqualTo(OutputRootException.Code.OUTPUT_ROOT_INVALID));
    }

    @Test
    void noVarDirFailsTheServerStartToo() {
        assertThatThrownBy(() -> start("--spring.profiles.active=server", "--server.port=0"))
                .satisfies(e -> assertThat(refusal(e)).hasMessage("OUTPUT_ROOT_INVALID: CARDDEMO_VAR_DIR is unset"));
    }

    @Test
    void aRelativeVarDirFailsTheStart() {
        assertThatThrownBy(() -> start("--carddemo.var-dir=var"))
                .satisfies(e -> assertThat(refusal(e).code()).isEqualTo(OutputRootException.Code.OUTPUT_ROOT_INVALID));
    }

    @Test
    void theOldFolderSettingsFailTheStartByNameOnly(CapturedOutput output) {
        for (String name : List.of("CARDDEMO_INBOUND_DIR", "CARDDEMO_OUTPUT_DIR", "carddemo.batch.files.inbound-dir",
                "carddemo.batch.files.output-dir")) {
            assertThatThrownBy(() -> start("--carddemo.var-dir=" + temp, "--" + name + "=/secret/old/folder"))
                    .satisfies(e -> assertThat(refusal(e))
                            .hasMessage("OUTPUT_ROOT_LEGACY_SETTING: " + name
                                    + " is no longer read; remove it (outputs are under CARDDEMO_VAR_DIR/out/)")
                            .extracting(OutputRootException::code)
                            .isEqualTo(OutputRootException.Code.OUTPUT_ROOT_LEGACY_SETTING));
        }
        assertThat(output.getAll()).doesNotContain("/secret/old");
    }
}
