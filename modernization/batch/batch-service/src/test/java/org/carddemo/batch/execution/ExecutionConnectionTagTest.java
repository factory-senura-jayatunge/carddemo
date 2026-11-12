package org.carddemo.batch.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The archive step's session tag (batch-execution-contract.md section 4.5) on fake sessions that
 * keep a CONTEXT_INFO value, under Spring's own transaction manager, and for the pool under a real
 * HikariCP pool. Each session records, in order, the statements and the transaction calls it gets.
 */
@ExtendWith(OutputCaptureExtension.class)
class ExecutionConnectionTagTest {

    /** "carddemo-exec-v1" in US-ASCII, written out by hand. */
    static final String PREFIX_HEX = "6361726464656D6F2D657865632D7631";
    /** In the message of every failure the fake driver throws; it must reach no exception message and no log. */
    static final String SENTINEL = "sentinel-driver-text-Q7";
    static final String ARCHIVE = "UPDATE core.[transaction] SET in_master = 0 WHERE in_master = 1";

    @Test
    void theTagIsThePrefixAndTheIdAsEightBytesBigEndian() {
        assertThat(hex(ExecutionConnectionTag.encode(0))).isEqualTo(PREFIX_HEX + "0000000000000000");
        assertThat(hex(ExecutionConnectionTag.encode(1))).isEqualTo(PREFIX_HEX + "0000000000000001");
        assertThat(hex(ExecutionConnectionTag.encode(0x0102030405060708L))).isEqualTo(PREFIX_HEX + "0102030405060708");
        assertThat(hex(ExecutionConnectionTag.encode(Long.MAX_VALUE))).isEqualTo(PREFIX_HEX + "7FFFFFFFFFFFFFFF");
        // Two's complement. -1 is no job execution ID, but the encoding checks no range.
        assertThat(hex(ExecutionConnectionTag.encode(-1))).isEqualTo(PREFIX_HEX + "FFFFFFFFFFFFFFFF");
        assertThat(ExecutionConnectionTag.encode(42)).hasSize(24);
    }

    @Test
    void trailingZerosAndNullAreTheSameValue() {
        byte[] foreign = {1, 2, 3, 0, 4};
        assertThat(ExecutionConnectionTag.sameValue(null, null)).isTrue();
        assertThat(ExecutionConnectionTag.sameValue(new byte[0], null)).isTrue();
        assertThat(ExecutionConnectionTag.sameValue(new byte[128], null)).isTrue();
        assertThat(ExecutionConnectionTag.sameValue(Arrays.copyOf(foreign, 128), foreign)).isTrue();
        assertThat(ExecutionConnectionTag.sameValue(new byte[] {1, 2, 3}, foreign)).isFalse();
        assertThat(ExecutionConnectionTag.sameValue(ExecutionConnectionTag.encode(0), null)).isFalse();
    }

    @Test
    void aSessionWithNoValueIsTaggedForTheWorkAndGetsTheEmptyValueBack() {
        for (boolean padded : new boolean[] {false, true}) {
            Fakes fakes = new Fakes();
            fakes.padTo128 = padded;

            assertThat(inTransaction(fakes.dataSource, () -> ExecutionConnectionTag.within(fakes.dataSource, 7L,
                    archive(fakes.dataSource)))).isEqualTo(3);

            Session session = fakes.only();
            assertThat(session.events).as("padded %s", padded).containsExactly("autoCommit:false", "read",
                    "set:" + PREFIX_HEX + "0000000000000007", "work", "set:", "read", "commit", "autoCommit:true",
                    "close");
            assertThat(ExecutionConnectionTag.sameValue(session.contextInfo, null)).isTrue();
        }
    }

    @Test
    void aForeignValueIsSetBackByteForByte() {
        byte[] foreign = new byte[40];
        for (int i = 0; i < foreign.length; i++) {
            foreign[i] = (byte) (0xC0 + i);
        }
        foreign[39] = 0;
        for (boolean padded : new boolean[] {false, true}) {
            Fakes fakes = new Fakes();
            fakes.padTo128 = padded;
            fakes.initial = padded ? Arrays.copyOf(foreign, 128) : foreign;

            inTransaction(fakes.dataSource, () -> ExecutionConnectionTag.within(fakes.dataSource, 7L,
                    archive(fakes.dataSource)));

            Session session = fakes.only();
            assertThat(session.events).as("padded %s", padded)
                    .contains("set:" + hex(padded ? Arrays.copyOf(foreign, 128) : foreign)).contains("commit")
                    .doesNotContain("abort");
            assertThat(session.contextInfo).isEqualTo(padded ? Arrays.copyOf(foreign, 128) : foreign);
        }
    }

    @Test
    void withoutATransactionOfTheDataSourceNoSqlRunsAndNoWork() {
        Fakes fakes = new Fakes();
        AtomicBoolean worked = new AtomicBoolean();
        Supplier<Integer> work = () -> {
            worked.set(true);
            return 0;
        };

        assertThatThrownBy(() -> ExecutionConnectionTag.within(fakes.dataSource, 7L, work))
                .isInstanceOf(ExecutionTagException.class).hasMessageStartingWith("CONNECTION_TAG_UNBOUND: ");
        assertThat(fakes.sessions).isEmpty();

        Fakes other = new Fakes();
        assertThatThrownBy(() -> inTransaction(other.dataSource, () -> ExecutionConnectionTag.within(fakes.dataSource,
                7L, work))).isInstanceOf(ExecutionTagException.class).hasMessageStartingWith("CONNECTION_TAG_UNBOUND: ");
        assertThat(fakes.sessions).isEmpty();
        assertThat(other.only().events).containsExactly("autoCommit:false", "rollback", "autoCommit:true", "close");

        assertThatThrownBy(() -> inTransaction(other.dataSource, () -> ExecutionConnectionTag.within(other.dataSource,
                null, work))).isInstanceOf(ExecutionTagException.class)
                .hasMessage("CONNECTION_TAG_UNBOUND: the step has no job execution ID");
        assertThat(other.sessions.get(1).events).doesNotContain("read");
        assertThat(worked).isFalse();
    }

    @Test
    void aFailedReadEvictsTheConnectionAndRunsNoWork() {
        Fakes fakes = new Fakes();
        fakes.failing = ExecutionConnectionTag.READ::equals;

        assertThatThrownBy(() -> inTransaction(fakes.dataSource, () -> ExecutionConnectionTag.within(fakes.dataSource,
                7L, archive(fakes.dataSource)))).isInstanceOf(ExecutionTagException.class).hasMessage(
                "CONNECTION_TAG_FAILED: the session's value could not be read (SQL error 50001); the connection is evicted");

        assertThat(fakes.only().events).containsExactly("autoCommit:false", "read-failed", "rollback", "abort",
                "close");
    }

    @Test
    void aFailedSetEvictsTheConnectionAndRunsNoWork() {
        Fakes fakes = new Fakes();
        fakes.failing = sql -> sql.startsWith(ExecutionConnectionTag.SET + PREFIX_HEX);

        assertThatThrownBy(() -> inTransaction(fakes.dataSource, () -> ExecutionConnectionTag.within(fakes.dataSource,
                7L, archive(fakes.dataSource)))).isInstanceOf(ExecutionTagException.class)
                .hasMessageStartingWith("CONNECTION_TAG_FAILED: the tag could not be set (SQL error 50001)");

        assertThat(fakes.only().events).containsExactly("autoCommit:false", "read", "set-failed", "rollback", "abort",
                "close");
    }

    @Test
    void aSessionThatAlreadyCarriesATagIsEvictedBeforeAnySet() {
        Fakes fakes = new Fakes();
        fakes.initial = ExecutionConnectionTag.encode(99);

        assertThatThrownBy(() -> inTransaction(fakes.dataSource, () -> ExecutionConnectionTag.within(fakes.dataSource,
                7L, archive(fakes.dataSource)))).isInstanceOf(ExecutionTagException.class)
                .hasMessage("CONNECTION_TAG_STALE: the session already carries an execution tag; the connection is evicted");

        assertThat(fakes.only().events).containsExactly("autoCommit:false", "read", "rollback", "abort", "close");
    }

    @Test
    void aFailedRestoreAfterTheWorkEvictsAndNothingCommits() {
        Fakes fakes = new Fakes();
        fakes.failing = sql -> sql.equals(ExecutionConnectionTag.SET);

        assertThatThrownBy(() -> inTransaction(fakes.dataSource, () -> ExecutionConnectionTag.within(fakes.dataSource,
                7L, archive(fakes.dataSource)))).isInstanceOf(ExecutionTagException.class)
                .hasMessageStartingWith("CONNECTION_TAG_NOT_RESTORED: the session's prior value could not be set back");

        assertThat(fakes.only().events).containsExactly("autoCommit:false", "read",
                "set:" + PREFIX_HEX + "0000000000000007", "work", "set-failed", "rollback", "abort", "close");
    }

    @Test
    void aValueThatReadsBackDifferentEvicts() {
        Fakes fakes = new Fakes();
        fakes.initial = new byte[] {5, 6, 7};
        fakes.storing = value -> value.length == 3 ? new byte[] {5, 6, 8} : value;

        assertThatThrownBy(() -> inTransaction(fakes.dataSource, () -> ExecutionConnectionTag.within(fakes.dataSource,
                7L, archive(fakes.dataSource)))).isInstanceOf(ExecutionTagException.class)
                .hasMessage("CONNECTION_TAG_NOT_RESTORED: the session's prior value did not read back; the connection is evicted");

        assertThat(fakes.only().events).containsSubsequence("set:050607", "read", "rollback", "abort", "close")
                .doesNotContain("commit");
    }

    @Test
    void aFailedUpdateKeepsItsOwnExceptionAndTheValueIsStillSetBack() {
        Fakes fakes = new Fakes();
        fakes.failing = ARCHIVE::equals;

        Throwable thrown = catchFrom(() -> inTransaction(fakes.dataSource,
                () -> ExecutionConnectionTag.within(fakes.dataSource, 7L, archive(fakes.dataSource))));

        assertThat(thrown).isInstanceOf(WorkFailed.class).hasNoSuppressedExceptions();
        assertThat(fakes.only().events).containsExactly("autoCommit:false", "read",
                "set:" + PREFIX_HEX + "0000000000000007", "work-failed", "set:", "read", "rollback", "autoCommit:true",
                "close");
    }

    @Test
    void aFailedUpdateWhoseRestoreFailsTooKeepsItsExceptionWithTheRestoreAttached() {
        Fakes fakes = new Fakes();
        fakes.failing = sql -> sql.equals(ARCHIVE) || sql.equals(ExecutionConnectionTag.SET);

        Throwable thrown = catchFrom(() -> inTransaction(fakes.dataSource,
                () -> ExecutionConnectionTag.within(fakes.dataSource, 7L, archive(fakes.dataSource))));

        assertThat(thrown).isInstanceOf(WorkFailed.class);
        assertThat(thrown.getSuppressed()).singleElement().isInstanceOf(ExecutionTagException.class)
                .extracting(Throwable::getMessage).asString().startsWith("CONNECTION_TAG_NOT_RESTORED: ");
        assertThat(fakes.only().events).containsSubsequence("work-failed", "set-failed", "rollback", "abort", "close")
                .doesNotContain("commit");
    }

    @Test
    void aCallerThatSwallowsTheFailureStillCannotCommit() {
        Fakes fakes = new Fakes();
        fakes.failing = sql -> sql.startsWith(ExecutionConnectionTag.SET + PREFIX_HEX);

        assertThatThrownBy(() -> inTransaction(fakes.dataSource, () -> {
            try {
                return ExecutionConnectionTag.within(fakes.dataSource, 7L, archive(fakes.dataSource));
            } catch (ExecutionTagException swallowed) {
                return 0;
            }
        })).isInstanceOf(ExecutionTagException.class)
                .hasMessage("CONNECTION_TAG_FAILED: a transaction whose connection is evicted cannot commit");

        assertThat(fakes.only().events).containsSubsequence("set-failed", "rollback", "abort", "close")
                .doesNotContain("commit");
    }

    @Test
    void noDriverMessageReachesAnExceptionOrTheLog(CapturedOutput output) {
        List<Throwable> thrown = new ArrayList<>();
        List<Predicate<String>> failures = List.of(ExecutionConnectionTag.READ::equals,
                sql -> sql.startsWith(ExecutionConnectionTag.SET), sql -> sql.equals(ExecutionConnectionTag.SET));
        for (Predicate<String> failing : failures) {
            Fakes fakes = new Fakes();
            fakes.failing = failing;
            thrown.add(catchFrom(() -> inTransaction(fakes.dataSource,
                    () -> ExecutionConnectionTag.within(fakes.dataSource, 7L, archive(fakes.dataSource)))));
        }

        assertThat(thrown).allSatisfy(t -> assertThat(messages(t)).noneMatch(m -> m.contains(SENTINEL)));
        assertThat(output.getAll()).contains("CONNECTION_TAG_FAILED: ", "CONNECTION_TAG_NOT_RESTORED: ")
                .doesNotContain(SENTINEL);
    }

    @Test
    void underHikariATaggedConnectionGoesBackToThePoolWithNoTag() throws Exception {
        Fakes fakes = new Fakes();
        try (HikariDataSource pool = pool(fakes)) {
            inTransaction(pool, () -> ExecutionConnectionTag.within(pool, 7L, archive(pool)));

            Session first = fakes.only();
            assertThat(first.events).containsSubsequence("read", "set:" + PREFIX_HEX + "0000000000000007", "work",
                    "set:", "read", "commit");
            try (Connection next = pool.getConnection(); Statement statement = next.createStatement();
                    ResultSet rs = statement.executeQuery(ExecutionConnectionTag.READ)) {
                assertThat(rs.next()).isTrue();
                assertThat(ExecutionConnectionTag.sameValue(rs.getBytes(1), null)).isTrue();
            }
            assertThat(fakes.sessions).as("the same physical connection").containsExactly(first);
            assertThat(first.closed).isFalse();
        }
    }

    @Test
    void underHikariAnEvictedConnectionNeverGoesBackToThePool() throws Exception {
        Fakes fakes = new Fakes();
        AtomicInteger reads = new AtomicInteger();
        fakes.failing = sql -> sql.equals(ExecutionConnectionTag.READ) && reads.incrementAndGet() == 2;
        try (HikariDataSource pool = pool(fakes)) {
            assertThatThrownBy(() -> inTransaction(pool, () -> ExecutionConnectionTag.within(pool, 7L, archive(pool))))
                    .isInstanceOf(ExecutionTagException.class)
                    .hasMessageStartingWith("CONNECTION_TAG_NOT_RESTORED: the session's prior value could not be set back");

            Session first = fakes.sessions.get(0);
            // Hikari closes an evicted connection on its own executor.
            for (int i = 0; i < 500 && !first.closed; i++) {
                Thread.sleep(10);
            }
            assertThat(first.closed).as("the physical connection is closed").isTrue();
            assertThat(first.events).contains("rollback").doesNotContain("commit", "abort");
            try (Connection next = pool.getConnection(); Statement statement = next.createStatement()) {
                statement.executeQuery(ExecutionConnectionTag.READ).close();
            }
            assertThat(fakes.sessions).hasSize(2);
            assertThat(fakes.sessions.get(1).events).endsWith("read");
        }
    }

    private static HikariDataSource pool(Fakes fakes) {
        HikariConfig config = new HikariConfig();
        config.setDataSource(fakes.dataSource);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        config.setPoolName("execution-tag-test");
        return new HikariDataSource(config);
    }

    private static <T> T inTransaction(DataSource dataSource, Supplier<T> body) {
        return new TransactionTemplate(new JdbcTransactionManager(dataSource)).execute(status -> body.get());
    }

    /** The archive's first UPDATE on the transaction's connection; 3 rows. */
    private static Supplier<Integer> archive(DataSource dataSource) {
        return () -> {
            Connection con = DataSourceUtils.getConnection(dataSource);
            try (Statement statement = con.createStatement()) {
                return statement.executeUpdate(ARCHIVE);
            } catch (SQLException e) {
                throw new WorkFailed(e);
            } finally {
                DataSourceUtils.releaseConnection(con, dataSource);
            }
        };
    }

    private static Throwable catchFrom(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            return t;
        }
        throw new AssertionError("nothing was thrown");
    }

    /** Every message in the chain, causes and suppressed included, except the test's own work failure. */
    private static List<String> messages(Throwable t) {
        List<String> all = new ArrayList<>();
        if (t == null) {
            return all;
        }
        if (!(t instanceof WorkFailed)) {
            all.add(String.valueOf(t.getMessage()));
            all.addAll(messages(t.getCause()));
        }
        for (Throwable s : t.getSuppressed()) {
            all.addAll(messages(s));
        }
        return all;
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    /** The work's own failure, as the tasklet's JdbcClient would throw one. */
    static final class WorkFailed extends RuntimeException {
        WorkFailed(Throwable cause) {
            super("the archive UPDATE failed", cause);
        }
    }

    /** A fake server: a data source that opens a new session per connection. */
    static final class Fakes {

        final List<Session> sessions = new CopyOnWriteArrayList<>();
        final DataSource dataSource;
        volatile boolean padTo128;
        volatile byte[] initial;
        volatile Predicate<String> failing = sql -> false;
        volatile UnaryOperator<byte[]> storing = value -> value;

        Fakes() {
            dataSource = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {DataSource.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getConnection" -> {
                            Session session = new Session(this, sessions.size() + 1);
                            sessions.add(session);
                            yield session.connection;
                        }
                        case "isWrapperFor" -> false;
                        case "unwrap" -> throw new SQLException("not a wrapper");
                        case "toString" -> "fake data source";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method);
                    });
        }

        Session only() {
            assertThat(sessions).hasSize(1);
            return sessions.get(0);
        }
    }

    /** One fake session: its CONTEXT_INFO value and the calls it got. */
    static final class Session {

        final Fakes fakes;
        final int number;
        final List<String> events = new CopyOnWriteArrayList<>();
        final Connection connection;
        volatile byte[] contextInfo;
        volatile boolean autoCommit = true;
        volatile boolean closed;

        Session(Fakes fakes, int number) {
            this.fakes = fakes;
            this.number = number;
            this.contextInfo = fakes.initial;
            this.connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> connectionCall(proxy, method, args));
        }

        private Object connectionCall(Object proxy, Method method, Object[] args) throws SQLException {
            switch (method.getName()) {
                case "toString":
                    return "fake session " + number;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "isClosed":
                    return closed;
                case "isValid":
                    return !closed;
                case "close":
                    events.add("close");
                    closed = true;
                    return null;
                case "abort":
                    events.add("abort");
                    closed = true;
                    return null;
                default:
                    break;
            }
            if (closed) {
                throw new SQLException("the connection is closed", "08S01", 0);
            }
            switch (method.getName()) {
                case "getAutoCommit":
                    return autoCommit;
                case "setAutoCommit":
                    autoCommit = (boolean) args[0];
                    events.add("autoCommit:" + args[0]);
                    return null;
                case "commit":
                    events.add("commit");
                    return null;
                case "rollback":
                    if (args == null) {
                        events.add("rollback");
                    }
                    return null;
                case "createStatement":
                    return statement();
                case "getTransactionIsolation":
                    return Connection.TRANSACTION_READ_COMMITTED;
                case "isWrapperFor":
                    return false;
                case "unwrap":
                    throw new SQLException("not a wrapper");
                default:
                    return defaultValue(method);
            }
        }

        private Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Statement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "executeQuery" -> query((String) args[0]);
                        case "execute" -> {
                            update((String) args[0]);
                            yield false;
                        }
                        case "executeUpdate" -> update((String) args[0]);
                        case "getConnection" -> connection;
                        case "getUpdateCount" -> -1;
                        case "toString" -> "fake statement";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method);
                    });
        }

        private ResultSet query(String sql) throws SQLException {
            if (!sql.equals(ExecutionConnectionTag.READ)) {
                throw new AssertionError("unexpected query");
            }
            if (fakes.failing.test(sql)) {
                events.add("read-failed");
                throw driverFailure();
            }
            events.add("read");
            byte[] value = contextInfo == null ? null : contextInfo.clone();
            AtomicBoolean done = new AtomicBoolean();
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> !done.getAndSet(true);
                        case "getBytes" -> value;
                        case "wasNull" -> value == null;
                        case "toString" -> "fake result";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method);
                    });
        }

        private int update(String sql) throws SQLException {
            if (sql.startsWith(ExecutionConnectionTag.SET)) {
                String hex = sql.substring(ExecutionConnectionTag.SET.length());
                if (fakes.failing.test(sql)) {
                    events.add("set-failed");
                    throw driverFailure();
                }
                events.add("set:" + hex);
                byte[] value = fakes.storing.apply(HexFormat.of().parseHex(hex));
                contextInfo = fakes.padTo128 ? Arrays.copyOf(value, 128) : value;
                return 0;
            }
            if (!sql.equals(ARCHIVE)) {
                throw new AssertionError("unexpected statement");
            }
            if (fakes.failing.test(sql)) {
                events.add("work-failed");
                throw driverFailure();
            }
            events.add("work");
            return 3;
        }

        private static SQLException driverFailure() {
            return new SQLException("a driver failure quoting " + SENTINEL, "S0001", 50001);
        }
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
