package org.carddemo.platform.web;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A PII value (card number, SSN, date of birth, government ID, EFT account; D-09, D-SEC-05). It
 * serialises and prints masked to its last four characters, so a DTO field of this type cannot leak
 * the clear value into a response or a log. Only {@link #reveal()} gives the clear value, for an
 * audited unmask or for the service's own checks.
 */
public final class Pii {

    private enum Kind { CARD_NUMBER, SSN, DATE, TEXT }

    private final Kind kind;
    private final String clear;

    private Pii(Kind kind, String clear) {
        this.kind = kind;
        this.clear = clear;
    }

    /** {@code 4111111111111111} shows as {@code ************1111}. */
    public static Pii cardNumber(String value) {
        return new Pii(Kind.CARD_NUMBER, value);
    }

    /** Nine digits {@code 123456789} show as {@code ***-**-6789}. */
    public static Pii ssn(String value) {
        return new Pii(Kind.SSN, value);
    }

    /** {@code 1961-03-14} shows as {@code ****-03-14}: the last four digits, in the date's layout. */
    public static Pii date(LocalDate value) {
        return new Pii(Kind.DATE, value == null ? null : value.toString());
    }

    /** Any other text: every character but the last four becomes {@code *}. */
    public static Pii text(String value) {
        return new Pii(Kind.TEXT, value);
    }

    /** The clear value: a date as {@code YYYY-MM-DD}. */
    public String reveal() {
        return clear;
    }

    public boolean isBlank() {
        return clear == null || clear.isBlank();
    }

    @JsonValue
    public String masked() {
        if (clear == null) {
            return null;
        }
        if (clear.isBlank()) {
            return clear;
        }
        return switch (kind) {
            case SSN -> clear.length() == 9 ? "***-**-" + clear.substring(5) : lastFour(clear);
            case DATE -> "****-" + clear.substring(5);
            default -> lastFour(clear);
        };
    }

    private static String lastFour(String s) {
        if (s.length() <= 4) {
            return "****";
        }
        return "*".repeat(s.length() - 4) + s.substring(s.length() - 4);
    }

    @Override
    public String toString() {
        return String.valueOf(masked());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Pii p && p.kind == kind && Objects.equals(p.clear, clear);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, clear);
    }
}
