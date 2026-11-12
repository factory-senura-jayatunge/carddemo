package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;

/**
 * Both modes publish through the same code and differ only in the file set they expect (batch.md,
 * "Build rulings", legacy crash cases): {@link StatementPublication} never reads the run's mode,
 * the folder publication and the folder check are called from it alone, and the one branch on the
 * mode in the publication path is {@link StatementFolders#expected}. This is why the crash after
 * some links and the lease-loss cases run in fixed mode only.
 */
class StatementPublicationModesTest {

    static final Path PACKAGE = StatementCaptureSourceTest.MAIN.resolve("org/carddemo/batch/statement");
    static final Pattern MODE = Pattern.compile("\\bmode\\(\\)|\\bMode\\.");

    private static String source(String name) throws IOException {
        return Files.readString(PACKAGE.resolve(name), StandardCharsets.UTF_8);
    }

    /** The source files of the package that contain {@code pattern}. */
    private static List<String> callers(Pattern pattern) throws IOException {
        List<String> files = new ArrayList<>();
        try (Stream<Path> paths = Files.list(PACKAGE)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                if (pattern.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                    files.add(file.getFileName().toString());
                }
            }
        }
        return files;
    }

    private static int occurrences(Pattern pattern, String source) {
        Matcher m = pattern.matcher(source);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    void thePublicationNeverReadsTheMode() throws IOException {
        assertThat(source("StatementPublication.java")).as("the scan reads the source").contains("publishFolder(");
        assertThat(occurrences(MODE, source("StatementPublication.java"))).isZero();
    }

    @Test
    void bothModesPublishAndVerifyThroughTheSameCalls() throws IOException {
        assertThat(callers(Pattern.compile("\\bFolderPublication\\.publishFolder\\(")))
                .containsExactly("StatementPublication.java");
        assertThat(callers(Pattern.compile("\\bStatementFolders\\.verify\\(")))
                .containsExactly("StatementPublication.java");
        assertThat(occurrences(Pattern.compile("\\bStatementFolders\\.verify\\([^;]*StatementFolders\\.expected\\(run, runs\\)"),
                source("StatementPublication.java"))).isEqualTo(2);
        assertThat(occurrences(Pattern.compile("\\bmode\\(\\)"), source("StatementFolders.java"))).isEqualTo(1);
    }

    @Test
    void theExpectedFileSetIsTheOnlyDifference() {
        StatementRuns runs = new StatementRuns(TestDatabase.shared().admin());
        assertThat(StatementFolders.expected(run(StatementRuns.Mode.LEGACY), runs))
                .containsOnlyKeys(StatementFolders.HTML, StatementFolders.TEXT)
                .containsEntry(StatementFolders.HTML, null).containsEntry(StatementFolders.TEXT, null);
        // A run with no account rows: the fixed set is its index alone.
        assertThat(StatementFolders.expected(run(StatementRuns.Mode.FIXED), runs))
                .containsOnlyKeys(StatementFolders.INDEX);
    }

    private static StatementRuns.Run run(StatementRuns.Mode mode) {
        return new StatementRuns.Run(-1, -1, 1, mode, StatementRuns.Status.PUBLISHING, 0, null, "0-1", "0-1.0",
                "0".repeat(64), new StatementRuns.Counts(0, 0, 0, 0), 0);
    }
}
