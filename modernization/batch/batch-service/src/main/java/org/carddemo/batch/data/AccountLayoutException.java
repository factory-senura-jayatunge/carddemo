package org.carddemo.batch.data;

/**
 * A layout manifest or an account record that the DEF-ACUP-002 rules refuse. The message names
 * the manifest line, the override position or the file line, never an account ID (D-NFR-04).
 */
public final class AccountLayoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccountLayoutException(String message) {
        super(message);
    }
}
