package org.carddemo.platform.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.carddemo.platform.web.CarddemoJwt;
import org.carddemo.platform.web.CarddemoSecurityProperties;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Access tokens in the identity-service format (RS256, {@code typ: at+jwt}, issuer, audience,
 * {@code sub}, {@code sid}, {@code role}, {@code utype}, 300 s lifetime), signed with a key made
 * for this JVM, so service tests need no identity-service. {@link TestTokensConfiguration} makes
 * the service accept them.
 */
public final class TestTokens {

    public static final Duration LIFETIME = Duration.ofSeconds(300);

    private static final RSAKey KEY = generate("carddemo-test");
    private static final RSAKey OTHER_KEY = generate("carddemo-other");

    private TestTokens() {
    }

    /** A {@code USER} token (legacy type {@code U}). */
    public static String user(String userId) {
        return builder(userId).build();
    }

    /** An {@code ADMIN} token (legacy type {@code A}). */
    public static String admin(String userId) {
        return builder(userId).role("ADMIN").userType("A").build();
    }

    public static Builder builder(String userId) {
        return new Builder(userId);
    }

    /** The public half as a JWKS document. */
    public static String jwkSetJson() {
        return new JWKSet(KEY.toPublicJWK()).toString();
    }

    /** A decoder that trusts the test key and applies the production validators. */
    public static JwtDecoder decoder(CarddemoSecurityProperties properties, Clock clock) {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .validateType(false)
                    .build();
            decoder.setJwtValidator(CarddemoJwt.validator(properties, clock));
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class Builder {
        private final String userId;
        private String role = "USER";
        private String userType = "U";
        private String type = CarddemoJwt.ACCESS_TOKEN_TYPE;
        private String issuer = "carddemo-identity";
        private String audience = "carddemo-api";
        private Instant issuedAt;
        private Duration lifetime = LIFETIME;
        private boolean otherKey;

        private Builder(String userId) {
            this.userId = userId;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder userType(String userType) {
            this.userType = userType;
            return this;
        }

        /** The JOSE {@code typ} header; null leaves it out. */
        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Builder audience(String audience) {
            this.audience = audience;
            return this;
        }

        /** Default: {@link TestClock#shared()} at build time. */
        public Builder issuedAt(Instant issuedAt) {
            this.issuedAt = issuedAt;
            return this;
        }

        public Builder lifetime(Duration lifetime) {
            this.lifetime = lifetime;
            return this;
        }

        /** Signs with a key the decoder does not trust. */
        public Builder signedWithUnknownKey() {
            this.otherKey = true;
            return this;
        }

        public String build() {
            RSAKey key = otherKey ? OTHER_KEY : KEY;
            Instant iat = issuedAt != null ? issuedAt : TestClock.shared().instant();
            JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID());
            if (type != null) {
                header.type(new JOSEObjectType(type));
            }
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(userId)
                    .claim("sid", UUID.randomUUID().toString())
                    .claim("role", role)
                    .claim("utype", userType)
                    .issueTime(Date.from(iat))
                    .notBeforeTime(Date.from(iat))
                    .expirationTime(Date.from(iat.plus(lifetime)))
                    .jwtID(UUID.randomUUID().toString());
            if (audience != null) {
                claims.audience(audience);
            }
            SignedJWT jwt = new SignedJWT(header.build(), claims.build());
            try {
                jwt.sign(new RSASSASigner(key));
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
            return jwt.serialize();
        }
    }

    private static RSAKey generate(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
