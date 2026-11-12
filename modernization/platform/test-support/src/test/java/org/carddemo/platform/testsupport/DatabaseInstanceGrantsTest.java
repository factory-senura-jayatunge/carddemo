package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.carddemo.db.ServiceLogin;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The database instance marker (V420) is read by batch_svc and transaction_svc, through
 * R__grants_batch.sql and R__grants_transaction.sql, and changed by no service login.
 */
class DatabaseInstanceGrantsTest {

    private static final String READ = "SELECT DB_NAME() AS database_name, CONVERT(CHAR(36), instance_id) AS instance_id"
            + " FROM ops.database_instance WHERE id = 1";

    private final TestDatabase db = TestDatabase.shared();

    @Test
    void theOutputRootReadersReadTheSameMarkerAsTheOwner() {
        Map<String, Object> owner = db.admin().sql(READ).query().singleRow();
        for (ServiceLogin login : ServiceLogin.values()) {
            if (login.readsOutputRoot()) {
                assertThat(JdbcClient.create(db.dataSource(login)).sql(READ).query().singleRow()).as(login.userName())
                        .isEqualTo(owner);
            }
        }
        assertThat(ServiceLogin.BATCH.readsOutputRoot()).isTrue();
        assertThat(ServiceLogin.TRANSACTION.readsOutputRoot()).isTrue();
    }

    @Test
    void noServiceLoginWritesTheMarkerAndOnlyTheReadersReadIt() throws SQLException {
        Map<String, Object> before = db.admin().sql("SELECT id, instance_id, created_at FROM ops.database_instance")
                .query().singleRow();
        for (ServiceLogin login : ServiceLogin.values()) {
            try (Connection c = db.dataSource(login).getConnection(); Statement s = c.createStatement()) {
                for (String probe : login.forbiddenProbes()) {
                    if (probe.contains("ops.database_instance")) {
                        assertThatThrownBy(() -> s.execute(probe)).as(login.userName() + ": " + probe)
                                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isIn(229, 230));
                    }
                }
            }
            assertThat(login.forbiddenProbes().stream().filter(p -> p.startsWith("SELECT") && p.contains("ops.database_instance")))
                    .as(login.userName()).hasSize(login.readsOutputRoot() ? 0 : 1);
            assertThat(login.forbiddenProbes().stream().filter(p -> !p.startsWith("SELECT") && p.contains("ops.database_instance")))
                    .as(login.userName()).hasSize(3);
        }
        assertThat(db.admin().sql("SELECT id, instance_id, created_at FROM ops.database_instance").query().singleRow())
                .isEqualTo(before);
    }
}
