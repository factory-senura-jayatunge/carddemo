package org.carddemo.batch.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLTransientConnectionException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * A request that gets no database connection is a retryable 503, not a 500; any other failure is
 * logged without the driver's message.
 */
@ExtendWith(OutputCaptureExtension.class)
class InternalExceptionHandlerTest {

    private final InternalExceptionHandler handler = new InternalExceptionHandler();

    @Test
    void aPoolTimeoutIsAnUnavailableDatabaseWithRetryAfter() {
        SQLTransientConnectionException timeout = new SQLTransientConnectionException("pool timed out");
        for (Exception e : new Exception[] {
                new CannotCreateTransactionException("Could not open JDBC Connection for transaction", timeout),
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", timeout),
                new IllegalStateException("wrapped", new CannotGetJdbcConnectionException("no connection"))}) {
            ResponseEntity<Map<String, Object>> answer = handler.other(e);
            assertThat(answer.getStatusCode().value()).isEqualTo(503);
            assertThat(answer.getHeaders().getFirst("Retry-After")).isEqualTo("5");
            assertThat(answer.getBody()).containsEntry("code", "DATABASE_UNAVAILABLE");
        }
    }

    @Test
    void anyOtherFailureStaysAnInternalError() {
        ResponseEntity<Map<String, Object>> answer = handler.other(new IllegalStateException("bug"));
        assertThat(answer.getStatusCode().value()).isEqualTo(500);
        assertThat(answer.getBody()).containsEntry("code", "INTERNAL_ERROR");
    }

    @Test
    void aFailureIsLoggedWithoutTheDriversMessage(CapturedOutput output) {
        ResponseEntity<Map<String, Object>> answer = handler.other(CommandFailureLogTest.driverFailure());

        assertThat(answer.getStatusCode().value()).isEqualTo(500);
        assertThat(output).doesNotContain("quoted-row-value")
                .contains("internal API request failed: DataIntegrityViolationException < SQLException SQL error 2627");
    }
}
