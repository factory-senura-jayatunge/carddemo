package org.carddemo.platform.ledger;

/** A batch window is open; online writes must wait until it closes. */
public class BatchWindowException extends RuntimeException {

    public BatchWindowException() {
        super("a batch window is open; online writes are blocked");
    }
}
