package org.carddemo.platform.legacyformat;

/**
 * COBOL {@code FUNCTION UPPER-CASE} on legacy text: the letters {@code a} to {@code z} become
 * {@code A} to {@code Z} and nothing else changes, whatever the code page. Java's
 * {@code toUpperCase} would also change {@code é} to {@code É} and {@code ß} to {@code SS}, which
 * changes the value and its length; every target upper-casing of legacy text uses this instead
 * (data-model.md, Upper-casing).
 */
public final class LegacyCase {

    private LegacyCase() {
    }

    public static String upper(String s) {
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= 'a' && chars[i] <= 'z') {
                chars[i] -= 'a' - 'A';
            }
        }
        return new String(chars);
    }
}
