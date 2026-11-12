package org.carddemo.batch.cycle;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Optional;

import org.carddemo.batch.execution.SafeTransactions;
import org.carddemo.platform.ledger.AccountNotFoundException;
import org.carddemo.platform.ledger.BalancePosting;
import org.carddemo.platform.ledger.DuplicateTranIdException;
import org.carddemo.platform.ledger.NewTransaction;
import org.carddemo.platform.ledger.Origin;
import org.carddemo.platform.ledger.PostedCategory;
import org.carddemo.platform.ledger.TransactionWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * postDailyTransactionsJob's {@code post}: CBTRN02C over the cycle's claimed daily rows, one at a
 * time in seq order (BR-POST-007), {@code chunkSize} rows per transaction. A row that passes the
 * checks becomes a master transaction and changes its account and category balance through
 * ledger-core; a row that fails them becomes a reject. The step's execution context holds the last
 * seq done and commits with the chunk's rows, so a restart resumes after it and no row is half
 * applied (D-22, DEF-POST-008). Each posting runs under a savepoint: an account update that finds
 * no account rolls back the transaction row and the category change and rejects the row with
 * 0109 (D-20, DEF-POST-003 fixed). A category balance the posting creates gets the FILLER of the
 * category record read last, kept in the context with the last seq ({@link CategoryFiller},
 * DEF-POST-010); a posting that is rolled back leaves it as it was. Every transaction names the
 * cycle, so that a rerun can remove what the cycle posted.
 */
final class PostDailyTransactionsTasklet implements Tasklet {

    static final String LAST_SEQ = "post.lastSeq";
    static final String POSTED = "post.posted";
    static final String REJECTED = "post.rejected";

    private static final Logger log = LoggerFactory.getLogger(PostDailyTransactionsTasklet.class);

    /** DALYTRAN-ORIG-TS as the sample and the online add write it. */
    private static final DateTimeFormatter ORIG_TS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS")
            .withResolverStyle(ResolverStyle.STRICT);

    private static final String NEXT = """
            SELECT TOP (:n) d.daily_pk, d.seq, d.tran_id, d.type_cd, d.cat_cd, d.source, d.description, d.amount,
                   d.merchant_id, d.merchant_name, d.merchant_city, d.merchant_zip, d.card_num, d.orig_ts,
                   d.created_by, x.acct_id
            FROM core.daily_tran AS d
            LEFT JOIN core.card_xref AS x ON x.card_num = d.card_num
            WHERE d.cycle_id = :cycle AND d.seq > :after
            ORDER BY d.seq""";

    private static final String ACCOUNT = """
            SELECT curr_cyc_credit, curr_cyc_debit, credit_limit, expiration_date
            FROM core.account WHERE acct_id = :acct""";

    private static final String REJECT = """
            INSERT INTO core.daily_reject (cycle_id, seq, daily_pk, reason_cd, reason_desc, reason_cds, raw_record)
            SELECT :cycle, COALESCE((SELECT MAX(seq) FROM core.daily_reject WHERE cycle_id = :cycle), 0) + 1,
                   daily_pk, :reason, :description, :reasons, raw_record
            FROM core.daily_tran WHERE daily_pk = :pk""";

    /** A posting's outcome: the reject, if any, and the category work area after it. */
    private record Posted(PostingRules.Reject reject, String filler) {
    }

    record Daily(long dailyPk, int seq, String tranId, String typeCd, String catCd, String source,
            String description, BigDecimal amount, String merchantId, String merchantName, String merchantCity,
            String merchantZip, String cardNum, String origTs, String createdBy, String acctId) {
    }

    private final JdbcClient db;
    private final TransactionWriter transactions;
    private final BalancePosting balances;
    private final SafeTransactions savepoint;
    private final Clock clock;
    private final int chunkSize;

    PostDailyTransactionsTasklet(JdbcClient db, TransactionWriter transactions, BalancePosting balances,
            PlatformTransactionManager tx, Clock clock, int chunkSize) {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("carddemo.batch.files.chunk-size must be at least 1");
        }
        this.db = db;
        this.transactions = transactions;
        this.balances = balances;
        this.savepoint = new SafeTransactions(tx);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.clock = clock;
        this.chunkSize = chunkSize;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        CycleRun run = CycleRun.of(contribution.getStepExecution());
        ExecutionContext context = contribution.getStepExecution().getExecutionContext();
        int after = context.getInt(LAST_SEQ, 0);
        List<Daily> rows = db.sql(NEXT).param("n", chunkSize).param("cycle", run.cycleId()).param("after", after)
                .query((rs, n) -> new Daily(rs.getLong("daily_pk"), rs.getInt("seq"), rs.getString("tran_id"),
                        rs.getString("type_cd"), rs.getString("cat_cd"), rs.getString("source"),
                        rs.getString("description"), rs.getBigDecimal("amount"), rs.getString("merchant_id"),
                        rs.getString("merchant_name"), rs.getString("merchant_city"), rs.getString("merchant_zip"),
                        rs.getString("card_num"), rs.getString("orig_ts"), rs.getString("created_by"),
                        rs.getString("acct_id")))
                .list();
        long posted = context.getLong(POSTED, 0);
        long rejected = context.getLong(REJECTED, 0);
        String filler = CategoryFiller.from(context);
        for (Daily row : rows) {
            contribution.incrementReadCount();
            PostingRules.Reject reject = check(row).orElse(null);
            if (reject == null) {
                Posted outcome = post(run, row, filler);
                reject = outcome.reject();
                filler = outcome.filler();
            }
            if (reject != null) {
                reject(run, row, reject);
                rejected++;
            } else {
                posted++;
            }
            after = row.seq();
        }
        contribution.incrementWriteCount(rows.size());
        context.putInt(LAST_SEQ, after);
        context.putLong(POSTED, posted);
        context.putLong(REJECTED, rejected);
        CategoryFiller.put(context, filler);
        if (rows.size() < chunkSize) {
            log.info("post: cycle {} attempt {} posted {} and rejected {}", run.cycleId(), run.attempt(), posted,
                    rejected);
            return RepeatStatus.FINISHED;
        }
        return RepeatStatus.CONTINUABLE;
    }

    private Optional<PostingRules.Reject> check(Daily row) {
        PostingRules.Account account = row.acctId() == null ? null : db.sql(ACCOUNT).param("acct", row.acctId())
                .query((rs, n) -> new PostingRules.Account(rs.getBigDecimal(1), rs.getBigDecimal(2),
                        rs.getBigDecimal(3), rs.getString(4) == null ? "" : rs.getString(4)))
                .optional().orElse(null);
        if (account != null && row.amount() == null) {
            throw new CycleJobException("DAILY_AMOUNT_INVALID", "daily record " + row.seq()
                    + " has no valid DALYTRAN-AMT");
        }
        return PostingRules.check(row.acctId() != null, account, row.amount(), row.origTs());
    }

    /** The posting of a row that passed the checks, or the 0109 reject when its account update failed. */
    private Posted post(CycleRun run, Daily row, String filler) {
        NewTransaction t = new NewTransaction(row.tranId(), row.typeCd(), row.catCd(), row.source(),
                row.description(), row.amount(), row.cardNum(), row.merchantId(), row.merchantName(),
                row.merchantCity(), row.merchantZip(), origTs(row), ProcessingTimestamps.of(run.businessDate(), clock),
                Origin.POSTED, row.createdBy(), Math.toIntExact(run.cycleId()));
        try {
            PostedCategory category = savepoint.execute(status -> {
                transactions.insert(t);
                return balances.post(row.acctId(), row.typeCd(), row.catCd(), row.amount(), filler);
            });
            return new Posted(null, CategoryFiller.after(category));
        } catch (DuplicateTranIdException e) {
            throw new CycleJobException("DUPLICATE_TRAN_ID", "tran_id " + row.tranId() + " at daily record "
                    + row.seq() + " is already in the master");
        } catch (AccountNotFoundException e) {
            return new Posted(PostingRules.Reject.of(PostingRules.ACCOUNT_UPDATE_FAILED), filler);
        }
    }

    private void reject(CycleRun run, Daily row, PostingRules.Reject reject) {
        db.sql(REJECT).param("cycle", run.cycleId()).param("reason", reject.reasonCd())
                .param("description", reject.description()).param("reasons", reject.reasonCds())
                .param("pk", row.dailyPk()).update();
    }

    private static LocalDateTime origTs(Daily row) {
        try {
            return LocalDateTime.parse(row.origTs(), ORIG_TS);
        } catch (DateTimeParseException e) {
            throw new CycleJobException("DAILY_ORIG_TS_INVALID", "daily record " + row.seq()
                    + " has no DALYTRAN-ORIG-TS of the form YYYY-MM-DD HH:MM:SS.NNNNNN");
        }
    }
}
