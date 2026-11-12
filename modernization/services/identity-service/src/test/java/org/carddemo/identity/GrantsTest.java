package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The grants table of the contract for identity_svc, against the migrated database. */
class GrantsTest {

    private static final TestDatabase DB = TestDatabase.shared();

    private final JdbcClient admin = DB.admin();
    private final JdbcClient service = JdbcClient.create(DB.dataSource(ServiceLogin.IDENTITY));

    @AfterEach
    void clean() {
        new IdentityFixtures(admin).deleteAll();
    }

    @Test
    void identitySvcHoldsExactlyTheContractGrants() {
        List<String> granted = admin.sql("""
                SELECT p.permission_name + COALESCE(' ' + OBJECT_SCHEMA_NAME(p.major_id) + '.' + OBJECT_NAME(p.major_id), '')
                       + CASE WHEN p.minor_id <> 0 THEN '(' + COL_NAME(p.major_id, p.minor_id) + ')' ELSE '' END
                FROM sys.database_permissions p
                JOIN sys.database_principals u ON u.principal_id = p.grantee_principal_id
                WHERE u.name = 'identity_svc' AND p.state IN ('G', 'W')
                """).query(String.class).list();
        assertThat(granted).containsExactlyInAnyOrder(
                "CONNECT",
                "SELECT iam.app_user", "INSERT iam.app_user", "UPDATE iam.app_user",
                "SELECT iam.session", "INSERT iam.session", "UPDATE iam.session",
                "SELECT iam.user_signon_state", "INSERT iam.user_signon_state", "UPDATE iam.user_signon_state",
                "INSERT iam.audit_event",
                "INSERT ops.audit", "SELECT ops.batch_window");
        Integer roles = admin.sql("""
                SELECT COUNT(*) FROM sys.database_role_members m
                JOIN sys.database_principals u ON u.principal_id = m.member_principal_id
                WHERE u.name = 'identity_svc'
                """).query(Integer.class).single();
        assertThat(roles).as("no role membership widens the grants").isZero();
    }

    @Test
    void theServiceCanDoWhatSignOnNeeds() {
        service.sql("""
                INSERT INTO iam.app_user (user_id, first_name, last_name, password_hash, user_type)
                VALUES ('GRANT001', 'G', 'T', '{bcrypt}x', 'U')
                """).update();
        service.sql("INSERT INTO iam.user_signon_state (user_id) VALUES ('GRANT001')").update();
        service.sql("UPDATE iam.user_signon_state SET failed_attempts = 1 WHERE user_id = 'GRANT001'").update();
        assertThat(service.sql("SELECT failed_attempts FROM iam.user_signon_state WHERE user_id = 'GRANT001'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(service.sql("SELECT COUNT(*) FROM iam.app_user").query(Integer.class).single()).isEqualTo(1);
        assertThat(service.sql("SELECT COUNT(*) FROM iam.session").query(Integer.class).single()).isZero();
        service.sql("""
                INSERT INTO iam.audit_event (occurred_at, event_type, subject_key, client_ip)
                VALUES (SYSUTCDATETIME(), 'SIGN_ON_FAILED', 'X', '127.0.0.1')
                """).update();
    }

    @Test
    void theServiceCanDoWhatUserAdministrationNeeds() {
        service.sql("""
                INSERT INTO ops.audit (service, actor_user_id, action, subject_type, subject_key)
                VALUES ('identity-service', 'ADMIN001', 'CREATE', 'USER', 'GRANT001')
                """).update();
        assertThat(service.sql("SELECT COUNT(*) FROM ops.batch_window").query(Integer.class).single()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT COUNT(*) FROM iam.audit_event",
            "UPDATE iam.audit_event SET detail = NULL WHERE 1 = 0",
            "DELETE FROM iam.audit_event WHERE 1 = 0",
            "DELETE FROM iam.app_user WHERE 1 = 0",
            "DELETE FROM iam.session WHERE 1 = 0",
            "DELETE FROM iam.user_signon_state WHERE 1 = 0",
            "SELECT COUNT(*) FROM core.account",
            "SELECT COUNT(*) FROM core.customer",
            "SELECT COUNT(*) FROM ops.audit",
            "UPDATE ops.audit SET field_names = NULL WHERE 1 = 0",
            "DELETE FROM ops.audit WHERE 1 = 0",
            "INSERT INTO ops.batch_window (mode) VALUES ('WRITES_BLOCKED')",
            "SELECT COUNT(*) FROM billpay.payment",
            "CREATE TABLE iam.extra (x INT)"})
    void everythingElseIsRefused(String statement) {
        assertThatThrownBy(() -> service.sql(statement).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("denied");
    }
}
