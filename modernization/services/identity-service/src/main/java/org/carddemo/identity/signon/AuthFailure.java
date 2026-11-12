package org.carddemo.identity.signon;

import java.time.Duration;

import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.SafeErrors;
import org.springframework.http.HttpStatus;

/**
 * A refused auth call: the HTTP status, the catalogue message (code, text, severity) and the
 * field to focus. Unlike the platform's ApiException it can clear the auth cookies.
 */
public class AuthFailure extends RuntimeException implements SafeErrors.MessageFree {

    private final HttpStatus status;
    private final Message catalogMessage;
    private final String field;
    private final Duration retryAfter;
    private final boolean clearCookies;

    public AuthFailure(HttpStatus status, Message catalogMessage, String field, Duration retryAfter,
            boolean clearCookies) {
        super(catalogMessage.text(), null, false, false);
        this.status = status;
        this.catalogMessage = catalogMessage;
        this.field = field;
        this.retryAfter = retryAfter;
        this.clearCookies = clearCookies;
    }

    public static AuthFailure of(HttpStatus status, Message catalogMessage, String field) {
        return new AuthFailure(status, catalogMessage, field, null, false);
    }

    public static AuthFailure sessionExpired() {
        return new AuthFailure(HttpStatus.UNAUTHORIZED, MessageCatalog.SESSION_EXPIRED, null, null, true);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return catalogMessage.code();
    }

    public String field() {
        return field;
    }

    public String severity() {
        return catalogMessage.severity().key();
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean clearCookies() {
        return clearCookies;
    }
}
