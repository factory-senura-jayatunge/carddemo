package org.carddemo.identity.token;

import java.time.Duration;

import org.carddemo.identity.ApiSecurityProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** {@code cd_at} goes with every API call, {@code cd_rt} only to /api/auth. The SPA reads neither. */
@Component
public class AuthCookies {

    public static final String ACCESS = "cd_at";
    public static final String REFRESH = "cd_rt";
    public static final String ACCESS_PATH = "/api";
    public static final String REFRESH_PATH = "/api/auth";

    private final boolean secure;

    public AuthCookies(ApiSecurityProperties security) {
        this.secure = security.cookieSecure();
    }

    public HttpHeaders session(String access, Duration accessMaxAge, String refresh, Duration refreshMaxAge) {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.SET_COOKIE, cookie(ACCESS, access, ACCESS_PATH, accessMaxAge).toString());
        h.add(HttpHeaders.SET_COOKIE, cookie(REFRESH, refresh, REFRESH_PATH, refreshMaxAge).toString());
        return h;
    }

    public HttpHeaders cleared() {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.SET_COOKIE, cookie(ACCESS, "", ACCESS_PATH, Duration.ZERO).toString());
        h.add(HttpHeaders.SET_COOKIE, cookie(REFRESH, "", REFRESH_PATH, Duration.ZERO).toString());
        return h;
    }

    private ResponseCookie cookie(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(path)
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
    }
}
