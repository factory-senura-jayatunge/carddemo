package org.carddemo.platform.ledger;

/**
 * The category balance row that {@link BalancePosting#post(String, String, String, java.math.BigDecimal, String)}
 * changed: whether it created the row, and the row's {@code legacy_filler} after the call. That is
 * the filler it was given when it created the row, else the existing row's, which is {@code null}
 * on a row an online writer created.
 */
public record PostedCategory(boolean created, String legacyFiller) {

    /** CVTRA01Y's FILLER PIC X(22). */
    public static final int FILLER_LENGTH = 22;

    /**
     * {@code filler} when it can be a category balance FILLER: exactly 22 characters, each one a
     * byte of the legacy record (U+0000 to U+00FF), low-values included.
     */
    static String requireFiller(String filler) {
        if (filler == null || filler.length() != FILLER_LENGTH) {
            throw new IllegalArgumentException("a new category balance FILLER is exactly " + FILLER_LENGTH
                    + " characters");
        }
        for (int i = 0; i < filler.length(); i++) {
            if (filler.charAt(i) > '\u00FF') {
                throw new IllegalArgumentException("a category balance FILLER holds only U+0000 to U+00FF; position "
                        + (i + 1) + " does not");
            }
        }
        return filler;
    }
}
