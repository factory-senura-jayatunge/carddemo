package org.carddemo.batch.statement;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.carddemo.batch.data.CoreRows;
import org.carddemo.batch.execution.FolderPublication;
import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.CardXrefRecord;
import org.carddemo.platform.legacyformat.entity.CustomerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * statementJob's {@code render} step for a RUNNING run (batch.md, "Modes", legacy; "Build rulings",
 * legacy capture): CREASTMT's two files from the cross-reference in key order, the customers and
 * accounts, and the master ({@code in_master = 1}, {@code tran_pk <= W}) in card and transaction ID
 * order, through {@link LegacyStatementRenderer} unchanged. Each account's Current Balance is the
 * run's capture row's {@code curr_bal}, not {@code core.account}'s, so a restart renders the same
 * bytes from the same frozen input even after a write outside the window guard; at the first
 * execution nothing has written since the capture, so they are the live values. The renderer runs
 * into memory first, so a refused run ({@code STATEMENT_TABLE_LIMIT} and the not-found codes)
 * creates no file. The files and their manifest go into this execution's
 * own working folder, each created new, {@code 0600} and forced to disk, and are never written
 * again. The run row is read first with {@code UPDLOCK} and must be RUNNING or PUBLISHING with this
 * execution, or COMPLETED; otherwise the step fails with {@code STATEMENT_RUN_NOT_OWNED} and writes
 * nothing. A PUBLISHING or COMPLETED run renders nothing.
 *
 * <p>Before its own folder, the step removes this instance's working folders whose execution is
 * terminal and that no run row names (contract section 4.8, "Clean-up"). It reads every run row's
 * {@code work_folder} in this transaction, after this execution's lease is held. A terminal
 * execution can no longer commit a fence, so no folder it wrote can become a committed intent
 * afterwards; folder names carry the job execution ID, so no two executions share one.
 */
final class LegacyRenderTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(LegacyRenderTasklet.class);

    private final JdbcClient db;
    private final StatementRuns runs;
    private final OutputRoots roots;
    private final OutputPublication publication;

    LegacyRenderTasklet(JdbcClient db, StatementRuns runs, OutputRoots roots, OutputPublication publication) {
        this.db = db;
        this.runs = runs;
        this.roots = roots;
        this.publication = publication;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        JobExecution job = contribution.getStepExecution().getJobExecution();
        StatementCycle cycle = StatementCycle.of(contribution.getStepExecution());
        // Update-locked for the whole render: the run stays RUNNING and this execution's until the commit.
        StatementRuns.Run run = runs.owned(cycle, job.getId(), true);
        if (run.status() != StatementRuns.Status.RUNNING) {
            return RepeatStatus.FINISHED;
        }
        if (run.mode() != StatementRuns.Mode.LEGACY) {
            throw new IllegalStateException("the legacy render got a " + run.mode() + " run");
        }
        Path workRoot = StatementFolders.workRoot(roots.root(job));
        publication.cleanUpFolders(job, null, runs.workFolders(), workRoot,
                id -> FolderPublication.workFolderName(run.folder(), id));

        CoreRows rows = new CoreRows(db);
        List<CardXrefRecord> xrefs = rows.cardXrefs();
        Map<String, CustomerRecord> customers = rows.customers().stream()
                .collect(Collectors.toMap(CustomerRecord::custId, Function.identity()));
        Map<String, AccountRecord> accounts = capturedAccounts(run, rows.accounts());
        for (CardXrefRecord x : xrefs) {
            if (!accounts.containsKey(x.acctId())) {
                // The window keeps the cross-reference unchanged between the capture and the render.
                throw new StatementFailure(StatementRuns.CHANGED, "an account of statement run " + run.id()
                        + " has no capture row at the render");
            }
        }
        List<LegacyStatementRenderer.WorkRecord> workFile = new ArrayList<>(db.sql("""
                SELECT card_num, tran_id, description, amount FROM core.[transaction]
                WHERE in_master = 1 AND tran_pk <= :watermark""")
                .param("watermark", run.watermark())
                .query((rs, n) -> new LegacyStatementRenderer.WorkRecord(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getBigDecimal(4)))
                .list());
        workFile.sort(LegacyStatementRenderer.KEY_ORDER);
        LegacyStatementRenderer.Rendered rendered = LegacyStatementRenderer.renderToBytes(xrefs, customers,
                accounts, workFile);

        Path folder = FolderPublication.createWorkFolder(workRoot, run.folder(), job.getId());
        write(folder.resolve(StatementFolders.TEXT), rendered.text());
        write(folder.resolve(StatementFolders.HTML), rendered.html());
        FolderPublication.writeManifest(folder);

        LegacyStatementRenderer.Result result = rendered.result();
        ExecutionContext context = job.getExecutionContext();
        context.putInt(StatementPublication.STATEMENTS, result.statements());
        context.putLong(StatementPublication.TRANSACTIONS, result.listed());
        context.putLong(StatementPublication.UNASSIGNED, result.unlisted());
        context.putInt(StatementPublication.ADJUSTED, 0);
        context.putLong(StatementPublication.RENDERED_BY, job.getId());
        // Spring Batch 6's StepContribution counts reads only one at a time (no incrementReadCount(long)).
        for (int i = 0; i < workFile.size(); i++) {
            contribution.incrementReadCount();
        }
        contribution.incrementWriteCount(result.statements());
        log.info("statementJob: run {} rendered {} statements with {} transactions; {} work-file transactions of"
                + " cards outside the cross-reference", run.id(), result.statements(), result.listed(), result.unlisted());
        return RepeatStatus.FINISHED;
    }

    /** The accounts that have a capture row in this run, each with its captured balance as ACCT-CURR-BAL. */
    private Map<String, AccountRecord> capturedAccounts(StatementRuns.Run run, List<AccountRecord> live) {
        Map<String, BigDecimal> captured = new HashMap<>();
        db.sql("SELECT acct_id, curr_bal FROM batch.statement_capture WHERE statement_run_id = :run")
                .param("run", run.id())
                .query((ResultSet rs) -> {
                    captured.put(rs.getString(1), rs.getBigDecimal(2));
                });
        Map<String, AccountRecord> accounts = new HashMap<>();
        for (AccountRecord a : live) {
            BigDecimal balance = captured.get(a.acctId());
            if (balance != null) {
                accounts.put(a.acctId(), new AccountRecord(a.acctId(), a.activeStatus(), balance, a.creditLimit(),
                        a.cashCreditLimit(), a.openDate(), a.expirationDate(), a.reissueDate(), a.currCycCredit(),
                        a.currCycDebit(), a.addrZip(), a.groupId(), a.legacyFiller()));
            }
        }
        return accounts;
    }

    private static void write(Path file, byte[] bytes) {
        try (OutputStream out = OutputPublication.createWorkFile(file)) {
            out.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
