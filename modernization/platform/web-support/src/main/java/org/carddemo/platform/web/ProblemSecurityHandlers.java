package org.carddemo.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.csrf.CsrfException;

/** 401 {@code UNAUTHENTICATED} and 403 {@code ACCESS_DENIED} as problem details, from the security filters. */
public final class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, HttpStatus.UNAUTHORIZED, MessageCatalog.UNAUTHENTICATED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        if (e instanceof CsrfException) {
            // The CSRF filter runs before authentication: a request with no token at all is
            // unauthenticated first, which is what the client must act on (sign on again).
            if (CookieBearerTokenResolver.cookieToken(request) == null && !CookieBearerTokenResolver.hasBearerHeader(request)) {
                commence(request, response, null);
                return;
            }
            write(response, HttpStatus.FORBIDDEN, MessageCatalog.CSRF_REJECTED);
            return;
        }
        write(response, HttpStatus.FORBIDDEN, MessageCatalog.ACCESS_DENIED);
    }

    static void write(HttpServletResponse response, HttpStatus status, Message message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(ApiProblem.MEDIA_TYPE.toString());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ApiProblem.of(status, message, null).toJson());
    }
}
