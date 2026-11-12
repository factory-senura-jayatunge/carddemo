package org.carddemo.batch.internal;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /internal/*: a loopback caller with the bearer token, compared in constant time; anything else
 * gets 401 UNAUTHENTICATED. The token is never logged.
 */
class InternalTokenFilter extends OncePerRequestFilter {

    static final String UNAUTHENTICATED = "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
            + "\"code\":\"UNAUTHENTICATED\",\"message\":\"A valid bearer token is required.\",\"field\":null,"
            + "\"severity\":\"error\"}";

    private final byte[] expected;

    InternalTokenFilter(InternalApiProperties properties) {
        this.expected = properties.hasToken() ? sha256(properties.token()) : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!authorized(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(Problems.MEDIA_TYPE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(UNAUTHENTICATED);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean authorized(HttpServletRequest request) {
        if (expected == null || !loopback(request.getRemoteAddr())) {
            return false;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        // Equal-length digests, so the comparison takes the same time whatever the sent token is.
        return MessageDigest.isEqual(expected, sha256(header.substring(7).trim()));
    }

    private static boolean loopback(String address) {
        // The servlet container reports the peer as an IP literal, which getByName parses without a lookup.
        try {
            return address != null && !address.isEmpty() && InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
