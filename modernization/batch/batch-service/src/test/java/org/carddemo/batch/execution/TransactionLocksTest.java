package org.carddemo.batch.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transaction-owned application locks on a non-autocommit mssql-jdbc connection, which runs with
 * IMPLICIT_TRANSACTIONS ON: a BEGIN TRANSACTION at count 0 opens two levels and commit() ends only
 * one. Spring calls afterCommit after commit() and before it resets and returns the connection,
 * so what another session sees there is what the commit itself made durable and released.
 */
class TransactionLocksTest {

    static final TestDatabase DB = TestDatabase.shared();

    private final DataSource dataSource = DB.dataSource(ServiceLogin.BATCH);
    private final JdbcClient service = JdbcClient.create(dataSource);
    private final SafeTransactions transactions = new SafeTransactions(new JdbcTransactionManager(dataSource));
    private final JdbcClient admin = DB.admin();
    private final String resource = "carddemo.test_lock:" + UUID.randomUUID();

    @BeforeAll
    static void probeTable() {
        DB.admin().sql("""
                IF OBJECT_ID('batch.lock_probe') IS NULL
                    CREATE TABLE batch.lock_probe (id CHAR(36) NOT NULL PRIMARY KEY)""").update();
    }

    @Test
    void aLockTakenFirstIsReleasedAndItsWorkCommittedAtCommit() {
        lockIsReleasedAndWorkCommittedAtCommit(true);
    }

    @Test
    void aLockTakenAfterTheFirstStatementIsReleasedAndItsWorkCommittedAtCommit() {
        lockIsReleasedAndWorkCommittedAtCommit(false);
    }

    private void lockIsReleasedAndWorkCommittedAtCommit(boolean lockFirst) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> seen = new HashMap<>();
        transactions.executeWithoutResult(status -> {
            if (lockFirst) {
                assertThat(TransactionLocks.acquire(service, resource, "Exclusive", 5000)).isTrue();
            }
            insert(id);
            if (!lockFirst) {
                assertThat(TransactionLocks.acquire(service, resource, "Exclusive", 5000)).isTrue();
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    seen.put("trancount", service.sql("SELECT @@TRANCOUNT").query(Integer.class).single());
                    seen.put("lockFree", lockFree(resource));
                    seen.put("rows", committedRows(id));
                }
            });
        });
        assertThat(seen).containsEntry("trancount", 0).containsEntry("lockFree", true).containsEntry("rows", 1);
    }

    /**
     * The session dies after commit() returned and before Spring resets the connection, whose
     * failure Spring only logs. What commit() reported must already be durable, and the call must
     * succeed: the session dies before any later synchronization could query it.
     */
    @Test
    void workIsDurableAndTheCallSucceedsThoughTheSessionDiesRightAfterCommit() {
        String id = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status -> {
            // SELECT @@SPID opens no implicit transaction, so the lock is still taken first.
            int spid = service.sql("SELECT @@SPID").query(Integer.class).single();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    admin.sql("KILL " + spid).update();
                }
            });
            assertThat(TransactionLocks.acquire(service, resource, "Exclusive", 5000)).isTrue();
            insert(id);
        });
        assertThat(committedRows(id)).as("rows of a commit that returned normally").isOne();
        assertThat(lockFree(resource)).isTrue();
    }

    /**
     * A level opened behind the lock's back would stay open after commit(); the call must fail and
     * the work be rolled back, never left for the connection reset to commit. A pool of one
     * connection shows what the next borrower of that same connection gets.
     */
    @Test
    void aSecondLevelOpenAtCommitFailsTheCallAndKeepsNothing() {
        try (HikariDataSource pool = singleConnectionPool()) {
            JdbcClient db = JdbcClient.create(pool);
            String id = UUID.randomUUID().toString();
            Map<String, Object> seen = new HashMap<>();
            assertThatThrownBy(() -> new SafeTransactions(new JdbcTransactionManager(pool)).executeWithoutResult(s -> {
                seen.put("spid", db.sql("SELECT CAST(@@SPID AS INT)").query(Integer.class).single());
                assertThat(TransactionLocks.acquire(db, resource, "Exclusive", 5000)).isTrue();
                insert(db, id);
                db.sql("BEGIN TRANSACTION").update();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        seen.put("afterCommit", true);
                    }

                    @Override
                    public void afterCompletion(int completion) {
                        seen.put("completion", completion);
                        seen.putAll(boundSession(db, resource));
                        seen.put("lockFreeBeforeReturn", lockFree(resource));
                        seen.put("rowsBeforeReturn", committedRows(id));
                    }
                });
            })).isExactlyInstanceOf(IllegalStateException.class).hasMessageContaining("2 levels open before commit");
            // Seen before Spring resets the connection and returns it: the rollback ended every level.
            assertThat(seen).doesNotContainKey("afterCommit")
                    .containsEntry("completion", TransactionSynchronization.STATUS_ROLLED_BACK)
                    .containsEntry("boundSpid", seen.get("spid"))
                    .containsEntry("boundTrancount", 0)
                    .containsEntry("boundSessionLockMode", "NoLock")
                    .containsEntry("boundAppLocks", 0)
                    .containsEntry("boundOpenTransactions", 0)
                    .containsEntry("lockFreeBeforeReturn", true)
                    .containsEntry("rowsBeforeReturn", 0);
            assertThat(committedRows(id)).isZero();
            assertThat(lockFree(resource)).isTrue();
            Map<String, Object> next = nextBorrower(db);
            assertThat(next.get("spid")).as("the same connection").isEqualTo(seen.get("spid"));
            assertThat(next).containsEntry("trancount", 0);
            assertThat(DB.admin().sql("""
                            SELECT COUNT(*) FROM sys.dm_exec_sessions
                            WHERE login_name = :login AND open_transaction_count > 0""")
                    .param("login", DB.loginName(ServiceLogin.BATCH)).query(Integer.class).single())
                    .as("batch sessions with a transaction open").isZero();
        }
    }

    /**
     * The session dies before commit, so the level check's own query fails. Its exception is a
     * plain DataAccessException, not a TransactionException, so Spring takes the rollback path;
     * that rollback fails on the dead session and Spring throws its failure instead, after logging
     * the check's. Nothing is kept and the pool hands out a working connection with no transaction
     * open. The same failure on a usable connection is the next test.
     */
    @Test
    void aFailedLevelCheckFailsTheCallAndKeepsNothing() {
        try (HikariDataSource pool = singleConnectionPool()) {
            JdbcClient db = JdbcClient.create(pool);
            String id = UUID.randomUUID().toString();
            Map<String, Object> seen = new HashMap<>();
            assertThatThrownBy(() -> new SafeTransactions(new JdbcTransactionManager(pool)).executeWithoutResult(s -> {
                int spid = db.sql("SELECT @@SPID").query(Integer.class).single();
                // Registered before the lock, so it runs before the level check.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        admin.sql("KILL " + spid).update();
                    }

                    @Override
                    public void afterCommit() {
                        seen.put("afterCommit", true);
                    }
                });
                assertThat(TransactionLocks.acquire(db, resource, "Exclusive", 5000)).isTrue();
                insert(db, id);
            })).isInstanceOf(TransactionSystemException.class).hasMessageContaining("rollback failed");
            assertThat(seen).doesNotContainKey("afterCommit");
            assertThat(committedRows(id)).isZero();
            assertThat(lockFree(resource)).isTrue();
            assertThat(nextBorrower(db)).containsEntry("trancount", 0);
        }
    }

    /**
     * The level check's query fails while the connection stays usable. The caller gets the check's
     * own exception, unwrapped. Spring's rollback ends the transaction, not the connection reset
     * or the pool: in afterCompletion, still on the bound connection, no level is open and the
     * session holds no application lock, and another session sees the lock free and no row. The
     * connection got rollback() before autocommit was restored and before it went back to the
     * pool, and never commit().
     */
    @Test
    void aLevelCheckFailingOnAUsableConnectionRollsBackBeforeTheConnectionGoesBack() {
        try (HikariDataSource pool = singleConnectionPool()) {
            FaultyLevelCheck faulty = new FaultyLevelCheck(pool);
            JdbcClient db = JdbcClient.create(faulty);
            String id = UUID.randomUUID().toString();
            Map<String, Object> seen = new HashMap<>();
            assertThatThrownBy(() -> new SafeTransactions(new JdbcTransactionManager(faulty)).executeWithoutResult(s -> {
                seen.put("spid", db.sql("SELECT CAST(@@SPID AS INT)").query(Integer.class).single());
                assertThat(TransactionLocks.acquire(db, resource, "Exclusive", 5000)).isTrue();
                insert(db, id);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        seen.put("afterCommit", true);
                    }

                    @Override
                    public void afterCompletion(int completion) {
                        seen.put("completion", completion);
                        seen.put("callsBeforeReturn", List.copyOf(faulty.calls));
                        seen.putAll(boundSession(db, resource));
                        seen.put("lockFreeBeforeReturn", lockFree(resource));
                        seen.put("rowsBeforeReturn", committedRows(id));
                    }
                });
                faulty.armed = true;
            })).isInstanceOf(DataAccessException.class).isNotInstanceOf(TransactionException.class)
                    .hasRootCauseMessage(FaultyLevelCheck.MESSAGE);
            assertThat(seen).doesNotContainKey("afterCommit")
                    .containsEntry("completion", TransactionSynchronization.STATUS_ROLLED_BACK)
                    .containsEntry("boundSpid", seen.get("spid"))
                    .containsEntry("boundTrancount", 0)
                    .containsEntry("boundSessionLockMode", "NoLock")
                    .containsEntry("boundAppLocks", 0)
                    .containsEntry("boundOpenTransactions", 0)
                    .containsEntry("lockFreeBeforeReturn", true)
                    .containsEntry("rowsBeforeReturn", 0);
            assertThat(calls(seen.get("callsBeforeReturn")))
                    .containsSubsequence("setAutoCommit(false)", FaultyLevelCheck.INJECTED, "rollback")
                    .doesNotContain("commit", "setAutoCommit(true)", "close");
            assertThat(faulty.calls)
                    .containsSubsequence(FaultyLevelCheck.INJECTED, "rollback", "setAutoCommit(true)", "close")
                    .doesNotContain("commit")
                    .containsOnlyOnce(FaultyLevelCheck.INJECTED);
            assertThat(committedRows(id)).isZero();
            assertThat(lockFree(resource)).isTrue();
            Map<String, Object> next = nextBorrower(db);
            assertThat(next.get("spid")).as("the same connection").isEqualTo(seen.get("spid"));
            assertThat(next).containsEntry("trancount", 0);
        }
    }
    /** One connection only, so the next borrow gets the connection the last call returned, if it is still alive. */
    private static HikariDataSource singleConnectionPool() {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(DB.jdbcUrl());
        pool.setUsername(DB.loginName(ServiceLogin.BATCH));
        pool.setPassword(DB.password(ServiceLogin.BATCH));
        pool.setMaximumPoolSize(1);
        pool.setMinimumIdle(1);
        pool.setPoolName("transaction-locks-test");
        return pool;
    }

    /** The session and transaction count a new plain borrower sees. */
    private static Map<String, Object> nextBorrower(JdbcClient db) {
        return db.sql("SELECT CAST(@@SPID AS INT) AS spid, @@TRANCOUNT AS trancount").query().singleRow();
    }
    @Test
    void aLockHeldElsewhereIsNotGrantedWithinTheTimeout() {
        new TransactionTemplate(new JdbcTransactionManager(DB.adminDataSource())).executeWithoutResult(held -> {
            assertThat(TransactionLocks.acquire(admin, resource, "Exclusive", 1000)).isTrue();
            Boolean granted = transactions.execute(status -> TransactionLocks.acquire(service, resource, "Shared", 200));
            assertThat(granted).isFalse();
        });
    }

    private void insert(String id) {
        insert(service, id);
    }

    private static void insert(JdbcClient db, String id) {
        db.sql("INSERT INTO batch.lock_probe (id) VALUES (:id)").param("id", id).update();
    }

    /**
     * Rows with the ID that another session sees as committed. The lock timeout keeps a held row
     * lock from stalling the test on a database that reads without row versions.
     */
    private Integer committedRows(String id) {
        return admin.sql("""
                        SET NOCOUNT ON;
                        SET LOCK_TIMEOUT 2000;
                        DECLARE @n INT = (SELECT COUNT(*) FROM batch.lock_probe WHERE id = :id);
                        SET LOCK_TIMEOUT -1;
                        SELECT @n;""")
                .param("id", id).query(Integer.class).single();
    }

    /**
     * The bound connection's session, level count and holds, before Spring releases it, without
     * opening a level. With no level open, APPLOCK_MODE for the Transaction owner is an error
     * (3918), so the bound session reads only its session-owned hold, and the admin session reads
     * from the server's lock and session views whether the bound session still holds any
     * application lock or has a transaction open.
     */
    private static Map<String, Object> boundSession(JdbcClient db, String resource) {
        Map<String, Object> seen = new HashMap<>(db.sql("""
                        SELECT CAST(@@SPID AS INT) AS boundSpid, @@TRANCOUNT AS boundTrancount,
                               APPLOCK_MODE('public', :resource, 'Session') AS boundSessionLockMode""")
                .param("resource", resource).query().singleRow());
        seen.putAll(DB.admin().sql("""
                        SELECT (SELECT COUNT(*) FROM sys.dm_tran_locks
                                WHERE request_session_id = :spid AND resource_type = 'APPLICATION') AS boundAppLocks,
                               (SELECT open_transaction_count FROM sys.dm_exec_sessions
                                WHERE session_id = :spid) AS boundOpenTransactions""")
                .param("spid", seen.get("boundSpid")).query().singleRow());
        return seen;
    }

    @SuppressWarnings("unchecked")
    private static List<String> calls(Object recorded) {
        return (List<String>) recorded;
    }

    /**
     * The pool's connections with the transaction calls Spring makes on them recorded in order,
     * and the level check's query failing once while armed, on a connection that stays usable.
     */
    private static final class FaultyLevelCheck extends DelegatingDataSource {

        static final String MESSAGE = "injected failure of the level check";
        static final String INJECTED = "injected";
        private static final String LEVEL_CHECK = "SELECT @@TRANCOUNT";

        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        volatile boolean armed;

        FaultyLevelCheck(DataSource pool) {
            super(pool);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection target = obtainTargetDataSource().getConnection();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "commit", "rollback", "close" -> {
                                if (args == null) {
                                    calls.add(method.getName());
                                }
                            }
                            case "setAutoCommit" -> calls.add("setAutoCommit(" + args[0] + ")");
                            case "prepareStatement", "prepareCall" -> failIfLevelCheck((String) args[0]);
                            default -> {
                            }
                        }
                        Object result = invoke(target, method, args);
                        return result instanceof Statement statement && method.getName().equals("createStatement")
                                ? statement(statement) : result;
                    });
        }

        private Statement statement(Statement target) {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Statement.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("execute") && args != null && args[0] instanceof String sql) {
                            failIfLevelCheck(sql);
                        }
                        return invoke(target, method, args);
                    });
        }

        private void failIfLevelCheck(String sql) throws SQLException {
            if (armed && LEVEL_CHECK.equals(sql.strip())) {
                armed = false;
                calls.add(INJECTED);
                throw new SQLException(MESSAGE, "S0001", 50001);
            }
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
    /** Whether another session gets the lock exclusively within a second. */
    static boolean lockFree(String resource) {
        JdbcClient other = DB.admin();
        return new TransactionTemplate(new JdbcTransactionManager(DB.adminDataSource()))
                .execute(s -> TransactionLocks.acquire(other, resource, "Exclusive", 1000));
    }
}
