package org.carddemo.identity.user;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.carddemo.identity.signon.LegacyText;
import org.carddemo.platform.legacyformat.LegacyCase;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Salted password hashes in Spring Security's {@code {id}hash} format (identity-service.md,
 * section 6): every new hash is Argon2id; {@code {bcrypt}} hashes stored before keep verifying
 * and sign-on replaces each one with an Argon2id hash after its first successful check.
 */
public class PasswordHasher {

    /** OWASP's minimum for Argon2id: 16-byte salt, 32-byte hash, parallelism 1, 19 MiB, 2 iterations. */
    public static final int SALT_BYTES = 16;
    public static final int HASH_BYTES = 32;
    public static final int PARALLELISM = 1;
    public static final int MEMORY_KIB = 19456;
    public static final int ITERATIONS = 2;

    /** bcrypt reads only the first 72 bytes of its input and refuses to hash more. */
    static final int BCRYPT_MAX_BYTES = 72;
    static final String BCRYPT_PREFIX = "{bcrypt}";
    static final String DUMMY_PASSWORD = "not-a-real-password";

    /**
     * Each Argon2 call holds 19 MiB of heap; the service runs with {@code -Xmx256m} on virtual
     * threads, so a burst of sign-ons must not run them all at once.
     */
    static final int MAX_CONCURRENT = 2;

    private final PasswordEncoder encoder;
    private final PasswordEncoder bcrypt;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT, true);
    private final String dummyHash;
    private final String bcryptDummyHash;

    /** {@code bcryptStrength} is the cost of the bcrypt encoder, which now only verifies. */
    public PasswordHasher(int bcryptStrength) {
        this(new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS),
                new BCryptPasswordEncoder(bcryptStrength));
    }

    PasswordHasher(PasswordEncoder argon2, PasswordEncoder bcrypt) {
        this.encoder = new DelegatingPasswordEncoder("argon2", Map.of("argon2", argon2, "bcrypt", bcrypt));
        this.bcrypt = bcrypt;
        this.dummyHash = encoder.encode(DUMMY_PASSWORD);
        this.bcryptDummyHash = bcrypt.encode(DUMMY_PASSWORD);
    }

    /**
     * What is hashed and compared: the typed value without trailing spaces (the 3270 field pads
     * them), upper-cased unless passwords are case-sensitive (BR-SGN-003, DEF-SGN-011).
     */
    public static String prepare(String typed, boolean caseSensitive) {
        String trimmed = LegacyText.rtrim(typed);
        return caseSensitive ? trimmed : LegacyCase.upper(trimmed);
    }

    public String hash(String prepared) {
        return limited(() -> encoder.encode(prepared));
    }

    public boolean matches(String prepared, String storedHash) {
        if (storedHash != null && storedHash.startsWith(BCRYPT_PREFIX)
                && prepared.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            // A stored bcrypt hash came from at most 72 bytes, and bcrypt would compare only the
            // first 72 of this input, so it is a wrong password. It still costs one bcrypt check,
            // against a dummy, so the answer takes as long as checking the stored hash would and
            // does not tell a bcrypt hash from an Argon2 one.
            limited(() -> bcrypt.matches("x", bcryptDummyHash));
            return false;
        }
        return limited(() -> encoder.matches(prepared, storedHash));
    }

    /**
     * Whether a stored hash is an old {@code {bcrypt}} one, which costs a bcrypt check where an
     * unknown user costs an Argon2 one, so its sign-on time tells it apart.
     */
    public boolean needsUpgrade(String storedHash) {
        return storedHash != null && storedHash.startsWith(BCRYPT_PREFIX);
    }

    /** Spends the time of one check, so an unknown or locked user answers as slowly as a known one. */
    public void burn() {
        limited(() -> encoder.matches("x", dummyHash));
    }

    private <T> T limited(Supplier<T> call) {
        slots.acquireUninterruptibly();
        try {
            return call.get();
        } finally {
            slots.release();
        }
    }
}
