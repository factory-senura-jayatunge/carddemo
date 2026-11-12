package org.carddemo.authorization.request;

/**
 * A request that cannot be read: it is dead-lettered with no reply and no write (messaging.md,
 * ERR-AUTH-003). The reason is logged; the body never is.
 */
public final class UnparsableRequestException extends Exception {

    public enum Reason { UNKNOWN_CONTENT_TYPE, OVERSIZED, UNDECODABLE, TOO_FEW_VALUES, INVALID_JSON }

    private final Reason reason;

    public UnparsableRequestException(Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
