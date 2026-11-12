package org.carddemo.batch.cycle;

import java.util.ArrayList;
import java.util.List;

import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.ledger.LedgerSwitches;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ingestDailyFileJob's {@code claimOnlineAdds} (D-27): checks the staged file's transaction IDs,
 * then gives every waiting online add the cycle and the seq numbers after the file's, in insert
 * order. Check and claim are one transaction inside the window, so a failed check leaves the adds
 * waiting and the staged rows unposted (batch.md, transaction IDs, rules 2 and 3).
 */
final class ClaimOnlineAddsTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ClaimOnlineAddsTasklet.class);
    private static final int LISTED = 20;

    /** A file row's ID that meets another row: a second file row, an online add or a master row. */
    private static final String CONFLICTS = """
            SELECT f.tran_id, f.seq, o.seq AS other_seq, o.origin AS other_origin, o.kind
            FROM core.daily_tran AS f
            JOIN (
                SELECT tran_id, seq, origin, 'file' AS kind FROM core.daily_tran
                WHERE cycle_id = :cycle AND origin = 'DAILY_FILE'
                UNION ALL
                SELECT tran_id, NULL, origin, 'add' FROM core.daily_tran
                WHERE origin = 'ONLINE_ADD' AND (cycle_id IS NULL OR cycle_id = :cycle)
                UNION ALL
                SELECT tran_id, NULL, origin, 'master' FROM core.[transaction] WHERE in_master = 1
            ) AS o ON o.tran_id = f.tran_id AND (o.kind <> 'file' OR o.seq > f.seq)
            WHERE f.cycle_id = :cycle AND f.origin = 'DAILY_FILE'
            ORDER BY f.seq, o.seq""";

    /** All 16 characters digits and the first one 9 (D-59); LEN ignores a trailing space, so it cannot pass. */
    private static final String RESERVED = """
            SELECT tran_id, seq FROM core.daily_tran
            WHERE cycle_id = :cycle AND origin = 'DAILY_FILE'
              AND LEN(tran_id) = 16 AND tran_id LIKE '9%' AND tran_id NOT LIKE '%[^0-9]%'
            ORDER BY seq""";

    private static final String CLAIM = """
            UPDATE d SET cycle_id = :cycle, seq = :base + w.n
            FROM core.daily_tran AS d
            JOIN (SELECT daily_pk, ROW_NUMBER() OVER (ORDER BY daily_pk) AS n
                  FROM core.daily_tran WITH (UPDLOCK, HOLDLOCK) WHERE cycle_id IS NULL) AS w
              ON w.daily_pk = d.daily_pk""";

    private final JdbcClient db;
    private final FidelitySwitches switches;

    ClaimOnlineAddsTasklet(JdbcClient db, FidelitySwitches switches) {
        this.db = db;
        this.switches = switches;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        CycleRun run = CycleRun.of(contribution.getStepExecution());
        // Locks the waiting rows and the range of their index before the checks read them.
        db.sql("SELECT COUNT(*) FROM core.daily_tran WITH (UPDLOCK, HOLDLOCK) WHERE cycle_id IS NULL")
                .query(Integer.class).single();
        List<String> conflicts = db.sql(CONFLICTS).param("cycle", run.cycleId())
                .query((rs, n) -> describe(rs.getString("tran_id"), rs.getInt("seq"), rs.getString("kind"),
                        rs.getObject("other_seq", Integer.class), rs.getString("other_origin")))
                .list();
        if (!conflicts.isEmpty()) {
            throw new CycleJobException("DUPLICATE_TRAN_ID", listed(conflicts));
        }
        if (switches.isFixed(LedgerSwitches.BR_TRNA_003)) {
            List<String> reserved = db.sql(RESERVED).param("cycle", run.cycleId())
                    .query((rs, n) -> "tran_id " + rs.getString("tran_id") + " at file record " + rs.getInt("seq")
                            + " is in the range of generated IDs")
                    .list();
            if (!reserved.isEmpty()) {
                throw new CycleJobException("TRAN_ID_RESERVED", listed(reserved));
            }
        }
        int base = db.sql("SELECT COALESCE(MAX(seq), 0) FROM core.daily_tran WHERE cycle_id = :cycle")
                .param("cycle", run.cycleId()).query(Integer.class).single();
        int claimed = db.sql(CLAIM).param("cycle", run.cycleId()).param("base", base).update();
        contribution.incrementWriteCount(claimed);
        log.info("claimOnlineAdds: cycle {} claimed {} waiting online adds", run.cycleId(), claimed);
        return RepeatStatus.FINISHED;
    }

    private static String describe(String tranId, int seq, String kind, Integer otherSeq, String otherOrigin) {
        String other = switch (kind) {
            case "file" -> "file record " + otherSeq;
            case "add" -> "an " + otherOrigin + " row";
            default -> "a master row (" + otherOrigin + ")";
        };
        return "tran_id " + tranId + " at file record " + seq + " and " + other;
    }

    private static String listed(List<String> items) {
        List<String> shown = new ArrayList<>(items.subList(0, Math.min(LISTED, items.size())));
        if (items.size() > LISTED) {
            shown.add("and " + (items.size() - LISTED) + " more");
        }
        return String.join("; ", shown);
    }
}
