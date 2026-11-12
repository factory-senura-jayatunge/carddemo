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

import org.junit.jupiter.api.Test;

/**
 * The capture is written once and never changed or removed by any code of batch-service
 * (batch.md, "Tables": no statement code deletes a capture row; UPDATE is also denied to
 * batch_svc). Only the capture's INSERT names the table in a writing statement.
 */
class StatementCaptureSourceTest {

    static final Path MAIN = Path.of("src/main/java").toAbsolutePath();

    /** A writing verb followed, within one statement, by the table's name. */
    static final Pattern WRITE = Pattern.compile(
            "\\b(UPDATE|DELETE|MERGE|TRUNCATE)\\b[^;\"]{0,200}?\\bstatement_capture\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    static final Pattern INSERT = Pattern.compile("\\bINSERT\\s+INTO\\s+batch\\.statement_capture\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    void noCodeUpdatesOrDeletesCaptureRows() throws IOException {
        List<String> writes = new ArrayList<>();
        List<String> inserts = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = WRITE.matcher(source);
                while (m.find()) {
                    writes.add(MAIN.relativize(file) + ": " + m.group().replaceAll("\\s+", " "));
                }
                if (INSERT.matcher(source).find()) {
                    inserts.add(MAIN.relativize(file).getFileName().toString());
                }
            }
        }
        assertThat(inserts).as("the scan reads the sources").containsExactly("StatementCapture.java");
        assertThat(writes).isEmpty();
    }
}
