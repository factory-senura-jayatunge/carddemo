package org.carddemo.verify.differential;

/**
 * The database does not hold one complete, consistent run of the job, or the files cannot be
 * written: exit status 1. Messages name jobs, steps, IDs and counts, never a record's content.
 */
final class ExportException extends RuntimeException {

    ExportException(String message) {
        super(message);
    }

    ExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
