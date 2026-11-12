package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.carddemo.platform.fidelity.FidelityMode;
import org.junit.jupiter.api.Test;

class ExportOptionsTest {

    private static final String[] FULL = {"--export", "out/POSTTRAN", "--job", "POSTTRAN", "--cycle-id", "3",
        "--fidelity", "DEF-ACUP-002=fixed"};

    @Test
    void readsEveryOption() {
        ExportOptions o = ExportOptions.parse(FULL);
        assertThat(o.exportDir()).isEqualTo(Path.of("out/POSTTRAN"));
        assertThat(o.job()).isEqualTo(ExportOptions.Job.POSTTRAN);
        assertThat(o.cycleId()).isEqualTo(3);
        assertThat(o.groupIdMode()).isEqualTo(FidelityMode.FIXED);
        assertThat(o.outputDir()).isEmpty();
        assertThat(ExportOptions.parse(new String[] {"--job", "INTCALC", "--cycle-id", "1", "--fidelity",
            "DEF-ACUP-002=legacy", "--export", "x", "--output-dir", "var"}).outputDir()).contains(Path.of("var"));
    }

    @Test
    void theGroupIdModeIsNeverDefaulted() {
        assertThatThrownBy(() -> ExportOptions.parse(new String[] {"--export", "x", "--job", "INTCALC", "--cycle-id",
            "1"})).isInstanceOf(UsageException.class).hasMessageContaining("--fidelity");
    }

    @Test
    void refusesBadValues() {
        assertThatThrownBy(() -> ExportOptions.parse(with(3, "TRANREPT"))).isInstanceOf(UsageException.class);
        assertThatThrownBy(() -> ExportOptions.parse(with(5, "0"))).isInstanceOf(UsageException.class);
        assertThatThrownBy(() -> ExportOptions.parse(with(5, "x"))).isInstanceOf(UsageException.class);
        assertThatThrownBy(() -> ExportOptions.parse(with(7, "DEF-INTC-010=fixed"))).isInstanceOf(UsageException.class);
        assertThatThrownBy(() -> ExportOptions.parse(with(7, "DEF-ACUP-002=both"))).isInstanceOf(UsageException.class);
        assertThatThrownBy(() -> ExportOptions.parse(new String[] {"--export"})).isInstanceOf(UsageException.class);
        assertThatThrownBy(() -> ExportOptions.parse(new String[] {"--bogus", "1"})).isInstanceOf(UsageException.class);
    }

    private static final String[] V2 = {"--export", "out/INTCALC", "--job", "INTCALC", "--cycle-id", "3",
        "--load-execution-id", "12"};

    @Test
    void theLoadExecutionIdSelectsV2WithoutAMode() {
        ExportOptions v2 = ExportOptions.parse(V2);
        assertThat(v2.v2()).isTrue();
        assertThat(v2.loadExecutionId()).hasValue(12);
        assertThat(v2.groupIdMode()).isNull();
        assertThat(v2.fidelityDeprecated()).isFalse();
        ExportOptions v1 = ExportOptions.parse(FULL);
        assertThat(v1.v2()).isFalse();
        assertThat(v1.fidelityDeprecated()).isFalse();
    }

    @Test
    void inV2TheFidelityIsCheckedThenDeprecated() {
        String[] withFidelity = java.util.stream.Stream.concat(java.util.Arrays.stream(V2),
                java.util.stream.Stream.of("--fidelity", "DEF-ACUP-002=legacy")).toArray(String[]::new);
        assertThat(ExportOptions.parse(withFidelity).fidelityDeprecated()).isTrue();
        withFidelity[withFidelity.length - 1] = "DEF-ACUP-002=both";
        assertThatThrownBy(() -> ExportOptions.parse(withFidelity)).isInstanceOf(UsageException.class);
        assertThat(ExportOptions.FIDELITY_DEPRECATED).isEqualTo("verify-differential: warning: --fidelity is deprecated"
                + " and ignored; account records are exported per row (counts schemaVersion 2)");
    }

    /** Non-canonical, negative, malformed or out-of-range values, refused in both modes. */
    private static final String[] BAD_IDS = {"-1", "-0", "+1", "00", "01", " 1", "1 ", "", "x", "1.5", "1e3", "0x1",
        "\u0661", "9223372036854775808", "99999999999999999999"};

    @Test
    void theLoadExecutionIdIsACanonicalNonNegativeWholeNumber() {
        String[] args = V2.clone();
        for (String good : new String[] {"0", "1", "12", "9223372036854775807"}) {
            args[7] = good;
            assertThat(ExportOptions.parse(args).loadExecutionId()).as(good).hasValue(Long.parseLong(good));
        }
        for (String bad : BAD_IDS) {
            args[7] = bad;
            assertThatThrownBy(() -> ExportOptions.parse(args)).as("'%s'", bad).isInstanceOf(UsageException.class)
                    .hasMessageStartingWith("--load-execution-id must be");
        }
    }

    @Test
    void theCycleIdStaysAtLeastOne() {
        assertThatThrownBy(() -> ExportOptions.parse(with(5, "0"))).isInstanceOf(UsageException.class)
                .hasMessage("--cycle-id must be at least 1");
    }

    @Test
    void theLoadReportTakesOnlyItsId() {
        assertThat(ExportOptions.loadReport(new String[] {"--load-report", "4"})).hasValue(4);
        assertThat(ExportOptions.loadReport(new String[] {"--load-report", "0"})).as("a fresh repository's first load")
                .hasValue(0);
        assertThat(ExportOptions.loadReport(new String[] {"--load-report", "9223372036854775807"}))
                .hasValue(Long.MAX_VALUE);
        assertThat(ExportOptions.loadReport(V2)).isEmpty();
        assertThat(ExportOptions.loadReport(FULL)).isEmpty();
        for (String[] bad : new String[][] {{"--load-report"}, {"--load-report", "x"},
            {"--load-report", "4", "--export", "x"}, {"--export", "x", "--load-report", "4"},
            {"--load-report", "4", "--load-report", "4"}}) {
            assertThatThrownBy(() -> ExportOptions.loadReport(bad)).as(String.join(" ", bad))
                    .isInstanceOf(UsageException.class);
        }
        for (String bad : BAD_IDS) {
            assertThatThrownBy(() -> ExportOptions.loadReport(new String[] {"--load-report", bad})).as("'%s'", bad)
                    .isInstanceOf(UsageException.class).hasMessageStartingWith("--load-report must be");
        }
    }

    private static String[] with(int index, String value) {
        String[] args = FULL.clone();
        args[index] = value;
        return args;
    }
}
