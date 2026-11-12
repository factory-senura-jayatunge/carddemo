package org.carddemo.verify.differential;

/**
 * A refusal of the refusal mode: one code of {@link RefusalCodes}, and nothing else. It never
 * carries a cause, a message beyond the code or a stack trace, so nothing about a path, a value or
 * an error text can leave through it.
 */
final class RefusalException extends RuntimeException {

    private final String code;

    RefusalException(String code) {
        super(code, null, false, false);
        if (code == null || !RefusalCodes.ALL.contains(code)) {
            throw new IllegalArgumentException("not a refusal code");
        }
        this.code = code;
    }

    String code() {
        return code;
    }
}
