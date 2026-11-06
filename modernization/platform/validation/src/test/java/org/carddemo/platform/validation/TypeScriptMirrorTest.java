package org.carddemo.platform.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Runs the TypeScript mirror's own tests ({@code node --test src/test/ts}) with the pinned Node, so a
 * Maven build proves the Java validators and their mirror on the same vectors. Skipped only when the
 * job opts out of Node through {@code CARDDEMO_SETUP_SKIP}, as the other toolchain checks are.
 */
class TypeScriptMirrorTest {

    @Test
    void typeScriptMirrorPassesItsTests() throws Exception {
        String skip = System.getenv().getOrDefault("CARDDEMO_SETUP_SKIP", "");
        assumeFalse((" " + skip + " ").contains(" node "), "CARDDEMO_SETUP_SKIP has node");
        Path module = Path.of("").toAbsolutePath();
        Path modernization = module.getParent().getParent();
        Path toolchain = modernization.resolve("scripts/toolchain.sh");
        assertThat(toolchain).isRegularFile();
        String nodeBin = run(List.of(toolchain.toString(), "node-bin"), module).strip();
        assertThat(nodeBin).as("Node 24 from scripts/toolchain.sh node-bin").isNotEmpty();
        String out = run(List.of(Path.of(nodeBin, "node").toString(), "--import", "./src/test/ts/resolve-ts.mjs", "--test", "--test-reporter=tap",
                "src/test/ts/**/*.test.ts"), module);
        assertThat(out).contains("# fail 0").doesNotContain("# pass 0");
    }

    private static String run(List<String> command, Path dir) throws Exception {
        Path log = Files.createTempFile("ts-mirror", ".log");
        try {
            Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            assertThat(p.waitFor(120, TimeUnit.SECONDS)).as("%s finished", command).isTrue();
            String out = Files.readString(log, StandardCharsets.UTF_8);
            assertThat(p.exitValue()).as("%s exit code; output:%n%s", command, out).isZero();
            return out;
        } finally {
            Files.deleteIfExists(log);
        }
    }
}
