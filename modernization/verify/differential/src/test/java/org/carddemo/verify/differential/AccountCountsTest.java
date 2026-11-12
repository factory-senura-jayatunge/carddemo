package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.carddemo.batch.data.CoreRows;
import org.junit.jupiter.api.Test;

/** The schema 2 account counts of an export and their invariants, without a database. */
class AccountCountsTest {

    private static CoreRows.AccountExport row(String origin, boolean restored) {
        return new CoreRows.AccountExport(null, origin, restored);
    }

    @Test
    void countsEachProvenanceAndExportRuleInKeyOrder() {
        Map<String, Long> counts = BatchExport.accountCounts(List.of(row("R", true), row("M", true), row("M", false),
                row(null, false)));

        assertThat(counts).containsExactly(Map.entry("accountRecords", 4L), Map.entry("accountOriginR", 1L),
                Map.entry("accountOriginM", 2L), Map.entry("accountOriginNone", 1L),
                Map.entry("accountZipGroupRestored", 2L), Map.entry("accountZipGroupRebuilt", 2L));
        assertThat(BatchExport.accountProblems(counts)).isEmpty();
    }

    @Test
    void brokenInvariantsAreNamed() {
        Map<String, Long> counts = new LinkedHashMap<>(BatchExport.accountCounts(List.of(row(null, false))));
        counts.put("accountZipGroupRestored", 1L);
        counts.put("accountZipGroupRebuilt", 0L);
        counts.put("accountOriginR", 1L);

        assertThat(BatchExport.accountProblems(counts)).containsExactly(
                "account origins R + M + none do not add up to the account records",
                "an account with no provenance was restored");
    }
}
