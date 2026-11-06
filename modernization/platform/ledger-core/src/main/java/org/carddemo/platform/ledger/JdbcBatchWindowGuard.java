package org.carddemo.platform.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link BatchWindowGuard} on ops.batch_window and the application lock carddemo.batch_window. */
public class JdbcBatchWindowGuard implements BatchWindowGuard {

    static final String LOCK = "carddemo.batch_window";
    static final int LOCK_TIMEOUT_MILLIS = 5_000;

    private final JdbcClient jdbc;

    public JdbcBatchWindowGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireWritesAllowed() {
        // Not granted in time means the batch holds it exclusively: a window is opening.
        if (!AppLocks.acquire(jdbc, LOCK, "Shared", LOCK_TIMEOUT_MILLIS)) {
            throw new BatchWindowException();
        }
        Integer open = jdbc.sql("SELECT COUNT(*) FROM ops.batch_window WHERE closed_at IS NULL")
                .query(Integer.class).single();
        if (open != null && open > 0) {
            throw new BatchWindowException();
        }
    }
}
