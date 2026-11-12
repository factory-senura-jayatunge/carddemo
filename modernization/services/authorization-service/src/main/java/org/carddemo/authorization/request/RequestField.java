package org.carddemo.authorization.request;

/**
 * The 18 values of an authorization request (ENT-AUTH-REQUEST, COPAUA0C §4.1), in the order of the
 * CSV body, with their JSON names and stored widths (BR-AUTH-001, must-preserve).
 */
public enum RequestField {
    AUTH_DATE("authDate", 6, Fit.TEXT),
    AUTH_TIME("authTime", 6, Fit.TEXT),
    CARD_NUM("cardNum", 16, Fit.TEXT),
    AUTH_TYPE("authType", 4, Fit.TEXT),
    CARD_EXPIRY_DATE("cardExpiryDate", 4, Fit.TEXT),
    MESSAGE_TYPE("messageType", 6, Fit.TEXT),
    MESSAGE_SOURCE("messageSource", 6, Fit.TEXT),
    PROCESSING_CODE("processingCode", 6, Fit.DIGITS),
    TRANSACTION_AMT("transactionAmt", 13, Fit.AMOUNT),
    MERCHANT_CATEGORY_CODE("merchantCategoryCode", 4, Fit.TEXT),
    ACQR_COUNTRY_CODE("acqrCountryCode", 3, Fit.TEXT),
    POS_ENTRY_MODE("posEntryMode", 2, Fit.DIGITS),
    MERCHANT_ID("merchantId", 15, Fit.TEXT),
    MERCHANT_NAME("merchantName", 22, Fit.TEXT),
    MERCHANT_CITY("merchantCity", 13, Fit.TEXT),
    MERCHANT_STATE("merchantState", 2, Fit.TEXT),
    MERCHANT_ZIP("merchantZip", 9, Fit.TEXT),
    TRANSACTION_ID("transactionId", 15, Fit.TEXT);

    /** How a value is brought to its width. */
    public enum Fit {
        /** Cut on the right, padded with spaces on the right. */
        TEXT,
        /** The legacy {@code 9(n)} receivers: right-aligned, zero-padded and cut on the left. */
        DIGITS,
        /** Not stored as text: the amount is parsed (BR-AUTH-002). */
        AMOUNT
    }

    private final String jsonName;
    private final int width;
    private final Fit fit;

    RequestField(String jsonName, int width, Fit fit) {
        this.jsonName = jsonName;
        this.width = width;
        this.fit = fit;
    }

    public String jsonName() {
        return jsonName;
    }

    public int width() {
        return width;
    }

    public Fit fit() {
        return fit;
    }
}
