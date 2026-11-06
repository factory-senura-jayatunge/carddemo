package org.carddemo.identity.token;

import java.util.Set;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * The access token from cookie {@code cd_at}, else from {@code Authorization: Bearer} (scripts
 * and tests). Only the endpoints that need a session read it: an expired cookie sent along with
 * sign-on or refresh must not turn those calls into a 401.
 */
public final class SessionTokenResolver implements BearerTokenResolver {

    private static final Set<String> SESSION_PATHS = Set.of("/api/auth/me", "/api/features");

    private final DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return SESSION_PATHS.contains(path) ? fromRequest(request) : null;
    }

    /** The token wherever the request carries it, for endpoints where a session is optional. */
    public String fromRequest(HttpServletRequest request) {
        String cookie = cookie(request, AuthCookies.ACCESS);
        return cookie != null ? cookie : header.resolve(request);
    }

    public static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (name.equals(c.getName()) && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        return null;
    }
}
