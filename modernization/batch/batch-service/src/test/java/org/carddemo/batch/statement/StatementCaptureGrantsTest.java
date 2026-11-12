package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The capture is write-once: batch_svc's schema grant on {@code batch} is narrowed by an object
 * {@code DENY UPDATE} on {@code batch.statement_capture}, and by nothing else. {@code DELETE} is not
 * denied (no retention design is approved), so it stays effective through the schema grant.
 */
class StatementCaptureGrantsTest {

    static final TestDatabase DB = TestDatabase.shared();

    static final Path GRANTS = Path.of(System.getProperty("carddemo.repo.root", "../../.."))
            .toAbsolutePath().normalize().resolve("modernization/db/migrations/R__grants_batch.sql");

    private static boolean has(String permission) {
        return JdbcClient.create(DB.dataSource(ServiceLogin.BATCH))
                .sql("SELECT HAS_PERMS_BY_NAME('batch.statement_capture', 'OBJECT', :p)")
                .param("p", permission).query(Integer.class).single() == 1;
    }

    @Test
    void updateIsDeniedAndSelectInsertDeleteAreNot() {
        assertThat(has("UPDATE")).as("UPDATE").isFalse();
        assertThat(has("SELECT")).as("SELECT").isTrue();
        assertThat(has("INSERT")).as("INSERT").isTrue();
        assertThat(has("DELETE")).as("DELETE").isTrue();
    }

    @Test
    void anUpdateProbeIsRefusedWhileSelectAndDeleteRun() {
        JdbcClient batch = JdbcClient.create(DB.dataSource(ServiceLogin.BATCH));
        // Permissions are checked when the statement compiles, so a predicate that matches no row
        // still proves the refusal without touching another test's rows.
        assertThatThrownBy(() -> batch.sql("UPDATE batch.statement_capture SET curr_bal = curr_bal WHERE 1 = 0")
                .update()).hasMessageContaining("permission was denied");
        batch.sql("SELECT COUNT(*) FROM batch.statement_capture WHERE 1 = 0").query(Integer.class).single();
        assertThat(batch.sql("DELETE FROM batch.statement_capture WHERE 1 = 0").update()).isZero();
    }

    @Test
    void theOnlyDenyOfBatchSvcIsUpdateOnTheCapture() {
        List<String> denied = DB.admin().sql("""
                SELECT p.permission_name + ' ' + OBJECT_SCHEMA_NAME(p.major_id) + '.' + OBJECT_NAME(p.major_id)
                       + CASE WHEN p.minor_id <> 0 THEN '(' + COL_NAME(p.major_id, p.minor_id) + ')' ELSE '' END
                FROM sys.database_permissions p
                JOIN sys.database_principals u ON u.principal_id = p.grantee_principal_id
                WHERE u.name = 'batch_svc' AND p.state = 'D'
                """).query(String.class).list();
        assertThat(denied).containsExactly("UPDATE batch.statement_capture");
    }

    /**
     * The reset at the top of R__grants_batch.sql must revoke denials too, or a DENY removed from
     * the file would survive the next replay. Its first batch runs here against a scratch user.
     */
    @Test
    void theGrantsResetRevokesADenyOnReplay() throws Exception {
        String sql = Files.readString(GRANTS, StandardCharsets.UTF_8);
        String reset = sql.substring(0, sql.indexOf("\nGO\n"));
        assertThat(reset).contains("N'batch_svc'");
        String probeReset = reset.replace("N'batch_svc'", "N'statement_grant_probe'");
        try (Connection c = DB.adminDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("IF USER_ID('statement_grant_probe') IS NULL CREATE USER statement_grant_probe WITHOUT LOGIN");
            try {
                s.execute("DENY UPDATE ON OBJECT::batch.statement_capture TO statement_grant_probe");
                s.execute("GRANT SELECT ON OBJECT::batch.statement_capture TO statement_grant_probe");
                s.execute(probeReset);
                Integer left = DB.admin().sql("""
                        SELECT COUNT(*) FROM sys.database_permissions p
                        JOIN sys.database_principals u ON u.principal_id = p.grantee_principal_id
                        WHERE u.name = 'statement_grant_probe' AND NOT (p.class = 0 AND p.permission_name = 'CONNECT')
                        """).query(Integer.class).single();
                assertThat(left).as("permissions left after the reset").isZero();
            } finally {
                s.execute("DROP USER statement_grant_probe");
            }
        }
    }
}
