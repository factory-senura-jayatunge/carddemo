package org.carddemo.identity.token;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.carddemo.identity.IdentityProperties;
import org.carddemo.platform.web.CarddemoSecurityProperties;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Signs the access tokens every service accepts (D-ARCH-11). */
@Component
public class AccessTokens {

    /** RFC 9068 type, so an access token cannot be mistaken for another JWT. */
    public static final String TYPE = "at+jwt";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_USER_TYPE = "utype";
    public static final String CLAIM_SESSION = "sid";

    public record Issued(String value, Instant expiresAt) {
    }

    private final JwtEncoder encoder;
    private final SigningKeys keys;
    private final CarddemoSecurityProperties security;
    private final IdentityProperties props;

    public AccessTokens(JwtEncoder encoder, SigningKeys keys, CarddemoSecurityProperties security,
            IdentityProperties props) {
        this.encoder = encoder;
        this.keys = keys;
        this.security = security;
        this.props = props;
    }

    public Issued issue(String userId, char userType, String role, UUID sessionId, Instant now) {
        Instant expires = now.plus(props.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(security.getIssuer())
                .audience(List.of(security.getAudience()))
                .subject(userId)
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expires)
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_SESSION, sessionId.toString())
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_USER_TYPE, String.valueOf(userType))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type(TYPE).keyId(keys.keyId()).build();
        return new Issued(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expires);
    }
}
