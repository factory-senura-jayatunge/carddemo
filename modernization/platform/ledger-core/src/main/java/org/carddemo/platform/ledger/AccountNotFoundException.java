package org.carddemo.platform.ledger;

/** A balance change found no account row (the legacy NOTFND on REWRITE). */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String acctId) {
        super("no account " + acctId);
    }
}
