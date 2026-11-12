package org.carddemo.batch.cycle;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The checks CBTRN02C makes on one daily transaction (VAL-POST-001, VAL-POST-002, BR-POST-001 to
 * BR-POST-003), against the account as the earlier postings of the run left it (BR-POST-007).
 * The over-limit formula is the legacy one (D-19, DEF-POST-005). A reject keeps the legacy reason,
 * the last failing check, and every failing check in check order (D-21).
 */
final class PostingRules {

    static final String CARD_NOT_FOUND = "0100";
    static final String ACCOUNT_NOT_FOUND = "0101";
    static final String OVER_LIMIT = "0102";
    static final String EXPIRED = "0103";
    /** D-20: a valid record whose account update failed is rolled back and rejected (DEF-POST-003 fixed). */
    static final String ACCOUNT_UPDATE_FAILED = "0109";

    /** The reason texts, as CBTRN02C writes them before padding to 76 characters. */
    static String description(String reasonCd) {
        return switch (reasonCd) {
            case CARD_NOT_FOUND -> "INVALID CARD NUMBER FOUND";
            case ACCOUNT_NOT_FOUND, ACCOUNT_UPDATE_FAILED -> "ACCOUNT RECORD NOT FOUND";
            case OVER_LIMIT -> "OVERLIMIT TRANSACTION";
            case EXPIRED -> "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";
            default -> throw new IllegalArgumentException("no reason " + reasonCd);
        };
    }

    /** The account columns the checks read, as stored after this run's earlier postings. */
    record Account(BigDecimal currCycCredit, BigDecimal currCycDebit, BigDecimal creditLimit,
            String expirationDate) {
    }

    /** Why a transaction is rejected: {@code reasonCd} for the reject record, {@code reasonCds} for D-21. */
    record Reject(String reasonCd, String reasonCds) {

        static Reject of(String reasonCd) {
            return new Reject(reasonCd, reasonCd);
        }

        String description() {
            return PostingRules.description(reasonCd);
        }
    }

    private PostingRules() {
    }

    /**
     * @param cardFound whether the card cross-reference has the transaction's card number
     * @param account the cross-referenced account, or {@code null} when there is none
     * @param origTs {@code DALYTRAN-ORIG-TS} as read, possibly blank
     */
    static Optional<Reject> check(boolean cardFound, Account account, BigDecimal amount, String origTs) {
        if (!cardFound) {
            return Optional.of(Reject.of(CARD_NOT_FOUND));
        }
        if (account == null) {
            return Optional.of(Reject.of(ACCOUNT_NOT_FOUND));
        }
        boolean overLimit = account.currCycCredit().subtract(account.currCycDebit()).add(amount)
                .compareTo(account.creditLimit()) > 0;
        boolean expired = compareAsText(account.expirationDate(), first10(origTs)) < 0;
        if (overLimit && expired) {
            return Optional.of(new Reject(EXPIRED, OVER_LIMIT + "," + EXPIRED));
        }
        if (expired) {
            return Optional.of(Reject.of(EXPIRED));
        }
        if (overLimit) {
            return Optional.of(Reject.of(OVER_LIMIT));
        }
        return Optional.empty();
    }

    /** The first 10 characters of a 26-character field as COBOL sees it, padded with spaces. */
    private static String first10(String ts) {
        String padded = ts == null ? "" : ts;
        return padded.length() >= 10 ? padded.substring(0, 10) : padded + " ".repeat(10 - padded.length());
    }

    /**
     * COBOL's alphanumeric comparison of two X(10) fields: the shorter is padded with spaces and
     * the characters compare by code point, which is the ISO-8859-1 byte order of the records.
     */
    static int compareAsText(String a, String b) {
        String x = pad10(a);
        String y = pad10(b);
        return x.compareTo(y);
    }

    private static String pad10(String s) {
        String v = s == null ? "" : s;
        return v.length() >= 10 ? v.substring(0, 10) : v + " ".repeat(10 - v.length());
    }
}
