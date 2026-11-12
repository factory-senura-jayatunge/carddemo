package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class RefusalOptionsTest {

    private static final String[] FULL = {"--refusal", "--job", "CREASTMT", "--cycle-id", "7",
        "--load-execution-id", "0", "--job-execution-id", "41", "--output-dir", "out/refusal"};

    @Test
    void readsEveryOptionInAnyOrder() {
        RefusalOptions o = RefusalOptions.parse(FULL);
        assertThat(o).isEqualTo(new RefusalOptions(7, 0, 41, Path.of("out/refusal")));
        String[] shuffled = {"--output-dir", "out/refusal", "--job-execution-id", "41", "--refusal",
            "--load-execution-id", "0", "--cycle-id", "7", "--job", "CREASTMT"};
        assertThat(RefusalOptions.parse(shuffled)).isEqualTo(o);
        assertThat(RefusalOptions.parse(with(8, "9223372036854775807")).jobExecutionId()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void theFlagAnywhereSelectsTheMode() {
        assertThat(RefusalOptions.requested(FULL)).isTrue();
        assertThat(RefusalOptions.requested(new String[] {"--export", "x", "--refusal"})).isTrue();
        assertThat(RefusalOptions.requested(new String[] {"--export", "x", "--job", "POSTTRAN"})).isFalse();
        assertThat(RefusalOptions.requested(new String[] {"--REFUSAL", "--refusal=1"})).isFalse();
    }

    @Test
    void everyOptionIsRequiredAndGivenOnce() {
        for (int i = 0; i < FULL.length; i++) {
            if (FULL[i].startsWith("--")) {
                List<String> args = new ArrayList<>(Arrays.asList(FULL));
                args.remove(i);
                if (!FULL[i].equals("--refusal")) {
                    args.remove(i);
                }
                String[] missing = args.toArray(String[]::new);
                assertThatThrownBy(() -> RefusalOptions.parse(missing)).as(FULL[i])
                        .isInstanceOf(UsageException.class);
                List<String> twice = new ArrayList<>(Arrays.asList(FULL));
                twice.add(FULL[i]);
                if (!FULL[i].equals("--refusal")) {
                    twice.add(FULL[i + 1]);
                }
                assertThatThrownBy(() -> RefusalOptions.parse(twice.toArray(String[]::new))).as(FULL[i])
                        .isInstanceOf(UsageException.class);
            }
        }
        assertThatThrownBy(() -> RefusalOptions.parse(Arrays.copyOf(FULL, FULL.length - 1)))
                .isInstanceOf(UsageException.class).hasMessageContaining("--output-dir");
    }

    @Test
    void onlyCreastmtAndTheListedOptions() {
        for (String job : new String[] {"POSTTRAN", "creastmt", "CREASTMT ", ""}) {
            assertThatThrownBy(() -> RefusalOptions.parse(with(2, job))).isInstanceOf(UsageException.class);
        }
        String[] extra = Arrays.copyOf(FULL, FULL.length + 2);
        extra[FULL.length] = "--export";
        extra[FULL.length + 1] = "x";
        assertThatThrownBy(() -> RefusalOptions.parse(extra)).isInstanceOf(UsageException.class);
    }

    @Test
    void integersAreCanonicalAndInRange() {
        for (int index : new int[] {4, 6, 8}) {
            for (String bad : new String[] {"01", "+1", "-1", "1.0", " 1", "1 ", "0x1", "", "9223372036854775808"}) {
                assertThatThrownBy(() -> RefusalOptions.parse(with(index, bad))).as(index + ":" + bad)
                        .isInstanceOf(UsageException.class);
            }
        }
        assertThatThrownBy(() -> RefusalOptions.parse(with(4, "0"))).isInstanceOf(UsageException.class);
        assertThatThrownBy(() -> RefusalOptions.parse(with(8, "0"))).isInstanceOf(UsageException.class);
        assertThat(RefusalOptions.parse(with(6, "0")).loadExecutionId()).isZero();
    }

    @Test
    void anEmptyOutputDirIsUsage() {
        assertThatThrownBy(() -> RefusalOptions.parse(with(10, ""))).isInstanceOf(UsageException.class);
    }

    @Test
    void messagesNameOptionsNeverValues() {
        String canary = RefusalStubs.CANARY;
        for (int index : new int[] {2, 4, 6, 8}) {
            assertThatThrownBy(() -> RefusalOptions.parse(with(index, canary))).isInstanceOf(UsageException.class)
                    .hasMessageNotContaining(canary);
        }
        String[] unknown = Arrays.copyOf(FULL, FULL.length + 1);
        unknown[FULL.length] = canary;
        assertThatThrownBy(() -> RefusalOptions.parse(unknown)).isInstanceOf(UsageException.class)
                .hasMessageNotContaining(canary);
    }

    @Test
    void theUsageLineIsPartOfTheExportersUsage() {
        assertThat(ExportOptions.USAGE).endsWith("\n" + RefusalOptions.USAGE);
    }

    private static String[] with(int index, String value) {
        String[] args = FULL.clone();
        args[index] = value;
        return args;
    }
}
