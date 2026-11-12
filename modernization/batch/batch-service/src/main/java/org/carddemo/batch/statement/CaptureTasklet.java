package org.carddemo.batch.statement;

import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * statementJob's first step, at every start of the instance (batch.md, "Runs, restart and rerun").
 * With no run row it captures in the mode DEF-STMT-005 gives, and the row names this execution.
 * With one, it is a restart, in the mode the capture stored whatever the switch says now. A
 * SUPERSEDED or INVALIDATED run is refused first, then a stored watermark above the current
 * {@code MAX(tran_pk)}, then the row decides. A RUNNING or FAILED run is claimed RUNNING by this execution, keeping {@code W}
 * and the captured figures, and renders again; a PUBLISHING run is claimed with its status kept
 * and goes straight to the publication; a COMPLETED run is never claimed, and only its published
 * folder is checked. For a refused SUPERSEDED or INVALIDATED run the way forward is cycle-rerun.
 */
final class CaptureTasklet implements Tasklet {

    static final String WATERMARK_INVALID = "STATEMENT_WATERMARK_INVALID";
    static final String RUN_INVALIDATED = "STATEMENT_RUN_INVALIDATED";

    private static final Logger log = LoggerFactory.getLogger(CaptureTasklet.class);

    private final StatementRuns runs;
    private final StatementCapture capture;
    private final OutputRoots roots;
    private final FidelitySwitches switches;

    CaptureTasklet(StatementRuns runs, StatementCapture capture, OutputRoots roots, FidelitySwitches switches) {
        this.runs = runs;
        this.capture = capture;
        this.roots = roots;
        this.switches = switches;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        JobExecution job = contribution.getStepExecution().getJobExecution();
        StatementCycle cycle = StatementCycle.of(contribution.getStepExecution());
        // The path checks of statements/ and .work/ at each start (contract section 4.10).
        StatementFolders.workRoot(roots.root(job));
        var existing = runs.find(cycle.cycleId(), cycle.attempt());
        if (existing.isEmpty()) {
            StatementRuns.Mode mode = StatementSwitches.mode(switches);
            long runId = capture.capture(cycle, mode, job.getId());
            log.info("statementJob: cycle {} attempt {} captured run {} in {} mode", cycle.cycleId(), cycle.attempt(),
                    runId, mode);
            return RepeatStatus.FINISHED;
        }
        StatementRuns.Run run = existing.get();
        // A data load invalidates every unfinished run and also empties core.transaction, which
        // puts every stored W above MAX(tran_pk): the status is checked first, so the refusal
        // names the invalidation and the way forward rather than the watermark.
        if (run.status() == StatementRuns.Status.SUPERSEDED || run.status() == StatementRuns.Status.INVALIDATED) {
            throw invalidated(run);
        }
        if (run.watermark() > runs.maxTranPk()) {
            throw new StatementFailure(WATERMARK_INVALID, "statement run " + run.id()
                    + " has a watermark above the current highest transaction; the transactions were reloaded");
        }
        switch (run.status()) {
            case RUNNING, FAILED -> {
                StatementRuns.Run claimed = runs.claim(run, job.getId());
                if (run.mode() == StatementRuns.Mode.FIXED) {
                    runs.deleteAccountRows(claimed, job.getId());
                }
                log.info("statementJob: run {} claimed by job execution {}; it renders again from its capture",
                        run.id(), job.getId());
            }
            case PUBLISHING -> {
                runs.claim(run, job.getId());
                log.info("statementJob: run {} claimed by job execution {}; it is PUBLISHING and is not rendered"
                        + " again", run.id(), job.getId());
            }
            case COMPLETED -> log.info("statementJob: run {} is COMPLETED; its published folder is checked",
                    run.id());
            case SUPERSEDED, INVALIDATED -> throw invalidated(run);
        }
        return RepeatStatus.FINISHED;
    }

    private static StatementFailure invalidated(StatementRuns.Run run) {
        return new StatementFailure(RUN_INVALIDATED, "statement run " + run.id() + " is " + run.status()
                + "; rerun the cycle to issue its statements");
    }
}
