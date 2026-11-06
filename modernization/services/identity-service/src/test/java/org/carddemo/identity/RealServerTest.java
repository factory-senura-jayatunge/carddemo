package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Collectors;

import org.carddemo.identity.ApiClient.SetCookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;

/**
 * The same calls through Tomcat, which writes the Set-Cookie headers itself: MockMvc formats
 * cookies differently, so the attributes the browser sees are checked here.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "carddemo.identity.bcrypt-strength=4",
        "carddemo.identity.seed.enabled=false",
        "carddemo.security.cookie-secure=false"})
class RealServerTest extends IdentityTestBase {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    Environment env;

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + env.getProperty("local.server.port") + path);
    }

    private static List<SetCookie> setCookies(HttpResponse<?> r) {
        return r.headers().allValues("Set-Cookie").stream().map(ApiClient::parse).toList();
    }

    private static SetCookie named(List<SetCookie> cookies, String name) {
        return cookies.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theBrowserGetsStrictCookiesAndTheCsrfRoundTripWorks() throws Exception {
        db.demoUsers();
        HttpResponse<String> signOn = http.send(HttpRequest.newBuilder(uri("/api/auth/sign-on"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":\"USER0001\",\"password\":\"PASSWORD\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(signOn.statusCode()).as(signOn.body()).isEqualTo(200);
        List<SetCookie> cookies = setCookies(signOn);
        for (String name : List.of("cd_at", "cd_rt", "XSRF-TOKEN")) {
            SetCookie c = named(cookies, name);
            assertThat(c.attr("SameSite")).as(name).isEqualTo("Strict");
            assertThat(c.has("Secure")).as(name + " with cookie-secure=false").isFalse();
        }
        assertThat(named(cookies, "cd_at").has("HttpOnly")).isTrue();
        assertThat(named(cookies, "cd_rt").has("HttpOnly")).isTrue();
        assertThat(named(cookies, "XSRF-TOKEN").has("HttpOnly")).isFalse();

        String xsrf = named(cookies, "XSRF-TOKEN").value();
        String all = cookies.stream().map(c -> c.name() + "=" + c.value()).collect(Collectors.joining("; "));

        HttpResponse<String> me = http.send(HttpRequest.newBuilder(uri("/api/auth/me")).header("Cookie", all).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.headers().allValues("Set-Cookie")).as("me keeps XSRF-TOKEN").isEmpty();

        HttpResponse<String> noHeader = http.send(HttpRequest.newBuilder(uri("/api/auth/refresh")).header("Cookie", all)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noHeader.statusCode()).isEqualTo(403);

        HttpResponse<String> refresh = http.send(HttpRequest.newBuilder(uri("/api/auth/refresh")).header("Cookie", all)
                .header("X-XSRF-TOKEN", xsrf).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(refresh.statusCode()).as(refresh.body()).isEqualTo(200);
        assertThat(setCookies(refresh)).extracting(SetCookie::name).contains("cd_at", "cd_rt", "XSRF-TOKEN");
    }

    @Test
    void healthIsOpen() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"UP\"");
    }
}
