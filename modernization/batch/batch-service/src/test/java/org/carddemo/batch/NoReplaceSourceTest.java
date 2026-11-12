package org.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * No main source of batch-service replaces or renames a path (batch-execution-contract.md section
 * 4.8): every published name is made by an operation that fails when the name exists.
 */
class NoReplaceSourceTest {

    static final List<String> FORBIDDEN = List.of("REPLACE_EXISTING", "ATOMIC_MOVE", "Files.move", "renameTo");

    @Test
    void noMainSourceReplacesOrRenamesAPath() throws IOException {
        Path main = Path.of("src", "main", "java");
        List<Path> sources;
        try (Stream<Path> files = Files.walk(main)) {
            sources = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertThat(sources).as("the sources this check reads").hasSizeGreaterThan(50)
                .contains(main.resolve(Path.of("org", "carddemo", "batch", "execution", "FolderPublication.java")));
        List<String> found = new ArrayList<>();
        for (Path source : sources) {
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                for (String word : FORBIDDEN) {
                    if (lines.get(i).contains(word)) {
                        found.add(main.relativize(source) + ":" + (i + 1) + " " + word);
                    }
                }
            }
        }
        assertThat(found).isEmpty();
    }
}
