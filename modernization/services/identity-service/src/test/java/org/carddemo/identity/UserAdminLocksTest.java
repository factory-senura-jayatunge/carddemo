package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.carddemo.db.ServiceLogin;
import org.carddemo.identity.ApiClient.Response;
import org.carddemo.identity.user.UserAdminLocks;
import org.carddemo.identity.user.UserAdminLocksStubs;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.Ordered;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The application locks of user administration are the first statement of the transaction, on a
 * non-autocommit mssql-jdbc connection that runs with IMPLICIT_TRANSACTIONS ON, where a BEGIN
 * TRANSACTION at count 0 opens two levels and commit() ends only one. Spring calls afterCommit
 * after commit() and before it resets and returns the connection, so what another session sees
 * there is what the commit itself made durable and released.
 */
@ExtendWith(OutputCaptureExtension.class)
class UserAdminLocksTest extends UserAdminTestBase {

    private static final String BATCH_WINDOW_LOCK = "carddemo.batch_window";
    private static final String ADMINS_LOCK = "carddemo.iam.admins";
    /** Valid in both password modes: 1 to 8 characters legacy (the default here), 8 to 64 fixed. */
    private static final String PASSWORD = "PASSWD26";

    @Autowired
    private JdbcClient service;

    @Autowired
    private DataSource dataSource;

    @MockitoSpyBean
    private UserAdminLocks locks;

    @MockitoSpyBean
    private PlatformTransactionManager transactions;

    private final JdbcClient admin = TestDatabase.shared().admin();

    @BeforeEach
    void users() {
        db.demoUsers();
    }

    /**
     * A level that a regression leaves open holds its locks into the next test's reset; ending
     * such sessions here turns the regression into this test's failure, not the next one's lock
     * timeout. Only sessions of this JVM's identity-service test database, logged in as the
     * service login provisioned for it, are ended: never another database's, an agent's or the
     * admin's own.
     */
    @AfterEach
    void endStrayTransactions() throws SQLException {
        TestDatabase test = TestDatabase.shared();
        String database = admin.sql("SELECT DB_NAME()").query(String.class).single();
        String login = test.loginName(ServiceLogin.IDENTITY);
        if (!database.equals(test.databaseName()) || !database.endsWith("_identity_service")
                || !login.startsWith(database + "_")) {
            throw new IllegalStateException("not the private identity-service test database: " + database);
        }
        admin.sql("""
                        SELECT session_id FROM sys.dm_exec_sessions
                        WHERE database_id = DB_ID() AND login_name = :login AND open_transaction_count > 0
                          AND session_id <> @@SPID""")
                .param("login", login)
                .query(Integer.class).list()
                .forEach(spid -> admin.sql("KILL " + spid).update());
        dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
    }

    /**
     * Runs {@code atCommit} after the commit of the add's transaction, on its connection, before
     * every other synchronization, so nothing of the service runs between the commit and it.
     */
    private void afterTheAddCommits(Runnable inTransaction, Runnable atCommit) {
        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            inTransaction.run();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public int getOrder() {
                    return Ordered.HIGHEST_PRECEDENCE;
                }

                @Override
                public void afterCommit() {
                    atCommit.run();
                }
            });
            return result;
        }).when(userRepository).addOrReuse(any(), any(), anyString());
    }

    @Test
    void anAddIsCommittedAndItsLockReleasedByTheCommit() {
        ApiClient c = admin();
        Map<String, Object> seen = new HashMap<>();
        afterTheAddCommits(() -> { }, () -> {
            seen.put("trancount", service.sql("SELECT @@TRANCOUNT").query(Integer.class).single());
            seen.put("windowLockFree", lockFree(BATCH_WINDOW_LOCK));
            seen.put("rows", committedUsers("NEW00001"));
        });
        Response r = c.post("/api/users", newUser("N", "U", "NEW00001", PASSWORD, "U"));
        assertThat(r.status()).as("%s", r.body()).isEqualTo(201);
        assertThat(seen).containsEntry("trancount", 0).containsEntry("rows", 1).containsEntry("windowLockFree", true);
    }

    @Test
    void aDeleteIsCommittedAndBothLocksReleasedByTheCommit() {
        ApiClient c = admin();
        String etag = etag(c, "USER0001");
        Map<String, Object> seen = new HashMap<>();
        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    seen.put("trancount", service.sql("SELECT @@TRANCOUNT").query(Integer.class).single());
                    seen.put("windowLockFree", lockFree(BATCH_WINDOW_LOCK));
                    seen.put("adminsLockFree", lockFree(ADMINS_LOCK));
                    seen.put("deleted", committedDeletes("USER0001"));
                }
            });
            return result;
        }).when(userRepository).markDeleted(anyString(), any(), anyString(), any());
        Response r = c.delete("/api/users/USER0001", etag);
        assertThat(r.status()).as("%s", r.body()).isBetween(200, 299);
        assertThat(seen).containsEntry("trancount", 0).containsEntry("deleted", 1)
                .containsEntry("windowLockFree", true).containsEntry("adminsLockFree", true);
    }

    /**
     * The session dies right after commit() returned, before any other synchronization and before
     * Spring resets the connection, whose failure Spring only logs. What commit() reported must
     * already be durable, and the add answers success: nothing after the commit queries the dead
     * session.
     */
    @Test
    void anAddIsDurableOnceCommitReturnsThoughTheSessionDiesBeforeTheReset() {
        ApiClient c = admin();
        int[] spid = new int[1];
        afterTheAddCommits(() -> spid[0] = service.sql("SELECT @@SPID").query(Integer.class).single(),
                () -> admin.sql("KILL " + spid[0]).update());
        Response r = c.post("/api/users", newUser("N", "U", "NEW00002", PASSWORD, "U"));
        assertThat(r.status()).as("%s", r.body()).isEqualTo(201);
        assertThat(committedUsers("NEW00002")).as("the user of a commit that returned").isOne();
    }

    /** What the synchronizations of a transaction saw: whether afterCommit ran, and the completion status. */
    private static final class Completion implements TransactionSynchronization {
        volatile boolean committed;
        volatile Integer status;

        @Override
        public void afterCommit() {
            committed = true;
        }

        @Override
        public void afterCompletion(int status) {
            this.status = status;
        }
    }

    /**
     * A level opened behind the lock's back would stay open after commit(); the add must fail
     * before commit, roll the whole transaction back and keep nothing, never leave its work for the
     * connection reset to commit. The connection goes back to the pool out of any transaction.
     */
    @Test
    void aTransactionStillOpenAtCommitRollsTheAddBackAndFailsIt(CapturedOutput output) throws SQLException {
        ApiClient c = admin();
        Completion completion = new Completion();
        int[] spid = new int[1];
        afterTheAddCommits(() -> {
            spid[0] = service.sql("SELECT @@SPID").query(Integer.class).single();
            service.sql("BEGIN TRANSACTION").update();
            TransactionSynchronizationManager.registerSynchronization(completion);
        }, () -> { });
        Response r = c.post("/api/users", newUser("N", "U", "NEW00003", PASSWORD, "U"));
        assertProblem(r, 500, "USER_ADD_FAILED", "Unable to Add User...", "firstName");
        assertThat(output).contains("500 USER_ADD_FAILED: OpenLevelAtCommit");
        assertThat(completion.committed).as("afterCommit").isFalse();
        assertThat(completion.status).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(committedUsers("NEW00003")).isZero();
        assertThat(db.opsAudit()).as("the audit row went with it").isEmpty();
        assertThat(lockFree(BATCH_WINDOW_LOCK)).isTrue();
        assertThat(lockFree(ADMINS_LOCK)).isTrue();
        everyPooledSessionIsOutOfTransaction(spid[0]);
    }

    /**
     * A database failure of the guard's own read leaves commit() as the DataAccessException itself,
     * never a TransactionException, so commit rolls everything back, and the add answers its own
     * failure message.
     */
    @Test
    void aFailedLevelReadAtCommitRollsTheAddBackAndFailsIt(CapturedOutput output) throws SQLException {
        ApiClient c = admin();
        Completion completion = new Completion();
        int[] spid = new int[1];
        QueryTimeoutException failure = new QueryTimeoutException("the level read timed out");
        UserAdminLocksStubs.levelsFail(locks, failure);
        List<Throwable> fromCommit = new ArrayList<>();
        doAnswer(inv -> {
            try {
                return inv.callRealMethod();
            } catch (Throwable t) {
                fromCommit.add(t);
                throw t;
            }
        }).when(transactions).commit(any());
        afterTheAddCommits(() -> {
            spid[0] = service.sql("SELECT @@SPID").query(Integer.class).single();
            TransactionSynchronizationManager.registerSynchronization(completion);
        }, () -> { });
        Response r = c.post("/api/users", newUser("N", "U", "NEW00004", PASSWORD, "U"));
        assertProblem(r, 500, "USER_ADD_FAILED", "Unable to Add User...", "firstName");
        assertThat(output).contains("500 USER_ADD_FAILED: QueryTimeoutException")
                .doesNotContain("TransactionSystemException").doesNotContain("UnexpectedRollbackException");
        assertThat(fromCommit).as("what commit() threw: the guard's exception itself, not a wrapper")
                .singleElement().isSameAs(failure);
        assertThat(completion.committed).as("afterCommit").isFalse();
        assertThat(completion.status).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(committedUsers("NEW00004")).isZero();
        assertThat(db.opsAudit()).as("the audit row went with it").isEmpty();
        assertThat(lockFree(BATCH_WINDOW_LOCK)).isTrue();
        assertThat(lockFree(ADMINS_LOCK)).isTrue();
        everyPooledSessionIsOutOfTransaction(spid[0]);
    }

    /** The user, its row version and its signed-on session are as they were before the delete. */
    @Test
    void aTransactionStillOpenAtCommitRollsTheDeleteBackAndFailsIt(CapturedOutput output) throws SQLException {
        signedOn("USER0001");
        ApiClient c = admin();
        String etag = etag(c, "USER0001");
        String version = db.rowVersion("USER0001");
        IdentityFixtures.AdminRow row = db.adminRow("USER0001");
        List<IdentityFixtures.SessionRow> sessions = db.sessions();
        assertThat(sessions).filteredOn(s -> s.userId().equals("USER0001")).singleElement()
                .satisfies(s -> assertThat(s.endReason()).isNull());
        Completion completion = new Completion();
        int[] spid = new int[1];
        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            spid[0] = service.sql("SELECT @@SPID").query(Integer.class).single();
            service.sql("BEGIN TRANSACTION").update();
            TransactionSynchronizationManager.registerSynchronization(completion);
            return result;
        }).when(userRepository).markDeleted(anyString(), any(), anyString(), any());
        Response r = c.delete("/api/users/USER0001", etag);
        assertProblem(r, 500, "USER_DELETE_FAILED", "Unable to Update User...", "userId");
        assertThat(output).contains("500 USER_DELETE_FAILED: OpenLevelAtCommit");
        assertThat(completion.committed).as("afterCommit").isFalse();
        assertThat(completion.status).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(db.adminRow("USER0001")).as("the user as it was").isEqualTo(row);
        assertThat(db.adminRow("USER0001").deletedAt()).isNull();
        assertThat(db.rowVersion("USER0001")).isEqualTo(version);
        assertThat(db.sessions()).as("no session ended").isEqualTo(sessions);
        assertThat(db.opsAudit()).as("no audit row").isEmpty();
        assertThat(lockFree(BATCH_WINDOW_LOCK)).isTrue();
        assertThat(lockFree(ADMINS_LOCK)).isTrue();
        everyPooledSessionIsOutOfTransaction(spid[0]);
        assertThat(c.get("/api/users/USER0001").status()).as("still there").isEqualTo(200);
    }

    /**
     * Borrows every connection of the service's own pool at once: none is in a transaction, and
     * the session of the failed call is among them (it went back to the pool, not away).
     */
    private void everyPooledSessionIsOutOfTransaction(int spid) throws SQLException {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        List<Connection> held = new ArrayList<>();
        Set<Integer> spids = new HashSet<>();
        try {
            for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
                Connection c = pool.getConnection();
                held.add(c);
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT @@SPID, @@TRANCOUNT")) {
                    rs.next();
                    spids.add(rs.getInt(1));
                    assertThat(rs.getInt(2)).as("@@TRANCOUNT of session %d", rs.getInt(1)).isZero();
                }
            }
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
        assertThat(spids).as("the failed call's session is back in the pool").contains(spid);
    }

    /** Users with the ID another session sees as committed; the timeout keeps a held lock from stalling. */
    private Integer committedUsers(String userId) {
        return admin.sql("""
                        SET NOCOUNT ON;
                        SET LOCK_TIMEOUT 2000;
                        DECLARE @n INT = (SELECT COUNT(*) FROM iam.app_user WHERE user_id = :id);
                        SET LOCK_TIMEOUT -1;
                        SELECT @n;""")
                .param("id", userId).query(Integer.class).single();
    }

    private Integer committedDeletes(String userId) {
        return admin.sql("""
                        SET NOCOUNT ON;
                        SET LOCK_TIMEOUT 2000;
                        DECLARE @n INT = (SELECT COUNT(*) FROM iam.app_user
                                          WHERE user_id = :id AND deleted_at IS NOT NULL);
                        SET LOCK_TIMEOUT -1;
                        SELECT @n;""")
                .param("id", userId).query(Integer.class).single();
    }

    /** Whether another session gets the lock exclusively within a second. */
    private boolean lockFree(String resource) {
        return new TransactionTemplate(new DataSourceTransactionManager(TestDatabase.shared().adminDataSource()))
                .execute(s -> {
                    Integer r = admin.sql("""
                                    SET NOCOUNT ON;
                                    IF @@TRANCOUNT = 0
                                    BEGIN
                                        BEGIN TRANSACTION;
                                        IF @@TRANCOUNT > 1 COMMIT TRANSACTION;
                                    END;
                                    DECLARE @r INT;
                                    EXEC @r = sp_getapplock @Resource = :resource, @LockMode = 'Exclusive',
                                        @LockOwner = 'Transaction', @LockTimeout = 1000;
                                    SELECT @r;""")
                            .param("resource", resource).query(Integer.class).single();
                    return r != null && r >= 0;
                });
    }
}
