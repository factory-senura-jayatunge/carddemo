package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.carddemo.db.EffectiveGrants;
import org.carddemo.db.ServiceLogin;
import org.junit.jupiter.api.Test;

/**
 * inquiry_svc holds exactly R__grants_inquiry.sql: SELECT on eleven columns of core.account, and every
 * probe of ServiceLogin.INQUIRY is refused.
 */
class InquiryGrantsTest {

    private static final List<String> GRANTED = EffectiveGrants.INQUIRY_ACCOUNT_COLUMNS;

    private final TestDatabase db = TestDatabase.shared();

    @Test
    void theAccountInquiryReadsItsColumnsAndIsRefusedEveryProbe() throws SQLException {
        try (Connection c = db.dataSource(ServiceLogin.INQUIRY).getConnection(); Statement s = c.createStatement()) {
            s.executeQuery("SELECT TOP 0 " + String.join(", ", GRANTED) + " FROM core.account").close();
            for (String probe : ServiceLogin.INQUIRY.forbiddenProbes()) {
                assertThatThrownBy(() -> s.execute(probe)).as(probe)
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isIn(229, 230));
            }
        }
    }

    @Test
    void theCatalogHoldsOnlyTheColumnGrant() {
        List<String> held = db.admin().sql("""
                SELECT p.permission_name + N' ' + OBJECT_SCHEMA_NAME(p.major_id) + N'.' + OBJECT_NAME(p.major_id)
                       + CASE WHEN p.minor_id = 0 THEN N'' ELSE N'(' + COL_NAME(p.major_id, p.minor_id) + N')' END
                FROM sys.database_permissions AS p
                JOIN sys.database_principals AS u ON u.principal_id = p.grantee_principal_id
                WHERE u.name = N'inquiry_svc' AND p.state IN ('G', 'W')
                  AND NOT (p.class = 0 AND p.permission_name = N'CONNECT')
                """).query(String.class).list();
        assertThat(held).containsExactlyInAnyOrderElementsOf(GRANTED.stream().map(c -> "SELECT core.account(" + c + ")").toList());
    }
}
