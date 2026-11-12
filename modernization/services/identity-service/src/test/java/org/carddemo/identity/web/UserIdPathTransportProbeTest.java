package org.carddemo.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transport probe for contract S31 without a database: the real embedded Tomcat, the default
 * Spring Security firewall and Spring MVC path matching, with a stand-in controller that echoes
 * what it receives. Every user ID the rules allow reaches the controller unchanged in key form
 * ({@code /api/users/key/{userKey}}). In the plain form ({@code /api/users/{userId}}, sent as
 * {@code encodeURIComponent} sends it) the result each ID gets is pinned, so a change in Tomcat
 * or Spring behaviour shows here. UserIdTransportPilotTest runs the same IDs through the real
 * service.
 */
@SpringBootTest(classes = UserIdPathTransportProbeTest.Probe.class, webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:sqlserver://127.0.0.1:1;databaseName=none",
                "spring.flyway.enabled=false"})
public class UserIdPathTransportProbeTest {

    /** Canonical user IDs (at most 8 of U+0000 to U+00FF, no U+0020 inside) that stress the URL. */
    static final List<String> IDS = List.of(
            "ADMIN001", "KEY", "A/B", "A%B", "%2F", "A\\B", "A;B", "A?B", "A#B", "A+B", "A&B", "A=B",
            "A\0B", "A\u0001B", "A\tB", "A\u007fB", "A\u0080B", "A\u0085B", "A\u009fB",
            "A\u00a0B", "\u00ff\u00e9", "\u00df", "A.B", ".", "..", "...", "A//B", "A/../B");

    /**
     * The IDs the server stack refuses in the plain form, with 400 before any controller: Tomcat
     * for an encoded {@code /}, {@code \} or NUL, the Spring Security firewall for an encoded
     * {@code %} or {@code ;} and for the {@code .} and {@code ..} segments.
     */
    public static final Set<String> PLAIN_REFUSED = Set.of("A/B", "A%B", "%2F", "A\\B", "A;B", "A\0B", ".", "..", "A//B",
            "A/../B");

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    Environment env;

    @Test
    void everyAllowedUserIdReachesTheControllerInKeyForm() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String id : IDS) {
            for (String method : List.of("GET", "PUT", "DELETE")) {
                String got = send(method, "/api/users/key/" + UserKeys.encode(id));
                if (!got.equals("200 key " + hex(id))) {
                    wrong.add(method + " " + show(id) + " -> " + got);
                }
            }
        }
        assertThat(wrong).as("IDs that do not reach the controller unchanged in key form").isEmpty();
    }

    @Test
    void thePlainFormGivesEachIdItsPinnedResult() throws Exception {
        Map<String, String> outcome = new LinkedHashMap<>();
        List<String> unexpected = new ArrayList<>();
        for (String id : IDS) {
            for (String method : List.of("GET", "PUT", "DELETE")) {
                String got = send(method, "/api/users/" + encodeUriComponent(id));
                outcome.put(method + " " + show(id), got);
                boolean ok = PLAIN_REFUSED.contains(id) ? got.startsWith("400 ") : got.equals("200 plain " + hex(id));
                if (!ok) {
                    unexpected.add(method + " " + show(id) + " -> " + got);
                }
            }
        }
        assertThat(unexpected).as("plain-form results that differ from the pinned ones:\n%s", outcome.entrySet()
                .stream().map(e -> e.getKey() + " => " + e.getValue()).collect(Collectors.joining("\n"))).isEmpty();
    }

    private String send(String method, String rawPathAndQuery) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + env.getProperty("local.server.port") + rawPathAndQuery);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri);
        b = switch (method) {
            case "PUT" -> b.PUT(HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type", "application/json");
            case "DELETE" -> b.DELETE();
            default -> b.GET();
        };
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        String body = r.body() == null ? "" : r.body().strip();
        return r.statusCode() + " " + (r.statusCode() == 200 ? body : body.length() > 80 ? body.substring(0, 80) : body);
    }

    /** JavaScript's encodeURIComponent: UTF-8, every byte escaped except A-Z a-z 0-9 - _ . ! ~ * ' ( ). */
    public static String encodeUriComponent(String s) {
        StringBuilder b = new StringBuilder();
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || "-_.!~*'()".indexOf(c) >= 0) {
                b.append((char) c);
            } else {
                b.append('%').append(String.format("%02X", c));
            }
        }
        return b.toString();
    }

    static String hex(String s) {
        return s.chars().mapToObj(c -> String.format("%04x", c)).collect(Collectors.joining(" "));
    }

    private static String show(String id) {
        return "[" + encodeUriComponent(id) + "]";
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(EchoController.class)
    static class Probe {

        @Bean
        SecurityFilterChain probeChain(HttpSecurity http) throws Exception {
            return http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new BadJwtException("probe");
            };
        }
    }

    @RestController
    @RequestMapping
    static class EchoController {

        @GetMapping("/api/users/{userId}")
        String get(@PathVariable String userId) {
            return "plain " + hex(userId);
        }

        @PutMapping("/api/users/{userId}")
        String put(@PathVariable String userId) {
            return "plain " + hex(userId);
        }

        @DeleteMapping("/api/users/{userId}")
        String delete(@PathVariable String userId) {
            return "plain " + hex(userId);
        }

        @GetMapping("/api/users/key/{userKey}")
        String getKey(@PathVariable String userKey) {
            return "key " + hex(UserKeys.decode(userKey));
        }

        @PutMapping("/api/users/key/{userKey}")
        String putKey(@PathVariable String userKey) {
            return "key " + hex(UserKeys.decode(userKey));
        }

        @DeleteMapping("/api/users/key/{userKey}")
        String deleteKey(@PathVariable String userKey) {
            return "key " + hex(UserKeys.decode(userKey));
        }
    }
}
