package org.carddemo.batch.cycle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * postDailyTransactionsJob's {@code stageAuthorizationMatches} (D-33, d33-matching-brief.md): in one
 * transaction, the pending approvals and the transactions this cycle posted that agree on card,
 * amount, merchant and date window, kept in {@code batch.auth_match_pair} when the approval and
 * the transaction each appear in exactly one such pair. The candidates are read by one statement,
 * so uniqueness is judged on one snapshot; a completed step is not rerun on a restart, so the
 * pairs stay fixed for the attempt. Card numbers are compared in the database and never read.
 *
 * <p>Counts in the step context: {@code candidatePairs} (pairs that agree on everything),
 * {@code matched} (the unique ones), {@code ambiguous} (the others) and {@code merchantInvalid}
 * (pairs that agree on card, amount and window but where either merchant ID is not a valid one).
 */
final class StageAuthorizationMatchesTasklet implements Tasklet {

    static final String CANDIDATE_PAIRS = "candidatePairs";
    static final String MATCHED = "matched";
    static final String AMBIGUOUS = "ambiguous";
    static final String MERCHANT_INVALID = "merchantInvalid";

    private static final Logger log = LoggerFactory.getLogger(StageAuthorizationMatchesTasklet.class);

    /**
     * N(merchant_id) of the brief: trimmed of spaces; NULL when empty or not all digits 0-9 (BIN2,
     * so no other digit character qualifies), when all zeros, or, once leading zeros are gone,
     * longer than the 9 digits of TRAN-MERCHANT-ID.
     */
    private static final String MERCHANT = """
            CROSS APPLY (SELECT TRIM(%1$s.merchant_id) COLLATE Latin1_General_100_BIN2 AS s) AS %1$s_s
            CROSS APPLY (SELECT CASE WHEN %1$s_s.s = '' OR %1$s_s.s LIKE '%%[^0-9]%%' OR PATINDEX('%%[^0]%%', %1$s_s.s) = 0
                                     THEN NULL ELSE SUBSTRING(%1$s_s.s, PATINDEX('%%[^0]%%', %1$s_s.s), 15) END AS z) AS %1$s_z
            CROSS APPLY (SELECT CASE WHEN LEN(%1$s_z.z) <= 9 THEN %1$s_z.z END AS merchant) AS %1$s_m""";

    private static final String PAIRS = """
            WITH a AS (
                SELECT d.auth_id, d.card_num, d.approved_amt, CAST(d.processed_at AS DATE) AS da, d_m.merchant
                FROM pauth.auth_detail AS d
                %s
                WHERE d.auth_resp_code = '00' AND d.match_status = 'P' AND d.auth_fraud <> 'F' AND d.approved_amt > 0
            ), t AS (
                SELECT t.tran_pk, t.card_num, t.amount, CAST(t.orig_ts AS DATE) AS dt, t_m.merchant
                FROM core.[transaction] AS t
                %s
                WHERE t.cycle_id = :cycle AND t.in_master = 1 AND t.origin IN ('POSTED', 'ONLINE_ADD') AND t.amount > 0
                  AND NOT EXISTS (SELECT 1 FROM pauth.auth_detail AS x WHERE x.matched_tran_pk = t.tran_pk)
            )
            SELECT a.auth_id, t.tran_pk,
                   CASE WHEN a.merchant IS NULL OR t.merchant IS NULL THEN 0 ELSE 1 END AS valid
            FROM a JOIN t ON t.card_num = a.card_num AND t.amount = a.approved_amt
                AND DATEDIFF(day, a.da, t.dt) BETWEEN 0 AND :expiryDays
            WHERE a.merchant IS NULL OR t.merchant IS NULL OR a.merchant = t.merchant""".formatted(
            MERCHANT.formatted("d"), MERCHANT.formatted("t"));

    record Pair(long authId, long tranPk, boolean valid) {
    }

    private final JdbcClient db;

    StageAuthorizationMatchesTasklet(JdbcClient db) {
        this.db = db;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        CycleRun run = CycleRun.of(contribution.getStepExecution());
        long expiryDays = expiryDays(contribution);
        db.sql("DELETE FROM batch.auth_match_pair WHERE cycle_id = :c AND attempt = :a")
                .param("c", run.cycleId()).param("a", run.attempt()).update();
        List<Pair> pairs = db.sql(PAIRS).param("cycle", run.cycleId()).param("expiryDays", expiryDays)
                .query((rs, n) -> new Pair(rs.getLong(1), rs.getLong(2), rs.getInt(3) == 1)).list();
        List<Pair> candidates = pairs.stream().filter(Pair::valid).toList();
        List<Pair> unique = unique(candidates);
        for (Pair p : unique) {
            db.sql("INSERT INTO batch.auth_match_pair (cycle_id, attempt, auth_id, tran_pk) VALUES (:c, :a, :auth, :tran)")
                    .param("c", run.cycleId()).param("a", run.attempt()).param("auth", p.authId())
                    .param("tran", p.tranPk()).update();
        }
        ExecutionContext context = contribution.getStepExecution().getExecutionContext();
        context.putLong(CANDIDATE_PAIRS, candidates.size());
        context.putLong(MATCHED, unique.size());
        context.putLong(AMBIGUOUS, candidates.size() - unique.size());
        context.putLong(MERCHANT_INVALID, pairs.size() - candidates.size());
        contribution.incrementWriteCount(unique.size());
        log.info("stageAuthorizationMatches: cycle {} attempt {}: {} candidate pairs, {} unique, {} with an invalid"
                + " merchant ID", run.cycleId(), run.attempt(), candidates.size(), unique.size(),
                pairs.size() - candidates.size());
        return RepeatStatus.FINISHED;
    }

    /** The pairs whose approval and transaction each appear in no other pair. */
    static List<Pair> unique(List<Pair> candidates) {
        Map<Long, Integer> perAuth = new HashMap<>();
        Map<Long, Integer> perTran = new HashMap<>();
        for (Pair p : candidates) {
            perAuth.merge(p.authId(), 1, Integer::sum);
            perTran.merge(p.tranPk(), 1, Integer::sum);
        }
        return candidates.stream().filter(p -> perAuth.get(p.authId()) == 1 && perTran.get(p.tranPk()) == 1)
                .toList();
    }

    private static long expiryDays(StepContribution contribution) {
        Long days = contribution.getStepExecution().getJobParameters().getLong("expiryDays");
        if (days == null || days < 1) {
            throw new CycleJobException("EXPIRY_DAYS_INVALID", "postDailyTransactionsJob needs expiryDays of at least 1"
                    + " (carddemo.batch.pauth.expiry-days)");
        }
        return days;
    }
}
