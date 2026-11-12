package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.carddemo.identity.ApiClient.Response;

/** Helpers of the user-admin API tests: signed-on clients, bodies and problem checks. */
abstract class UserAdminTestBase extends IdentityTestBase {

    /** A client signed on as {@code userId} with the demo password. */
    protected ApiClient signedOn(String userId) {
        ApiClient c = client();
        Response r = c.signOn(userId, IdentityFixtures.DEMO_PASSWORD);
        assertThat(r.status()).as("sign-on of %s: %s", userId, r.body()).isEqualTo(200);
        return c;
    }

    protected ApiClient admin() {
        return signedOn("ADMIN001");
    }

    /** The add form; null leaves a member out. */
    protected static String newUser(String firstName, String lastName, String userId, String password,
            String userType) {
        Map<String, String> body = new LinkedHashMap<>();
        put(body, "firstName", firstName);
        put(body, "lastName", lastName);
        put(body, "userId", userId);
        put(body, "password", password);
        put(body, "userType", userType);
        return ApiClient.json(body);
    }

    /** The edit form; null leaves a member out. */
    protected static String change(String firstName, String lastName, String userType, String password) {
        Map<String, String> body = new LinkedHashMap<>();
        put(body, "firstName", firstName);
        put(body, "lastName", lastName);
        put(body, "userType", userType);
        put(body, "password", password);
        return ApiClient.json(body);
    }

    protected static void assertProblem(Response r, int status, String code, String message, String field) {
        assertThat(r.status()).as("status of %s", r.body()).isEqualTo(status);
        assertThat(r.text("code")).isEqualTo(code);
        assertThat(r.text("message")).isEqualTo(message);
        assertThat(r.text("field")).isEqualTo(field);
        assertThat(r.text("severity")).isEqualTo("error");
    }

    /** The ETag of a fresh fetch. */
    protected static String etag(ApiClient c, String userId) {
        Response r = c.get("/api/users/{id}", userId);
        assertThat(r.status()).as("fetch of %s: %s", userId, r.body()).isEqualTo(200);
        return r.header("ETag");
    }

    private static void put(Map<String, String> body, String name, String value) {
        if (value != null) {
            body.put(name, value);
        }
    }
}
