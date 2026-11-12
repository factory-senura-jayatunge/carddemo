package org.carddemo.authorization.request;

/**
 * The character set and width rules of the request's text fields (data-model.md, text repertoire;
 * architecture rulings on authorization-service §1 and §6).
 */
public final class RequestText {

    private RequestText() {
    }

    /**
     * Every character from U+0000 to U+00FF as received; each code point above U+00FF becomes one
     * {@code ?}. A surrogate pair is one code point, so an emoji gives one {@code ?}, and a lone
     * surrogate is one code point above U+00FF too. Afterwards every character is one UTF-16 unit,
     * so {@link String#length()} counts characters of the range.
     */
    public static String toLatin1Range(String raw) {
        StringBuilder sb = null;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            int n = Character.charCount(cp);
            if (cp > 0xFF) {
                if (sb == null) {
                    sb = new StringBuilder(raw.length()).append(raw, 0, i);
                }
                sb.append('?');
            } else if (sb != null) {
                sb.append((char) cp);
            }
            i += n;
        }
        return sb == null ? raw : sb.toString();
    }

    /** Cut on the right, padded with spaces; nothing is trimmed. */
    public static String fitText(String value, int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * A {@code 9(n)} receiver: the text right-aligned, padded with zeros on the left and cut on the
     * left, unchecked, so {@code 1} gives {@code 01} and {@code 123} gives {@code 23} in two places.
     */
    public static String fitDigits(String value, int width) {
        if (value.length() >= width) {
            return value.substring(value.length() - width);
        }
        return "0".repeat(width - value.length()) + value;
    }
}
