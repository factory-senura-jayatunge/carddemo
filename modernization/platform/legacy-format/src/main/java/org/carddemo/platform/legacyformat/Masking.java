package org.carddemo.platform.legacyformat;

/** Masking for anything that leaves a process: logs, reports, exception messages. */
public final class Masking {

    private Masking() {
    }

    /** A card number shown as its last four characters, for example {@code ************1234}. */
    public static String lastFour(String cardNumber) {
        if (cardNumber == null) {
            return null;
        }
        String s = cardNumber.strip();
        if (s.length() <= 4) {
            return "*".repeat(s.length());
        }
        return "*".repeat(s.length() - 4) + s.substring(s.length() - 4);
    }
}
