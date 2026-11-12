package org.carddemo.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * When a rollback fails, Spring's template logs the callback's exception with its causes before
 * any handler runs. A driver exception there must not carry a value into the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class SafeTransactionTemplateTest {

    static final String SENTINEL = "Qk9-rollback-sentinel";
    static final String OVERRIDDEN = "Application exception overridden by rollback exception";

    /** A transaction manager whose rollback fails, or succeeds, as the test asks; it counts both. */
    static final class Manager extends AbstractPlatformTransactionManager {
        final boolean rollbackFails;
        final List<String> calls = new ArrayList<>();

        Manager(boolean rollbackFails) {
            this.rollbackFails = rollbackFails;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            calls.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            calls.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            calls.add("rollback");
            if (rollbackFails) {
                throw new TransactionSystemException("rollback failed: connection closed");
            }
        }
    }

    /** The driver's error 2628, which quotes the value it truncated, as Spring translates it. */
    static UncategorizedSQLException truncation(int errorCode) {
        SQLException driver = new SQLException("String or binary data would be truncated in table 'core.customer',"
                + " column 'govt_issued_id'. Truncated value: '" + SENTINEL + "'.", "22001", errorCode);
        return new UncategorizedSQLException("update", "UPDATE core.customer SET govt_issued_id = '" + SENTINEL + "'",
                driver);
    }

    static String printed(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    static SQLException sqlCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    @Test
    void thePlainTemplateLogsTheDriverMessageWhenTheRollbackFails(CapturedOutput output) {
        Throwable thrown = catchThrowable(() -> new TransactionTemplate(new Manager(true)).execute(status -> {
            throw truncation(2628);
        }));
        assertThat(thrown).isInstanceOf(TransactionSystemException.class);
        assertThat(output.getAll()).as("the probe reaches the framework's log line").contains(OVERRIDDEN, SENTINEL);
    }

    @Test
    void aDriverExceptionReachesNoLogWhenTheRollbackFails(CapturedOutput output) {
        Manager manager = new Manager(true);
        Throwable thrown = catchThrowable(() -> new SafeTransactionTemplate(manager).execute(status -> {
            throw truncation(2628);
        }));

        assertThat(manager.calls).containsExactly("begin", "rollback");
        assertThat(thrown).isInstanceOf(TransactionSystemException.class);
        Throwable application = ((TransactionSystemException) thrown).getApplicationException();
        assertThat(application).isInstanceOf(DataAccessException.class);
        SQLException sql = sqlCause(application);
        assertThat(sql.getErrorCode()).isEqualTo(2628);
        assertThat(sql.getSQLState()).isEqualTo("22001");
        assertThat(SafeErrors.describe(application))
                .isEqualTo("UncategorizedSQLException < SQLException SQL error 2628 (state 22001)");
        assertThat(printed(thrown)).doesNotContain(SENTINEL, "Truncated", "UPDATE");
        assertThat(output.getAll()).contains(OVERRIDDEN, "UncategorizedSQLException",
                        "SQLException SQL error 2628 (state 22001)")
                .doesNotContain(SENTINEL, "Truncated", "UPDATE core");
    }

    @Test
    void aDomainProblemKeepsItsTypeAndLosesTheDriverMessage(CapturedOutput output) {
        Manager manager = new Manager(true);
        Throwable thrown = catchThrowable(() -> new SafeTransactionTemplate(manager).execute(status -> {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.ACCOUNT_SAVE_FAILED, "acctId",
                    truncation(2628));
        }));

        Throwable application = ((TransactionSystemException) thrown).getApplicationException();
        assertThat(application).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(e.code()).isEqualTo(MessageCatalog.ACCOUNT_SAVE_FAILED.code());
            assertThat(e.field()).isEqualTo("acctId");
        });
        assertThat(sqlCause(application).getErrorCode()).isEqualTo(2628);
        assertThat(output.getAll()).contains(OVERRIDDEN, "SQL error 2628").doesNotContain(SENTINEL, "Truncated");
    }

    @Test
    void afterARolledBackCallbackTheCallerStillMapsByTypeAndErrorNumber(CapturedOutput output) {
        Manager manager = new Manager(false);
        Throwable thrown = catchThrowable(() -> new SafeTransactionTemplate(manager).executeWithoutResult(status -> {
            throw truncation(1222);
        }));

        assertThat(manager.calls).containsExactly("begin", "rollback");
        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(sqlCause(thrown).getErrorCode()).as("a lock timeout is still recognised").isEqualTo(1222);
        assertThat(printed(thrown)).doesNotContain(SENTINEL);
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }

    /** A message-free domain exception, as a service's own control-flow exception is. */
    static final class Replay extends RuntimeException implements SafeErrors.MessageFree {
        Replay(Throwable cause) {
            super("replay", cause == null ? null : SafeErrors.sanitized(cause));
        }
    }

    @Test
    void aMessageFreeExceptionIsThrownAsItIs() {
        Replay replay = new Replay(truncation(2627));
        ApiException plain = new ApiException(HttpStatus.CONFLICT, MessageCatalog.ACCOUNT_LOCK_FAILED, "acctId");
        for (RuntimeException e : List.of(replay, plain)) {
            Throwable thrown = catchThrowable(() -> new SafeTransactionTemplate(new Manager(false)).execute(status -> {
                throw e;
            }));
            assertThat(thrown).isSameAs(e);
        }
        assertThat(printed(replay)).doesNotContain(SENTINEL).contains("SQL error 2627");
    }

    @Test
    void aCyclicOrDeepChainEndsAndHoldsNoMessage(CapturedOutput output) {
        RuntimeException a = new RuntimeException(SENTINEL);
        RuntimeException b = new RuntimeException(SENTINEL, a);
        a.initCause(b);
        RuntimeException deep = new IllegalStateException(SENTINEL);
        for (int i = 0; i < 40; i++) {
            deep = new IllegalStateException(SENTINEL + i, deep);
        }
        for (RuntimeException e : List.of(b, deep)) {
            Throwable thrown = catchThrowable(() -> new SafeTransactionTemplate(new Manager(true)).execute(status -> {
                throw e;
            }));
            assertThat(printed(thrown)).doesNotContain(SENTINEL);
        }
        assertThat(output.getAll()).contains(OVERRIDDEN).doesNotContain(SENTINEL);
    }

    @Test
    void aSuppressedExceptionCannotTurnADomainProblemIntoAnotherType() {
        ApiException problem = new ApiException(HttpStatus.CONFLICT, MessageCatalog.ACCOUNT_LOCK_FAILED, "acctId");
        problem.addSuppressed(new IllegalStateException(SENTINEL));
        Throwable thrown = catchThrowable(() -> new SafeTransactionTemplate(new Manager(false)).execute(status -> {
            throw problem;
        }));
        assertThat(thrown).isSameAs(problem);
        assertThat(printed(thrown)).doesNotContain(SENTINEL);
    }

    @Test
    void aTransactionExceptionStaysOneAndLosesItsMessage(CapturedOutput output) {
        Throwable thrown = catchThrowable(() -> new SafeTransactionTemplate(new Manager(false)).execute(status -> {
            throw new TransactionTimedOutException(SENTINEL);
        }));
        assertThat(thrown).isInstanceOf(TransactionException.class).hasMessage("TransactionTimedOutException");
        assertThat(printed(thrown)).doesNotContain(SENTINEL);

        Throwable failed = catchThrowable(() -> new SafeTransactionTemplate(new Manager(true)).execute(status -> {
            throw new TransactionTimedOutException(SENTINEL);
        }));
        assertThat(((TransactionSystemException) failed).getApplicationException()).isInstanceOf(TransactionException.class);
        assertThat(output.getAll()).contains(OVERRIDDEN).doesNotContain(SENTINEL);
    }

    @Test
    void anErrorStaysOneAndLosesItsMessage(CapturedOutput output) {
        Manager manager = new Manager(false);
        Throwable thrown = catchThrowable(() -> new SafeTransactionTemplate(manager).execute(status -> {
            throw new AssertionError(SENTINEL, truncation(2628));
        }));
        assertThat(manager.calls).containsExactly("begin", "rollback");
        assertThat(thrown).isInstanceOf(Error.class).hasMessage("AssertionError");
        assertThat(sqlCause(thrown).getErrorCode()).isEqualTo(2628);
        assertThat(printed(thrown)).doesNotContain(SENTINEL);

        Throwable failed = catchThrowable(() -> new SafeTransactionTemplate(new Manager(true)).execute(status -> {
            throw new AssertionError(SENTINEL, truncation(2628));
        }));
        assertThat(((TransactionSystemException) failed).getApplicationException()).isInstanceOf(Error.class);
        assertThat(output.getAll()).contains(OVERRIDDEN, "SQL error 2628").doesNotContain(SENTINEL, "Truncated");
    }

    @Test
    void aSuccessfulCallbackCommitsAndReturnsItsResult() {
        Manager manager = new Manager(false);
        String result = new SafeTransactionTemplate(manager).execute(status -> "done");
        assertThat(result).isEqualTo("done");
        assertThat(manager.calls).containsExactly("begin", "commit");
    }
}
