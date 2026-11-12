package org.carddemo.platform.outputroot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Contract 4.10, Messages outside the root: the module's exception messages keep their paths, but a
 * line it logs itself names none. Every log call may pass only a fixed text and an exception's class
 * name, so a later call that adds a path argument fails here.
 */
class OutputRootLogTest {

    private static final Path SOURCE = Path.of("src/main/java/org/carddemo/platform/outputroot");
    private static final Pattern CALL = Pattern.compile("LOG\\.log\\((.*?)\\);", Pattern.DOTALL);
    private static final Pattern ALLOWED = Pattern.compile(
            "System\\.Logger\\.Level\\.[A-Z]+,\\s*\"[^\"]*\"(,\\s*[a-z]\\.getClass\\(\\)\\.getSimpleName\\(\\))?");

    @Test
    void everyLogLineHasAFixedTextAndAClassNameOnly() throws Exception {
        List<String> calls = new ArrayList<>();
        try (var files = Files.list(SOURCE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = CALL.matcher(Files.readString(file));
                while (m.find()) {
                    calls.add(m.group(1).strip());
                }
            }
        }
        assertThat(calls).isNotEmpty().allSatisfy(call -> assertThat(call).matches(ALLOWED));
    }
}
