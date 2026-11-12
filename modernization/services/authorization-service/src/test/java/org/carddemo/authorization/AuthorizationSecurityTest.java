package org.carddemo.authorization;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contract §11 A20: every endpoint is closed without a token and to an ADMIN token, and the fraud
 * POST with the session cookie needs the CSRF header. Each request is refused by the security
 * chain, before a repository is called.
 */
class AuthorizationSecurityTest extends AuthorizationWebTestBase {

    static final String ACCT = "00000000091";
    static final String VERSION = "\"00000000000007d1\"";

    static List<MockHttpServletRequestBuilder> endpoints() {
        return List.of(
                get("/api/accounts/{a}/authorizations/summary", ACCT),
                get("/api/accounts/{a}/authorizations", ACCT),
                get("/api/authorizations/{id}", 1),
                post("/api/authorizations/{id}/fraud", 1).header("If-Match", VERSION)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"F\"}"));
    }

    @Test
    @DisplayName("A20: no token is 401 UNAUTHENTICATED on every endpoint")
    void withoutAToken() throws Exception {
        for (MockHttpServletRequestBuilder request : endpoints()) {
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    @DisplayName("A20: an ADMIN token is 403 ACCESS_DENIED on every endpoint (USER only)")
    void withAnAdminToken() throws Exception {
        String admin = TestTokens.admin("ADMIN001");
        for (MockHttpServletRequestBuilder request : endpoints()) {
            mvc.perform(request.header("Authorization", "Bearer " + admin)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        mvc.perform(get("/api/accounts/{a}/authorizations/summary", ACCT).cookie(new Cookie("cd_at", admin)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("A20: the fraud POST with the session cookie and no CSRF header is 403 CSRF_REJECTED")
    void fraudNeedsTheCsrfHeaderWithTheCookie() throws Exception {
        Cookie session = new Cookie("cd_at", TestTokens.user("USER0001"));
        mvc.perform(post("/api/authorizations/{id}/fraud", 1).cookie(session).header("If-Match", VERSION)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"F\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
    }

    @Test
    @DisplayName("A20: the fraud POST with the session cookie and a bearer header still needs the CSRF header")
    void fraudNeedsTheCsrfHeaderWithTheCookieAndABearerHeader() throws Exception {
        String user = TestTokens.user("USER0001");
        Cookie session = new Cookie("cd_at", user);
        Cookie csrf = new Cookie("XSRF-TOKEN", "t0k3n");
        // A browser sends the cookie on its own, so an added Authorization header must not lift the check.
        mvc.perform(post("/api/authorizations/{id}/fraud", 1).cookie(session).header("Authorization", "Bearer " + user)
                        .header("If-Match", VERSION).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"X\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        mvc.perform(post("/api/authorizations/{id}/fraud", 1).cookie(session, csrf).header("Authorization", "Bearer " + user)
                        .header("X-XSRF-TOKEN", "other").header("If-Match", VERSION)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"X\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        // With the matching header the request passes the chain and reaches the action check.
        mvc.perform(post("/api/authorizations/{id}/fraud", 1).cookie(session, csrf).header("Authorization", "Bearer " + user)
                        .header("X-XSRF-TOKEN", "t0k3n").header("If-Match", VERSION)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"X\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FRAUD_ACTION"));
    }

    @Test
    @DisplayName("Checks before any read answer without the database: INVALID_AUTH_ID, INVALID_FRAUD_ACTION, 428")
    @SpecItem("VAL-AUDT-001")
    void checksBeforeAnyRead() throws Exception {
        String user = "Bearer " + TestTokens.user("USER0001");
        mvc.perform(get("/api/authorizations/{id}", "abc").header("Authorization", user))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AUTH_ID"));
        mvc.perform(post("/api/authorizations/{id}/fraud", 1).header("Authorization", user).header("If-Match", VERSION)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"X\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FRAUD_ACTION"));
        mvc.perform(post("/api/authorizations/{id}/fraud", 1).header("Authorization", user)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"F\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mvc.perform(post("/api/authorizations/{id}/fraud", 1).header("Authorization", user).header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"F\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));
        mvc.perform(get("/api/accounts/{a}/authorizations", "123").header("Authorization", user))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ACCOUNT_ID_NOT_NUMERIC"));
    }
}
