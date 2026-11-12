package org.carddemo.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;

class SafeErrorsTest {

    static final String SENTINEL = "Zq7-sentinel";

    private static UncategorizedSQLException truncation() {
        SQLException driver = new SQLException("String or binary data would be truncated in table 'core.customer',"
                + " column 'govt_issued_id'. Truncated value: '" + SENTINEL + "'.", "22001", 2628);
        return new UncategorizedSQLException("update", "UPDATE core.customer SET govt_issued_id = '" + SENTINEL + "'",
                driver);
    }

    private static String printed(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    @Test
    void describeNamesClassesAndSqlCodesOnly() {
        assertThat(SafeErrors.describe(truncation()))
                .isEqualTo("UncategorizedSQLException < SQLException SQL error 2628 (state 22001)");
        assertThat(SafeErrors.describe(new IllegalStateException(SENTINEL))).isEqualTo("IllegalStateException");
    }

    @Test
    void sanitizedKeepsTheFramesAndDropsEveryMessage() {
        UncategorizedSQLException original = truncation();
        IllegalArgumentException suppressed = new IllegalArgumentException(SENTINEL);
        original.addSuppressed(suppressed);
        Throwable copy = SafeErrors.sanitized(original);

        assertThat(printed(original)).contains(SENTINEL);
        assertThat(printed(copy)).doesNotContain(SENTINEL, "Truncated", "UPDATE")
                .contains("UncategorizedSQLException", "SQLException SQL error 2628 (state 22001)",
                        "IllegalArgumentException");
        assertThat(copy.getStackTrace()).isEqualTo(original.getStackTrace());
        assertThat(copy.getCause().getStackTrace()).isEqualTo(original.getCause().getStackTrace());
        assertThat(copy.getSuppressed()).hasSize(1);
    }

    @Test
    void sanitizedKeepsTheKindAndTheSqlErrorNumber() {
        Throwable copy = SafeErrors.sanitized(truncation());
        assertThat(copy).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(copy.getCause()).isInstanceOfSatisfying(SQLException.class, sql -> {
            assertThat(sql.getErrorCode()).isEqualTo(2628);
            assertThat(sql.getSQLState()).isEqualTo("22001");
        });
        assertThat(SafeErrors.describe(copy)).isEqualTo(SafeErrors.describe(truncation()));
        assertThat(SafeErrors.describe(SafeErrors.sanitized(copy))).isEqualTo(SafeErrors.describe(truncation()));
        assertThat(SafeErrors.isMessageFree(copy)).isTrue();
        assertThat(SafeErrors.isMessageFree(truncation())).isFalse();
    }

    @Test
    void anApiExceptionHoldsOnlyAMessageFreeCause() {
        ApiException e = new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                MessageCatalog.ACCOUNT_SAVE_FAILED, "acctId", truncation());
        assertThat(SafeErrors.isMessageFree(e)).isTrue();
        assertThat(printed(e)).doesNotContain(SENTINEL, "Truncated", "UPDATE core");
        assertThat(SafeErrors.describe(e.getCause())).isEqualTo(SafeErrors.describe(truncation()));
    }

    @Test
    void aCyclicChainEnds() {
        RuntimeException a = new RuntimeException(SENTINEL);
        RuntimeException b = new RuntimeException(SENTINEL, a);
        a.initCause(b);
        assertThat(SafeErrors.describe(b)).isEqualTo("RuntimeException < RuntimeException");
        assertThat(printed(SafeErrors.sanitized(b))).doesNotContain(SENTINEL);
    }
}
