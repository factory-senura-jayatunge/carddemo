package org.carddemo.identity.user;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.carddemo.identity.signon.LegacyText;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Salted bcrypt hashes in the {@code {id}hash} format, so another algorithm can be added later
 * without rehashing.
 */
public class PasswordHasher {

    /** bcrypt reads at most 72 bytes; a longer input would be cut silently. */
    public static final int MAX_BYTES = 72;

    private final PasswordEncoder encoder;
    private final String dummyHash;

    public PasswordHasher(int strength) {
        this.encoder = new DelegatingPasswordEncoder("bcrypt",
                Map.of("bcrypt", new BCryptPasswordEncoder(strength)));
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    /**
     * What is hashed and compared: the typed value without trailing spaces (the 3270 field pads
     * them), upper-cased unless passwords are case-sensitive (BR-SGN-003, DEF-SGN-011).
     */
    public static String prepare(String typed, boolean caseSensitive) {
        String trimmed = LegacyText.rtrim(typed);
        return caseSensitive ? trimmed : LegacyText.upper(trimmed);
    }

    public static boolean fits(String prepared) {
        return prepared.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }

    public String hash(String prepared) {
        if (!fits(prepared)) {
            throw new IllegalArgumentException("password longer than " + MAX_BYTES + " bytes");
        }
        return encoder.encode(prepared);
    }

    public boolean matches(String prepared, String storedHash) {
        if (!fits(prepared)) {
            burn();
            return false;
        }
        return encoder.matches(prepared, storedHash);
    }

    /** Spends the time of one check, so an unknown or locked user answers as slowly as a known one. */
    public void burn() {
        encoder.matches("x", dummyHash);
    }
}
