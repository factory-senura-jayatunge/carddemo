package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.carddemo.batch.execution.SafeTransactions;
import org.carddemo.db.ServiceLogin;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * The capture's session settings on a pooled connection (batch.md, "Tests (statement job)",
 * capture and watermark; contract section 4.4). The capture sets {@code LOCK_TIMEOUT} and
 * {@code DEADLOCK_PRIORITY LOW} on its session; with a pool of one connection, the next borrower
 * of the same session sees the defaults again, {@code @@LOCK_TIMEOUT = -1} and
 * {@code deadlock_priority = 0} in {@code sys.dm_exec_sessions}, after a capture that commits and
 * after one that fails on a lock timeout. A restore that fails aborts the connection, whose
 * server session then ends.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class StatementCaptureSessionTest extends StatementJobTestBase {

    private static final Duration WAIT = Duration.ofSeconds(2);

    /** One connection only, so the next borrow gets the connection the capture returned, if it is still alive. */
    private static HikariDataSource singleConnectionPool() {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(DB.jdbcUrl());
        pool.setUsername(DB.loginName(ServiceLogin.BATCH));
        pool.setPassword(DB.password(ServiceLogin.BATCH));
        pool.setMaximumPoolSize(1);
        pool.setMinimumIdle(1);
        pool.setPoolName("statement-capture-session-test");
        return pool;
    }

    /** A registered cycle at attempt 1 with its window open. */
    private StatementCycle cycle() {
        return new StatementCycle(openCycle(DATE), LocalDate.parse(DATE), 1);
    }

    /** An existing job execution for the run row's foreign key: the sample load's. */
    private static long anExecution() {
        return count("SELECT MAX(JOB_EXECUTION_ID) FROM batch.BATCH_JOB_EXECUTION");
    }

    private static int spid(JdbcClient db) {
        return db.sql("SELECT CAST(@@SPID AS INT)").query(Integer.class).single();
    }

    /** What a new plain borrower of the pool sees of its session, and what the server records for it. */
    private static Map<String, Object> nextBorrower(JdbcClient db) {
        Map<String, Object> seen = new HashMap<>(db.sql(
                "SELECT CAST(@@SPID AS INT) AS spid, @@LOCK_TIMEOUT AS lockTimeout").query().singleRow());
        seen.putAll(DB.admin().sql("""
                SELECT lock_timeout AS sessionLockTimeout, deadlock_priority AS deadlockPriority
                FROM sys.dm_exec_sessions WHERE session_id = :spid""").param("spid", seen.get("spid")).query()
                .singleRow());
        return seen;
    }

    private static void assertDefaults(JdbcClient db, Object spid) {
        assertThat(nextBorrower(db)).containsEntry("spid", spid).containsEntry("lockTimeout", -1)
                .containsEntry("sessionLockTimeout", -1).containsEntry("deadlockPriority", 0);
    }

    @Test
    void aCaptureThatCommitsLeavesItsSessionAtTheDefaults() {
        StatementCycle cycle = cycle();
        long execution = anExecution();
        try (HikariDataSource pool = singleConnectionPool()) {
            JdbcClient db = JdbcClient.create(pool);
            StatementCapture capture = new StatementCapture(db, pool, WAIT);
            Map<String, Object> seen = new HashMap<>();
            Long runId = new SafeTransactions(new JdbcTransactionManager(pool)).execute(s -> {
                seen.put("spid", spid(db));
                return capture.capture(cycle, StatementRuns.Mode.FIXED, execution);
            });
            assertThat(count("SELECT COUNT_BIG(*) FROM batch.statement_run WHERE statement_run_id = ? AND status = 'RUNNING'",
                    runId)).isOne();
            assertDefaults(db, seen.get("spid"));
        }
    }

    @Test
    void aCaptureThatFailsOnALockTimeoutLeavesItsSessionAtTheDefaults() throws SQLException {
        StatementCycle cycle = cycle();
        long execution = anExecution();
        String card = DB.admin().sql("SELECT TOP (1) card_num FROM core.card_xref ORDER BY card_num")
                .query(String.class).single();
        try (InFlightWrite writer = new InFlightWrite(DB.adminDataSource());
                HikariDataSource pool = singleConnectionPool()) {
            writer.insert("STMTSES000000001", card, "1.00", DATE + " 10:00:00", "held", "POSTED");
            JdbcClient db = JdbcClient.create(pool);
            StatementCapture capture = new StatementCapture(db, pool, WAIT);
            Map<String, Object> seen = new HashMap<>();
            assertThatThrownBy(() -> new SafeTransactions(new JdbcTransactionManager(pool)).execute(s -> {
                seen.put("spid", spid(db));
                return capture.capture(cycle, StatementRuns.Mode.FIXED, execution);
            })).isInstanceOfSatisfying(StatementFailure.class, f -> assertThat(f.code()).isEqualTo(StatementCapture.BUSY))
                    .hasMessageContaining("SQL error 1222");
            assertDefaults(db, seen.get("spid"));
            assertThat(count("SELECT COUNT_BIG(*) FROM batch.statement_run WHERE cycle_id = ?", cycle.cycleId()))
                    .isZero();
        }
    }

    /**
     * The restore's statement fails on a usable connection. The capture fails with
     * {@code STATEMENT_SESSION_RESTORE_FAILED}, aborts the connection before it can go back to the
     * pool, and the server ends that session, so nothing of the capture is kept.
     */
    @Test
    void aRestoreThatFailsAbortsTheConnection() throws InterruptedException {
        StatementCycle cycle = cycle();
        long execution = anExecution();
        try (HikariDataSource pool = singleConnectionPool()) {
            FailingRestore failing = new FailingRestore(pool);
            JdbcClient db = JdbcClient.create(failing);
            StatementCapture capture = new StatementCapture(db, failing, WAIT);
            Map<String, Object> seen = new HashMap<>();
            Throwable thrown = catchThrowable(() -> new SafeTransactions(new JdbcTransactionManager(failing)).execute(s -> {
                seen.put("connection", DB.admin().sql(
                        "SELECT CAST(connection_id AS CHAR(36)) FROM sys.dm_exec_connections WHERE session_id = :spid")
                        .param("spid", spid(db)).query(String.class).single());
                try {
                    return capture.capture(cycle, StatementRuns.Mode.FIXED, execution);
                } catch (StatementFailure e) {
                    seen.put("failure", e.code());
                    throw e;
                }
            }));
            assertThat(thrown).isNotNull();
            assertThat(seen).containsEntry("failure", StatementCapture.RESTORE_FAILED);
            assertThat(failing.calls).containsSubsequence(FailingRestore.INJECTED, "abort").containsOnlyOnce("abort");
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (count("SELECT COUNT_BIG(*) FROM sys.dm_exec_connections WHERE connection_id = ?",
                    seen.get("connection")) > 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(count("SELECT COUNT_BIG(*) FROM sys.dm_exec_connections WHERE connection_id = ?",
                    seen.get("connection"))).as("the aborted connection's server session").isZero();
            assertThat(count("SELECT COUNT_BIG(*) FROM batch.statement_run WHERE cycle_id = ?", cycle.cycleId()))
                    .isZero();
        }
    }

    /**
     * The pool's connections, with {@code abort} recorded and the capture's restore statement failing
     * once, on a connection that stays usable.
     */
    private static final class FailingRestore extends DelegatingDataSource {

        static final String INJECTED = "injected";

        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean armed = true;

        FailingRestore(DataSource pool) {
            super(pool);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection target = obtainTargetDataSource().getConnection();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("abort")) {
                            calls.add("abort");
                        }
                        Object result = invoke(target, method, args);
                        return result instanceof Statement statement && method.getName().equals("createStatement")
                                ? statement(statement) : result;
                    });
        }

        private Statement statement(Statement target) {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Statement.class},
                    (proxy, method, args) -> {
                        if (armed && method.getName().equals("execute") && args != null
                                && StatementCapture.SESSION_DEFAULTS.equals(args[0])) {
                            armed = false;
                            calls.add(INJECTED);
                            throw new SQLException("injected failure of the session restore", "S0001", 50001);
                        }
                        return invoke(target, method, args);
                    });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
