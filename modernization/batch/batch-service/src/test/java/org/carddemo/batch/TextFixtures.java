package org.carddemo.batch;

import java.util.BitSet;
import java.util.Collection;

import org.carddemo.platform.legacyformat.record.FieldSpec;
import org.carddemo.platform.legacyformat.record.RecordLayout;

/**
 * Fixture text that runs through the 256 characters a legacy byte maps to (U+0000 to U+00FF),
 * field after field, for the text repertoire's load and export round trips. LF never appears: it
 * separates the records of the line-sequential sample files, so no record of them can hold it. A
 * field never ends in a space (the pad) or CR (the reader takes CR before LF as a line end).
 */
public final class TextFixtures {

    private int next;
    private final BitSet used = new BitSet(256);

    /** {@code n} characters continuing the cycle. */
    public String take(int n) {
        char[] out = new char[n];
        for (int i = 0; i < n; i++) {
            out[i] = nextChar();
        }
        while (out[n - 1] == ' ' || out[n - 1] == '\r') {
            out[n - 1] = nextChar();
        }
        for (char c : out) {
            used.set(c);
        }
        return new String(out);
    }

    private char nextChar() {
        char c = (char) (next++ % 256);
        return c == '\n' ? nextChar() : c;
    }

    /** The line with each named field replaced by the next characters of the cycle. */
    public String fill(String line, RecordLayout layout, Collection<String> fields) {
        StringBuilder sb = new StringBuilder(line);
        while (sb.length() < layout.length()) {
            sb.append(' ');
        }
        for (String name : fields) {
            FieldSpec f = layout.field(name);
            sb.replace(f.offset(), f.end(), take(f.length()));
        }
        return sb.toString();
    }

    /** How many of the 256 characters the fixture has used so far. */
    public int charactersUsed() {
        return used.cardinality();
    }
}
