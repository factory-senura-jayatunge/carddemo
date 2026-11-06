package org.carddemo.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The production security wiring, against a JWKS endpoint served by this test. */
@SpringBootTest(classes = WebSupportSecurityTest.App.class, properties = "carddemo.zone=America/Chicago")
@AutoConfigureMockMvc
class WebSupportSecurityTest {

    private static final RSAKey KEY = key("test-key");
    private static final RSAKey UNTRUSTED = key("other-key");
    private static final HttpServer JWKS = jwksServer();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Endpoints.class)
    static class App {
    }

    record Body(String value) {
    }

    @RestController
    static class Endpoints {
        @GetMapping("/api/test/me")
        @PreAuthorize("hasRole('USER')")
        Map<String, String> me(@AuthenticationPrincipal Jwt jwt) {
            return Map.of("sub", jwt.getSubject());
        }

        @PostMapping("/api/test/echo")
        @PreAuthorize("hasRole('USER')")
        MessageResponse echo() {
            return MessageResponse.of(MessageCatalog.PAYMENT_SUCCESSFUL, "acctId", "0000000000000001");
        }

        @PostMapping("/api/test/body")
        @PreAuthorize("hasRole('USER')")
        String body(@RequestBody Body body) {
            return body.value();
        }

        @GetMapping("/api/test/window")
        @PreAuthorize("hasRole('USER')")
        String window() {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, MessageCatalog.BATCH_WINDOW, "acctId")
                    .header("Retry-After", "300")
                    .with("currentBalance", new BigDecimal("194.00"));
        }

        @GetMapping("/api/test/boom")
        @PreAuthorize("hasRole('USER')")
        String boom() {
            throw new IllegalStateException("secret technical detail");
        }
    }

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry registry) {
        registry.add("carddemo.security.jwks-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/api/auth/.well-known/jwks.json");
    }

    @AfterAll
    static void stop() {
        JWKS.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtDecoder decoder;

    @Autowired
    Clock clock;

    @Test
    void noTokenIsUnauthenticated() throws Exception {
        mvc.perform(get("/api/test/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value(MessageCatalog.UNAUTHENTICATED.text()))
                .andExpect(jsonPath("$.field").value(nullValue()))
                .andExpect(jsonPath("$.severity").value("error"));
    }

    @Test
    void bearerHeaderAndCookieAreAccepted() throws Exception {
        mvc.perform(get("/api/test/me").header("Authorization", "Bearer " + token(b -> { })))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sub").value("USER0001"));
        mvc.perform(get("/api/test/me").cookie(new Cookie("cd_at", token(b -> { }))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sub").value("USER0001"));
        assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
    }

    @Test
    void theCookieWinsOverTheHeader() throws Exception {
        String admin = token(b -> b.claim("role", "ADMIN").subject("ADMIN001"));
        mvc.perform(get("/api/test/me").cookie(new Cookie("cd_at", token(b -> { })))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sub").value("USER0001"));
    }

    @Test
    void wrongRoleIsAccessDenied() throws Exception {
        mvc.perform(get("/api/test/me").header("Authorization", "Bearer " + token(b -> b.claim("role", "ADMIN"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("You are not authorized to use this function ..."));
    }

    @Test
    void tokensOutsideTheContractAreRejected() throws Exception {
        Instant now = Instant.now();
        for (String bad : new String[] {
            sign(KEY, "JWT", claims(now).build()),
            sign(KEY, null, claims(now).build()),
            sign(KEY, "at+jwt", claims(now).issuer("someone-else").build()),
            sign(KEY, "at+jwt", claims(now).audience("other-api").build()),
            sign(KEY, "at+jwt", claims(now.minus(Duration.ofMinutes(10))).build()),
            sign(UNTRUSTED, "at+jwt", claims(now).build()),
            "not-a-jwt",
        }) {
            mvc.perform(get("/api/test/me").header("Authorization", "Bearer " + bad))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    void cookieSessionsNeedTheCsrfToken() throws Exception {
        Cookie session = new Cookie("cd_at", token(b -> { }));
        mvc.perform(post("/api/test/echo").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        mvc.perform(post("/api/test/echo").cookie(session, new Cookie("XSRF-TOKEN", "abc")).header("X-XSRF-TOKEN", "xyz"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        mvc.perform(post("/api/test/echo").cookie(session, new Cookie("XSRF-TOKEN", "abc")).header("X-XSRF-TOKEN", "abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Payment successful.  Your Transaction ID is 0000000000000001."))
                .andExpect(jsonPath("$.severity").value("success"))
                .andExpect(jsonPath("$.field").value("acctId"));
    }

    @Test
    void headerOnlyClientsNeedNoCsrfToken() throws Exception {
        mvc.perform(post("/api/test/echo").header("Authorization", "Bearer " + token(b -> { })))
                .andExpect(status().isOk());
    }

    @Test
    void anUnsafeRequestWithoutAnyTokenIsUnauthenticated() throws Exception {
        mvc.perform(post("/api/test/echo"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void apiExceptionsBecomeProblemDetails() throws Exception {
        mvc.perform(get("/api/test/window").header("Authorization", "Bearer " + token(b -> { })))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "300"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BATCH_WINDOW"))
                .andExpect(jsonPath("$.message").value(
                        "Bill payment is not available while the batch cycle runs. Try again later..."))
                .andExpect(jsonPath("$.field").value("acctId"))
                .andExpect(jsonPath("$.currentBalance").value("194.00"));
    }

    @Test
    void unexpectedErrorsHideTheirCause() throws Exception {
        mvc.perform(get("/api/test/boom").header("Authorization", "Bearer " + token(b -> { })))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
        mvc.perform(post("/api/test/body").header("Authorization", "Bearer " + token(b -> { }))
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/test/none").header("Authorization", "Bearer " + token(b -> { })))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void correlationIdIsEchoedOrCreated() throws Exception {
        mvc.perform(get("/api/test/me").header("X-Correlation-Id", "abc-123"))
                .andExpect(header().string("X-Correlation-Id", "abc-123"));
        mvc.perform(get("/api/test/me").header("X-Correlation-Id", "bad id\nwith newline"))
                .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void theClockUsesCarddemoZone() {
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("America/Chicago"));
    }

    private static String token(Consumer<JWTClaimsSet.Builder> customizer) {
        JWTClaimsSet.Builder claims = claims(Instant.now());
        customizer.accept(claims);
        return sign(KEY, "at+jwt", claims.build());
    }

    private static JWTClaimsSet.Builder claims(Instant iat) {
        return new JWTClaimsSet.Builder().issuer("carddemo-identity").audience("carddemo-api").subject("USER0001")
                .claim("sid", UUID.randomUUID().toString()).claim("role", "USER").claim("utype", "U")
                .issueTime(Date.from(iat)).notBeforeTime(Date.from(iat))
                .expirationTime(Date.from(iat.plusSeconds(300))).jwtID(UUID.randomUUID().toString());
    }

    private static String sign(RSAKey key, String type, JWTClaimsSet claims) {
        try {
            JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID());
            if (type != null) {
                header.type(new JOSEObjectType(type));
            }
            SignedJWT jwt = new SignedJWT(header.build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey key(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer jwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/api/auth/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
