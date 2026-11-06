package org.carddemo.platform.ledger;

import org.carddemo.platform.fidelity.FidelitySwitches;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link TransactionIds} on core.transaction and core.tran_id_seq. */
public class JdbcTransactionIds implements TransactionIds {

    static final String LOCK = "carddemo.tran_id";
    static final int LOCK_TIMEOUT_MILLIS = 10_000;

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
            Long value = jdbc.sql("SELECT NEXT VALUE FOR core.tran_id_seq").query(Long.class).single();
            return format(value);
        }
        // The lock is held until commit, so the next writer sees this one's insert (DEF-BILL-003).
        if (!AppLocks.acquire(jdbc, LOCK, "Exclusive", LOCK_TIMEOUT_MILLIS)) {
            throw new CannotAcquireLockException("application lock " + LOCK + " not granted");
        }
        String highest = jdbc.sql("SELECT MAX(tran_id) FROM core.[transaction] WHERE in_master = 1")
                .query(String.class).optional().orElse(null);
        return format(highest == null ? 1 : parse(highest) + 1);
    }

    static String format(long id) {
        if (id < 1 || id > 9_999_999_999_999_999L) {
            throw new IllegalStateException("transaction ID " + id + " does not fit 16 digits");
        }
        return String.format("%016d", id);
    }

    private static long parse(String tranId) {
        String digits = tranId.trim();
        if (!digits.matches("\\d{1,16}")) {
            throw new IllegalStateException("highest master transaction ID is not numeric: " + tranId);
        }
        return Long.parseLong(digits);
    }
}
