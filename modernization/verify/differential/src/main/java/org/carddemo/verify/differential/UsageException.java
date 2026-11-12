package org.carddemo.verify.differential;

/** A bad command line: exit status 2. */
final class UsageException extends RuntimeException {

    UsageException(String message) {
        super(message);
    }
}
