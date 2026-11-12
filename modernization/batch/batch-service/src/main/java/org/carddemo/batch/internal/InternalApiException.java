package org.carddemo.batch.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * An answer of the internal API other than a success, rendered as problem details. The message is
 * a fixed text: no record value and no exception text reaches the caller.
 */
public final class InternalApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String field;
    private final Map<String, Object> extras = new LinkedHashMap<>();
    private final HttpHeaders headers = new HttpHeaders();

    public InternalApiException(HttpStatus status, String code, String message, String field) {
        super(code + ": " + message, null, false, false);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    static InternalApiException invalidRequest(String field, String message) {
        return new InternalApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, field);
    }

    static InternalApiException invalidParameter(String field, String message) {
        return new InternalApiException(HttpStatus.BAD_REQUEST, "INVALID_JOB_PARAMETERS", message, field);
    }

    public InternalApiException with(String name, Object value) {
        extras.put(name, value);
        return this;
    }

    public InternalApiException withAll(Map<String, ?> more) {
        extras.putAll(more);
        return this;
    }

    public InternalApiException header(String name, String value) {
        headers.add(name, value);
        return this;
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

    public HttpHeaders headers() {
        return headers;
    }

    public Map<String, Object> body() {
        return Problems.body(status, code, getMessage().substring(code.length() + 2), field, extras);
    }
}
