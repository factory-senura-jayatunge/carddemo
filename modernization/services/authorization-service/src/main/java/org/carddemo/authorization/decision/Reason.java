package org.carddemo.authorization.decision;

/**
 * Reason codes and their descriptions (COPAUA0C BR-AUTH-007, COPAUS1C BR-AUDT-003). The service
 * sets {@code 0000}, {@code 3100}, {@code 4100}, {@code 4200}, {@code 4300}, {@code 5100} and
 * {@code 9000}; {@code 5200} is reserved until the business defines merchant fraud, and
 * {@code 4400} and {@code 5300} are only described.
 */
public enum Reason {
    APPROVED("0000", "APPROVED"),
    INVALID_CARD("3100", "INVALID CARD"),
    INSUFFICIENT_FUND("4100", "INSUFFICNT FUND"),
    CARD_NOT_ACTIVE("4200", "CARD NOT ACTIVE"),
    ACCOUNT_CLOSED("4300", "ACCOUNT CLOSED"),
    EXCEEDED_DAILY_LIMIT("4400", "EXCED DAILY LMT"),
    CARD_FRAUD("5100", "CARD FRAUD"),
    MERCHANT_FRAUD("5200", "MERCHANT FRAUD"),
    LOST_CARD("5300", "LOST CARD"),
    UNKNOWN("9000", "UNKNOWN");

    private final String code;
    private final String description;

    Reason(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    /** The detail screen's text: code, {@code -}, description; an unknown code gives {@code 9999-ERROR}. */
    public static String displayText(String code) {
        for (Reason r : values()) {
            if (r.code.equals(code)) {
                return r.code + "-" + r.description;
            }
        }
        return "9999-ERROR";
    }
}
