package org.carddemo.platform.legacyformat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A numeric-edited PICTURE (for example {@code +ZZZ,ZZZ,ZZZ.99} or
 * {@code -ZZZ,ZZZ,ZZZ.ZZ}) and the text a MOVE into it produces.
 *
 * <p>The algorithm is a port of {@code cob_move_display_to_edited} in GnuCOBOL
 * 3.2 ({@code libcob/move.c}), because the GnuCOBOL build is the reference the
 * differential tests compare with. Two of its effects differ from Enterprise
 * COBOL and are kept on purpose: a sign or currency symbol at the start of the
 * picture consumes the source digit just above the picture's integer digits,
 * so when that digit is not zero the zero suppression stops (MOVE of
 * -10000000000.00 into {@code -ZZZZZZZZZ9.99} gives {@code -0000000000.00});
 * and a trailing sign consumes the decimal digit just below the picture's
 * decimals (MOVE of -0.009 into {@code Z(9).99-} gives {@code .00-}). Both only
 * show when the value does not fit the picture.
 *
 * <p>Supported symbols: {@code 9 Z * , . + - $ B 0 / V CR DB}. {@code P} and
 * {@code BLANK WHEN ZERO} are not used by CardDemo and are rejected.
 */
public final class NumericEdit {

    private static final Pattern REPEAT = Pattern.compile("(.)\\((\\d+)\\)");
    private static final char DEC = '.';
    private static final char CURRENCY = '$';

    private record Symbol(char c, int times) {
    }

    private final String picture;
    private final List<Symbol> symbols;
    private final int size;

    private NumericEdit(String picture, List<Symbol> symbols, int size) {
        this.picture = picture;
        this.symbols = symbols;
        this.size = size;
    }

    public static NumericEdit of(String picture) {
        String upper = picture.trim().toUpperCase();
        Matcher m = REPEAT.matcher(upper);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1).repeat(Integer.parseInt(m.group(2)))));
        }
        m.appendTail(sb);
        String p = sb.toString();
        List<Symbol> out = new ArrayList<>();
        int size = 0;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if ((c == 'C' && p.startsWith("CR", i)) || (c == 'D' && p.startsWith("DB", i))) {
                if (i + 2 != p.length()) {
                    throw new IllegalArgumentException("CR or DB must end the picture: " + picture);
                }
                out.add(new Symbol(c, 1));
                size += 2;
                i++;
                continue;
            }
            if ("9Z*,.+-$B0/V".indexOf(c) < 0) {
                throw new IllegalArgumentException("unsupported symbol '" + c + "' in picture " + picture);
            }
            if (!out.isEmpty() && out.getLast().c() == c) {
                out.set(out.size() - 1, new Symbol(c, out.getLast().times() + 1));
            } else {
                out.add(new Symbol(c, 1));
            }
            if (c != 'V') {
                size++;
            }
        }
        return new NumericEdit(picture, List.copyOf(out), size);
    }

    public String picture() {
        return picture;
    }

    /** Length of the edited item in characters. */
    public int size() {
        return size;
    }

    /**
     * MOVE from a numeric item with picture {@code source} holding {@code value}.
     * The value is first stored in the source item as a MOVE would (high-order
     * digits and extra decimals dropped, sign kept), because the source item's
     * digits are what the edit reads.
     */
    public String format(BigDecimal value, Pic source) {
        char[] src = LegacyNumbers.digitsOf(value, source);
        boolean neg = source.signed() && value.signum() < 0;
        return edit(src, source.scale(), neg);
    }

    /** MOVE from an item just large enough to hold the value as given. */
    public String format(BigDecimal value) {
        int scale = Math.max(value.scale(), 0);
        int intDigits = Math.max(value.precision() - value.scale(), 1);
        return format(value, Pic.signed(intDigits, scale));
    }

    private String edit(char[] srcData, int srcScale, boolean neg) {
        char[] dst = new char[size];
        final int min = 0;
        final int max = srcData.length;
        int end = size;
        int decimalPoint = Integer.MIN_VALUE;
        int count = 0;
        boolean countSign = true;
        boolean countCurr = true;
        boolean trailingSign = false;
        boolean trailingCurr = false;
        boolean isZero = true;
        boolean suppressZero = true;
        int signFirst = 0;
        boolean hasB = false;
        char pad = ' ';
        char signSymbol = 0;
        char currSymbol = 0;
        boolean floatingInsertion = false;
        int lastFixedInsertionPos = -1;
        char lastFixedInsertionChar = 0;

        for (Symbol s : symbols) {
            char c = s.c();
            if (c == '9' || c == 'Z' || c == '*') {
                count += s.times();
                countSign = false;
                countCurr = false;
            } else if (countCurr && c == CURRENCY) {
                count += s.times();
            } else if (countSign && (c == '+' || c == '-')) {
                count += s.times();
            } else if (c == 'V' || c == DEC) {
                break;
            }
        }

        int src = max - srcScale - count;
        int d = 0;
        for (Symbol s : symbols) {
            char c = s.c();
            for (int n = s.times(); n > 0; n--, d++) {
                char x;
                switch (c) {
                    case '9' -> {
                        x = (min <= src && src < max) ? srcData[src] : '0';
                        src++;
                        if (x != '0') {
                            isZero = false;
                        }
                        suppressZero = false;
                        trailingSign = true;
                        trailingCurr = true;
                        dst[d] = x;
                    }
                    case 'Z', '*' -> {
                        x = (min <= src && src < max) ? srcData[src] : '0';
                        src++;
                        if (x != '0') {
                            isZero = false;
                            suppressZero = false;
                        }
                        trailingSign = true;
                        trailingCurr = true;
                        pad = c == '*' ? '*' : ' ';
                        dst[d] = suppressZero ? pad : x;
                    }
                    case '+', '-' -> {
                        x = (min <= src && src < max) ? srcData[src] : '0';
                        src++;
                        if (x != '0') {
                            isZero = false;
                            suppressZero = false;
                        }
                        if (trailingSign) {
                            if (neg && !isZero) {
                                dst[d] = '-';
                            } else if (c == '+') {
                                dst[d] = '+';
                            } else {
                                dst[d] = ' ';
                            }
                            --end;
                        } else if (d == 0 || suppressZero) {
                            dst[d] = pad;
                            signSymbol = c;
                            if (currSymbol == 0) {
                                ++signFirst;
                            }
                        } else {
                            dst[d] = x;
                        }
                        if (n > 1 || lastFixedInsertionChar == c) {
                            floatingInsertion = true;
                        } else if (!trailingSign) {
                            if (lastFixedInsertionPos >= 0) {
                                dst[lastFixedInsertionPos] = lastFixedInsertionChar;
                            }
                            lastFixedInsertionPos = d;
                            lastFixedInsertionChar = c;
                        }
                    }
                    case '.', ',' -> {
                        if (c == DEC) {
                            dst[d] = DEC;
                            decimalPoint = d;
                        } else {
                            dst[d] = suppressZero ? pad : c;
                        }
                    }
                    case 'V' -> {
                        --d;
                        decimalPoint = d;
                    }
                    case '0', '/' -> dst[d] = c;
                    case 'B' -> {
                        if (suppressZero) {
                            dst[d] = pad;
                        } else {
                            dst[d] = 'B';
                            hasB = true;
                        }
                    }
                    case 'C', 'D' -> {
                        end = d;
                        if (neg && !isZero) {
                            dst[d] = c;
                            dst[d + 1] = c == 'C' ? 'R' : 'B';
                        } else {
                            dst[d] = ' ';
                            dst[d + 1] = ' ';
                        }
                        d++;
                    }
                    case CURRENCY -> {
                        x = (min <= src && src < max) ? srcData[src] : '0';
                        src++;
                        if (x != '0') {
                            isZero = false;
                            suppressZero = false;
                        }
                        if (trailingCurr) {
                            dst[d] = CURRENCY;
                            --end;
                        } else if (d == 0 || suppressZero) {
                            dst[d] = pad;
                            currSymbol = CURRENCY;
                        } else {
                            dst[d] = x;
                        }
                        if (n > 1 || lastFixedInsertionChar == c) {
                            floatingInsertion = true;
                        } else if (!trailingCurr) {
                            if (lastFixedInsertionPos >= 0) {
                                dst[lastFixedInsertionPos] = lastFixedInsertionChar;
                            }
                            lastFixedInsertionPos = d;
                            lastFixedInsertionChar = c;
                        }
                    }
                    default -> throw new IllegalStateException("symbol " + c);
                }
            }
        }

        if (signSymbol != 0) {
            if (neg && !isZero) {
                signSymbol = '-';
            } else if (signSymbol != '+') {
                signSymbol = ' ';
            }
        }

        if (suppressZero) {
            if (pad == ' ') {
                java.util.Arrays.fill(dst, ' ');
            } else {
                for (int i = 0; i < size; i++) {
                    if (dst[i] != DEC) {
                        dst[i] = pad;
                    }
                }
            }
        } else {
            if (decimalPoint != Integer.MIN_VALUE) {
                for (int i = decimalPoint + 1; i < end; i++) {
                    switch (dst[i]) {
                        case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ',', '.', '+', '-', '/', 'B' -> {
                        }
                        default -> dst[i] = '0';
                    }
                }
            }
            if (signSymbol != 0 || currSymbol != 0) {
                if (floatingInsertion) {
                    int i;
                    for (i = end - 1; i > 0; --i) {
                        if (dst[i] == ' ') {
                            break;
                        }
                    }
                    if (signSymbol != 0 && currSymbol != 0) {
                        dst[i] = signFirst != 0 ? currSymbol : signSymbol;
                    } else if (signSymbol != 0) {
                        dst[i] = signSymbol;
                    } else {
                        dst[i] = currSymbol;
                    }
                } else if (lastFixedInsertionPos >= 0) {
                    dst[lastFixedInsertionPos] = lastFixedInsertionChar == CURRENCY ? currSymbol : signSymbol;
                }
            }
            if (hasB) {
                boolean leading = true;
                for (int i = 0; i < end; i++) {
                    if (dst[i] == 'B') {
                        dst[i] = leading ? pad : ' ';
                    } else {
                        leading = false;
                    }
                }
            }
        }
        return new String(dst);
    }
}
