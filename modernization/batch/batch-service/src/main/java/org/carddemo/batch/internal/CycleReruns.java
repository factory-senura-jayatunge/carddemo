package org.carddemo.batch.internal;

import java.util.ArrayList;
import java.util.List;

import org.carddemo.batch.cycle.CycleRestore;
import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.execution.ExecutionRecovery;
import org.carddemo.batch.execution.RequestTransactions;
import org.carddemo.batch.execution.SafeFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * cycle-rerun (contract section 7; batch.md, "Deliberate rerun"). It refuses while an execution of
 * the cycle is live, opens the cycle's window (a completed cycle's too) unless the cycle already
 * holds it, and recovers the executions whose owner is lost. Then, in one transaction that holds the
 * launch lock exclusively and the cycle's row, it prepares the return of the staged daily file when
 * the inbound folder has none, checks again, does the restore and links the returned file last. On
 * success the window stays open: the cycle is OPEN again, and the next DAG run resumes it in that
 * window. A refused rerun closes the window again only if it opened it, without completing the
 * cycle. When that close fails, the window stays open and the rerun logs a warning with the close's
 * error code or class; the close's error is also attached as suppressed, which an
 * InternalApiException refusal does not keep. A refusal keeps the recoveries it already made, as
 * any launch would: the lease revoked, the execution and its running steps FAILED with OWNER_LOST,
 * and a RUNNING statement run of that execution FAILED.
 */
public class CycleReruns {

    public record Rerun(Cycle cycle, CycleRestore.Restored restored, List<Long> recovered, boolean dailyFileReturned) { }

    private static final Logger log = LoggerFactory.getLogger(CycleReruns.class);

    private final JdbcClient db;
    private final RequestTransactions transactions;
    private final Cycles cycles;
    private final BatchWindows windows;
    private final JobCatalog catalog;
    private final ExecutionRecovery recovery;
    private final CycleRestore restore;
    private final InternalApiProperties properties;

    public CycleReruns(JdbcClient db, RequestTransactions transactions, Cycles cycles, BatchWindows windows,
            JobCatalog catalog, ExecutionRecovery recovery, CycleRestore restore, InternalApiProperties properties) {
        this.db = db;
        this.transactions = transactions;
        this.cycles = cycles;
        this.windows = windows;
        this.catalog = catalog;
        this.recovery = recovery;
        this.restore = restore;
        this.properties = properties;
    }

    public Rerun rerun(long cycleId) {
        Cycle cycle = cycles.find(cycleId).orElseThrow(() -> new InternalApiException(HttpStatus.NOT_FOUND,
                "CYCLE_NOT_FOUND", "No cycle has this cycleId.", "cycleId"));
        refuseLive(executions(cycleId, Lease.LIVE));
        WindowHolder holder = WindowHolder.cycle(cycleId);
        boolean wasOpen = windows.heldBy(holder);
        windows.open(holder, true);
        try {
            return restore(cycle);
        } catch (RuntimeException e) {
            if (!wasOpen) {
                try {
                    windows.close(holder, false);
                } catch (RuntimeException closing) {
                    // An InternalApiException keeps no suppressed exceptions, so the open window is logged.
                    String why = closing instanceof InternalApiException api ? api.code() : SafeFailures.describe(closing);
                    log.warn("cycle-rerun: the batch window of cycle {} stays open; closing it after the refusal"
                            + " failed: {}", cycleId, why);
                    e.addSuppressed(closing);
                }
            }
            throw e;
        }
    }

    private Rerun restore(Cycle cycle) {
        long cycleId = cycle.cycleId();
        List<Long> recovered = new ArrayList<>();
        for (long id : executions(cycleId, Lease.RECOVERABLE)) {
            if (recovery.recover(id) instanceof ExecutionRecovery.Outcome.Recovered) {
                recovered.add(id);
            }
        }
        Restoring done = transactions.execute(status -> {
            if (!AppLocks.inTransaction(db, AppLocks.LAUNCH, "Exclusive", properties.launchLockTimeout())) {
                throw new InternalApiException(HttpStatus.SERVICE_UNAVAILABLE, "LAUNCH_IN_PROGRESS",
                        "A job launch is in progress. Try again later.", null);
            }
            Cycle locked = db.sql("SELECT cycle_id, business_date, attempt, interest_parm, status, opened_at, closed_at"
                            + " FROM batch.cycle WITH (UPDLOCK, HOLDLOCK) WHERE cycle_id = :id")
                    .param("id", cycleId).query(Cycles::row).single();
            if (locked.attempt() != cycle.attempt()) {
                throw new InternalApiException(HttpStatus.CONFLICT, "CYCLE_CHANGED",
                        "Another rerun of the cycle ran meanwhile.", null).withAll(locked.body());
            }
            // Every later refusal and the restore run inside this block, so the temporary copy is
            // removed on each of them; the link is the last change before the commit.
            try (CycleRestore.PreparedFile file = prepareDailyFile(cycle)) {
                refuseLive(executions(cycleId, Lease.ANY));
                restore.refusal(cycleId).ifPresent(r -> {
                    throw refused(r);
                });
                CycleRestore.Restored restored = restore.restore(cycleId, cycle.attempt());
                return new Restoring(restored, file.link());
            }
        });
        return new Rerun(cycles.find(cycleId).orElseThrow(), done.restored(), List.copyOf(recovered), done.returned());
    }

    private record Restoring(CycleRestore.Restored restored, boolean returned) { }

    private CycleRestore.PreparedFile prepareDailyFile(Cycle cycle) {
        try {
            return restore.prepareDailyFile(cycle.cycleId(), cycle.businessDate(), cycle.attempt());
        } catch (CycleRestore.Refused e) {
            throw refused(e.refusal());
        }
    }

    private static InternalApiException refused(CycleRestore.Refusal refusal) {
        return new InternalApiException(HttpStatus.CONFLICT, refusal.code(), refusal.message(), null);
    }

    private static void refuseLive(List<Long> running) {
        if (!running.isEmpty()) {
            throw new InternalApiException(HttpStatus.CONFLICT, "EXECUTIONS_RUNNING",
                    "Executions of this cycle are still running.", null).with("executionIds", running);
        }
    }

    private enum Lease { LIVE, RECOVERABLE, ANY }

    /** Non-terminal executions of the cycle's jobs, by whether their owner is lost (contract 4.4). */
    private List<Long> executions(long cycleId, Lease lease) {
        String which = switch (lease) {
            case LIVE -> "AND NOT " + ExecutionLeases.RECOVERABLE_EXECUTION;
            case RECOVERABLE -> "AND " + ExecutionLeases.RECOVERABLE_EXECUTION;
            case ANY -> "";
        };
        return db.sql("""
                SELECT e.JOB_EXECUTION_ID
                FROM batch.BATCH_JOB_EXECUTION AS e
                JOIN batch.BATCH_JOB_INSTANCE AS i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID
                JOIN batch.BATCH_JOB_EXECUTION_PARAMS AS p ON p.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID
                LEFT JOIN batch.execution_lease AS l ON l.job_execution_id = e.JOB_EXECUTION_ID
                WHERE e.STATUS IN %s AND i.JOB_NAME IN (:jobs)
                  AND p.PARAMETER_NAME = 'cycleId' AND p.PARAMETER_VALUE = :value
                  %s
                ORDER BY e.JOB_EXECUTION_ID""".formatted(ExecutionLeases.RUNNING_STATUSES, which))
                .param("jobs", catalog.cycleJobs()).param("value", Long.toString(cycleId))
                .query(Long.class).list();
    }
}
