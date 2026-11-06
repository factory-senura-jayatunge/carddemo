package org.carddemo.identity.signon;

import java.time.Duration;

import org.springframework.http.HttpStatus;

/**
 * A refused auth call: the HTTP status, a stable code, the screen message, the field to focus
 * and the message colour ({@code error}, {@code info}).
 */
public class AuthFailure extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String field;
    private final String severity;
    private final Duration retryAfter;
    private final boolean clearCookies;

    public AuthFailure(HttpStatus status, String code, String message, String field, String severity,
            Duration retryAfter, boolean clearCookies) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.field = field;
        this.severity = severity;
        this.retryAfter = retryAfter;
        this.clearCookies = clearCookies;
    }

    public static AuthFailure of(HttpStatus status, String code, String message, String field) {
        return new AuthFailure(status, code, message, field, "error", null, false);
    }

    public static AuthFailure sessionExpired() {
        return new AuthFailure(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", Messages.SESSION_EXPIRED, null, "error",
                null, true);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }

    public String severity() {
        return severity;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean clearCookies() {
        return clearCookies;
    }
}
