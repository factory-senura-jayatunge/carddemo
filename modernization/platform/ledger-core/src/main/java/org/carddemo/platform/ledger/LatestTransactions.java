package org.carddemo.platform.ledger;

import java.util.Optional;

/**
 * "Copy the last transaction" of transaction add (BR-TRNA-006): the data fields of the
 * transaction with the highest ID, read from the same set as legacy ID allocation, the master
 * plus the online adds waiting for the next cycle (D-27). The result does not depend on the
 * BR-TRNA-003 mode. Between a cycle's claim and the end of its posting a claimed add is in
 * neither set; callers run outside the batch window, as transaction add does.
 */
public interface LatestTransactions {

    /** Empty when neither the master nor the waiting adds hold a row. */
    Optional<LatestTransaction> latest();
}
