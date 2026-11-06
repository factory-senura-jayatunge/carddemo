package org.carddemo.platform.ledger;

/** The master already holds the transaction ID (the legacy DUPKEY/DUPREC response). */
public class DuplicateTranIdException extends RuntimeException {

    private final String tranId;

    public DuplicateTranIdException(String tranId, Throwable cause) {
        super("transaction ID " + tranId + " already exists in the master", cause);
        this.tranId = tranId;
    }

    public String tranId() {
        return tranId;
    }
}
