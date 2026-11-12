package org.carddemo.batch.pauth;

/**
 * The purge stopped on a database error. The message gives the SQL Server error number only:
 * driver messages can quote key values, and the purge logs no account or card number (D-10).
 */
public class PurgeException extends RuntimeException {

    public PurgeException(String message) {
        super(message);
    }
}
