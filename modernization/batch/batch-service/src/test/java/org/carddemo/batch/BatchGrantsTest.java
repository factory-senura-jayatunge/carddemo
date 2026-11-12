package org.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Every permission batch_svc holds, against the migrated database: exactly R__grants_batch.sql, so
 * a grant that widens it (here or in another owner's file) fails this test.
 */
class BatchGrantsTest {

    static final TestDatabase DB = TestDatabase.shared();

    private static List<String> on(String object, String... permissions) {
        List<String> list = new ArrayList<>();
        for (String permission : permissions) {
            list.add(permission + " " + object);
        }
        return list;
    }

    private static List<String> update(String object, String... columns) {
        return columns("UPDATE", object, columns);
    }

    private static List<String> columns(String permission, String object, String... columns) {
        List<String> list = new ArrayList<>();
        for (String column : columns) {
            list.add(permission + " " + object + "(" + column + ")");
        }
        return list;
    }

    @Test
    void batchSvcHoldsExactlyItsGrants() {
        JdbcClient admin = DB.admin();
        List<String> granted = admin.sql("""
                SELECT p.permission_name
                       + CASE p.class
                           WHEN 1 THEN ' ' + OBJECT_SCHEMA_NAME(p.major_id) + '.' + OBJECT_NAME(p.major_id)
                                + CASE WHEN p.minor_id <> 0 THEN '(' + COL_NAME(p.major_id, p.minor_id) + ')' ELSE '' END
                           WHEN 3 THEN ' SCHEMA::' + SCHEMA_NAME(p.major_id)
                           WHEN 0 THEN ''
                           ELSE ' class ' + CAST(p.class AS VARCHAR(10)) + ':' + CAST(p.major_id AS VARCHAR(20))
                         END
                       + CASE WHEN p.state = 'W' THEN ' WITH GRANT OPTION' ELSE '' END
                FROM sys.database_permissions p
                JOIN sys.database_principals u ON u.principal_id = p.grantee_principal_id
                WHERE u.name = 'batch_svc' AND p.state IN ('G', 'W')
                """).query(String.class).list();

        List<String> expected = new ArrayList<>(List.of("CONNECT"));
        for (String table : new String[] {"core.account", "core.customer", "core.card", "core.card_xref",
                "core.tcat_bal", "core.transaction", "core.daily_tran", "core.daily_reject", "ref.tran_type",
                "ref.tran_category", "ref.disclosure_group", "ref.us_state", "ref.us_state_zip_prefix",
                "ref.nanp_area_code", "pauth.auth_summary", "pauth.auth_detail"}) {
            expected.addAll(on(table, "SELECT", "INSERT", "DELETE"));
        }
        expected.addAll(update("core.transaction", "in_master"));
        expected.addAll(on("core.tran_id_seq", "ALTER", "UPDATE"));
        // By column, and nothing on core.card: the CVV stays masked (D-DATA-04).
        expected.addAll(columns("UNMASK", "core.customer", "ssn", "govt_issued_id", "dob", "eft_account_id"));
        expected.addAll(columns("UNMASK", "core.transaction", "card_num"));
        expected.addAll(columns("UNMASK", "core.daily_tran", "card_num"));
        expected.addAll(update("core.account", "curr_bal", "curr_cyc_credit", "curr_cyc_debit"));
        expected.addAll(update("core.tcat_bal", "balance"));
        expected.addAll(update("core.daily_tran", "cycle_id", "seq"));
        expected.add("SELECT core.v_daily_reject");
        expected.addAll(update("pauth.auth_summary", "credit_balance", "approved_auth_cnt", "approved_auth_amt",
                "declined_auth_cnt", "declined_auth_amt"));
        expected.addAll(update("pauth.auth_detail", "match_status", "matched_tran_pk", "matched_tran_id",
                "matched_cycle_id", "matched_at"));
        expected.addAll(on("pauth.processed_message", "SELECT", "DELETE"));
        expected.addAll(on("pauth.outbox", "SELECT", "DELETE"));
        expected.addAll(on("ops.batch_window", "SELECT", "INSERT"));
        expected.addAll(update("ops.batch_window", "closed_at"));
        expected.addAll(on("SCHEMA::batch", "SELECT", "INSERT", "UPDATE", "DELETE"));
        expected.addAll(on("ops.database_instance", "SELECT"));

        assertThat(granted).containsExactlyInAnyOrderElementsOf(expected);

        Integer roles = admin.sql("""
                SELECT COUNT(*) FROM sys.database_role_members m
                JOIN sys.database_principals u ON u.principal_id = m.member_principal_id
                WHERE u.name = 'batch_svc'
                """).query(Integer.class).single();
        assertThat(roles).as("no role membership widens the grants").isZero();
    }
}
