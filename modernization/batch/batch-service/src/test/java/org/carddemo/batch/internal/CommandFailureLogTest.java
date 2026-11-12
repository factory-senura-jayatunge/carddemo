package org.carddemo.batch.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;

import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.execution.LeaseOwner;
import org.carddemo.batch.load.DataLoadStarts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The one-shot commands log an unexpected failure by class and SQL error number, as the internal
 * API does, instead of leaving it to Spring Boot's "Application run failed", which logs the
 * driver's message and the statement values it quotes.
 */
@ExtendWith(OutputCaptureExtension.class)
class CommandFailureLogTest {

    /** Stands in for a statement value that a driver message quotes. */
    private static final String SENTINEL = "quoted-row-value-27182";

    static DataIntegrityViolationException driverFailure() {
        return new DataIntegrityViolationException("PreparedStatementCallback; duplicate key value is (" + SENTINEL + ")",
                new SQLException("Cannot insert duplicate key. The duplicate key value is (" + SENTINEL + ").",
                        "23000", 2627));
    }

    @Test
    void aFailedCycleRerunExitsOneAndLogsNoDriverMessage(CapturedOutput output) {
        CycleReruns failing = new CycleReruns(null, null, null, null, null, null, null, null) {
            @Override
            public Rerun rerun(long cycleId) {
                throw driverFailure();
            }
        };
        CycleRerunCommand command = new CycleRerunCommand(failing, "7");

        command.run(null);

        assertThat(command.getExitCode()).isEqualTo(1);
        assertThat(output).doesNotContain(SENTINEL)
                .contains("cycle-rerun of cycle 7 failed: DataIntegrityViolationException < SQLException SQL error 2627");
    }

    @Test
    void aFailedLoadAndWindowCloseExitOneAndLogNoDriverMessage(CapturedOutput output) throws InterruptedException {
        BatchWindows windows = new BatchWindows(null, null, null, null, null, null) {
            @Override
            public Map<String, Object> open(WindowHolder holder) {
                return Map.of();
            }

            @Override
            public Map<String, Object> close(WindowHolder holder, Boolean complete) {
                throw driverFailure();
            }
        };
        JobLaunches launches = new JobLaunches(null, null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public Launched launch(String jobName, Map<String, Object> request) {
                throw driverFailure();
            }
        };
        LoadCommand command = new LoadCommand(windows, launches, null,
                new ExecutionLeases(null, LeaseOwner.ofThisProcess(), null), new DataLoadStarts(), LocalDate.of(2022, 7, 6), "");

        command.run(null);

        assertThat(command.getExitCode()).isEqualTo(1);
        assertThat(output).doesNotContain(SENTINEL)
                .contains("dataLoadJob failed: DataIntegrityViolationException < SQLException SQL error 2627")
                .contains("did not close; close it with make batch-window-close: DataIntegrityViolationException");
    }

    @Test
    void aWindowThatDidNotOpenForAnUnexpectedReasonLogsNoDriverMessage(CapturedOutput output)
            throws InterruptedException {
        BatchWindows windows = new BatchWindows(null, null, null, null, null, null) {
            @Override
            public Map<String, Object> open(WindowHolder holder) {
                throw driverFailure();
            }
        };
        LoadCommand command = new LoadCommand(windows, null, null,
                new ExecutionLeases(null, LeaseOwner.ofThisProcess(), null), new DataLoadStarts(), LocalDate.of(2022, 7, 6), "");

        command.run(null);

        assertThat(command.getExitCode()).isEqualTo(1);
        assertThat(output).doesNotContain(SENTINEL)
                .contains("dataLoadJob not started: the batch window did not open: DataIntegrityViolationException");
    }
}
