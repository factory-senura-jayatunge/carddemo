package org.carddemo.platform.legacyformat;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * The encoding of legacy text: one byte per character, U+0000 to U+00FF as the bytes 0x00 to
 * 0xFF (ISO-8859-1), so every byte value of an {@code X(n)} field is a character and back.
 * A character outside that range is an error, never a silent {@code ?} as {@code String.getBytes}
 * would write.
 */
public final class Latin1 {

    private Latin1() {
    }

    /** The bytes of {@code text}; {@code what} names the text in the error, which never shows it. */
    public static byte[] encode(String text, String what) {
        CharsetEncoder encoder = StandardCharsets.ISO_8859_1.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer out = encoder.encode(CharBuffer.wrap(text));
            byte[] b = new byte[out.remaining()];
            out.get(b);
            return b;
        } catch (CharacterCodingException e) {
            throw new LegacyFormatException(what + " has a character outside ISO-8859-1 at position "
                    + (firstUnmappable(text) + 1));
        }
    }

    /** The index of the first character above U+00FF, or -1. */
    public static int firstUnmappable(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0xFF) {
                return i;
            }
        }
        return -1;
    }
}
