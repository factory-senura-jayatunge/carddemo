package org.carddemo.batch.internal;

import java.sql.SQLTransientConnectionException;
import java.util.Map;

import org.carddemo.batch.execution.SafeFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every answer of the internal API that is not a success is problem details; causes are only
 * logged, by class and SQL error number, because a driver message quotes statement values.
 */
@RestControllerAdvice
class InternalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InternalExceptionHandler.class);
    private static final MediaType PROBLEM = MediaType.parseMediaType(Problems.MEDIA_TYPE);
    private static final String NO_CONNECTION_RETRY_AFTER = "5";

    @ExceptionHandler(InternalApiException.class)
    ResponseEntity<Map<String, Object>> api(InternalApiException e) {
        log.debug("{} {}", e.status().value(), e.code());
        return ResponseEntity.status(e.status()).headers(e.headers()).contentType(PROBLEM).body(e.body());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request body is not valid JSON.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception e) {
        if (e instanceof ErrorResponse response) {
            HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
            if (status != null && status.is4xxClientError()) {
                String code = switch (status) {
                    case NOT_FOUND -> "NOT_FOUND";
                    case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
                    default -> "INVALID_REQUEST";
                };
                return problem(status, code, "The request is not valid.");
            }
        }
        if (noConnection(e)) {
            // Retryable like any 5xx (contract section 5); the pool or the database is busy or down.
            log.warn("internal API request found no database connection: {}", e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", NO_CONNECTION_RETRY_AFTER)
                    .contentType(PROBLEM).body(Problems.body(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
                            "batch-service could not get a database connection. Try again later.", null, Map.of()));
        }
        log.error("internal API request failed: {}", SafeFailures.describe(e), SafeFailures.sanitized(e));
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "batch-service could not answer the request.");
    }

    private static boolean noConnection(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CannotGetJdbcConnectionException || t instanceof CannotCreateTransactionException
                    || t instanceof SQLTransientConnectionException) {
                return true;
            }
        }
        return false;
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(PROBLEM).body(Problems.body(status, code, message, null, Map.of()));
    }
}
