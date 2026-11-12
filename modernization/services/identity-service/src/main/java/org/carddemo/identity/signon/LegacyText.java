package org.carddemo.identity.signon;

/** Text rules of the 8-character BMS fields of the sign-on screen. */
public final class LegacyText {

    private LegacyText() {
    }

    /** Spaces or low-values, as the screen sends an empty field (VAL-SGN-001, VAL-SGN-002). */
    public static boolean isBlank(String s) {
        if (s == null) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\0') {
                return false;
            }
        }
        return true;
    }

    /** Trailing spaces are field padding: {@code PASS} and {@code PASS    } are the same value. */
    public static String rtrim(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\0')) {
            end--;
        }
        return s.substring(0, end);
    }
}
