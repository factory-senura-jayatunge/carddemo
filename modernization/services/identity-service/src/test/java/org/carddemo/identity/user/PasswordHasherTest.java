package org.carddemo.identity.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import java.util.Map;

import org.carddemo.identity.IdentityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PasswordHasherTest {

    @Test
    void theDefaultStrengthIsTwelve() {
        IdentityProperties props = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("carddemo.identity", IdentityProperties.class);
        assertThat(props.bcryptStrength()).isEqualTo(12);
        String hash = new PasswordHasher(props.bcryptStrength()).hash("PASSWORD");
        assertThat(hash).startsWith("{bcrypt}$2a$12$");
    }

    @Test
    void hashesAreSaltedAndCheckTheExactValue() {
        PasswordHasher h = new PasswordHasher(4);
        String a = h.hash("PASSWORD");
        assertThat(h.hash("PASSWORD")).isNotEqualTo(a);
        assertThat(h.matches("PASSWORD", a)).isTrue();
        assertThat(h.matches("password", a)).isFalse();
        assertThat(h.matches("PASSWORD ", a)).isFalse();
    }

    @Test
    void prepareDropsTheFieldPaddingAndUpperCasesInLegacy() {
        assertThat(PasswordHasher.prepare("passw0rd  ", false)).isEqualTo("PASSW0RD");
        assertThat(PasswordHasher.prepare("passw0rd  ", true)).isEqualTo("passw0rd");
        assertThat(PasswordHasher.prepare("  pass word ", true)).isEqualTo("  pass word");
    }

    @Test
    void longerThanBcryptReadsIsNeverHashedNorMatched() {
        PasswordHasher h = new PasswordHasher(4);
        String max = "x".repeat(72);
        String stored = h.hash(max);
        assertThat(PasswordHasher.fits(max)).isTrue();
        assertThat(PasswordHasher.fits("x".repeat(73))).isFalse();
        assertThat(PasswordHasher.fits("é".repeat(37))).as("74 bytes in UTF-8").isFalse();
        assertThat(h.matches(max + "y", stored)).as("bcrypt alone would ignore byte 73").isFalse();
        assertThatThrownBy(() -> h.hash("x".repeat(73))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theEbcdicSortKeyIsTheCp037UserIdPaddedWithSpaces() {
        assertThat(HexFormat.of().formatHex(EbcdicSortKey.of("ADMIN001"))).isEqualTo("c1c4d4c9d5f0f0f1");
        assertThat(HexFormat.of().formatHex(EbcdicSortKey.of("AB"))).isEqualTo("c1c2404040404040");
        // EBCDIC puts letters before digits, the reverse of ASCII.
        assertThat(java.util.Arrays.compareUnsigned(EbcdicSortKey.of("USERA001"), EbcdicSortKey.of("USER0001")))
                .isNegative();
    }
}
