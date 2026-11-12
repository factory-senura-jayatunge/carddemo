package org.carddemo.batch.statement;

/**
 * A statementJob failure with a stable code; the message, {@code CODE: detail}, becomes the exit
 * description. The detail holds IDs and counts, never a card number, a name or an amount.
 */
final class StatementFailure extends RuntimeException {

    private final String code;

    StatementFailure(String code, String detail) {
        super(code + ": " + detail, null, false, false);
        this.code = code;
    }

    String code() {
        return code;
    }
}
