package org.carddemo.platform.ledger;

import java.sql.SQLException;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link TransactionIds} on core.transaction, core.daily_tran and core.tran_id_seq. */
public class JdbcTransactionIds implements TransactionIds {

    static final String LOCK = "carddemo.tran_id";
    static final int LOCK_TIMEOUT_MILLIS = 10_000;
    static final long MAX_ID = 9_999_999_999_999_999L;
    /** SQL Server: the sequence object has reached its minimum or maximum value. */
    static final int SEQUENCE_EXHAUSTED = 11728;

    /**
     * The legacy file the ID follows is the master plus the online adds that wait for the next
     * cycle (D-27): an add goes to core.daily_tran, not to the master, until posting. Claimed rows
     * are left out; they reach the master when the cycle posts them, and writers are blocked by
     * the batch window in between.
     */
    static final String HIGHEST_ID = """
            SELECT MAX(tran_id) FROM (
                SELECT MAX(tran_id) AS tran_id FROM core.[transaction] WHERE in_master = 1
                UNION ALL
                SELECT MAX(tran_id) FROM core.daily_tran WHERE cycle_id IS NULL AND origin = 'ONLINE_ADD'
            ) AS ids""";

    private final JdbcClient jdbc;
    private final FidelitySwitches switches;

    public JdbcTransactionIds(JdbcClient jdbc, FidelitySwitches switches) {
        this.jdbc = jdbc;
        this.switches = switches;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String next() {
        if (switches.isFixed(LedgerSwitches.BR_TRNA_003)) {
            Long value;
            try {
                value = jdbc.sql("SELECT NEXT VALUE FOR core.tran_id_seq").query(Long.class).single();
            } catch (DataAccessException e) {
                if (sequenceExhausted(e)) {
                    throw new TranIdExhaustedException(e);
                }
                throw e;
            }
            return format(value);
        }
        // The lock is held until commit, so the next writer sees this one's insert (DEF-BILL-003).
        if (!AppLocks.acquire(jdbc, LOCK, "Exclusive", LOCK_TIMEOUT_MILLIS)) {
            throw new CannotAcquireLockException("application lock " + LOCK + " not granted");
        }
        String highest = jdbc.sql(HIGHEST_ID).query(String.class).optional().orElse(null);
        long last = highest == null ? 0 : parse(highest);
        if (last >= MAX_ID) {
            throw new TranIdExhaustedException(null);
        }
        return format(last + 1);
    }

    private static boolean sequenceExhausted(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == SEQUENCE_EXHAUSTED) {
                return true;
            }
        }
        return false;
    }

    static String format(long id) {
        if (id < 1 || id > MAX_ID) {
            throw new IllegalStateException("transaction ID " + id + " does not fit 16 digits");
        }
        return String.format("%016d", id);
    }

    private static long parse(String tranId) {
        String digits = tranId.trim();
        if (!digits.matches("\\d{1,16}")) {
            throw new IllegalStateException("highest transaction ID is not numeric: " + tranId);
        }
        return Long.parseLong(digits);
    }
}
