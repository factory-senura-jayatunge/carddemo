package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.util.Arrays;
import java.util.List;

import org.carddemo.identity.ApiClient.Response;
import org.carddemo.identity.IdentityFixtures.AuditRow;
import org.carddemo.identity.user.PasswordHasher;
import org.carddemo.platform.fidelity.FidelityMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The password hashing pilot before merge (identity-service.md, section 6, item 7), with the real
 * encoders on the service's database: a stored bcrypt hash still signs on and is then replaced by
 * an Argon2id hash of the same prepared password, a wrong password or a refused sign-on leaves it,
 * a password change between the check and the upgrade refuses the sign-on, every password the
 * fixed rule allows is set as Argon2id and signs on, one changed character fails, and a password
 * above 72 bytes never matches a bcrypt hash. A wrong password answers as fast as an
 * unknown user once the hash is upgraded. The hash timings alone are PasswordHashingBench, in a
 * fresh JVM with the service's heap.
 */
@ExtendWith(OutputCaptureExtension.class)
class PasswordHashingPilotTest extends UserAdminTestBase {

    static final String ARGON2_PREFIX = "{argon2}$argon2id$v=19$m=19456,t=2,p=1$";
    private static final PasswordHasher CHECK = new PasswordHasher(4);
    static final int TIMING_TOLERANCE_PERCENT = 25;
    /** The known user's count costs 2 to 3 ms more on a laptop; the bcrypt(12) gap is about 200 ms. */
    static final int TIMING_TOLERANCE_FLOOR_MS = 8;

    @Test
    void aStoredBcryptHashIsUpgradedToArgon2idByItsFirstSuccessfulSignOn(CapturedOutput output) {
        db.bcryptUser("ADMIN001", "MARGARET", "GOLD", 'A', IdentityFixtures.DEMO_PASSWORD, false);
        // A legacy hash is of the upper-cased password.
        db.bcryptUser("USER0001", "LAWRENCE", "THOMAS", 'U', "PASSW0RD", false);
        String admin = db.user("ADMIN001").passwordHash();
        String user = db.user("USER0001").passwordHash();
        String adminVersion = db.rowVersion("ADMIN001");
        assertThat(admin).startsWith("{bcrypt}$2a$");

        switches.all(FidelityMode.FIXED);
        assertThat(client().signOn("ADMIN001", "password").status()).as("fixed is case-sensitive").isEqualTo(401);
        switches.all(FidelityMode.LEGACY);
        assertThat(client().signOn("admin001", "wrong").status()).isEqualTo(401);
        assertThat(db.user("ADMIN001").passwordHash()).as("a wrong password never rehashes").isEqualTo(admin);
        assertThat(db.rowVersion("ADMIN001")).isEqualTo(adminVersion);

        assertThat(client().signOn("admin001", "password").status()).as("legacy").isEqualTo(200);
        assertThat(client().signOn("user0001", "PassW0rd").status()).as("legacy").isEqualTo(200);
        String upgraded = db.user("ADMIN001").passwordHash();
        assertThat(upgraded).startsWith(ARGON2_PREFIX).hasSizeLessThanOrEqualTo(255);
        assertThat(CHECK.matches("PASSWORD", upgraded)).isTrue();
        String upgradedUser = db.user("USER0001").passwordHash();
        assertThat(upgradedUser).startsWith(ARGON2_PREFIX);
        assertThat(CHECK.matches("PASSW0RD", upgradedUser)).as("the upper-cased value that was checked").isTrue();
        assertThat(CHECK.matches("PassW0rd", upgradedUser)).isFalse();
        String upgradedVersion = db.rowVersion("ADMIN001");
        assertThat(upgradedVersion).as("the upgrade writes the row once").isNotEqualTo(adminVersion);
        IdentityFixtures.AdminRow row = db.adminRow("ADMIN001");
        assertThat(row.mustChange()).isFalse();
        assertThat(row.updatedBy()).as("only the hash changes").isNull();

        // An Argon2 hash is never rewritten on sign-on.
        assertThat(client().signOn("admin001", "password").status()).isEqualTo(200);
        switches.all(FidelityMode.FIXED);
        assertThat(client().signOn("ADMIN001", "PASSWORD").status()).as("fixed").isEqualTo(200);
        assertThat(client().signOn("ADMIN001", "password").status()).as("fixed is case-sensitive").isEqualTo(401);
        assertThat(db.user("ADMIN001").passwordHash()).isEqualTo(upgraded);
        assertThat(db.rowVersion("ADMIN001")).isEqualTo(upgradedVersion);

        assertThat(output).contains("Upgraded the password hash of ADMIN001 to Argon2id",
                "Upgraded the password hash of USER0001 to Argon2id");
        assertThat(output).doesNotContain(admin, user, upgraded, upgradedUser);
        assertThat(db.auditText()).doesNotContain("argon2", "bcrypt", "PassW0rd");
    }

    @Test
    void aRefusedSignOnLeavesTheBcryptHash() {
        switches.all(FidelityMode.FIXED);
        db.bcryptUser("MUSTCHG1", "M", "C", 'U', IdentityFixtures.DEMO_PASSWORD, true);
        String stored = db.user("MUSTCHG1").passwordHash();
        Response r = client().signOn("MUSTCHG1", IdentityFixtures.DEMO_PASSWORD);
        assertThat(r.status()).as("%s", r.body()).isEqualTo(403);
        assertThat(db.user("MUSTCHG1").passwordHash()).isEqualTo(stored);
    }

    @Test
    void aPasswordChangeBetweenTheCheckAndTheUpgradeRefusesTheSignOnAsAWrongPassword(CapturedOutput output) {
        switches.all(FidelityMode.FIXED);
        db.bcryptUser("USER0001", "LAWRENCE", "THOMAS", 'U', IdentityFixtures.DEMO_PASSWORD, false);
        db.user("USER0002", "AJITH", "KUMAR", 'U', IdentityFixtures.DEMO_PASSWORD, false);
        Response wrong = client().signOn("USER0002", "Wrong#2026");
        assertThat(wrong.status()).isEqualTo(401);
        doAnswer(inv -> {
            db.changeHash("USER0001", "Changed#2026");
            return inv.callRealMethod();
        }).doCallRealMethod().when(userRepository).findLocked(anyString());

        Response r = client().signOn("USER0001", IdentityFixtures.DEMO_PASSWORD);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).as("the wrong-password answer").isEqualTo(wrong.body());
        assertThat(r.cookie("cd_rt")).isNull();
        assertThat(db.sessions()).isEmpty();
        assertThat(db.user("USER0001").failedAttempts()).as("no failure counted").isZero();
        String changed = db.user("USER0001").passwordHash();
        assertThat(CHECK.matches("Changed#2026", changed)).as("the change stays").isTrue();
        List<AuditRow> audit = db.audit();
        assertThat(audit.getLast().eventType()).isEqualTo("SIGN_ON_FAILED");
        assertThat(audit.getLast().subject()).isEqualTo("USER0001");
        assertThat(audit.getLast().detail()).isEqualTo("WRONG_PASSWORD");
        assertThat(output).doesNotContain("Upgraded the password hash of USER0001");

        assertThat(client().signOn("USER0001", "Changed#2026").status()).isEqualTo(200);
        assertThat(db.user("USER0001").passwordHash()).isEqualTo(changed);
    }

    @Test
    void aSignOnThatFindsTheHashAlreadyUpgradedChecksTheNewOne(CapturedOutput output) {
        db.bcryptUser("USER0001", "LAWRENCE", "THOMAS", 'U', IdentityFixtures.DEMO_PASSWORD, false);
        // Another sign-on with the same password upgrades the hash between this one's check and its locked read.
        doAnswer(inv -> {
            db.changeHash("USER0001", IdentityFixtures.DEMO_PASSWORD);
            return inv.callRealMethod();
        }).when(userRepository).findLocked(anyString());
        Response r = client().signOn("USER0001", IdentityFixtures.DEMO_PASSWORD);
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(db.sessions()).hasSize(1);
        assertThat(db.user("USER0001").passwordHash()).startsWith(ARGON2_PREFIX);
        assertThat(output).doesNotContain("Upgraded the password hash of USER0001");
    }

    /**
     * Fixed mode, lockout on: a wrong password for a known user answers as fast as an unknown
     * user, within {@link #TIMING_TOLERANCE_PERCENT} percent of the unknown user's median or
     * {@link #TIMING_TOLERANCE_FLOOR_MS} ms, whichever is larger. The known user also pays the
     * failure count (the state row check and the counting UPDATE) and the unknown user only the
     * rate limit, so that extra work is inside the comparison. Before the upgrade the stored hash
     * is bcrypt at the service's cost 12, and its gap must be beyond the tolerance, so the
     * measurement can see one. The count and the rate limit are reset before every sample so the
     * user never locks and no request is limited.
     */
    @Test
    void afterTheUpgradeAWrongPasswordTakesAsLongAsAnUnknownUser() {
        switches.all(FidelityMode.FIXED);
        db.user("USER0001", "LAWRENCE", "THOMAS", 'U', IdentityFixtures.DEMO_PASSWORD, false)
                .storeHash("USER0001", "{bcrypt}" + new BCryptPasswordEncoder(12).encode(IdentityFixtures.DEMO_PASSWORD));
        Samples before = sample();
        long beforeGap = before.known() - before.unknown();
        assertThat(beforeGap).as("the bcrypt(12) gap is beyond the tolerance, %s", before)
                .isGreaterThan(2 * tolerance(before.unknown()));

        assertThat(client().signOn("USER0001", IdentityFixtures.DEMO_PASSWORD).status()).isEqualTo(200);
        assertThat(db.user("USER0001").passwordHash()).startsWith(ARGON2_PREFIX);
        Samples after = sample();
        assertThat(Math.abs(after.known() - after.unknown())).as("within the tolerance, %s", after)
                .isLessThanOrEqualTo(tolerance(after.unknown()));
    }

    private static long tolerance(long unknownMedian) {
        return Math.max(unknownMedian * TIMING_TOLERANCE_PERCENT / 100, TIMING_TOLERANCE_FLOOR_MS * 1_000_000L);
    }

    /** Medians in nanoseconds of interleaved wrong-password sign-ons, after a warm-up. */
    private record Samples(long known, long unknown) {
        @Override
        public String toString() {
            return "medians known %.1f ms, unknown %.1f ms".formatted(known / 1e6, unknown / 1e6);
        }
    }

    private Samples sample() {
        int warmUp = 5;
        int n = 21;
        long[] known = new long[n];
        long[] unknown = new long[n];
        for (int i = -warmUp; i < n; i++) {
            long u = timedFailure("NOSUCH01");
            long k = timedFailure("USER0001");
            if (i >= 0) {
                unknown[i] = u;
                known[i] = k;
            }
        }
        assertThat(db.user("USER0001").lockedUntil()).as("never locked").isNull();
        Samples s = new Samples(median(known), median(unknown));
        System.out.println("sign-on timing: " + s);
        return s;
    }

    private long timedFailure(String userId) {
        throttle.reset();
        db.signOnState("USER0001", 0, null, null);
        long start = System.nanoTime();
        Response r = client().signOn(userId, "WRONGPW1");
        long took = System.nanoTime() - start;
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.text("code")).isEqualTo("SIGN_ON_FAILED");
        return took;
    }
    @Test
    void everyPasswordTheFixedRuleAllowsIsSetAsArgon2idAndSignsOn() {
        switches.all(FidelityMode.FIXED);
        db.demoUsers();
        ApiClient admin = admin();
        List<String> passwords = List.of(
                "Abcdef1!", // 8 ASCII
                "é".repeat(64), // 128 bytes
                "\uD83D\uDE00".repeat(64), // 64 four-byte characters, 256 bytes
                "a b".repeat(21) + "z", // 64 with spaces inside
                "é".repeat(36) + "x"); // 73 bytes
        for (int i = 0; i < passwords.size(); i++) {
            String id = "PWD0000" + i;
            String p = passwords.get(i);
            Response add = admin.post("/api/users", newUser("P", "W", id, p, "U"));
            assertThat(add.status()).as("add %s: %s", id, add.body()).isEqualTo(201);
            String hash = db.user(id).passwordHash();
            assertThat(hash).as(id).startsWith(ARGON2_PREFIX).hasSizeLessThanOrEqualTo(255);

            int last = p.offsetByCodePoints(0, p.codePointCount(0, p.length()) - 1);
            Response wrong = client().signOn(id, p.substring(0, last) + "Q");
            assertThat(wrong.status()).as("one character changed, %s", id).isEqualTo(401);
            Response ok = client().signOn(id, p);
            assertThat(ok.status()).as("sign-on %s: %s", id, ok.body()).isEqualTo(200);
            assertThat(db.user(id).passwordHash()).as("an Argon2 hash is never rewritten").isEqualTo(hash);
        }
    }

    @Test
    void thePasswordChangeWritesArgon2id() {
        switches.all(FidelityMode.FIXED);
        db.bcryptUser("MUSTCHG1", "M", "C", 'U', IdentityFixtures.DEMO_PASSWORD, true);
        String next = "é".repeat(40);
        Response r = client().changePassword("MUSTCHG1", IdentityFixtures.DEMO_PASSWORD, next);
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(db.user("MUSTCHG1").passwordHash()).startsWith(ARGON2_PREFIX);
        assertThat(client().signOn("MUSTCHG1", next).status()).isEqualTo(200);
        assertThat(client().signOn("MUSTCHG1", IdentityFixtures.DEMO_PASSWORD).status()).isEqualTo(401);
    }

    @Test
    void aPasswordAbove72BytesNeverMatchesABcryptHash() {
        switches.all(FidelityMode.FIXED);
        String stored72 = "é".repeat(36); // 72 bytes, the most bcrypt hashed
        db.bcryptUser("LONG0001", "L", "B", 'U', stored72, false);
        String stored = db.user("LONG0001").passwordHash();
        // bcrypt alone compares only the first 72 bytes and would accept this.
        assertThat(client().signOn("LONG0001", stored72 + "x").status()).isEqualTo(401);
        assertThat(db.user("LONG0001").passwordHash()).isEqualTo(stored);
        assertThat(client().signOn("LONG0001", stored72).status()).isEqualTo(200);
        assertThat(db.user("LONG0001").passwordHash()).startsWith(ARGON2_PREFIX);
        assertThat(client().signOn("LONG0001", stored72 + "x").status()).as("after the upgrade").isEqualTo(401);
        assertThat(client().signOn("LONG0001", stored72).status()).isEqualTo(200);
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
