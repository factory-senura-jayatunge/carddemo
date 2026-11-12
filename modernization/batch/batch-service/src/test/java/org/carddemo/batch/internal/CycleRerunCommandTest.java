package org.carddemo.batch.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.carddemo.batch.cycle.CycleRestore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;

/** The exit code of cycle-rerun: 0 only when the cycle was restored, 1 on every refusal or failure. */
class CycleRerunCommandTest {

    private static int exitCode(RuntimeException failure, String cycleId) {
        int[] calls = {0};
        CycleReruns reruns = new CycleReruns(null, null, null, null, null, null, null, null) {
            @Override
            public Rerun rerun(long id) {
                calls[0]++;
                if (failure != null) {
                    throw failure;
                }
                return new Rerun(new Cycle(id, LocalDate.of(2101, 6, 2), 2, null, "OPEN", null, null),
                        new CycleRestore.Restored(0, 1, 1, 1, 0, 1, 0), List.of(), false);
            }
        };
        CycleRerunCommand command = new CycleRerunCommand(reruns, cycleId);
        command.run(null);
        if (!BatchCommandCheck.isCycleId(cycleId.strip())) {
            assertThat(calls[0]).as("no rerun without a valid cycle ID").isZero();
        }
        return command.getExitCode();
    }

    @Test
    void aRestoredCycleExitsZero() {
        assertThat(exitCode(null, "7")).isZero();
        assertThat(exitCode(null, " 7 ")).isZero();
    }

    @Test
    void everyRefusalExitsOne() {
        for (String code : new String[] {"LATER_CYCLE_STARTED", "WRITTEN_SINCE_SNAPSHOT", "LATER_STATEMENT_COMPLETED",
                "EXECUTIONS_RUNNING", "CYCLE_CHANGED", "CYCLE_NOT_FOUND", "LAUNCH_IN_PROGRESS", "WINDOW_BUSY",
                "WINDOW_DRAIN_TIMEOUT", "DAILY_FILE_CHANGED"}) {
            assertThat(exitCode(new InternalApiException(HttpStatus.CONFLICT, code, "refused", null), "7")).as(code)
                    .isEqualTo(1);
        }
    }

    @Test
    void aFailedRecoveryOrRestoreExitsOne() {
        assertThat(exitCode(new CannotCreateTransactionException("no connection"), "7")).isEqualTo(1);
        assertThat(exitCode(new IllegalStateException("bug"), "7")).isEqualTo(1);
    }

    @Test
    void aMissingOrInvalidCycleIdExitsOneWithoutARerun() {
        for (String id : new String[] {"", "abc", "0", "-3", "2147483648", "9999999999", "12345678901"}) {
            assertThat(exitCode(null, id)).as("cycle-id '%s'", id).isEqualTo(1);
        }
    }
}
