package org.carddemo.platform.fidelity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.platform.testkit.engine.EngineTestKit;

class SpecItemExtensionTest {

    @TempDir
    Path dir;

    @Test
    void recordsItemsStatusAndFidelityModeOfEachTest() throws Exception {
        // A separate directory: the sample's items must not count as evidence in the real matrix.
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(Sample.class))
                .configurationParameter(SpecItemExtension.DIR_PROPERTY, dir.toString())
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(5).succeeded(3).failed(2));

        Path file = dir.resolve(Sample.class.getName() + ".jsonl");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(4);
        assertThat(lines).anySatisfy(l -> assertThat(l)
                .contains("\"method\":\"passes\"")
                .contains("\"items\":[\"INV-001\",\"BR-BILL-004\",\"SE-BILL-003\"]")
                .contains("\"status\":\"SUCCESSFUL\"")
                .contains("\"mode\":null"));
        assertThat(lines).anySatisfy(l -> assertThat(l)
                .contains("\"method\":\"fails\"").contains("\"status\":\"FAILED\""));
        assertThat(lines).anySatisfy(l -> assertThat(l)
                .contains("\"method\":\"bothModes\"").contains("\"mode\":\"LEGACY\"").contains("DEF-BILL-010"));
        assertThat(lines).anySatisfy(l -> assertThat(l)
                .contains("\"method\":\"bothModes\"").contains("\"mode\":\"FIXED\""));
        assertThat(lines).noneMatch(l -> l.contains("BILL-1X"));
    }

    @SpecItem("INV-001")
    static class Sample {

        @Test
        @SpecItem({"BR-BILL-004", "SE-BILL-003"})
        void passes() {
        }

        @Test
        @SpecItem("BR-BILL-001")
        void fails() {
            throw new AssertionError("expected");
        }

        @ParameterizedTest
        @EnumSource(FidelityMode.class)
        @SpecItem("DEF-BILL-010")
        void bothModes(FidelityMode mode) {
        }

        @Test
        @SpecItem("BR-BILL-1X")
        void malformedIdErrorsBeforeTheTestRuns() {
        }
    }

    @Test
    void quotesJsonSpecialCharacters() {
        assertThat(SpecItemExtension.quote("a\"b\\c\nd\u0001")).isEqualTo("\"a\\\"b\\\\c\\nd\\u0001\"");
    }
}
