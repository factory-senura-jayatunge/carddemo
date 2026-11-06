package org.carddemo.platform.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * The access token from the {@code cd_at} cookie (the SPA), else from {@code Authorization: Bearer}
 * (scripts and tests).
 */
public class CookieBearerTokenResolver implements BearerTokenResolver {

    private final DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        String cookie = cookieToken(request);
        return cookie != null ? cookie : header.resolve(request);
    }

    static String cookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (CarddemoSecurityProperties.ACCESS_TOKEN_COOKIE.equals(c.getName()) && c.getValue() != null
                        && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    static boolean hasBearerHeader(HttpServletRequest request) {
        String value = request.getHeader("Authorization");
        return value != null && value.regionMatches(true, 0, "Bearer ", 0, 7);
    }
}
