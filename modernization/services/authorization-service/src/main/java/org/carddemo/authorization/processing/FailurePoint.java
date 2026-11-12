package org.carddemo.authorization.processing;

/**
 * The step of the transaction whose SQL failed, with the legacy log location where the legacy had
 * one (COPAUA0C ERR-AUTH-005, ERR-AUTH-007, ERR-AUTH-009). The duplicate key, card, fraud and
 * outbox reads and writes are new and have none.
 */
public enum FailurePoint {
    PROCESSED_MESSAGE_READ(null),
    XREF_READ("C001"),
    ACCOUNT_READ("C002"),
    CUSTOMER_READ("C003"),
    CARD_READ(null),
    FRAUD_READ(null),
    SUMMARY_READ("I002"),
    SUMMARY_WRITE("I003"),
    DETAIL_WRITE("I004"),
    OUTBOX_WRITE(null),
    PROCESSED_MESSAGE_WRITE(null);

    private final String legacyLocation;

    FailurePoint(String legacyLocation) {
        this.legacyLocation = legacyLocation;
    }

    /** The legacy location, or {@code null}. */
    public String legacyLocation() {
        return legacyLocation;
    }
}
