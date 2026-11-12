package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** DX-3 with 118's EX-1 to EX-3: exact matches only, and only literals of the class leave. */
class ExitTokensTest {

    @Test
    void eachOfTheSevenStatusesIsKept() {
        for (String status : ExitTokens.EXIT_STATUSES) {
            assertThat(ExitTokens.exitStatus(new String(status.toCharArray()))).isSameAs(status);
        }
        assertThat(ExitTokens.EXIT_STATUSES).containsExactly("COMPLETED", "COMPLETED_WITH_REJECTS", "FAILED",
                "STOPPED", "UNKNOWN", "NOOP", "EXECUTING");
    }

    @Test
    void nearMissStatusesAreUnrecognised() {
        String[] nearMisses = {null, "", " ", "completed", "Completed", " COMPLETED", "COMPLETED ", "COMPLETED\n",
            "COMPLETEDX", "COMPLETE", "FAIL", "FAILED.", "FAILED\u0000", "\uFEFFFAILED", "NOOP_", "STARTED",
            "ABANDONED", "unrecognised", RefusalStubs.CANARY};
        for (String raw : nearMisses) {
            assertThat(ExitTokens.exitStatus(raw)).as(String.valueOf(raw)).isSameAs(ExitTokens.UNRECOGNISED);
        }
    }

    @Test
    void theTableLimitCodeIsAWholeLeadingWord() {
        for (String raw : new String[] {"STATEMENT_TABLE_LIMIT", "STATEMENT_TABLE_LIMIT: 51 statements",
            "STATEMENT_TABLE_LIMIT " + RefusalStubs.CANARY, "STATEMENT_TABLE_LIMIT\n", "STATEMENT_TABLE_LIMIT-x",
            "STATEMENT_TABLE_LIMITa"}) {
            assertThat(ExitTokens.exitCode(raw)).as(raw).isSameAs(ExitTokens.TABLE_LIMIT);
        }
    }

    @Test
    void nearMissCodesAreUnrecognised() {
        String[] nearMisses = {null, "", "STATEMENT_TABLE_LIMITS", "STATEMENT_TABLE_LIMIT_2", "STATEMENT_TABLE_LIMIT9",
            " STATEMENT_TABLE_LIMIT", "statement_table_limit", "STATEMENT_TABLE_LIMI", "STATEMENT-TABLE-LIMIT",
            "java.lang.IllegalStateException: STATEMENT_TABLE_LIMIT", RefusalStubs.CANARY};
        for (String raw : nearMisses) {
            assertThat(ExitTokens.exitCode(raw)).as(String.valueOf(raw)).isSameAs(ExitTokens.UNRECOGNISED);
        }
    }
}
