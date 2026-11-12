package org.carddemo.batch.cycle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.statement.StatementRuns;
import org.carddemo.platform.outputroot.OutputRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What cycle-rerun undoes (batch.md, "Deliberate rerun"; contract section 7), in the caller's
 * transaction: the cycle's D-33 matches, the balances its posting started from (V412), the
 * transactions it wrote, its rejects and staged file rows; its online adds wait again, and the
 * cycle is OPEN with the next attempt and no daily file identity, so the next DAG run copies and
 * stages the inbound file afresh with fresh job instances. The inbound file is never moved or
 * rewritten (contract section 4.9).
 */
public final class CycleRestore {

    /** Row counts of one restore. */
    public record Restored(int matchesReverted, int accounts, int categoryBalances, int transactions, int rejects,
            int stagedRows, int onlineAdds) { }

    /** Why the cycle cannot be restored: an error code and a fixed text with counts only. */
    public record Refusal(String code, String message) { }

    private static final Logger log = LoggerFactory.getLogger(CycleRestore.class);

    private final JdbcClient db;
    private final DailyFiles files;

    CycleRestore(JdbcClient db, DailyFiles files) {
        this.db = db;
        this.files = files;
    }

    /**
     * Prepares the return of the daily file that the replaced attempt staged, only when the inbound
     * folder has none (contract section 4.9): the archive copy that the attempt's stored identity
     * names, in the current database instance's output root. A file already inbound is kept, so a
     * corrected file placed there is what the next attempt stages; an attempt with no stored
     * identity returns nothing. The copy goes to a temporary name of this invocation
     * ({@code <name>.rerun.<uuid>.tmp}, {@code 0600}) and its hash is checked against the identity.
     * Call it in the restore's transaction after locking the cycle's row, and {@link
     * PreparedFile#link()} last before the commit; closing the result removes the temporary copy.
     * When this method throws, it has removed its temporary copy itself.
     *
     * @throws Refused {@code DAILY_FILE_CHANGED} when the archive copy no longer has its stored hash
     */
    public PreparedFile prepareDailyFile(long cycleId, LocalDate businessDate, int attempt) {
        CycleRun run = new CycleRun(cycleId, businessDate, attempt);
        Path inbound = files.inbound(run);
        if (Files.exists(inbound, LinkOption.NOFOLLOW_LINKS)) {
            return PreparedFile.NONE;
        }
        Optional<DailyFiles.Identity> identity = db.sql("""
                        SELECT daily_file_name, daily_file_sha256 FROM batch.cycle
                        WHERE cycle_id = :id AND attempt = :attempt AND daily_file_sha256 IS NOT NULL""")
                .param("id", cycleId).param("attempt", attempt)
                .query((rs, n) -> new DailyFiles.Identity(rs.getString(1), rs.getBytes(2))).optional();
        Optional<Path> root = files.currentRoot();
        if (identity.isEmpty() || root.isEmpty()) {
            return PreparedFile.NONE;
        }
        Optional<Path> archived = OutputRoot.existingFile(root.get(), DailyFiles.DAILYTRAN, DailyFiles.ARCHIVE,
                identity.get().name());
        if (archived.isEmpty()) {
            return PreparedFile.NONE;
        }
        PreparedFile prepared = new PreparedFile(inbound,
                inbound.resolveSibling(files.name(run) + ".rerun." + UUID.randomUUID() + ".tmp"), files.name(run));
        boolean handedOver = false;
        try {
            try (OutputStream out = OutputPublication.createWorkFile(prepared.temp);
                 InputStream in = Files.newInputStream(archived.get(), LinkOption.NOFOLLOW_LINKS)) {
                in.transferTo(out);
            }
            if (!Arrays.equals(DailyFiles.sha256(DailyFiles.read(prepared.temp)), identity.get().sha256())) {
                throw new Refused(new Refusal("DAILY_FILE_CHANGED", "The archive copy of the daily file that"
                        + " attempt " + attempt + " staged no longer has its stored checksum; nothing was put back."));
            }
            handedOver = true;
            return prepared;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (!handedOver) {
                prepared.close();
            }
        }
    }

    /**
     * The checked temporary copy of a daily file to put back, or none ({@code NONE}). {@link #link()}
     * links it onto the inbound name with no replace, so an inbound file that appeared meanwhile
     * is kept; {@link #close()} removes the temporary copy on every path, the inbound link stays.
     */
    public static final class PreparedFile implements AutoCloseable {

        static final PreparedFile NONE = new PreparedFile(null, null, null);

        private final Path inbound;
        private final Path temp;
        private final String name;

        private PreparedFile(Path inbound, Path temp, String name) {
            this.inbound = inbound;
            this.temp = temp;
            this.name = name;
        }

        /** @return whether this invocation put the file back */
        public boolean link() {
            if (temp == null) {
                return false;
            }
            try {
                Files.createLink(inbound, temp);
                return true;
            } catch (FileAlreadyExistsException e) {
                return false;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            if (temp == null) {
                return;
            }
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                log.warn("cycle-rerun: could not remove its temporary copy of {} ({})", name,
                        e.getClass().getSimpleName());
            }
        }
    }

    /** A refused rerun step, with the refusal's code and a fixed text. */
    public static final class Refused extends RuntimeException {

        private final transient Refusal refusal;

        Refused(Refusal refusal) {
            super(refusal.code() + ": " + refusal.message(), null, false, false);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /**
     * Whether restoring would lose work done after the cycle: a later cycle that has started, a
     * transaction outside the cycle written after its snapshot (a bill payment), whose balance
     * change the restore would drop while the transaction stays, or a later cycle with a COMPLETED
     * statement run, whose statement already issued would no longer follow from this period. Call
     * it in the restore's transaction, after locking the cycle's row.
     */
    public Optional<Refusal> refusal(long cycleId) {
        int later = db.sql("""
                SELECT COUNT(*) FROM batch.cycle AS c
                JOIN batch.cycle AS later ON later.business_date > c.business_date
                WHERE c.cycle_id = :c AND (later.snapshot_at IS NOT NULL OR later.archived_count IS NOT NULL
                    OR later.daily_file_sha256 IS NOT NULL)""").param("c", cycleId).query(Integer.class).single();
        if (later > 0) {
            return Optional.of(new Refusal("LATER_CYCLE_STARTED",
                    later + " later cycle(s) have started; only the latest cycle can be rerun."));
        }
        long since = db.sql("""
                SELECT COUNT_BIG(*) FROM batch.cycle AS c
                JOIN core.[transaction] AS t ON t.tran_pk > COALESCE(c.snapshot_tran_pk,
                    (SELECT MIN(x.tran_pk) - 1 FROM core.[transaction] AS x WHERE x.cycle_id = c.cycle_id))
                WHERE c.cycle_id = :c AND c.snapshot_at IS NOT NULL
                  AND (t.cycle_id IS NULL OR t.cycle_id <> c.cycle_id)""").param("c", cycleId)
                .query(Long.class).single();
        if (since > 0) {
            return Optional.of(new Refusal("WRITTEN_SINCE_SNAPSHOT", since + " transaction(s) outside the cycle were"
                    + " written after its balance snapshot; restoring it would drop their balance changes."));
        }
        int statements = StatementRuns.laterCompleted(db, cycleId);
        if (statements > 0) {
            return Optional.of(new Refusal("LATER_STATEMENT_COMPLETED", statements + " later cycle(s) have completed"
                    + " statements; replacing this period would change statements already issued."));
        }
        return Optional.empty();
    }

    /** The restore itself, in the caller's transaction; {@code attempt} is the attempt being replaced. */
    public Restored restore(long cycleId, int attempt) {
        // D-33 (d33-matching-brief.md, "Cycle rerun"): before the transactions go.
        db.sql("""
                UPDATE s SET credit_balance = s.credit_balance + m.amt
                FROM pauth.auth_summary AS s
                JOIN (SELECT acct_id, SUM(approved_amt) AS amt FROM pauth.auth_detail
                      WHERE matched_cycle_id = :c GROUP BY acct_id) AS m ON m.acct_id = s.acct_id""")
                .param("c", cycleId).update();
        int reverted = db.sql("""
                UPDATE pauth.auth_detail SET match_status = 'P', matched_tran_pk = NULL, matched_tran_id = NULL,
                    matched_cycle_id = NULL, matched_at = NULL
                WHERE matched_cycle_id = :c""").param("c", cycleId).update();
        db.sql("DELETE FROM batch.auth_match_pair WHERE cycle_id = :c").param("c", cycleId).update();

        boolean snapshot = db.sql("SELECT COUNT(*) FROM batch.cycle WHERE cycle_id = :c AND snapshot_at IS NOT NULL")
                .param("c", cycleId).query(Integer.class).single() == 1;
        int accounts = 0;
        int categories = 0;
        if (snapshot) {
            accounts = db.sql("""
                    UPDATE a SET curr_bal = s.curr_bal, curr_cyc_credit = s.curr_cyc_credit, curr_cyc_debit = s.curr_cyc_debit
                    FROM core.account AS a
                    JOIN batch.snapshot_account AS s ON s.acct_id = a.acct_id AND s.cycle_id = :c
                    WHERE a.curr_bal <> s.curr_bal OR a.curr_cyc_credit <> s.curr_cyc_credit
                       OR a.curr_cyc_debit <> s.curr_cyc_debit""").param("c", cycleId).update();
            // Delete and insert rather than update, so the FILLER comes back with no UPDATE grant on it;
            // a category balance the cycle created has no snapshot row and goes.
            categories = db.sql("""
                    DELETE t FROM core.tcat_bal AS t
                    WHERE NOT EXISTS (SELECT 1 FROM batch.snapshot_tcat_bal AS s
                        WHERE s.cycle_id = :c AND s.acct_id = t.acct_id AND s.type_cd = t.type_cd AND s.cat_cd = t.cat_cd
                          AND s.balance = t.balance
                          AND (s.legacy_filler = t.legacy_filler OR (s.legacy_filler IS NULL AND t.legacy_filler IS NULL)))""")
                    .param("c", cycleId).update();
            db.sql("""
                    INSERT INTO core.tcat_bal (acct_id, type_cd, cat_cd, balance, legacy_filler)
                    SELECT s.acct_id, s.type_cd, s.cat_cd, s.balance, s.legacy_filler
                    FROM batch.snapshot_tcat_bal AS s
                    WHERE s.cycle_id = :c AND NOT EXISTS (SELECT 1 FROM core.tcat_bal AS t
                        WHERE t.acct_id = s.acct_id AND t.type_cd = s.type_cd AND t.cat_cd = s.cat_cd)""")
                    .param("c", cycleId).update();
        }
        int transactions = db.sql("DELETE FROM core.[transaction] WHERE cycle_id = :c AND origin IN ('POSTED', 'INTEREST')")
                .param("c", cycleId).update();
        int rejects = db.sql("DELETE FROM core.daily_reject WHERE cycle_id = :c").param("c", cycleId).update();
        int staged = db.sql("DELETE FROM core.daily_tran WHERE cycle_id = :c AND origin = 'DAILY_FILE'")
                .param("c", cycleId).update();
        int adds = db.sql("UPDATE core.daily_tran SET cycle_id = NULL, seq = NULL WHERE cycle_id = :c AND origin = 'ONLINE_ADD'")
                .param("c", cycleId).update();
        StatementRuns.endForRerun(db, cycleId);
        int raised = db.sql("""
                UPDATE batch.cycle SET attempt = attempt + 1, status = 'OPEN', closed_at = NULL,
                    daily_file_name = NULL, daily_file_sha256 = NULL
                WHERE cycle_id = :c AND attempt = :a""").param("c", cycleId).param("a", attempt).update();
        if (raised != 1) {
            throw new IllegalStateException("cycle " + cycleId + " is no longer at attempt " + attempt);
        }
        return new Restored(reverted, accounts, categories, transactions, rejects, staged, adds);
    }
}
