package org.carddemo.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CarddemoJwtTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:30:15Z");

    private static Jwt jwt(String type, Instant iat, String role) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").issuer("carddemo-identity")
                .audience(List.of("carddemo-api")).subject("USER0001").issuedAt(iat).notBefore(iat)
                .expiresAt(iat.plusSeconds(300)).claim("role", role);
        if (type != null) {
            b.header("typ", type);
        }
        return b.build();
    }

    @Test
    void timestampsAreCheckedAgainstTheApplicationClock() {
        var validator = CarddemoJwt.validator(new CarddemoSecurityProperties(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(validator.validate(jwt("at+jwt", NOW, "USER")).hasErrors()).isFalse();
        // Five minutes plus the 60 s default skew later, the token has expired.
        var later = CarddemoJwt.validator(new CarddemoSecurityProperties(), Clock.fixed(NOW.plusSeconds(361), ZoneOffset.UTC));
        assertThat(later.validate(jwt("at+jwt", NOW, "USER")).hasErrors()).isTrue();
        assertThat(validator.validate(jwt("JWT", NOW, "USER")).hasErrors()).isTrue();
    }

    @Test
    void theRoleClaimBecomesOneAuthority() {
        var converter = CarddemoJwt.authenticationConverter();
        JwtAuthenticationToken user = (JwtAuthenticationToken) converter.convert(jwt("at+jwt", NOW, "USER"));
        assertThat(user.getName()).isEqualTo("USER0001");
        assertThat(roles(user)).containsExactly("ROLE_USER");
        assertThat(roles(converter.convert(jwt("at+jwt", NOW, "ADMIN")))).containsExactly("ROLE_ADMIN");
        for (String odd : new String[] {"user", "USER ADMIN", "", "ROLE_X Y"}) {
            assertThat(roles(converter.convert(jwt("at+jwt", NOW, odd)))).isEmpty();
        }
    }

    /** Spring Security also adds a FACTOR_BEARER authority; only the roles matter here. */
    private static List<String> roles(org.springframework.security.core.Authentication authentication) {
        return authentication.getAuthorities().stream().map(a -> a.getAuthority()).filter(a -> a.startsWith("ROLE_")).toList();
    }
}
