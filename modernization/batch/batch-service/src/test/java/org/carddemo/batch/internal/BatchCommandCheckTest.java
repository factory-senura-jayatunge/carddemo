package org.carddemo.batch.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.carddemo.batch.BatchServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * A command that nothing runs fails the start instead of exiting 0: an unknown
 * carddemo.batch.command, none outside server mode, and a cycle-rerun without a valid cycle ID.
 */
class BatchCommandCheckTest {

    private static MockEnvironment environment(String command, String webApplicationType, String cycleId) {
        MockEnvironment environment = new MockEnvironment();
        if (command != null) {
            environment.setProperty("carddemo.batch.command", command);
        }
        if (webApplicationType != null) {
            environment.setProperty("spring.main.web-application-type", webApplicationType);
        }
        if (cycleId != null) {
            environment.setProperty("carddemo.batch.rerun.cycle-id", cycleId);
        }
        return environment;
    }

    @Test
    void theKnownCommandsStart() {
        assertThatCode(() -> BatchCommandCheck.check(environment("load", "none", null))).doesNotThrowAnyException();
        assertThatCode(() -> BatchCommandCheck.check(environment("cycle-rerun", "none", "42")))
                .doesNotThrowAnyException();
        assertThatCode(() -> BatchCommandCheck.check(environment("cycle-rerun", "none", "2147483647")))
                .doesNotThrowAnyException();
        assertThatCode(() -> BatchCommandCheck.check(environment("none", "servlet", null))).doesNotThrowAnyException();
        assertThatCode(() -> BatchCommandCheck.check(environment("none", null, null))).doesNotThrowAnyException();
    }

    @Test
    void anUnknownOrEmptyCommandOrNoneWithoutTheApiFailsTheStart() {
        for (String command : new String[] {"rerun", "cycle_rerun", "", "  load"}) {
            assertThatThrownBy(() -> BatchCommandCheck.check(environment(command, "none", "42")))
                    .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("carddemo.batch.command must be");
        }
        assertThatThrownBy(() -> BatchCommandCheck.check(environment(null, "none", null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BatchCommandCheck.check(environment("none", "none", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'none'");
    }

    @Test
    void aOneShotCommandWithTheJobsTurnedOffFailsTheStart() {
        for (String command : new String[] {"load", "cycle-rerun"}) {
            MockEnvironment off = environment(command, "none", "42");
            off.setProperty("spring.batch.job.enabled", "false");
            assertThatThrownBy(() -> BatchCommandCheck.check(off)).as(command).isInstanceOf(IllegalStateException.class)
                    .hasMessage(command + " does not run with spring.batch.job.enabled=false");
            MockEnvironment on = environment(command, "none", "42");
            on.setProperty("spring.batch.job.enabled", "true");
            assertThatCode(() -> BatchCommandCheck.check(on)).as(command).doesNotThrowAnyException();
        }
        MockEnvironment server = environment("none", "servlet", null);
        server.setProperty("spring.batch.job.enabled", "false");
        assertThatCode(() -> BatchCommandCheck.check(server)).doesNotThrowAnyException();
    }

    @Test
    void aCycleRerunWithoutAValidCycleIdFailsTheStart() {
        for (String id : new String[] {null, "", "abc", "0", "-3", "07", "2147483648", "9999999999", "12345678901"}) {
            assertThatThrownBy(() -> BatchCommandCheck.check(environment("cycle-rerun", "none", id)))
                    .as("cycle-id %s", id).isInstanceOf(IllegalStateException.class)
                    .hasMessage("cycle-rerun needs carddemo.batch.rerun.cycle-id, a cycle ID");
        }
    }

    /** The jar's main, as make starts it: the check is registered and the JVM exits non-zero. */
    @Test
    void theApplicationExitsNonZeroBeforeItTouchesTheDatabase() throws Exception {
        assertThat(runMain("--carddemo.batch.command=rerun")).isNotZero();
        assertThat(runMain("--carddemo.batch.command=cycle-rerun")).isNotZero();
        assertThat(runMain("--carddemo.batch.command=cycle-rerun", "--carddemo.batch.rerun.cycle-id=abc")).isNotZero();
        assertThat(runMain("--carddemo.batch.command=load", "--spring.batch.job.enabled=false")).isNotZero();
    }

    /** A database nobody listens on: a start that got past the check would fail differently, later. */
    private static int runMain(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), BatchServiceApplication.class.getName(),
                "--spring.datasource.url=jdbc:sqlserver://127.0.0.1:9;databaseName=none;loginTimeout=1"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(output).as("failed at the check").contains("BatchCommandCheck")
                .doesNotContain("HikariPool").doesNotContain("Started BatchServiceApplication");
        return process.exitValue();
    }
}
