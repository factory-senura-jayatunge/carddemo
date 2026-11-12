package org.carddemo.batch.statement;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.carddemo.batch.execution.FolderPublication;
import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.execution.OutputRoots;
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
 * statementJob's {@code render} step for a RUNNING fixed run (batch.md, "Which accounts and
 * transactions (fixed)", "Dates, balances and the reconciled total", "Render"; DEF-STMT-005 fixed):
 * one {@link StatementPdf} per captured account, in {@code acct_id} order, in chunks of
 * {@value #CHUNK} accounts. Each call of the tasklet is one chunk in its own fenced transaction: it
 * reads the run row {@code WITH (UPDLOCK)}, which must be RUNNING with this execution, writes the
 * chunk's PDFs into this execution's working folder, then inserts their
 * {@code batch.account_statement} rows with the SHA-256 of the bytes written. The rows already
 * inserted are the cursor: after the restart's claim deleted the run's rows, every row of the run
 * is this execution's. The call that finds no account left writes {@code index.csv} and the
 * manifest and puts the counts for the intent.
 *
 * <p>Statement {@code C} and the credit limit are the capture's. The period set is the rows with
 * {@code tran_pk} in {@code (W_prev, W]}, where {@code W_prev} is the previous run's watermark (0
 * without one), put on accounts through {@code core.card_xref} as it is now; {@code in_master} is
 * not read. A row of the set whose card has no cross-reference row is counted as unassigned. The
 * previous run is the latest COMPLETED run of an earlier cycle in either mode. The opening balance
 * is what it holds for the account: a fixed run's {@code account_statement.closing_balance}, or a
 * legacy run's captured {@code curr_bal}, the Current Balance its files printed; the period starts
 * the day after its statement date. An account it holds nothing for gets a first statement. The
 * name and address are the customer's on the account's
 * cross-reference row with the lowest {@code card_num}. Only the last 4 digits of a card number are
 * read. Logs carry counts only (D-10).
 *
 * <p>The first call, which finds this execution's working folder missing, removes the instance's
 * working folders whose execution is terminal and that no run row names, as the legacy render does,
 * then creates its own. A run that is PUBLISHING or COMPLETED at the first call renders nothing.
 */
final class FixedRenderTasklet implements Tasklet {

    static final int CHUNK = 100;
    static final String INDEX_HEADER = "acct_id,period_start,period_end,closing_balance,minimum_payment,tran_count,sha256";

    private static final Logger log = LoggerFactory.getLogger(FixedRenderTasklet.class);

    private final JdbcClient db;
    private final StatementRuns runs;
    private final OutputRoots roots;
    private final OutputPublication publication;
    private final StatementProperties properties;

    FixedRenderTasklet(JdbcClient db, StatementRuns runs, OutputRoots roots, OutputPublication publication,
            StatementProperties properties) {
        this.db = db;
        this.runs = runs;
        this.roots = roots;
        this.publication = publication;
        this.properties = properties;
    }

    private record Captured(String acctId, BigDecimal closing, BigDecimal creditLimit) {
    }

    private record Holder(String name, List<String> address) {
    }

    private record Row(String acctId, LocalDateTime procTs, String description, String cardLast4, BigDecimal amount,
            boolean interest) {
    }

    private record Written(String acctId, StatementFigures figures, String fileName, String sha256) {
    }

    /** The previous run: its mode, watermark and statement date, or none (a first statement for every account). */
    private record PreviousRun(Long runId, StatementRuns.Mode mode, long watermark, LocalDate statementDate) {
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        JobExecution job = contribution.getStepExecution().getJobExecution();
        StatementCycle cycle = StatementCycle.of(contribution.getStepExecution());
        // Update-locked to this chunk's commit: the run stays RUNNING and this execution's meanwhile.
        StatementRuns.Run run = runs.owned(cycle, job.getId(), true);
        if (run.mode() != StatementRuns.Mode.FIXED) {
            throw new IllegalStateException("the fixed render got a " + run.mode() + " run");
        }
        Path workRoot = StatementFolders.workRoot(roots.root(job));
        Path folder = workRoot.resolve(FolderPublication.workFolderName(run.folder(), job.getId()));
        boolean first = !Files.exists(folder, NOFOLLOW_LINKS);
        if (run.status() != StatementRuns.Status.RUNNING) {
            if (first) {
                return RepeatStatus.FINISHED;
            }
            throw new StatementFailure(StatementRuns.NOT_OWNED, "statement run " + run.id() + " is " + run.status()
                    + " while job execution " + job.getId() + " renders it");
        }
        if (first) {
            publication.cleanUpFolders(job, null, runs.workFolders(), workRoot,
                    id -> FolderPublication.workFolderName(run.folder(), id));
            FolderPublication.createWorkFolder(workRoot, run.folder(), job.getId());
        }
        PreviousRun previous = previousRun(run);
        String after = db.sql("SELECT COALESCE(MAX(acct_id), '') FROM batch.account_statement WHERE statement_run_id = :run")
                .param("run", run.id()).query(String.class).single();
        List<Captured> accounts = db.sql("""
                SELECT TOP (:n) acct_id, curr_bal, credit_limit FROM batch.statement_capture
                WHERE statement_run_id = :run AND acct_id > :after ORDER BY acct_id""")
                .param("n", CHUNK).param("run", run.id()).param("after", after)
                .query((rs, n) -> new Captured(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3))).list();
        if (accounts.isEmpty()) {
            finish(job, run, previous, folder);
            return RepeatStatus.FINISHED;
        }
        String last = accounts.get(accounts.size() - 1).acctId();
        Map<String, Holder> holders = holders(after, last);
        Map<String, BigDecimal> closings = previous.runId() == null ? Map.of() : closings(previous, after, last);
        Map<String, List<Row>> rows = rows(previous.watermark(), run.watermark(), after, last);

        List<Written> written = new ArrayList<>(accounts.size());
        long listed = 0;
        for (Captured account : accounts) {
            Holder holder = holders.get(account.acctId());
            if (holder == null) {
                // The window keeps the cross-reference unchanged between the capture and the render.
                throw new StatementFailure(StatementRuns.CHANGED, "an account of statement run " + run.id()
                        + " has no cross-reference row at the render");
            }
            List<Row> lines = rows.getOrDefault(account.acctId(), List.of());
            Optional<StatementFigures.Previous> before = Optional.ofNullable(closings.get(account.acctId()))
                    .map(closing -> new StatementFigures.Previous(closing, previous.statementDate()));
            StatementFigures figures = StatementFigures.of(cycle.businessDate(), account.closing(),
                    account.creditLimit(), before,
                    lines.stream().map(r -> new StatementFigures.Line(r.amount(), r.interest(),
                            r.procTs().toLocalDate())).toList(),
                    properties.minimumPaymentFloor(), properties.minimumPaymentPercent());
            byte[] pdf = StatementPdf.render(new StatementPdf.Statement(run.id(), account.acctId(), holder.name(),
                    holder.address(), figures,
                    lines.stream().map(r -> new StatementPdf.Line(r.procTs().toLocalDate(), r.description(),
                            r.cardLast4(), r.amount())).toList()));
            String fileName = account.acctId() + ".pdf";
            write(folder.resolve(fileName), pdf);
            written.add(new Written(account.acctId(), figures, fileName, OutputPublication.sha256(pdf)));
            listed += lines.size();
        }
        for (Written w : written) {
            StatementFigures f = w.figures();
            db.sql("""
                    INSERT INTO batch.account_statement (statement_run_id, acct_id, period_start, period_end,
                        opening_balance, closing_balance, net_amount, interest_amount, adjustment_amount, minimum_payment,
                        credit_limit, tran_count, file_name, file_sha256)
                    VALUES (:run, :acct, :start, :end, :opening, :closing, :net, :interest, :adjustment, :minimum,
                        :limit, :count, :file, :sha)""")
                    .param("run", run.id()).param("acct", w.acctId()).param("start", f.periodStart())
                    .param("end", f.periodEnd()).param("opening", f.opening()).param("closing", f.closing())
                    .param("net", f.net()).param("interest", f.interest()).param("adjustment", f.adjustment())
                    .param("minimum", f.minimumPayment()).param("limit", f.creditLimit()).param("count", f.tranCount())
                    .param("file", w.fileName()).param("sha", w.sha256()).update();
        }
        // Spring Batch 6's StepContribution counts reads only one at a time (no incrementReadCount(long)).
        for (long i = 0; i < listed; i++) {
            contribution.incrementReadCount();
        }
        contribution.incrementWriteCount(accounts.size());
        return RepeatStatus.CONTINUABLE;
    }

    /** {@code index.csv} and the manifest, then the counts the intent commits. */
    private void finish(JobExecution job, StatementRuns.Run run, PreviousRun previous, Path folder) {
        List<String> index = new ArrayList<>();
        index.add(INDEX_HEADER);
        long[] transactions = {0};
        int[] adjusted = {0};
        db.sql("""
                SELECT acct_id, period_start, period_end, closing_balance, minimum_payment, tran_count, file_sha256,
                    adjustment_amount
                FROM batch.account_statement WHERE statement_run_id = :run ORDER BY acct_id""")
                .param("run", run.id()).query((ResultSet rs) -> {
                    index.add(String.join(",", rs.getString(1), rs.getObject(2, LocalDate.class).toString(),
                            rs.getObject(3, LocalDate.class).toString(), rs.getBigDecimal(4).toPlainString(),
                            rs.getBigDecimal(5).toPlainString(), Integer.toString(rs.getInt(6)), rs.getString(7)));
                    transactions[0] += rs.getInt(6);
                    if (rs.getBigDecimal(8).signum() != 0) {
                        adjusted[0]++;
                    }
                });
        long unassigned = db.sql("""
                SELECT COUNT_BIG(*) FROM core.[transaction] AS t
                WHERE t.tran_pk > :previous AND t.tran_pk <= :watermark
                    AND NOT EXISTS (SELECT 1 FROM core.card_xref AS x WHERE x.card_num = t.card_num)""")
                .param("previous", previous.watermark()).param("watermark", run.watermark())
                .query(Long.class).single();
        int statements = index.size() - 1;
        write(folder.resolve(StatementFolders.INDEX),
                index.stream().map(line -> line + "\n").collect(Collectors.joining()).getBytes(US_ASCII));
        FolderPublication.writeManifest(folder);

        ExecutionContext context = job.getExecutionContext();
        context.putInt(StatementPublication.STATEMENTS, statements);
        context.putLong(StatementPublication.TRANSACTIONS, transactions[0]);
        context.putLong(StatementPublication.UNASSIGNED, unassigned);
        context.putInt(StatementPublication.ADJUSTED, adjusted[0]);
        context.putLong(StatementPublication.RENDERED_BY, job.getId());
        log.info("statementJob: run {} rendered {} statements with {} transactions; {} adjusted, {} unassigned",
                run.id(), statements, transactions[0], adjusted[0], unassigned);
    }

    private PreviousRun previousRun(StatementRuns.Run run) {
        if (run.previousRunId() == null) {
            return new PreviousRun(null, null, 0, null);
        }
        return db.sql("""
                SELECT r.mode, r.watermark_tran_pk, c.business_date FROM batch.statement_run AS r
                JOIN batch.cycle AS c ON c.cycle_id = r.cycle_id WHERE r.statement_run_id = :previous""")
                .param("previous", run.previousRunId())
                .query((rs, n) -> new PreviousRun(run.previousRunId(), StatementRuns.Mode.valueOf(rs.getString(1)),
                        rs.getLong(2), rs.getObject(3, LocalDate.class)))
                .single();
    }

    /** The customer on each account's cross-reference row with the lowest card number. */
    private Map<String, Holder> holders(String after, String last) {
        Map<String, Holder> holders = new HashMap<>();
        db.sql("""
                SELECT x.acct_id, c.first_name, c.middle_name, c.last_name, c.addr_line_1, c.addr_line_2, c.addr_line_3,
                    c.state_cd, c.country_cd, c.zip
                FROM core.card_xref AS x JOIN core.customer AS c ON c.cust_id = x.cust_id
                WHERE x.acct_id > :after AND x.acct_id <= :last
                    AND x.card_num = (SELECT MIN(y.card_num) FROM core.card_xref AS y WHERE y.acct_id = x.acct_id)""")
                .param("after", after).param("last", last)
                .query((ResultSet rs) -> {
                    holders.put(rs.getString(1), holder(rs));
                });
        return holders;
    }

    /**
     * The name's three parts and the address's lines in full (D-44); blank parts are left out, and
     * the last line holds city, state, country and ZIP in the order of the legacy statement.
     */
    private static Holder holder(ResultSet rs) throws SQLException {
        String name = joined(rs.getString(2), rs.getString(3), rs.getString(4));
        List<String> address = new ArrayList<>();
        for (String line : new String[] {rs.getString(5), rs.getString(6)}) {
            if (!line.isBlank()) {
                address.add(line);
            }
        }
        String place = joined(rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10));
        if (!place.isEmpty()) {
            address.add(place);
        }
        return new Holder(name, address);
    }

    private static String joined(String... parts) {
        return Stream.of(parts).map(FixedRenderTasklet::spacesOff).filter(p -> !p.isEmpty())
                .collect(Collectors.joining(" "));
    }

    /** The padding blanks only: a control character at either end is data and prints as a placeholder. */
    private static String spacesOff(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * The chunk's closing balances in the previous run: a fixed run's account rows, or a legacy
     * run's capture rows, which are the same quantity, the balance after the rows up to its {@code W}.
     */
    private Map<String, BigDecimal> closings(PreviousRun previous, String after, String last) {
        Map<String, BigDecimal> closings = new HashMap<>();
        String sql = previous.mode() == StatementRuns.Mode.LEGACY
                ? """
                SELECT acct_id, curr_bal FROM batch.statement_capture
                WHERE statement_run_id = :previous AND acct_id > :after AND acct_id <= :last"""
                : """
                SELECT acct_id, closing_balance FROM batch.account_statement
                WHERE statement_run_id = :previous AND acct_id > :after AND acct_id <= :last""";
        db.sql(sql)
                .param("previous", previous.runId()).param("after", after).param("last", last)
                .query((ResultSet rs) -> {
                    closings.put(rs.getString(1), rs.getBigDecimal(2));
                });
        return closings;
    }

    /** The period's rows of the chunk's accounts, each account's in statement order. */
    private Map<String, List<Row>> rows(long previousWatermark, long watermark, String after, String last) {
        Map<String, List<Row>> rows = new HashMap<>();
        db.sql("""
                SELECT x.acct_id, t.proc_ts, t.description, RIGHT(t.card_num, 4), t.amount, t.origin
                FROM core.[transaction] AS t JOIN core.card_xref AS x ON x.card_num = t.card_num
                WHERE t.tran_pk > :previous AND t.tran_pk <= :watermark AND x.acct_id > :after AND x.acct_id <= :last
                ORDER BY x.acct_id, t.proc_ts, t.tran_id, t.tran_pk""")
                .param("previous", previousWatermark).param("watermark", watermark).param("after", after)
                .param("last", last)
                .query((ResultSet rs) -> {
                    Row row = new Row(rs.getString(1), rs.getObject(2, LocalDateTime.class), rs.getString(3),
                            rs.getString(4), rs.getBigDecimal(5), "INTEREST".equals(rs.getString(6)));
                    rows.computeIfAbsent(row.acctId(), k -> new ArrayList<>()).add(row);
                });
        return rows;
    }

    private static void write(Path file, byte[] bytes) {
        try (OutputStream out = OutputPublication.createWorkFile(file)) {
            out.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
