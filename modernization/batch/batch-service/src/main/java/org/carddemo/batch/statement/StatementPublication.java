package org.carddemo.batch.statement;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;

import org.carddemo.batch.execution.FolderPublication;
import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.batch.execution.PublicationException;
import org.carddemo.batch.internal.ExecutionOutputs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * statementJob's publication (batch.md, "Runs, restart and rerun", Publication; contract section
 * 4.8, intent in the run row), as three steps, each its own transaction:
 * <ol>
 * <li>{@code intent}: this execution's working folder is read back and checked against the run's
 * file set, then the fenced compare-and-set RUNNING to PUBLISHING commits {@code work_folder}, the
 * manifest's SHA-256 and the counts;</li>
 * <li>{@code publish}: the folder publication of the committed {@code work_folder} onto
 * {@code statements/<cycleId>-<attempt>/}. It changes no row and holds no lock on the run row
 * while it links, so a recovery is never kept waiting by it;</li>
 * <li>{@code complete}: the final folder is read back against {@code manifest_sha256} and the run's
 * file set, with {@code .claim}, then the fenced compare-and-set PUBLISHING to COMPLETED.</li>
 * </ol>
 * A restart that finds the run PUBLISHING (and claimed it) runs steps 2 and 3 only; one that finds
 * it COMPLETED runs step 3's check only. A mismatch fails the job with {@code PUBLICATION_MISMATCH}
 * and leaves the run PUBLISHING. Each step reads the run through {@link StatementRuns#owned}: a
 * RUNNING or PUBLISHING run of another execution fails with {@code STATEMENT_RUN_NOT_OWNED}, and
 * both compare-and-sets name this execution.
 */
final class StatementPublication {

    /** Job execution context: the execution whose render filled the counts. */
    static final String RENDERED_BY = "statement.renderedBy";
    /** Job execution context: the counts the intent commits, set by this execution's render. */
    static final String STATEMENTS = "statement.statements";
    static final String TRANSACTIONS = "statement.transactions";
    static final String UNASSIGNED = "statement.unassigned";
    /** Accounts with an adjustment line; always 0 in legacy mode, which has none. */
    static final String ADJUSTED = "statement.adjusted";
    /** The completed run's ID, the poll's {@code outputs.statementRunId}. */
    static final String OUTPUT = ExecutionOutputs.OUTPUT_PREFIX + "statementRunId";

    private static final Logger log = LoggerFactory.getLogger(StatementPublication.class);

    private final StatementRuns runs;
    private final OutputRoots roots;

    /*
     * Run on the step's thread at the start of each step, before it reads the run row or holds a
     * lock; tests stop an execution there (a crash), pause it (a lost lease) or change a file.
     */
    volatile Consumer<JobExecution> beforeIntent = execution -> { };
    volatile Consumer<JobExecution> beforePublish = execution -> { };
    volatile Consumer<JobExecution> beforeComplete = execution -> { };

    StatementPublication(StatementRuns runs, OutputRoots roots) {
        this.runs = runs;
        this.roots = roots;
    }

    /** Step 1: verify this execution's working folder, then commit the intent. */
    Tasklet intent() {
        return (contribution, chunkContext) -> {
            JobExecution job = contribution.getStepExecution().getJobExecution();
            beforeIntent.accept(job);
            StatementCycle cycle = StatementCycle.of(contribution.getStepExecution());
            StatementRuns.Run run = runs.owned(cycle, job.getId(), true);
            if (run.status() != StatementRuns.Status.RUNNING) {
                return RepeatStatus.FINISHED;
            }
            ExecutionContext context = job.getExecutionContext();
            if (!(context.get(RENDERED_BY) instanceof Long renderedBy) || renderedBy.longValue() != job.getId()) {
                throw new StatementFailure(StatementRuns.CHANGED, "statement run " + run.id()
                        + " has no folder rendered by job execution " + job.getId());
            }
            String workName = FolderPublication.workFolderName(run.folder(), job.getId());
            Path work = StatementFolders.workRoot(roots.root(job)).resolve(workName);
            String sha256 = StatementFolders.verify(work, StatementFolders.expected(run, runs), Set.of());
            StatementRuns.Counts counts = new StatementRuns.Counts(context.getInt(STATEMENTS),
                    context.getLong(TRANSACTIONS), context.getLong(UNASSIGNED), context.getInt(ADJUSTED, 0));
            runs.publishing(run, job.getId(), workName, sha256, counts);
            log.info("statementJob: run {} committed its publication intent", run.id());
            return RepeatStatus.FINISHED;
        };
    }

    /** Step 2: link the committed working folder onto the final folder. */
    Tasklet publish() {
        return (contribution, chunkContext) -> {
            JobExecution job = contribution.getStepExecution().getJobExecution();
            beforePublish.accept(job);
            StatementCycle cycle = StatementCycle.of(contribution.getStepExecution());
            StatementRuns.Run run = runs.owned(cycle, job.getId(), false);
            switch (run.status()) {
                case COMPLETED -> {
                    return RepeatStatus.FINISHED;
                }
                case PUBLISHING -> {
                    // Publishes below.
                }
                default -> throw unexpected(run);
            }
            Path root = roots.root(job);
            Path work = StatementFolders.workRoot(root).resolve(run.workFolder());
            Path target = StatementFolders.statements(root).resolve(run.folder());
            OutputPublication.Outcome outcome = FolderPublication.publishFolder(work, target, run.manifestSha256());
            log.info("statementJob: run {} publication {}", run.id(), outcome);
            return RepeatStatus.FINISHED;
        };
    }

    /** Step 3: verify the final folder, then complete the run. */
    Tasklet complete() {
        return (contribution, chunkContext) -> {
            JobExecution job = contribution.getStepExecution().getJobExecution();
            beforeComplete.accept(job);
            StatementCycle cycle = StatementCycle.of(contribution.getStepExecution());
            StatementRuns.Run run = runs.owned(cycle, job.getId(), true);
            if (run.status() != StatementRuns.Status.PUBLISHING && run.status() != StatementRuns.Status.COMPLETED) {
                throw unexpected(run);
            }
            Path target = StatementFolders.statements(roots.root(job)).resolve(run.folder());
            String sha256 = StatementFolders.verify(target, StatementFolders.expected(run, runs),
                    Set.of(FolderPublication.CLAIM));
            if (!sha256.equals(run.manifestSha256())) {
                throw new PublicationException("the final folder is not the one statement run " + run.id()
                        + " committed");
            }
            if (run.status() == StatementRuns.Status.PUBLISHING) {
                runs.completed(run, job.getId());
            }
            ExecutionContext context = job.getExecutionContext();
            context.putLong(ExecutionOutputs.REJECT_COUNT, run.counts().rejects());
            context.putLong(OUTPUT, run.id());
            contribution.incrementWriteCount(run.counts().statements());
            log.info("statementJob: run {} completed: {} statements, {} transactions, {} unassigned, {} adjusted",
                    run.id(), run.counts().statements(), run.counts().transactions(), run.counts().unassigned(),
                    run.counts().adjusted());
            return RepeatStatus.FINISHED;
        };
    }

    /** A RUNNING run after the intent step: its intent was never committed. */
    private static StatementFailure unexpected(StatementRuns.Run run) {
        return new StatementFailure(StatementRuns.CHANGED, "statement run " + run.id() + " is " + run.status()
                + " and is not published by this step");
    }
}
