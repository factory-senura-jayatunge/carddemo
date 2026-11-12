package org.carddemo.batch.execution;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The archive step's session tag (batch-execution-contract.md section 4.5): the job execution ID
 * in {@code CONTEXT_INFO} of the connection bound to the step's transaction, so an operator can
 * tell which blocked session belongs to which execution. Observability only; nothing decides on it.
 *
 * <p>The tag is 24 bytes: the US-ASCII prefix {@code carddemo-exec-v1}, then the ID as 8 bytes
 * big-endian two's complement. A job execution ID is an integer from 0 to 2^63 - 1, and for every
 * such ID those bytes are the same as the unsigned ones, so every valid ID, 0 included, has a tag
 * and no reader chooses a sign. The tag is opaque here: the ID is encoded as bound, with no check
 * of its range. A session matches when its first 24 bytes equal the tag and any further bytes are
 * {@code 0x00}. NULL, an empty value and all zeros mean no tag.
 *
 * <p>The pool does not reset {@code CONTEXT_INFO}, so the value read before the tag is set back
 * after the work and read back. {@code SET CONTEXT_INFO} takes no NULL: a session that had none
 * gets the empty value {@code 0x}, which reads as no tag. Any other value is set back byte for
 * byte. When the value cannot be read, the tag cannot be set, the session already carries a tag,
 * or the prior value cannot be set back or reads back different (trailing zeros aside), the
 * transaction can no longer commit and the physical connection is evicted after the rollback,
 * before Spring hands it back to the pool.
 */
public final class ExecutionConnectionTag {

    public static final int LENGTH = 24;
    static final byte[] PREFIX = "carddemo-exec-v1".getBytes(US_ASCII);
    static final String READ = "SELECT CONTEXT_INFO()";
    static final String SET = "SET CONTEXT_INFO 0x";

    private static final Logger log = LoggerFactory.getLogger(ExecutionConnectionTag.class);
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private ExecutionConnectionTag() {
    }

    /** The tag of a job execution ID. */
    public static byte[] encode(long executionId) {
        return ByteBuffer.allocate(LENGTH).put(PREFIX).putLong(executionId).array();
    }

    /**
     * Runs {@code work} with the current transaction's connection of {@code dataSource} tagged with
     * {@code executionId}, then sets the prior value back. Fails with {@code CONNECTION_TAG_UNBOUND}
     * before any SQL when the ID is missing or no transaction of the data source is bound. When
     * {@code work} fails, its exception is rethrown, with a failed restore attached as suppressed.
     */
    public static <T> T within(DataSource dataSource, Long executionId, Supplier<T> work) {
        if (executionId == null) {
            throw new ExecutionTagException(ExecutionTagException.UNBOUND, "the step has no job execution ID", null);
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder holder)
                || !holder.isSynchronizedWithTransaction()) {
            throw new ExecutionTagException(ExecutionTagException.UNBOUND,
                    "no transaction of the data source is bound to the thread", null);
        }
        Connection con = DataSourceUtils.getConnection(dataSource);
        try {
            return tagged(dataSource, con, encode(executionId), work);
        } finally {
            DataSourceUtils.releaseConnection(con, dataSource);
        }
    }

    private static <T> T tagged(DataSource dataSource, Connection con, byte[] tag, Supplier<T> work) {
        byte[] prior;
        try {
            prior = read(con);
        } catch (SQLException | RuntimeException e) {
            throw evict(dataSource, con, ExecutionTagException.FAILED, "the session's value could not be read", e);
        }
        if (prior != null && prior.length >= PREFIX.length
                && Arrays.equals(prior, 0, PREFIX.length, PREFIX, 0, PREFIX.length)) {
            throw evict(dataSource, con, ExecutionTagException.STALE, "the session already carries an execution tag",
                    null);
        }
        try {
            set(con, tag);
        } catch (SQLException | RuntimeException e) {
            throw evict(dataSource, con, ExecutionTagException.FAILED, "the tag could not be set", e);
        }
        T result;
        try {
            result = work.get();
        } catch (RuntimeException | Error e) {
            ExecutionTagException notRestored = restore(dataSource, con, prior);
            if (notRestored != null) {
                e.addSuppressed(notRestored);
            }
            throw e;
        }
        ExecutionTagException notRestored = restore(dataSource, con, prior);
        if (notRestored != null) {
            throw notRestored;
        }
        return result;
    }

    /** Null when the prior value is set back and reads back; otherwise the failure, with the eviction scheduled. */
    private static ExecutionTagException restore(DataSource dataSource, Connection con, byte[] prior) {
        try {
            set(con, prior == null ? new byte[0] : prior);
            if (!sameValue(read(con), prior)) {
                return evict(dataSource, con, ExecutionTagException.NOT_RESTORED,
                        "the session's prior value did not read back", null);
            }
            return null;
        } catch (SQLException | RuntimeException e) {
            return evict(dataSource, con, ExecutionTagException.NOT_RESTORED,
                    "the session's prior value could not be set back", e);
        }
    }

    /** Whether two values are the same once trailing zeros are dropped; NULL is the empty value. */
    static boolean sameValue(byte[] a, byte[] b) {
        return Arrays.equals(withoutTrailingZeros(a), withoutTrailingZeros(b));
    }

    private static byte[] withoutTrailingZeros(byte[] value) {
        if (value == null) {
            return new byte[0];
        }
        int n = value.length;
        while (n > 0 && value[n - 1] == 0) {
            n--;
        }
        return Arrays.copyOf(value, n);
    }

    private static byte[] read(Connection con) throws SQLException {
        try (Statement statement = con.createStatement(); ResultSet rs = statement.executeQuery(READ)) {
            if (!rs.next()) {
                throw new IllegalStateException("CONTEXT_INFO() returned no row");
            }
            return rs.getBytes(1);
        }
    }

    /** A plain statement whose literal is built from bytes computed here, never from input. */
    private static void set(Connection con, byte[] value) throws SQLException {
        try (Statement statement = con.createStatement()) {
            statement.execute(SET + HEX.formatHex(value));
        }
    }

    private static ExecutionTagException evict(DataSource dataSource, Connection con, String code, String what,
            Throwable cause) {
        ExecutionTagException failure = new ExecutionTagException(code, what + sqlError(cause)
                + "; the connection is evicted", cause);
        log.error("{}", failure.getMessage());
        if (TransactionSynchronizationManager.getSynchronizations().stream().noneMatch(Eviction.class::isInstance)) {
            TransactionSynchronizationManager.registerSynchronization(new Eviction(dataSource, con));
        }
        return failure;
    }

    private static String sqlError(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return " (SQL error " + sql.getErrorCode() + ")";
            }
        }
        return cause == null ? "" : " (" + cause.getClass().getSimpleName() + ")";
    }

    /**
     * Spring runs afterCompletion after the rollback and before it resets and releases the
     * connection, so the connection is gone before the pool could hand it out again. Evicting at
     * the failure instead would close it under the rollback, whose failure would then replace the
     * step's own.
     */
    private record Eviction(DataSource dataSource, Connection connection) implements TransactionSynchronization {

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            // Only reached when a caller swallowed the failure; not a TransactionException, so Spring rolls back.
            throw new ExecutionTagException(ExecutionTagException.FAILED,
                    "a transaction whose connection is evicted cannot commit", null);
        }

        @Override
        public void afterCompletion(int status) {
            evictNow(dataSource, connection);
        }
    }

    static void evictNow(DataSource dataSource, Connection con) {
        try {
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
                // HikariCP 7.0.2's evictConnection does nothing, silently, on a closed pool or for a
                // connection that is not one of its proxies; for its proxy in use it closes the
                // physical connection at once and never returns it.
                if (!pool.isClosed() && con.getClass().getName().startsWith("com.zaxxer.hikari.")) {
                    pool.evictConnection(con);
                    return;
                }
            }
            con.abort(Runnable::run);
        } catch (SQLException | RuntimeException e) {
            log.error("the tagged connection could not be evicted: {}", SafeFailures.describe(e));
        }
    }
}
