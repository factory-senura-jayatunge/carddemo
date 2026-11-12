package org.carddemo.batch.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.carddemo.batch.execution.SafeTransactions;
import org.carddemo.batch.execution.TransactionLocks;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The window and launch locks that window open, window close and cycle-rerun take as the first
 * statement of their transaction end with that transaction's commit.
 */
class AppLocksTest {

    static final TestDatabase DB = TestDatabase.shared();

    @Test
    void theWindowAndLaunchLocksAreReleasedAtCommit() {
        DataSource dataSource = DB.dataSource(ServiceLogin.BATCH);
        JdbcClient service = JdbcClient.create(dataSource);
        Map<String, Object> seen = new HashMap<>();
        new SafeTransactions(new JdbcTransactionManager(dataSource)).executeWithoutResult(status -> {
            assertThat(AppLocks.inTransaction(service, AppLocks.LAUNCH, "Exclusive", Duration.ofSeconds(10))).isTrue();
            assertThat(AppLocks.inTransaction(service, AppLocks.WINDOW, "Exclusive", Duration.ofSeconds(10))).isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    seen.put("trancount", service.sql("SELECT @@TRANCOUNT").query(Integer.class).single());
                    seen.put("launchFree", lockFree(AppLocks.LAUNCH));
                    seen.put("windowFree", lockFree(AppLocks.WINDOW));
                }
            });
        });
        assertThat(seen).containsEntry("trancount", 0).containsEntry("launchFree", true)
                .containsEntry("windowFree", true);
    }

    private static boolean lockFree(String resource) {
        JdbcClient other = DB.admin();
        return new TransactionTemplate(new JdbcTransactionManager(DB.adminDataSource()))
                .execute(s -> TransactionLocks.acquire(other, resource, "Exclusive", 1000));
    }
}
