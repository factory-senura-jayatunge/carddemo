package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CountRulesTest {

    private static CountRules.Posting posting(long daily, long rejects, long posted, long interest, long ctxPosted,
            long ctxRejected, Long jobRejects, String exit) {
        return new CountRules.Posting(daily, rejects, posted, interest, ctxPosted, ctxRejected, jobRejects, exit);
    }

    @Test
    void aCompleteRunHasNoProblem() {
        assertThat(CountRules.problems(posting(300, 38, 262, 0, 262, 38, 38L, "COMPLETED_WITH_REJECTS"))).isEmpty();
        assertThat(CountRules.problems(posting(10, 0, 10, 0, 10, 0, 0L, "COMPLETED"))).isEmpty();
        assertThat(CountRules.problems(new CountRules.Interest(50, 50, 50, 50))).isEmpty();
    }

    @Test
    void everyDisagreementIsReported() {
        assertThat(CountRules.problems(posting(301, 38, 262, 0, 262, 38, 38L, "COMPLETED_WITH_REJECTS")))
                .singleElement().asString().contains("301 daily rows");
        assertThat(CountRules.problems(posting(300, 37, 262, 0, 262, 38, 37L, "COMPLETED_WITH_REJECTS")))
                .singleElement().asString().contains("37 reject rows");
        assertThat(CountRules.problems(posting(300, 37, 262, 0, 262, 38, 38L, "COMPLETED_WITH_REJECTS")))
                .hasSize(2);
        assertThat(CountRules.problems(posting(300, 38, 262, 0, 262, 38, null, "COMPLETED_WITH_REJECTS")))
                .singleElement().asString().contains("exportRejects");
        assertThat(CountRules.problems(posting(300, 38, 261, 0, 262, 38, 38L, "COMPLETED_WITH_REJECTS")))
                .singleElement().asString().contains("261 posted");
        assertThat(CountRules.problems(posting(300, 38, 262, 5, 262, 38, 38L, "COMPLETED_WITH_REJECTS")))
                .singleElement().asString().contains("before calculateInterestJob");
        assertThat(CountRules.problems(posting(300, 38, 262, 0, 262, 38, 38L, "COMPLETED")))
                .singleElement().asString().contains("expected COMPLETED_WITH_REJECTS");
        assertThat(CountRules.problems(posting(10, 0, 10, 0, 10, 0, 0L, "COMPLETED_WITH_REJECTS"))).hasSize(1);
        assertThat(CountRules.problems(new CountRules.Interest(49, 50, 50, 50))).hasSize(1);
        assertThat(CountRules.problems(new CountRules.Interest(50, 51, 50, 50))).hasSize(1);
    }

    @Test
    void theReturnCodeIsFourWithRejects() {
        assertThat(CountRules.postingReturnCode(0)).isZero();
        assertThat(CountRules.postingReturnCode(1)).isEqualTo(4);
    }
}
