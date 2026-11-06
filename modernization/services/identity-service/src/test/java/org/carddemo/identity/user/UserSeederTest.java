package org.carddemo.identity.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.carddemo.identity.ApiClient;
import org.carddemo.identity.IdentityProperties.Seed;
import org.carddemo.identity.IdentityTestBase;
import org.carddemo.identity.fidelity.IdentityFidelity;
import org.carddemo.platform.fidelity.FidelityMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** D-06: the users identity-service creates in an empty iam.app_user. */
class UserSeederTest extends IdentityTestBase {

    record JclUser(String id, String first, String last, String password, char type) {
    }

    @Autowired
    PasswordHasher hasher;

    @Autowired
    IdentityFidelity fidelity;

    private UserSeeder seeder(Seed seed) {
        return new UserSeeder(userRepository, hasher, fidelity, seed, clock);
    }

    /** The SYSUT1 records of DUSRSECJ: id 8, first name 20, last name 20, password 8, type 1. */
    static List<JclUser> dusrsecj() throws IOException {
        Path jcl = Path.of("").toRealPath().resolve("../../../app/jcl/DUSRSECJ.jcl").normalize();
        List<String> lines = Files.readAllLines(jcl, StandardCharsets.US_ASCII);
        int start = lines.indexOf(lines.stream().filter(l -> l.startsWith("//SYSUT1")).findFirst().orElseThrow()) + 1;
        return lines.subList(start, lines.size()).stream().takeWhile(l -> !l.startsWith("/*"))
                .map(l -> new JclUser(l.substring(0, 8).strip(), l.substring(8, 28).strip(), l.substring(28, 48).strip(),
                        l.substring(48, 56).strip(), l.charAt(56)))
                .toList();
    }

    @Test
    void demoSeedingCreatesTheDusrsecjUsers() throws IOException {
        List<JclUser> jcl = dusrsecj();
        assertThat(jcl).hasSize(10);

        assertThat(seeder(new Seed(true, true, "PASSWORD", null)).seedIfEmpty()).isEqualTo(10);

        for (JclUser u : jcl) {
            AppUser stored = userRepository.find(u.id()).orElseThrow();
            assertThat(stored.firstName()).isEqualTo(u.first());
            assertThat(stored.lastName()).isEqualTo(u.last());
            assertThat(stored.userType()).isEqualTo(u.type());
            assertThat(stored.mustChangePassword()).isFalse();
            assertThat(hasher.matches(u.password(), db.user(u.id()).passwordHash())).isTrue();
            assertThat(db.user(u.id()).passwordHash()).startsWith("{bcrypt}$2");
        }
        assertThat(client().signOn("user0003", "password").status()).as("legacy sign-on").isEqualTo(200);
    }

    @Test
    void aNonEmptyTableIsLeftAlone() {
        db.user("ADMIN001", "SOMEONE", "ELSE", 'A', "OTHERPWD", false);
        assertThat(seeder(new Seed(true, true, "PASSWORD", null)).seedIfEmpty()).isZero();
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(userRepository.find("ADMIN001").orElseThrow().firstName()).isEqualTo("SOMEONE");
    }

    @Test
    void withoutDemoUsersOnlyABootstrapAdminThatMustChangeItsPassword() {
        switches.all(FidelityMode.FIXED);
        assertThat(seeder(new Seed(true, false, "PASSWORD", "Bootstrap Secret 1")).seedIfEmpty()).isEqualTo(1);
        AppUser admin = userRepository.find("ADMIN001").orElseThrow();
        assertThat(admin.userType()).isEqualTo('A');
        assertThat(admin.mustChangePassword()).isTrue();
        assertThat(userRepository.count()).isEqualTo(1);

        ApiClient c = client();
        assertThat(c.signOn("ADMIN001", "Bootstrap Secret 1").text("code")).isEqualTo("PASSWORD_CHANGE_REQUIRED");
        assertThat(c.changePassword("ADMIN001", "Bootstrap Secret 1", "A new admin pass").status()).isEqualTo(200);
    }

    @Test
    void aMissingPasswordStopsTheStartWithTheSettingToFix() {
        assertThatThrownBy(() -> seeder(new Seed(true, true, " ", null)).seedIfEmpty())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CARDDEMO_SEED_USER_PASSWORD");
        assertThatThrownBy(() -> seeder(new Seed(true, false, null, null)).seedIfEmpty())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CARDDEMO_BOOTSTRAP_ADMIN_PASSWORD");
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void disabledSeedingDoesNothing() throws Exception {
        seeder(new Seed(false, true, "PASSWORD", null)).run(null);
        assertThat(userRepository.count()).isZero();
    }
}
