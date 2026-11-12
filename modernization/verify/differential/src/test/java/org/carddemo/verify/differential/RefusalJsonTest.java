package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class RefusalJsonTest {

    @Test
    void sixKeysInOrder() {
        byte[] json = RefusalJson.of(new RefusalJson.Refusal(41, "FAILED", "STATEMENT_TABLE_LIMIT", false, true,
                "empty"));
        assertThat(new String(json, StandardCharsets.US_ASCII)).isEqualTo("{\n"
                + "  \"job_execution_id\": 41,\n"
                + "  \"exit_status\": \"FAILED\",\n"
                + "  \"exit_code\": \"STATEMENT_TABLE_LIMIT\",\n"
                + "  \"completed_run_row\": false,\n"
                + "  \"final_folder_exists\": true,\n"
                + "  \"work_folder\": \"empty\"\n"
                + "}\n");
    }

    @Test
    void everyValueOfEachClosedSetIsWritable() {
        for (String status : ExitTokens.EXIT_STATUSES) {
            for (String code : ExitTokens.EXIT_CODES) {
                for (String work : RefusalJson.WORK_FOLDERS) {
                    assertThat(RefusalJson.of(new RefusalJson.Refusal(1, status, code, true, false, work))).isNotEmpty();
                }
            }
        }
        assertThat(RefusalJson.of(new RefusalJson.Refusal(1, "unrecognised", "unrecognised", true, false, "absent")))
                .isNotEmpty();
    }

    @Test
    void aValueOutsideItsSetIsNeverWritten() {
        String canary = RefusalStubs.CANARY;
        RefusalJson.Refusal[] bad = {
            new RefusalJson.Refusal(0, "FAILED", "unrecognised", false, false, "absent"),
            new RefusalJson.Refusal(1, canary, "unrecognised", false, false, "absent"),
            new RefusalJson.Refusal(1, "failed", "unrecognised", false, false, "absent"),
            new RefusalJson.Refusal(1, null, "unrecognised", false, false, "absent"),
            new RefusalJson.Refusal(1, "FAILED", canary, false, false, "absent"),
            new RefusalJson.Refusal(1, "FAILED", "unrecognised", false, false, canary),
            new RefusalJson.Refusal(1, "FAILED", "unrecognised", false, false, null),
        };
        for (RefusalJson.Refusal r : bad) {
            assertThatThrownBy(() -> RefusalJson.of(r)).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(canary);
        }
    }
}
