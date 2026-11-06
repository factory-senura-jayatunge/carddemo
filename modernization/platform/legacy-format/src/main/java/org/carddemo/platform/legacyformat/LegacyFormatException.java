package org.carddemo.platform.legacyformat;

/**
 * A field that cannot be decoded or encoded under its layout. Messages name the
 * record, field and position but never the field's content, because many
 * fields hold card numbers or personal data.
 */
public class LegacyFormatException extends IllegalArgumentException {

    public LegacyFormatException(String message) {
        super(message);
    }
}
