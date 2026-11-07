package org.carddemo.platform.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.HttpStatus;

/**
 * Opaque page keys. A list whose natural key is sensitive (a card number) returns a token of a
 * surrogate (the {@code card_id}) instead, so no PAN appears in a URL or a request log.
 */
public final class Cursors {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private Cursors() {
    }

    public static String encode(String key) {
        return ENCODER.encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    /** The key of a token; a token this class did not make gives {@code 400 INVALID_CURSOR}. */
    public static String decode(String token) {
        try {
            String key = new String(DECODER.decode(token), StandardCharsets.UTF_8);
            if (key.isEmpty() || !encode(key).equals(token)) {
                throw new IllegalArgumentException("not canonical");
            }
            return key;
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_CURSOR, null);
        }
    }
}
