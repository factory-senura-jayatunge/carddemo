package org.carddemo.authorization.request;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.carddemo.platform.web.Pii;

/**
 * One parsed request. {@link #raw} holds each value as received, for the decision checks;
 * {@link #stored} holds the text fields after the character-set and width rules, as they are
 * written and repeated in the reply. {@link #toString()} masks the card number and leaves the
 * other values out, so a request never reaches a log line.
 */
public final class AuthorizationRequest {

    private final BodyForm form;
    private final Map<RequestField, String> raw;
    private final Map<RequestField, String> stored;
    private final Amount amount;
    private final List<RequestField> replaced;

    AuthorizationRequest(BodyForm form, Map<RequestField, String> raw) {
        this.form = form;
        this.raw = Collections.unmodifiableMap(new EnumMap<>(raw));
        EnumMap<RequestField, String> fitted = new EnumMap<>(RequestField.class);
        List<RequestField> changed = new java.util.ArrayList<>();
        for (RequestField f : RequestField.values()) {
            String value = raw.get(f);
            String inRange = RequestText.toLatin1Range(value);
            if (!inRange.equals(value)) {
                changed.add(f);
            }
            switch (f.fit()) {
                case TEXT -> fitted.put(f, RequestText.fitText(inRange, f.width()));
                case DIGITS -> fitted.put(f, RequestText.fitDigits(inRange, f.width()));
                case AMOUNT -> { }
            }
        }
        this.stored = Collections.unmodifiableMap(fitted);
        this.amount = Amount.parse(raw.get(RequestField.TRANSACTION_AMT));
        this.replaced = List.copyOf(changed);
    }

    public BodyForm form() {
        return form;
    }

    public String raw(RequestField field) {
        return raw.get(field);
    }

    /** The stored text of a text field; the amount has none (use {@link #amount()}). */
    public String stored(RequestField field) {
        if (field.fit() == RequestField.Fit.AMOUNT) {
            throw new IllegalArgumentException("the amount is not stored as text");
        }
        return stored.get(field);
    }

    public Amount amount() {
        return amount;
    }

    /** The fields in which a character above U+00FF was replaced, for an info log of their names. */
    public List<RequestField> replacedFields() {
        return replaced;
    }

    /**
     * The card value is valid when, without its trailing spaces, it is exactly 16 digits. The check
     * runs on the raw value, so a longer value is never cut into a valid one.
     */
    public boolean cardNumberValid() {
        String card = raw.get(RequestField.CARD_NUM);
        int end = card.length();
        while (end > 0 && card.charAt(end - 1) == ' ') {
            end--;
        }
        if (end != 16) {
            return false;
        }
        for (int i = 0; i < 16; i++) {
            char c = card.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "AuthorizationRequest[form=" + form + ", card=" + Pii.cardNumber(stored(RequestField.CARD_NUM)) + "]";
    }
}
