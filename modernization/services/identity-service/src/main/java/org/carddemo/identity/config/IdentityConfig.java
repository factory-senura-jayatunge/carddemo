package org.carddemo.identity.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.carddemo.identity.IdentityProperties;
import org.carddemo.identity.fidelity.IdentitySwitches;
import org.carddemo.identity.token.AccessTokens;
import org.carddemo.identity.token.SigningKeys;
import org.carddemo.identity.user.PasswordHasher;
import org.carddemo.platform.fidelity.FidelitySwitch;
import org.carddemo.platform.fidelity.FidelitySwitchCatalog;
import org.carddemo.platform.web.AuditLog;
import org.carddemo.platform.web.CarddemoSecurityProperties;
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
class IdentityConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    FidelitySwitchCatalog identitySwitchCatalog() {
        return FidelitySwitchCatalog.of(IdentitySwitches.ALL.toArray(FidelitySwitch[]::new));
    }

    /**
     * Sign-on, password change, sign-off, user administration and the seeder; replaces the template
     * Spring Boot would configure. A callback's exception leaves it redacted (an {@code ApiException}
     * passes through, any other DataAccessException becomes a plain one), so a mapping by exception
     * type, such as DuplicateKeyException to 409, has to happen inside the callback.
     */
    @Bean
    SafeTransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new SafeTransactionTemplate(transactionManager);
    }

    /** ops.audit rows of user administration (D-56); iam.audit_event keeps the sign-on events. */
    @Bean
    AuditLog opsAuditLog(JdbcClient jdbc) {
        return new AuditLog(jdbc, "identity-service");
    }

    @Bean
    PasswordHasher passwordHasher(IdentityProperties props) {
        return new PasswordHasher(props.bcryptStrength());
    }

    @Bean
    SigningKeys signingKeys(IdentityProperties props) {
        return SigningKeys.load(props.jwt().privateKeyPath());
    }

    @Bean
    JwtEncoder jwtEncoder(SigningKeys keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
    }

    /** Checks this service's own access tokens with the public key, as the other services do with the JWKS. */
    @Bean
    JwtDecoder jwtDecoder(SigningKeys keys, CarddemoSecurityProperties security, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .jwtProcessorCustomizer(p -> p.setJWSTypeVerifier(
                        new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType(AccessTokens.TYPE))))
                .build();
        // No clock skew: the default 60 s would let a revoked session's token live 6 minutes, not 5.
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        JwtClaimValidator<List<String>> audience = new JwtClaimValidator<>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(security.getAudience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps, new JwtIssuerValidator(security.getIssuer()), audience, new JwtTypeValidator(AccessTokens.TYPE)));
        return decoder;
    }

    @Bean
    OpenAPI identityOpenApi() {
        return new OpenAPI()
                .info(new Info().title("CardDemo identity-service")
                        .description("Sign-on, password change, sessions, the JWKS and user administration of the CardDemo API.")
                        .version("1"))
                .servers(List.of(new Server().url("/")));
    }
}
