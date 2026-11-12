package org.carddemo.batch.pauth;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStream;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Purges the summaries of one chunk, in the order read, inside the chunk's transaction
 * (authorization-service.md section 3, data-model.md purge). For each summary:
 * <ol>
 * <li>lock the summary row ({@code UPDLOCK, HOLDLOCK}), so a detail the listener committed
 * before the lock is seen and none arrives after it;</li>
 * <li>delete its expired details not flagged {@code F}, with {@code OUTPUT deleted.*};</li>
 * <li>subtract only what that {@code DELETE} returned: approved count and amount for {@code 00},
 * plus {@code credit_balance} for the rows still {@code P}; declined count and requested amount
 * for anything else. A detail is therefore subtracted once, by the purge that deleted it, even
 * when two purges run together;</li>
 * <li>delete the summary when no detail is left, read under {@code READCOMMITTEDLOCK}. The
 * foreign key has no cascade (V507): a cascading delete would seek the account's details again
 * and range-lock the next account's first detail. An empty summary is deleted whatever its
 * totals (D-34) and counted.</li>
 * </ol>
 * A database error fails the chunk, which rolls back; there is no retry (D-57).
 *
 * <p>The run totals (BR-AUPG-006 and the extras) live in the step's execution context, so a
 * restart continues them. They are numbers only: nothing here logs an account or card number.
 */
class PurgeSummaryWriter implements ItemWriter<List<String>>, ItemStream, StepExecutionListener {

    static final String SUMMARIES_READ = "summariesRead";
    static final String SUMMARIES_DELETED = "summariesDeleted";
    static final String DETAILS_READ = "detailsRead";
    static final String DETAILS_DELETED = "detailsDeleted";
    static final String SUMMARIES_DELETED_WITH_NON_ZERO_TOTALS = "summariesDeletedWithNonZeroTotals";
    static final String FRAUD_DETAILS_KEPT = "fraudDetailsKept";
    static final String CREDIT_RELEASED = "creditReleased";

    private static final Logger log = LoggerFactory.getLogger(PurgeSummaryWriter.class);

    // One batch per summary, so its statements run in the ruling's order in one round trip.
    // Details are deleted 1,000 at a time: one statement that takes about 5,000 row locks on an
    // index would escalate to a table lock on pauth.auth_detail, which would hold up the listener
    // on every other account and can deadlock with it. THROW ends the batch at the first error.
    // Every read of auth_detail seeks the account's rows in IX_auth_detail_acct_newest. @acct is a
    // local variable, so the cached plan is chosen without its value and may scan the table, which
    // locks rows of every account in auth_id order: two purges, each holding its own deleted rows,
    // then wait on each other. SQL Server refuses FORCESEEK on a DELETE's target (error 10724), so
    // the DELETE names its rows through the alias of its FROM clause, where the hint is allowed.
    private static final String PURGE_ONE = """
            SET NOCOUNT ON;
            DECLARE @acct CHAR(11) = :acct;
            DECLARE @before DATETIME2(3) = :before;
            DECLARE @found INT = 0, @details_read BIGINT = 0, @fraud_kept BIGINT = 0;
            DECLARE @gone TABLE (resp CHAR(2) NOT NULL, match_status CHAR(1) NOT NULL,
                approved DECIMAL(12,2) NOT NULL, requested DECIMAL(12,2) NOT NULL);
            DECLARE @summary_gone TABLE (non_zero BIT NOT NULL);
            BEGIN TRY
                SELECT @found = 1 FROM pauth.auth_summary WITH (UPDLOCK, HOLDLOCK) WHERE acct_id = @acct;
                IF @found = 1
                BEGIN
                    SELECT @details_read = COUNT_BIG(*),
                           @fraud_kept = COUNT_BIG(CASE WHEN processed_at < @before AND auth_fraud = 'F' THEN 1 END)
                    FROM pauth.auth_detail WITH (READCOMMITTEDLOCK, FORCESEEK(IX_auth_detail_acct_newest(acct_id))) WHERE acct_id = @acct;
                    WHILE 1 = 1
                    BEGIN
                        DELETE TOP (1000) d
                        OUTPUT deleted.auth_resp_code, deleted.match_status, deleted.approved_amt,
                               deleted.transaction_amt INTO @gone
                        FROM pauth.auth_detail AS d WITH (READCOMMITTEDLOCK, FORCESEEK(IX_auth_detail_acct_newest(acct_id)))
                        WHERE d.acct_id = @acct AND d.processed_at < @before AND d.auth_fraud <> 'F';
                        IF @@ROWCOUNT < 1000 BREAK;
                    END;
                    UPDATE s SET
                        approved_auth_cnt = s.approved_auth_cnt - g.approved_cnt,
                        approved_auth_amt = s.approved_auth_amt - g.approved_amt,
                        credit_balance = s.credit_balance - g.pending_amt,
                        declined_auth_cnt = s.declined_auth_cnt - g.declined_cnt,
                        declined_auth_amt = s.declined_auth_amt - g.declined_amt
                    FROM pauth.auth_summary AS s
                    CROSS JOIN (
                        SELECT COUNT(CASE WHEN resp = '00' THEN 1 END) AS approved_cnt,
                               ISNULL(SUM(CASE WHEN resp = '00' THEN approved END), 0) AS approved_amt,
                               ISNULL(SUM(CASE WHEN resp = '00' AND match_status = 'P' THEN approved END), 0) AS pending_amt,
                               COUNT(CASE WHEN resp <> '00' THEN 1 END) AS declined_cnt,
                               ISNULL(SUM(CASE WHEN resp <> '00' THEN requested END), 0) AS declined_amt
                        FROM @gone HAVING COUNT(*) > 0) AS g
                    WHERE s.acct_id = @acct;
                    DELETE FROM pauth.auth_summary
                    OUTPUT CASE WHEN deleted.credit_balance <> 0 OR deleted.cash_balance <> 0
                                  OR deleted.approved_auth_cnt <> 0 OR deleted.approved_auth_amt <> 0
                                  OR deleted.declined_auth_cnt <> 0 OR deleted.declined_auth_amt <> 0
                                THEN 1 ELSE 0 END INTO @summary_gone
                    WHERE acct_id = @acct
                      AND NOT EXISTS (SELECT 1 FROM pauth.auth_detail AS d WITH (READCOMMITTEDLOCK, FORCESEEK(IX_auth_detail_acct_newest(acct_id)))
                                      WHERE d.acct_id = @acct);
                END;
            END TRY
            BEGIN CATCH
                THROW;
            END CATCH;
            SELECT @found AS found, @details_read AS details_read, @fraud_kept AS fraud_kept,
                   (SELECT COUNT_BIG(*) FROM @gone) AS details_deleted,
                   (SELECT ISNULL(SUM(CASE WHEN resp = '00' AND match_status = 'P' THEN approved END), 0)
                    FROM @gone) AS credit_released,
                   (SELECT COUNT_BIG(*) FROM @summary_gone) AS summary_deleted,
                   (SELECT COUNT_BIG(*) FROM @summary_gone WHERE non_zero = 1) AS summary_deleted_non_zero;""";

    private final JdbcClient db;
    private final PurgeParameters parameters;
    private final Totals totals = new Totals();

    PurgeSummaryWriter(JdbcClient db, PurgeParameters parameters) {
        this.db = db;
        this.parameters = parameters;
    }

    /** The run totals; also the result of one summary. */
    static final class Totals {
        long summariesRead;
        long summariesDeleted;
        long detailsRead;
        long detailsDeleted;
        long summariesDeletedWithNonZeroTotals;
        long fraudDetailsKept;
        BigDecimal creditReleased = BigDecimal.ZERO;

        void add(Totals t) {
            summariesRead += t.summariesRead;
            summariesDeleted += t.summariesDeleted;
            detailsRead += t.detailsRead;
            detailsDeleted += t.detailsDeleted;
            summariesDeletedWithNonZeroTotals += t.summariesDeletedWithNonZeroTotals;
            fraudDetailsKept += t.fraudDetailsKept;
            creditReleased = creditReleased.add(t.creditReleased);
        }

        void load(ExecutionContext c) {
            summariesRead = c.getLong(SUMMARIES_READ, 0);
            summariesDeleted = c.getLong(SUMMARIES_DELETED, 0);
            detailsRead = c.getLong(DETAILS_READ, 0);
            detailsDeleted = c.getLong(DETAILS_DELETED, 0);
            summariesDeletedWithNonZeroTotals = c.getLong(SUMMARIES_DELETED_WITH_NON_ZERO_TOTALS, 0);
            fraudDetailsKept = c.getLong(FRAUD_DETAILS_KEPT, 0);
            creditReleased = new BigDecimal(c.getString(CREDIT_RELEASED, "0"));
        }

        void store(ExecutionContext c) {
            c.putLong(SUMMARIES_READ, summariesRead);
            c.putLong(SUMMARIES_DELETED, summariesDeleted);
            c.putLong(DETAILS_READ, detailsRead);
            c.putLong(DETAILS_DELETED, detailsDeleted);
            c.putLong(SUMMARIES_DELETED_WITH_NON_ZERO_TOTALS, summariesDeletedWithNonZeroTotals);
            c.putLong(FRAUD_DETAILS_KEPT, fraudDetailsKept);
            c.putString(CREDIT_RELEASED, creditReleased.toPlainString());
        }

        @Override
        public String toString() {
            return summariesRead + " summaries read, " + summariesDeleted + " deleted (" + summariesDeletedWithNonZeroTotals
                    + " with non-zero totals); " + detailsRead + " details read, " + detailsDeleted + " deleted, "
                    + fraudDetailsKept + " expired but flagged F kept; credit released " + creditReleased.toPlainString();
        }
    }

    @Override
    public void open(ExecutionContext context) {
        totals.load(context);
    }

    @Override
    public void update(ExecutionContext context) {
        totals.store(context);
    }

    @Override
    public void write(Chunk<? extends List<String>> chunk) {
        Totals inChunk = new Totals();
        for (List<String> keys : chunk) {
            for (String acctId : keys) {
                inChunk.add(purge(acctId));
            }
        }
        totals.add(inChunk);
    }

    private Totals purge(String acctId) {
        try {
            return db.sql(PURGE_ONE).param("acct", acctId).param("before", parameters.detailsBefore())
                    .query((rs, n) -> {
                        Totals t = new Totals();
                        if (rs.getInt("found") == 1) {
                            t.summariesRead = 1;
                            t.detailsRead = rs.getLong("details_read");
                            t.fraudDetailsKept = rs.getLong("fraud_kept");
                            t.detailsDeleted = rs.getLong("details_deleted");
                            t.creditReleased = rs.getBigDecimal("credit_released");
                            t.summariesDeleted = rs.getLong("summary_deleted");
                            t.summariesDeletedWithNonZeroTotals = rs.getLong("summary_deleted_non_zero");
                        }
                        return t;
                    })
                    .single();
        } catch (DataAccessException e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            String code = cause instanceof SQLException sql ? "SQL Server error " + sql.getErrorCode()
                    : cause.getClass().getSimpleName();
            throw new PurgeException("the purge of a pending-authorization summary failed (" + code + ")");
        }
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        Totals committed = new Totals();
        committed.load(stepExecution.getExecutionContext());
        log.info("{} {}: {}", PurgePendingAuthorizationsJobConfiguration.PURGE_STEP, stepExecution.getStatus(),
                committed);
        return null;
    }
}
