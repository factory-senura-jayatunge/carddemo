package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.carddemo.identity.ApiClient.Response;
import org.carddemo.identity.ApiClient.SetCookie;
import org.carddemo.identity.IdentityFixtures.SessionRow;
import org.carddemo.identity.token.SigningKeys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The token and cookie table of the identity contract, checked from the outside. */
class TokensAndCookiesTest extends IdentityTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void signOnSetsTheThreeCookiesWithTheirAttributes() {
        db.demoUsers();
        Response r = client().signOn("USER0001", "PASSWORD");
        assertThat(r.status()).isEqualTo(200);

        SetCookie at = r.cookie("cd_at");
        assertThat(at.attr("Path")).isEqualTo("/api");
        assertThat(at.attr("Max-Age")).isEqualTo("300");
        assertThat(at.attr("SameSite")).isEqualTo("Strict");
        assertThat(at.has("HttpOnly")).isTrue();
        assertThat(at.has("Secure")).as("carddemo.security.cookie-secure defaults to true").isTrue();

        SetCookie rt = r.cookie("cd_rt");
        assertThat(rt.attr("Path")).isEqualTo("/api/auth");
        assertThat(rt.attr("Max-Age")).isEqualTo("28800");
        assertThat(rt.attr("SameSite")).isEqualTo("Strict");
        assertThat(rt.has("HttpOnly")).isTrue();
        assertThat(rt.has("Secure")).isTrue();
        assertThat(Base64.getUrlDecoder().decode(rt.value())).as("32 random bytes").hasSize(32);

        SetCookie xsrf = r.cookie("XSRF-TOKEN");
        assertThat(xsrf.attr("Path")).isEqualTo("/");
        assertThat(xsrf.has("HttpOnly")).as("the SPA reads it").isFalse();
        assertThat(xsrf.value()).isNotBlank();
    }

    @Test
    void theAccessTokenCarriesTheContractClaimsAndVerifiesWithTheJwks() throws Exception {
        db.demoUsers();
        ApiClient c = client();
        c.signOn("ADMIN001", "PASSWORD");
        SignedJWT jwt = SignedJWT.parse(c.cookieValue("cd_at"));

        JsonNode jwks = JSON.readTree(mvc.perform(MockMvcRequestBuilders.get("/api/auth/.well-known/jwks.json"))
                .andReturn().getResponse().getContentAsString());
        assertThat(jwks.path("keys")).hasSize(1);
        JsonNode key = jwks.path("keys").get(0);
        assertThat(key.path("kty").asString()).isEqualTo("RSA");
        assertThat(key.path("use").asString()).isEqualTo("sig");
        assertThat(key.path("alg").asString()).isEqualTo("RS256");
        assertThat(key.has("d")).as("public parts only").isFalse();
        assertThat(key.has("p")).isFalse();

        // The key behind the JWKS is the one in the configured PEM file.
        RSAPublicKey filePublic = SigningKeys.load(TestKeys.path()).publicKey();
        RSAKey published = RSAKey.parse(JSON.writeValueAsString(key));
        assertThat(published.toRSAPublicKey().getModulus()).isEqualTo(filePublic.getModulus());

        JWSHeader header = jwt.getHeader();
        assertThat(header.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(header.getType()).isEqualTo(new JOSEObjectType("at+jwt"));
        assertThat(header.getKeyID()).isEqualTo(thumbprint(key)).isEqualTo(key.path("kid").asString());
        assertThat(jwt.verify(new RSASSAVerifier(published))).isTrue();

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("carddemo-identity");
        assertThat(claims.getAudience()).containsExactly("carddemo-api");
        assertThat(claims.getSubject()).isEqualTo("ADMIN001");
        assertThat(claims.getStringClaim("role")).isEqualTo("ADMIN");
        assertThat(claims.getStringClaim("utype")).isEqualTo("A");
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(START);
        assertThat(claims.getNotBeforeTime().toInstant()).isEqualTo(START);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(START.plusSeconds(300));
        assertThat(UUID.fromString(claims.getJWTID())).isNotNull();
        assertThat(db.sessions()).extracting(SessionRow::sessionId)
                .containsExactly(claims.getStringClaim("sid").toLowerCase());
    }

    @Test
    void scriptsMaySendTheTokenAsABearerHeader() throws Exception {
        db.demoUsers();
        ApiClient c = client();
        c.signOn("USER0001", "PASSWORD");
        String token = c.cookieValue("cd_at");
        int status = mvc.perform(MockMvcRequestBuilders.get("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
    }

    @Test
    void anExpiredAccessTokenIsUnauthenticated() {
        db.demoUsers();
        ApiClient c = client();
        c.signOn("USER0001", "PASSWORD");
        clock.advance(Duration.ofSeconds(300));
        assertThat(c.me().status()).as("valid up to exp").isEqualTo(200);
        clock.advance(Duration.ofSeconds(1));
        Response me = c.me();
        assertThat(me.status()).isEqualTo(401);
        assertThat(me.text("code")).isEqualTo("UNAUTHENTICATED");
        assertThat(me.text("message")).isEqualTo("Please sign on to use this function ...");
    }

    @Test
    void forgedTokensAreRefused() throws Exception {
        db.demoUsers();
        ApiClient c = client();
        c.signOn("USER0001", "PASSWORD");
        JWTClaimsSet claims = SignedJWT.parse(c.cookieValue("cd_at")).getJWTClaimsSet();
        RSAKey ours = SigningKeys.load(TestKeys.path()).signingKey();
        KeyPair other = TestKeys.generate();

        assertThat(meWith(sign(claims, ours, "at+jwt"))).as("the real token").isEqualTo(200);
        assertThat(meWith(sign(claims, new RSAKey.Builder((RSAPublicKey) other.getPublic())
                .privateKey(other.getPrivate()).keyID(ours.getKeyID()).build(), "at+jwt")))
                .as("another key").isEqualTo(401);
        assertThat(meWith(sign(claims, ours, "JWT"))).as("not an access token type").isEqualTo(401);
        assertThat(meWith(sign(new JWTClaimsSet.Builder(claims).audience(List.of("other-api")).build(), ours,
                "at+jwt"))).as("another audience").isEqualTo(401);
        assertThat(meWith(sign(new JWTClaimsSet.Builder(claims).issuer("someone-else").build(), ours, "at+jwt")))
                .as("another issuer").isEqualTo(401);
        assertThat(meWith(sign(new JWTClaimsSet.Builder(claims).notBeforeTime(Date.from(START.plusSeconds(120)))
                .build(), ours, "at+jwt"))).as("not yet valid").isEqualTo(401);
    }

    private int meWith(String token) {
        ApiClient c = client();
        c.setCookie("cd_at", token, "/api");
        return c.me().status();
    }

    private static String sign(JWTClaimsSet claims, RSAKey key, String type) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType(type))
                .keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    /** RFC 7638: SHA-256 of the required members in lexical order, base64url. */
    private static String thumbprint(JsonNode key) throws Exception {
        String canonical = "{\"e\":\"" + key.path("e").asString() + "\",\"kty\":\"RSA\",\"n\":\""
                + key.path("n").asString() + "\"}";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
