package org.carddemo.authorization.request;

/** The body form of one message, chosen by its {@code content_type} (D-MSG-02); not a switch. */
public enum BodyForm {
    /** 18 comma-separated values; replied with the 63-character text. */
    CSV(500),
    /** One object with the 18 named fields; replied with the JSON object. */
    JSON(2048);

    private final int maxBytes;

    BodyForm(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int maxBytes() {
        return maxBytes;
    }
}
