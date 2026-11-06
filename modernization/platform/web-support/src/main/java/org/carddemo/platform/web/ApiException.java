package org.carddemo.platform.web;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * A business outcome that ends the request with a catalogue message, rendered as problem details
 * by {@link ProblemExceptionHandler}. Throw it from the step that fails, so the first failing check
 * in the spec's order is the one the client sees.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Message catalogMessage;
    private final String text;
    private final String field;
    private final Map<String, Object> extras = new LinkedHashMap<>();
    private final HttpHeaders headers = new HttpHeaders();

    public ApiException(HttpStatus status, Message message, String field, String... args) {
        this(status, message, field, null, args);
    }

    public ApiException(HttpStatus status, Message message, String field, Throwable cause, String... args) {
        super(Objects.requireNonNull(message, "message").code() + ": " + message.render(args), cause);
        this.status = Objects.requireNonNull(status, "status");
        this.catalogMessage = message;
        this.text = message.render(args);
        this.field = field;
    }

    /**
     * Adds a body property next to code, message, field and severity. Amounts travel as decimal
     * strings, so a {@link BigDecimal} is stored as its plain string.
     */
    public ApiException with(String name, Object value) {
        extras.put(name, value instanceof BigDecimal d ? d.toPlainString() : value);
        return this;
    }

    public ApiException header(String name, String value) {
        headers.add(name, value);
        return this;
    }

    public HttpStatus status() {
        return status;
    }

    public Message catalogMessage() {
        return catalogMessage;
    }

    public String code() {
        return catalogMessage.code();
    }

    public String text() {
        return text;
    }

    public String field() {
        return field;
    }

    public Severity severity() {
        return catalogMessage.severity();
    }

    public Map<String, Object> extras() {
        return Collections.unmodifiableMap(extras);
    }

    public HttpHeaders headers() {
        return HttpHeaders.readOnlyHttpHeaders(headers);
    }

    public ApiProblem toProblem() {
        return ApiProblem.of(status, catalogMessage, text, field).withAll(extras);
    }
}
