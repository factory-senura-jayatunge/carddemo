package org.carddemo.platform.web;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * RFC 9457 problem details with the CardDemo members {@code code}, {@code message} (the exact
 * catalogue text), {@code field} (where the screen puts the cursor, or null) and {@code severity}.
 * {@code field} is always present, null included, because the client moves the cursor on it.
 */
@JsonPropertyOrder({"type", "title", "status", "code", "message", "field", "severity"})
@JsonInclude(JsonInclude.Include.ALWAYS)
public final class ApiProblem {

    public static final MediaType MEDIA_TYPE = MediaType.APPLICATION_PROBLEM_JSON;

    private final int status;
    private final String title;
    private final String code;
    private final String message;
    private final String field;
    private final Severity severity;
    private final Map<String, Object> extras = new LinkedHashMap<>();

    private ApiProblem(HttpStatus status, String code, String message, String field, Severity severity) {
        this.status = status.value();
        this.title = status.getReasonPhrase();
        this.code = code;
        this.message = message;
        this.field = field;
        this.severity = severity;
    }

    public static ApiProblem of(HttpStatus status, Message catalogMessage, String text, String field) {
        return new ApiProblem(status, catalogMessage.code(), text, field, catalogMessage.severity());
    }

    public static ApiProblem of(HttpStatus status, Message catalogMessage, String field) {
        return of(status, catalogMessage, catalogMessage.render(), field);
    }

    ApiProblem withAll(Map<String, Object> more) {
        extras.putAll(more);
        return this;
    }

    @JsonProperty("type")
    public String type() {
        return "about:blank";
    }

    @JsonProperty("title")
    public String title() {
        return title;
    }

    @JsonProperty("status")
    public int status() {
        return status;
    }

    @JsonProperty("code")
    public String code() {
        return code;
    }

    @JsonProperty("message")
    public String message() {
        return message;
    }

    @JsonProperty("field")
    public String field() {
        return field;
    }

    @JsonProperty("severity")
    public Severity severity() {
        return severity;
    }

    @JsonAnyGetter
    public Map<String, Object> extras() {
        return Collections.unmodifiableMap(extras);
    }

    /** For responses written outside Spring MVC (security filters), where no message converter runs. */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{")
                .append("\"type\":").append(Json.quote(type()))
                .append(",\"title\":").append(Json.quote(title))
                .append(",\"status\":").append(status)
                .append(",\"code\":").append(Json.quote(code))
                .append(",\"message\":").append(Json.quote(message))
                .append(",\"field\":").append(Json.quote(field))
                .append(",\"severity\":").append(Json.quote(severity.key()));
        extras.forEach((name, value) -> sb.append(',').append(Json.quote(name)).append(':').append(scalar(value)));
        return sb.append('}').toString();
    }

    private static String scalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof BigDecimal || value instanceof CharSequence) {
            return Json.quote(value.toString());
        }
        throw new IllegalArgumentException("problem extra of type " + value.getClass().getName() + " cannot be written here");
    }
}
