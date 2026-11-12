package org.carddemo.authorization.processing;

/**
 * The duplicate key was inserted by another delivery between the check before the transaction and
 * its {@code processed_message} insert. The transaction is rolled back; the caller takes the
 * duplicate path (contract §6.3).
 */
public class AlreadyProcessedException extends RuntimeException {

    public AlreadyProcessedException() {
        super("the request's duplicate key is already in pauth.processed_message");
    }
}
