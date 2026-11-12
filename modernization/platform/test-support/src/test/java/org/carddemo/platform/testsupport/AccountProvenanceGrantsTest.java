package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.carddemo.db.ServiceLogin;
import org.junit.jupiter.api.Test;

/**
 * DEF-ACUP-002's provenance columns (V418): batch_svc reads and inserts them with the row through its
 * table grants and never updates them; every other login is refused both, because their core.account
 * grants name columns and these are not among them.
 */
class AccountProvenanceGrantsTest {

    private static final List<String> COLUMNS = List.of("legacy_zip_group", "zip_group_origin");

    private final TestDatabase db = TestDatabase.shared();

    @Test
    void onlyBatchReadsTheProvenanceAndNoLoginUpdatesIt() throws SQLException {
        for (ServiceLogin login : ServiceLogin.values()) {
            try (Connection c = db.dataSource(login).getConnection(); Statement s = c.createStatement()) {
                for (String column : COLUMNS) {
                    String read = "SELECT TOP 0 " + column + " FROM core.account";
                    if (login == ServiceLogin.BATCH) {
                        s.executeQuery(read).close();
                    } else {
                        assertRefused(s, login, read);
                    }
                    assertRefused(s, login, "UPDATE core.account SET " + column + " = " + column + " WHERE 1 = 0");
                }
            }
        }
    }

    private static void assertRefused(Statement s, ServiceLogin login, String sql) {
        assertThatThrownBy(() -> s.execute(sql)).as(login.userName() + ": " + sql)
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isIn(229, 230));
    }
}
