package org.carddemo.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the API the way the SPA does: keeps the cookies by path, and sends the XSRF-TOKEN
 * cookie back as the X-XSRF-TOKEN header on unsafe calls.
 */
public final class ApiClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record SetCookie(String name, String value, Map<String, String> attributes) {

        public String attr(String key) {
            return attributes.get(key.toLowerCase(Locale.ROOT));
        }

        public boolean has(String key) {
            return attributes.containsKey(key.toLowerCase(Locale.ROOT));
        }
    }

    public record Response(int status, JsonNode body, List<SetCookie> setCookies, MockHttpServletResponse raw) {

        public String text(String field) {
            JsonNode n = body.path(field);
            return n.isNull() || n.isMissingNode() ? null : n.asString();
        }

        public String header(String name) {
            return raw.getHeader(name);
        }

        public SetCookie cookie(String name) {
            return setCookies.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
        }
    }

    private record Stored(String value, String path) {
    }

    private final MockMvc mvc;
    private final Map<String, Stored> jar = new LinkedHashMap<>();
    private boolean sendCsrfHeader = true;
    private String bearer;
    private String remoteAddr;

    public ApiClient(MockMvc mvc) {
        this.mvc = mvc;
    }

    public ApiClient withoutCsrfHeader() {
        sendCsrfHeader = false;
        return this;
    }

    public ApiClient withCsrfHeader() {
        sendCsrfHeader = true;
        return this;
    }

    /** Also sends {@code Authorization: Bearer token} on every request, as a script does; null stops it. */
    public ApiClient withBearer(String token) {
        bearer = token;
        return this;
    }

    /** Sends every request from this client address (MockMvc's default is 127.0.0.1). */
    public ApiClient from(String address) {
        remoteAddr = address;
        return this;
    }

    public Response signOn(String userId, String password) {
        return post("/api/auth/sign-on", json(Map.of("userId", userId, "password", password)));
    }

    public Response changePassword(String userId, String current, String next) {
        Map<String, String> body = new LinkedHashMap<>();
        if (userId != null) {
            body.put("userId", userId);
        }
        body.put("currentPassword", current);
        body.put("newPassword", next);
        return post("/api/auth/password", json(body));
    }

    public Response refresh() {
        return post("/api/auth/refresh", null);
    }

    public Response signOff() {
        return post("/api/auth/sign-off", null);
    }

    public Response me() {
        return get("/api/auth/me");
    }

    public Response post(String path, String body) {
        return unsafe(path, MockMvcRequestBuilders.post(path), body, null);
    }

    public Response put(String path, String body, String ifMatch) {
        return unsafe(path, MockMvcRequestBuilders.put(path), body, ifMatch);
    }

    public Response delete(String path, String ifMatch) {
        return unsafe(path, MockMvcRequestBuilders.delete(path), null, ifMatch);
    }

    private Response unsafe(String path, MockHttpServletRequestBuilder req, String body, String ifMatch) {
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (ifMatch != null) {
            req.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        Stored xsrf = jar.get("XSRF-TOKEN");
        if (sendCsrfHeader && xsrf != null) {
            req.header("X-XSRF-TOKEN", xsrf.value());
        }
        return send(path, req);
    }

    public Response get(String path) {
        return send(path, MockMvcRequestBuilders.get(path));
    }

    /** A GET whose {@code {name}} variables are expanded and percent-encoded, as a browser encodes a form value. */
    public Response get(String template, Object... vars) {
        return send(template, MockMvcRequestBuilders.get(template, vars));
    }

    public String cookieValue(String name) {
        Stored s = jar.get(name);
        return s == null ? null : s.value();
    }

    public void setCookie(String name, String value, String path) {
        jar.put(name, new Stored(value, path));
    }

    public void dropCookie(String name) {
        jar.remove(name);
    }

    public static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    private Response send(String path, MockHttpServletRequestBuilder req) {
        List<Cookie> cookies = new ArrayList<>();
        jar.forEach((name, c) -> {
            if (path.startsWith(c.path())) {
                cookies.add(new Cookie(name, c.value()));
            }
        });
        if (!cookies.isEmpty()) {
            req.cookie(cookies.toArray(Cookie[]::new));
        }
        if (bearer != null) {
            req.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        if (remoteAddr != null) {
            req.with(r -> {
                r.setRemoteAddr(remoteAddr);
                return r;
            });
        }
        try {
            MockHttpServletResponse res = mvc.perform(req).andReturn().getResponse();
            List<SetCookie> set = res.getHeaders(HttpHeaders.SET_COOKIE).stream().map(ApiClient::parse).toList();
            for (SetCookie c : set) {
                if ("0".equals(c.attr("Max-Age"))) {
                    jar.remove(c.name());
                } else {
                    jar.put(c.name(), new Stored(c.value(), c.attr("Path") == null ? "/" : c.attr("Path")));
                }
            }
            String content = res.getContentAsString();
            JsonNode body = content.isBlank() ? JSON.missingNode() : JSON.readTree(content);
            return new Response(res.getStatus(), body, set, res);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static SetCookie parse(String header) {
        String[] parts = header.split(";");
        String[] nv = parts[0].split("=", 2);
        Map<String, String> attrs = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String[] kv = parts[i].trim().split("=", 2);
            attrs.put(kv[0].toLowerCase(Locale.ROOT), kv.length > 1 ? kv[1] : "");
        }
        return new SetCookie(nv[0].trim(), nv.length > 1 ? nv[1] : "", attrs);
    }
}
