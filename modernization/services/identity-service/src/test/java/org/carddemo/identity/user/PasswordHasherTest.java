package org.carddemo.identity.user;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.carddemo.identity.IdentityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordHasherTest {

    static final String ARGON2_PREFIX = "{argon2}$argon2id$v=19$m=19456,t=2,p=1$";
    static final PasswordHasher HASHER = new PasswordHasher(4);
    static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(4);

    @Test
    void newHashesAreArgon2idWithOwaspsMinimumSettings() {
        IdentityProperties props = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("carddemo.identity", IdentityProperties.class);
        assertThat(props.bcryptStrength()).isEqualTo(12);
        String hash = new PasswordHasher(props.bcryptStrength()).hash("PASSWORD");
        // {argon2} + $argon2id$v=19$m=19456,t=2,p=1$ + 22-char salt + $ + 43-char hash.
        assertThat(hash).startsWith(ARGON2_PREFIX).hasSize(105).hasSizeLessThanOrEqualTo(255);
        String[] parts = hash.substring(ARGON2_PREFIX.length()).split("\\$");
        assertThat(parts[0]).hasSize(22);
        assertThat(parts[1]).hasSize(43);
    }

    @Test
    void hashesAreSaltedAndCheckTheExactValue() {
        String a = HASHER.hash("PASSWORD");
        assertThat(HASHER.hash("PASSWORD")).isNotEqualTo(a);
        assertThat(HASHER.matches("PASSWORD", a)).isTrue();
        assertThat(HASHER.matches("password", a)).isFalse();
        assertThat(HASHER.matches("PASSWORD ", a)).isFalse();
    }

    @Test
    void storedBcryptHashesStillVerify() {
        String old = "{bcrypt}" + BCRYPT.encode("PASSWORD");
        assertThat(HASHER.matches("PASSWORD", old)).isTrue();
        assertThat(HASHER.matches("PASSWORT", old)).isFalse();
        String legacy = "{bcrypt}" + BCRYPT.encode(PasswordHasher.prepare("passw0rd", false));
        assertThat(HASHER.matches(PasswordHasher.prepare("PassW0rd  ", false), legacy)).isTrue();
    }

    @Test
    void everyPasswordTheFixedRuleAllowsRoundTripsAndOneChangedCharacterFails() {
        List<String> passwords = List.of("Abcdef1!", "é".repeat(64), "\uD83D\uDE00".repeat(64),
                "a b".repeat(21) + "z", "é".repeat(36) + "x");
        for (String p : passwords) {
            String hash = HASHER.hash(p);
            assertThat(HASHER.matches(p, hash)).as("%s", p).isTrue();
            int last = p.offsetByCodePoints(0, p.codePointCount(0, p.length()) - 1);
            String changed = p.substring(0, last) + "Q";
            assertThat(HASHER.matches(changed, hash)).as("one character changed: %s", p).isFalse();
            String first = "Q" + p.substring(p.offsetByCodePoints(0, 1));
            assertThat(HASHER.matches(first, hash)).as("first character changed: %s", p).isFalse();
        }
    }

    @Test
    void aPasswordAbove72BytesAgainstABcryptHashIsWrongWithoutCheckingTheStoredHash() {
        Counting argon2 = new Counting(new Argon2PasswordEncoder(16, 32, 1, 19456, 2));
        Counting bcrypt = new Counting(BCRYPT);
        PasswordHasher h = new PasswordHasher(argon2, bcrypt);
        String max = "x".repeat(72);
        String bare = BCRYPT.encode(max);
        String stored = "{bcrypt}" + bare;
        assertThat(h.matches(max, stored)).isTrue();
        assertThat(bcrypt.checked).containsExactly(bare);

        // bcrypt alone would compare only the first 72 bytes and accept these.
        for (String tooLong : List.of(max + "y", "x".repeat(71) + "é")) {
            bcrypt.checked.clear();
            int argonBefore = argon2.matches.get();
            assertThat(h.matches(tooLong, stored)).as("%d bytes", tooLong.getBytes(UTF_8).length).isFalse();
            assertThat(bcrypt.checked).as("one bcrypt check, against the dummy").hasSize(1)
                    .doesNotContain(bare);
            assertThat(BCRYPT.matches(PasswordHasher.DUMMY_PASSWORD, bcrypt.checked.get(0))).as("the dummy").isTrue();
            assertThat(argon2.matches.get() - argonBefore).as("Argon2 checks").isZero();
        }
    }

    @Test
    void theBcryptDummyCheckCostsWhatTheConfiguredBcryptCosts() {
        Counting bcrypt = new Counting(new BCryptPasswordEncoder(12));
        PasswordHasher h = new PasswordHasher(new Argon2PasswordEncoder(16, 32, 1, 19456, 2), bcrypt);
        assertThat(h.matches("x".repeat(73), "{bcrypt}" + BCRYPT.encode("x"))).isFalse();
        assertThat(bcrypt.checked).hasSize(1);
        assertThat(bcrypt.checked.get(0)).startsWith("$2a$12$");
    }

    @Test
    void theBcryptDummyCheckWaitsForASlotLikeEveryOtherCheck() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        PasswordEncoder slow = new PasswordEncoder() {
            @Override
            public String encode(CharSequence raw) {
                return "d";
            }

            @Override
            public boolean matches(CharSequence raw, String encoded) {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                running.decrementAndGet();
                return false;
            }
        };
        PasswordHasher h = new PasswordHasher(BCRYPT, slow);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                calls.add(pool.submit(() -> h.matches("x".repeat(80), "{bcrypt}d")));
            }
            for (Future<Boolean> f : calls) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isFalse();
            }
        }
        assertThat(peak.get()).isEqualTo(PasswordHasher.MAX_CONCURRENT);
    }

    @Test
    void theDummyCheckIsAnArgon2Check() {
        Counting argon2 = new Counting(new Argon2PasswordEncoder(16, 32, 1, 19456, 2));
        Counting bcrypt = new Counting(BCRYPT);
        PasswordHasher h = new PasswordHasher(argon2, bcrypt);
        int before = argon2.matches.get();
        h.burn();
        assertThat(argon2.matches.get() - before).isEqualTo(1);
        assertThat(bcrypt.matches.get()).isZero();
    }

    @Test
    void atMostTwoHashesOrChecksRunAtOnce() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        PasswordEncoder slow = new PasswordEncoder() {
            @Override
            public String encode(CharSequence raw) {
                return work("h");
            }

            @Override
            public boolean matches(CharSequence raw, String encoded) {
                return work(encoded).equals("h");
            }

            private String work(String r) {
                int now = running.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                started.countDown();
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                running.decrementAndGet();
                return r;
            }
        };
        PasswordHasher h = new PasswordHasher(slow, BCRYPT);
        peak.set(0);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                int n = i;
                calls.add(pool.submit(() -> n % 3 == 0 ? h.hash("p") : n % 3 == 1 ? h.matches("p", "{argon2}h")
                        : burn(h)));
            }
            for (Future<?> f : calls) {
                f.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(started.getCount()).isZero();
        assertThat(peak.get()).isEqualTo(PasswordHasher.MAX_CONCURRENT);
    }

    @Test
    void prepareDropsTheFieldPaddingAndUpperCasesOnlyAToZInLegacy() {
        assertThat(PasswordHasher.prepare("passw0rd  ", false)).isEqualTo("PASSW0RD");
        assertThat(PasswordHasher.prepare("pässwörd", false)).isEqualTo("PäSSWöRD");
        assertThat(PasswordHasher.prepare("passw0rd  ", true)).isEqualTo("passw0rd");
        assertThat(PasswordHasher.prepare("  pass word ", true)).isEqualTo("  pass word");
    }

    private static boolean burn(PasswordHasher h) {
        h.burn();
        return true;
    }

    /** Counts the calls that reach an encoder. */
    static final class Counting implements PasswordEncoder {

        final AtomicInteger encodes = new AtomicInteger();
        final AtomicInteger matches = new AtomicInteger();
        /** The stored hashes that {@link #matches} was asked to check, in order. */
        final List<String> checked = new CopyOnWriteArrayList<>();
        private final PasswordEncoder delegate;

        Counting(PasswordEncoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public String encode(CharSequence raw) {
            encodes.incrementAndGet();
            return delegate.encode(raw);
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            matches.incrementAndGet();
            checked.add(encoded);
            return delegate.matches(raw, encoded);
        }
    }
}
