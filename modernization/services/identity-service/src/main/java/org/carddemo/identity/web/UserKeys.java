package org.carddemo.identity.web;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.MessageCatalog;
import org.springframework.http.HttpStatus;

/**
 * The URL-safe key form of a user ID: base64url (RFC 4648 section 5, no padding) of its UTF-8
 * bytes. Tomcat and the Spring Security firewall refuse an encoded {@code /}, {@code \},
 * {@code %}, {@code ;} or NUL in a path and normalise {@code .} and {@code ..} segments, all of
 * which a user ID may hold; the key carries any ID without relaxing them. A key is transport only:
 * it is never stored, logged or audited.
 */
public final class UserKeys {

    static final int MAX_KEY_LENGTH = 1024;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private UserKeys() {
    }

    public static String encode(String text) {
        return ENCODER.encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The text a key carries. Strict: padding, a character outside {@code A-Z a-z 0-9 - _}, a
     * length no encoding gives, non-zero trailing bits, bytes that are not UTF-8 or more than
     * {@value #MAX_KEY_LENGTH} characters give {@code 400 INVALID_REQUEST}.
     */
    public static String decode(String key) {
        if (key == null || key.length() > MAX_KEY_LENGTH || key.length() % 4 == 1) {
            throw invalid();
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-'
                    || c == '_';
            if (!ok) {
                throw invalid();
            }
        }
        byte[] bytes = Base64.getUrlDecoder().decode(key);
        // Java's decoder ignores the unused low bits of the last character; a second spelling of
        // the same bytes is not a key this service gives out.
        if (!ENCODER.encodeToString(bytes).equals(key)) {
            throw invalid();
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_REQUEST, null);
    }
}
