package org.carddemo.platform.ledger;

/** The only writer of {@code core.transaction}. Requires an active transaction. */
public interface TransactionWriter {

    /**
     * Inserts the transaction into the master ({@code in_master = 1}).
     *
     * @return the new row's {@code tran_pk}
     * @throws DuplicateTranIdException when the master already holds {@code t.tranId()}
     */
    long insert(NewTransaction t);
}
