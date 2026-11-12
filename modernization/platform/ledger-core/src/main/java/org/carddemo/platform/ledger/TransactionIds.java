package org.carddemo.platform.ledger;

/**
 * Allocates the 16-digit ID of a new transaction. Fidelity switch {@code BR-TRNA-003} (D-59):
 * fixed mode takes {@code NEXT VALUE FOR core.tran_id_seq}; legacy mode takes the highest ID among
 * the master and the online adds waiting for the next cycle (D-27), plus one, under the
 * application lock {@code carddemo.tran_id}, so two writers cannot get the same ID. Requires an
 * active transaction. No ID wraps: past {@code 9999999999999999} both modes throw
 * {@link TranIdExhaustedException}.
 */
public interface TransactionIds {

    /**
     * 16 digits with leading zeros.
     *
     * @throws TranIdExhaustedException when no ID is left in the range
     */
    String next();
}
