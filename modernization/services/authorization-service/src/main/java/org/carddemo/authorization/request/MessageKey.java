package org.carddemo.authorization.request;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The duplicate key of {@code pauth.processed_message} (DEF-AUTH-006 fixed, V502): the SHA-256 of
 * {@code M|} + message ID, or, when the message ID is empty, of {@code K|} + card, transaction ID,
 * auth date and auth time as stored. The key holds no card number in clear.
 */
public record MessageKey(byte[] hash, char kind) {

    public static MessageKey of(String messageId, AuthorizationRequest request) {
        if (messageId != null && !messageId.isEmpty()) {
            return new MessageKey(sha256("M|" + messageId), 'M');
        }
        return new MessageKey(sha256("K|" + request.stored(RequestField.CARD_NUM)
                + request.stored(RequestField.TRANSACTION_ID) + request.stored(RequestField.AUTH_DATE)
                + request.stored(RequestField.AUTH_TIME)), 'K');
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MessageKey k && k.kind == kind && MessageDigest.isEqual(k.hash, hash);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(hash) * 31 + kind;
    }

    @Override
    public String toString() {
        return "MessageKey[" + kind + "]";
    }
}
