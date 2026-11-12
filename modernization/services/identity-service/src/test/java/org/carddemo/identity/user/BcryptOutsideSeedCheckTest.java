package org.carddemo.identity.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.carddemo.identity.IdentityFixtures;
import org.carddemo.identity.IdentityTestBase;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;

/**
 * The check that no active user outside the demo seed still has a {@code {bcrypt}} hash, run on
 * the migrated test database as {@code scripts/identity_bcrypt_check.py} runs it on the stack.
 */
class BcryptOutsideSeedCheckTest extends IdentityTestBase {

    static final String CHECK = "/checks/bcrypt-outside-seed.sql";

    private static String sql() throws IOException {
        try (InputStream in = BcryptOutsideSeedCheckTest.class.getResourceAsStream(CHECK)) {
            assertThat(in).as(CHECK).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<String> listed() throws IOException {
        return TestDatabase.shared().admin().sql(sql()).query(String.class).list();
    }

    @Test
    void itListsOnlyActiveNonSeedUsersWithABcryptHashAndNoForcedChange() throws IOException {
        String pw = IdentityFixtures.DEMO_PASSWORD;
        db.bcryptUser("ADMIN001", "MARGARET", "GOLD", 'A', pw, false)
                .bcryptUser("USER0005", "LEE", "TING", 'U', pw, false)
                .bcryptUser("LEGACY01", "L", "A", 'U', pw, false)
                .bcryptUser("LEGACY02", "L", "B", 'A', pw, false)
                .bcryptUser("MUSTCHG1", "M", "C", 'U', pw, true)
                .bcryptUser("GONE0001", "G", "D", 'U', pw, false)
                .setUser("GONE0001", "deleted_at = SYSUTCDATETIME(), deleted_by = N'ADMIN001'")
                .user("ARGON001", "A", "E", 'U', pw, false);
        assertThat(listed()).containsExactly("LEGACY01", "LEGACY02");
    }

    @Test
    void anUpgradedHashLeavesTheList() throws IOException {
        db.demoUsers().bcryptUser("LEGACY01", "L", "A", 'U', IdentityFixtures.DEMO_PASSWORD, false);
        assertThat(listed()).containsExactly("LEGACY01");
        assertThat(client().signOn("LEGACY01", IdentityFixtures.DEMO_PASSWORD).status()).isEqualTo(200);
        assertThat(listed()).isEmpty();
    }

    @Test
    void theExemptIdsAreTheDemoSeedUsers() throws IOException {
        Matcher m = Pattern.compile("N'([A-Z0-9]{8})'").matcher(sql());
        List<String> exempt = m.results().map(r -> r.group(1)).toList();
        assertThat(exempt).containsExactlyElementsOf(
                UserSeeder.DEMO_USERS.stream().map(UserSeeder.SeedUser::userId).toList());
    }
}
