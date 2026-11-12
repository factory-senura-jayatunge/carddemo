package org.carddemo.batch.statement;

/**
 * A legacy statement run refused before any output is written. The message, {@code CODE: detail},
 * becomes the exit description; the detail holds counts and record numbers, never a card number,
 * an account number or a name.
 */
public final class LegacyStatementException extends RuntimeException {

    /** DEF-STMT-001 legacy: the work file does not fit the program's 51 x 10 transaction table. */
    public static final String STATEMENT_TABLE_LIMIT = "STATEMENT_TABLE_LIMIT";
    /** BR-STMT-004, ERR-STMT-004: a cross-reference record whose customer is not on file. */
    public static final String STATEMENT_CUSTOMER_NOT_FOUND = "STATEMENT_CUSTOMER_NOT_FOUND";
    /** BR-STMT-004, ERR-STMT-004: a cross-reference record whose account is not on file. */
    public static final String STATEMENT_ACCOUNT_NOT_FOUND = "STATEMENT_ACCOUNT_NOT_FOUND";

    private final String code;
    private final int cards;
    private final int mostTransactionsOnACard;

    private LegacyStatementException(String code, String detail, int cards, int mostTransactionsOnACard) {
        super(code + ": " + detail, null, false, false);
        this.code = code;
        this.cards = cards;
        this.mostTransactionsOnACard = mostTransactionsOnACard;
    }

    static LegacyStatementException tableLimit(int cards, int mostTransactionsOnACard) {
        return new LegacyStatementException(STATEMENT_TABLE_LIMIT, "the work file has " + cards
                + " cards and up to " + mostTransactionsOnACard + " transactions on one card; the legacy table holds "
                + LegacyStatementRenderer.MAX_CARDS + " cards of " + LegacyStatementRenderer.MAX_TRANSACTIONS_PER_CARD
                + " transactions (DEF-STMT-001)", cards, mostTransactionsOnACard);
    }

    static LegacyStatementException notFound(String code, String what, int xrefRecord) {
        return new LegacyStatementException(code, "cross-reference record " + xrefRecord + " has no " + what
                + " (BR-STMT-004)", 0, 0);
    }

    public String code() {
        return code;
    }

    /** For {@link #STATEMENT_TABLE_LIMIT}: the distinct cards of the work file; otherwise 0. */
    public int cards() {
        return cards;
    }

    /** For {@link #STATEMENT_TABLE_LIMIT}: the most work-file records of one card; otherwise 0. */
    public int mostTransactionsOnACard() {
        return mostTransactionsOnACard;
    }
}
