package org.carddemo.platform.ledger;

/**
 * No transaction ID is left in the 16-digit range: fixed mode's sequence ({@code NO CYCLE}) has
 * issued {@code 9999999999999999}, or legacy mode's highest ID is {@code 9999999999999999}. The
 * legacy program would wrap to {@code 0000000000000000} (NUM-BILL-005, NUM-TRNA-004); no ID wraps
 * here, in either mode.
 */
public class TranIdExhaustedException extends RuntimeException {

    /** The API code and batch exit description of this failure. */
    public static final String CODE = "TRAN_ID_EXHAUSTED";

    public TranIdExhaustedException(Throwable cause) {
        super("no transaction ID is left after 9999999999999999", cause);
    }
}
