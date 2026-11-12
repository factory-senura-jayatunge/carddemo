package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class RefusalCodesTest {

    private static final Path SOURCE = Path.of("src/main/java/org/carddemo/verify/differential/RefusalCodes.java");

    @Test
    void theSetIsTheRulingsFourteenLiterals() {
        assertThat(RefusalCodes.ALL).containsExactly("execution-refused", "execution-not-final", "execution-changed",
                "database-read-failed", "instance-read-refused", "var-dir-refused", "destination-refused",
                "refusal-write-failed", "work-folder-unsupported", "work-folder-input", "instance-root-refused",
                "work-folder-refused", "work-folder-helper-failed", "refusal-internal-error");
        assertThat(new HashSet<>(RefusalCodes.ALL)).hasSize(14);
    }

    @Test
    void theHelpersCodesAreFourOfTheSet() {
        assertThat(RefusalCodes.HELPER).containsExactly("work-folder-unsupported", "work-folder-input",
                "instance-root-refused", "work-folder-refused");
        assertThat(RefusalCodes.ALL).containsAll(RefusalCodes.HELPER);
    }

    @Test
    void eachCodeIsOneLiteralOnALineOfItsOwn() throws Exception {
        Pattern literal = Pattern.compile("    static final String [A-Z_]+ = \"([a-z-]+)\";");
        List<String> found = Files.readAllLines(SOURCE).stream().map(literal::matcher).filter(Matcher::matches)
                .map(m -> m.group(1)).toList();
        assertThat(found).containsExactlyElementsOf(RefusalCodes.ALL);
    }

    @Test
    void theFailedLineHasOneFormAndOnlyACodeOfTheSet() {
        for (String code : RefusalCodes.ALL) {
            assertThat(RefusalCodes.failedLine(code)).isEqualTo("verify-differential: FAILED " + code);
        }
        assertThatThrownBy(() -> RefusalCodes.failedLine("unknown-code")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("unknown-code");
    }

    @Test
    void aRefusalCarriesItsCodeAndNothingElse() {
        RefusalException e = new RefusalException(RefusalCodes.EXECUTION_REFUSED);
        assertThat(e.code()).isEqualTo("execution-refused");
        assertThat(e.getMessage()).isEqualTo("execution-refused");
        assertThat(e.getCause()).isNull();
        assertThat(e.getStackTrace()).isEmpty();
        e.addSuppressed(new IllegalStateException(RefusalStubs.CANARY));
        assertThat(e.getSuppressed()).isEmpty();
        assertThatThrownBy(() -> new RefusalException(RefusalStubs.CANARY)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(RefusalStubs.CANARY);
    }
}
