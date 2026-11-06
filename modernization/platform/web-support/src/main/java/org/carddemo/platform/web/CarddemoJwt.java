package org.carddemo.platform.web;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * The identity-service access token contract: RS256, {@code typ: at+jwt}, issuer, audience and
 * timestamps checked against the application clock, {@code sub} as the principal and the
 * {@code role} claim as {@code ROLE_<role>}.
 */
public final class CarddemoJwt {

    public static final String ACCESS_TOKEN_TYPE = "at+jwt";
    public static final String ROLE_CLAIM = "role";

    private CarddemoJwt() {
    }

    public static OAuth2TokenValidator<Jwt> validator(CarddemoSecurityProperties properties, Clock clock) {
        // No clock skew: the 300-second token lifetime is the session's idle bound (D-SEC-03).
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        // A JwtTypeValidator in the list stops createDefaultWithValidators from adding the "JWT" one.
        validators.add(new JwtTypeValidator(ACCESS_TOKEN_TYPE));
        validators.add(timestamps);
        validators.add(new JwtIssuerValidator(properties.getIssuer()));
        validators.add(new JwtAudienceValidator(properties.getAudience()));
        return JwtValidators.createDefaultWithValidators(validators);
    }

    /** Validates against the JWKS URI; the type is checked by {@link #validator}, not by Nimbus. */
    public static NimbusJwtDecoder decoder(CarddemoSecurityProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.getJwksUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .validateType(false)
                .build();
        decoder.setJwtValidator(validator(properties, clock));
        return decoder;
    }

    public static JwtAuthenticationConverter authenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        converter.setJwtGrantedAuthoritiesConverter(new RoleConverter());
        return converter;
    }

    static final class RoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
        @Override
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            String role = jwt.getClaimAsString(ROLE_CLAIM);
            if (role == null || !role.matches("[A-Z][A-Z_]{0,31}")) {
                return List.of();
            }
            return List.of(new SimpleGrantedAuthority("ROLE_" + role));
        }
    }
}
