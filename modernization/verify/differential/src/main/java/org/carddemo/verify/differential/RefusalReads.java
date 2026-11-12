package org.carddemo.verify.differential;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.carddemo.platform.outputroot.OutputRoot;

/**
 * The refusal mode's reads on the admin {@code ApplicationIntent=ReadOnly} connection. Every read
 * takes integer parameters only. Data-access failures leave as Spring's unchecked exceptions, and
 * the instance read as {@link SQLException} or {@link org.carddemo.platform.outputroot.OutputRootException}.
 */
interface RefusalReads {

    /** The {@code batch.cycle} row: its business date and attempt, as the cycle jobs are identified. */
    record Cycle(LocalDate businessDate, int attempt) {
    }

    /**
     * One job execution. {@code exitStatus} and {@code exitCode} are already reduced to
     * {@link ExitTokens}' literals inside the read, so the raw exit columns are never kept.
     *
     * @param statementJob whether its instance's {@code JOB_NAME} equals {@code statementJob} exactly
     * @param status the Spring Batch status, a {@code BatchStatus} name
     */
    record Execution(long instanceId, boolean statementJob, String status, LocalDateTime endTime, String exitStatus,
            String exitCode) {

        /** Terminal {@code FAILED} with an end time. */
        boolean terminalFailed() {
            return "FAILED".equals(status) && endTime != null;
        }
    }

    /** One statementJob execution of the cycle, with the attempt whose job instance it belongs to. */
    record StatementExecution(int attempt, long executionId) {
    }

    Optional<Execution> execution(long executionId);

    Optional<Cycle> cycle(long cycleId);

    /** Every statementJob execution above {@code loadExecutionId}, at each attempt from 1 to the cycle's, in ID order. */
    List<StatementExecution> statementExecutionsAbove(long cycleId, Cycle cycle, long loadExecutionId);

    /** The {@code status} of each {@code batch.statement_run} row of the cycle attempt. */
    List<String> statementRunStatuses(long cycleId, int attempt);

    /** The one {@code ops.database_instance} row, through {@link OutputRoot#read}. */
    OutputRoot.Instance instance() throws SQLException;
}
