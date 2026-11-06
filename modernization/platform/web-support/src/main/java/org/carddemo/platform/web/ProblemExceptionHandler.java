package org.carddemo.platform.web;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns every exception that leaves a controller into an {@link ApiProblem}. Only catalogue texts
 * reach the client; the technical cause is logged with the correlation ID (D-58).
 */
@RestControllerAdvice
public class ProblemExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiProblem> api(ApiException e) {
        if (e.status().is5xxServerError()) {
            log.error("{} {}", e.status().value(), e.code(), e.getCause() != null ? e.getCause() : e);
        } else {
            log.debug("{} {}", e.status().value(), e.code());
        }
        return ResponseEntity.status(e.status()).headers(e.headers()).contentType(ApiProblem.MEDIA_TYPE).body(e.toProblem());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiProblem> accessDenied(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, MessageCatalog.ACCESS_DENIED);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiProblem> unauthenticated(AuthenticationException e) {
        return problem(HttpStatus.UNAUTHORIZED, MessageCatalog.UNAUTHENTICATED);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiProblem> unreadable(HttpMessageNotReadableException e) {
        log.debug("unreadable request body", e);
        return problem(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> other(Exception e) {
        if (e instanceof ErrorResponse response) {
            HttpStatusCode code = response.getStatusCode();
            HttpStatus status = HttpStatus.resolve(code.value());
            if (status != null && status.is4xxClientError()) {
                log.debug("{} {}", status.value(), e.toString());
                return ResponseEntity.status(status).headers(response.getHeaders())
                        .contentType(ApiProblem.MEDIA_TYPE).body(ApiProblem.of(status, catalogFor(status), null));
            }
        }
        log.error("unexpected error", e);
        // The ID lets support find the logged cause; the cause itself never reaches the client.
        ApiProblem body = ApiProblem.of(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.INTERNAL_ERROR, null);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            body.withAll(Map.of("correlationId", correlationId));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(ApiProblem.MEDIA_TYPE).body(body);
    }

    private static Message catalogFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> MessageCatalog.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> MessageCatalog.METHOD_NOT_ALLOWED;
            default -> MessageCatalog.INVALID_REQUEST;
        };
    }

    private static ResponseEntity<ApiProblem> problem(HttpStatus status, Message message) {
        return ResponseEntity.status(status).contentType(ApiProblem.MEDIA_TYPE).body(ApiProblem.of(status, message, null));
    }
}
