package org.carddemo.batch.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/** The platform ApiProblem shape: type, title, status, code, message, field, severity, then the extras. */
final class Problems {

    static final String MEDIA_TYPE = "application/problem+json";

    private Problems() {
    }

    static Map<String, Object> body(HttpStatus status, String code, String message, String field,
            Map<String, Object> extras) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("code", code);
        body.put("message", message);
        body.put("field", field);
        body.put("severity", "error");
        body.putAll(extras);
        return body;
    }
}
