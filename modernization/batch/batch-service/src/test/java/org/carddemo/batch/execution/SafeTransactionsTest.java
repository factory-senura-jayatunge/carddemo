package org.carddemo.batch.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.UndeclaredThrowableException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The batch template keeps Spring's transaction behaviour and the callback's exception, and only
 * a failed rollback differs: neither its log nor what it throws repeats a driver message.
 */
@ExtendWith(OutputCaptureExtension.class)
class SafeTransactionsTest {

    /** Stands in for a statement value that a driver message quotes. */
    private static final String SENTINEL = "quoted-row-value-31415";

    @Test
    void aSuccessfulRollbackRethrowsTheCallbacksExceptionAsItIs(CapturedOutput output) {
        RecordingManager manager = new RecordingManager(false);
        DuplicateKeyException failure = duplicate();

        Throwable thrown = catchThrowable(() -> new SafeTransactions(manager).executeWithoutResult(s -> {
            throw failure;
        }));

        assertThat(thrown).isSameAs(failure);
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isZero();
        assertThat(output).doesNotContain(SENTINEL);
    }

    @Test
    void aFailedRollbackLogsAndThrowsClassesAndErrorNumbersOnly(CapturedOutput output) {
        RecordingManager manager = new RecordingManager(true);

        Throwable thrown = catchThrowable(() -> new SafeTransactions(manager).executeWithoutResult(s -> {
            throw duplicate();
        }));

        assertThat(thrown).isInstanceOf(TransactionSystemException.class);
        TransactionSystemException failure = (TransactionSystemException) thrown;
        assertThat(failure).hasMessage("Rollback failed: TransactionSystemException < SQLException SQL error 0 (state null)");
        assertThat(stackTrace(failure)).doesNotContain(SENTINEL);
        assertThat(failure.getApplicationException()).isInstanceOf(DataAccessException.class);
        assertThat(stackTrace(failure.getApplicationException())).doesNotContain(SENTINEL)
                .contains(": DuplicateKeyException").contains(": SQLException SQL error 2627 (state 23000)");
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(output).doesNotContain(SENTINEL)
                .contains("Application exception overridden by rollback exception: DuplicateKeyException < SQLException"
                        + " SQL error 2627 (state 23000)");
    }

    /** Spring's own template logs the callback's exception with its messages: the sentinel is seen. */
    @Test
    void springsTemplateWouldLogTheDriverMessage(CapturedOutput output) {
        Throwable thrown = catchThrowable(() -> new TransactionTemplate(new RecordingManager(true))
                .executeWithoutResult(s -> {
                    throw duplicate();
                }));

        assertThat(thrown).isInstanceOf(TransactionSystemException.class);
        assertThat(output).contains(SENTINEL);
    }

    @Test
    void theDefinitionAndTheSynchronizationsAreTheManagersAsWithSpringsTemplate() {
        RecordingManager manager = new RecordingManager(false);
        SafeTransactions template = new SafeTransactions(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        List<String> events = new ArrayList<>();

        String result = template.execute(s -> {
            TransactionSynchronizationManager.registerSynchronization(new Recording(events));
            return "done";
        });
        Throwable thrown = catchThrowable(() -> template.executeWithoutResult(s -> {
            TransactionSynchronizationManager.registerSynchronization(new Recording(events));
            throw new IllegalStateException("bug");
        }));

        assertThat(result).isEqualTo("done");
        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("bug");
        assertThat(manager.isolation).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(manager.commits).isEqualTo(1);
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(events).containsExactly("beforeCommit", "afterCompletion " + TransactionSynchronization.STATUS_COMMITTED,
                "afterCompletion " + TransactionSynchronization.STATUS_ROLLED_BACK);
    }

    @Test
    void anUndeclaredCheckedExceptionIsWrappedAsItIs() {
        RecordingManager manager = new RecordingManager(false);
        IOException checked = new IOException("io");

        Throwable thrown = catchThrowable(() -> new SafeTransactions(manager).executeWithoutResult(s -> sneaky(checked)));

        assertThat(thrown).isInstanceOf(UndeclaredThrowableException.class);
        assertThat(((UndeclaredThrowableException) thrown).getUndeclaredThrowable()).isSameAs(checked);
        assertThat(manager.rollbacks).isEqualTo(1);
    }

    private static DuplicateKeyException duplicate() {
        return new DuplicateKeyException("PreparedStatementCallback; duplicate key value is (" + SENTINEL + ")",
                new SQLException("Cannot insert duplicate key. The duplicate key value is (" + SENTINEL + ").",
                        "23000", 2627));
    }

    private static String stackTrace(Throwable t) {
        StringWriter s = new StringWriter();
        t.printStackTrace(new PrintWriter(s));
        return s.toString();
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneaky(Throwable t) throws E {
        throw (E) t;
    }

    private record Recording(List<String> events) implements TransactionSynchronization {
        @Override
        public void beforeCommit(boolean readOnly) {
            events.add("beforeCommit");
        }

        @Override
        public void afterCompletion(int status) {
            events.add("afterCompletion " + status);
        }
    }

    /** A manager without a connection whose rollback can fail with a driver message. */
    private static final class RecordingManager extends AbstractPlatformTransactionManager {
        private final boolean failRollback;
        int commits;
        int rollbacks;
        int isolation = Integer.MIN_VALUE;

        RecordingManager(boolean failRollback) {
            this.failRollback = failRollback;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            isolation = definition.getIsolationLevel();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
            if (failRollback) {
                throw new TransactionSystemException("Could not roll back JDBC transaction",
                        new SQLException("connection reset while sending " + SENTINEL));
            }
        }
    }
}
