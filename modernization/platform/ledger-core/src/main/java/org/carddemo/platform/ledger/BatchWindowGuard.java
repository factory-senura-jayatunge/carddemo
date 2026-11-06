package org.carddemo.platform.ledger;

/**
 * Online writers call this first in each write transaction. It takes the application lock
 * {@code carddemo.batch_window} shared for the rest of the transaction, then fails when a batch
 * window is open. Opening a window takes the lock exclusively, so it waits for writes in flight
 * and no write starts once it is open (INV-002, D-BAT-05 WRITES_BLOCKED).
 */
public interface BatchWindowGuard {

    /** @throws BatchWindowException when a window is open */
    void requireWritesAllowed();
}
