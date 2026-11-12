package org.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** What batch_svc may do on the pauth tables (R__grants_batch.sql), asked of the database as batch_svc. */
class PauthGrantsTest {

    static final TestDatabase DB = TestDatabase.shared();

    private static boolean has(String table, String permission) {
        return has(table, permission, null);
    }

    private static boolean has(String table, String permission, String column) {
        JdbcClient batch = JdbcClient.create(DB.dataSource(ServiceLogin.BATCH));
        String sql = column == null
                ? "SELECT HAS_PERMS_BY_NAME(:t, 'OBJECT', :p)"
                : "SELECT HAS_PERMS_BY_NAME(:t, 'OBJECT', :p, :c, 'COLUMN')";
        var statement = batch.sql(sql).param("t", table).param("p", permission);
        if (column != null) {
            statement = statement.param("c", column);
        }
        return statement.query(Integer.class).single() == 1;
    }

    @Test
    void theLoadAndThePurgeReplaceSummariesAndDetails() {
        for (String table : new String[] {"pauth.auth_summary", "pauth.auth_detail"}) {
            for (String permission : new String[] {"SELECT", "INSERT", "DELETE"}) {
                assertThat(has(table, permission)).as(permission + " " + table).isTrue();
            }
        }
        for (String table : new String[] {"pauth.processed_message", "pauth.outbox"}) {
            assertThat(has(table, "SELECT")).as("SELECT " + table).isTrue();
            assertThat(has(table, "DELETE")).as("DELETE " + table).isTrue();
            assertThat(has(table, "INSERT")).as("INSERT " + table).isFalse();
            assertThat(has(table, "UPDATE")).as("UPDATE " + table).isFalse();
        }
    }

    @Test
    void updatesReachOnlyTheTotalsAndTheMatchColumns() {
        for (String column : new String[] {"credit_balance", "approved_auth_cnt", "approved_auth_amt",
                "declined_auth_cnt", "declined_auth_amt"}) {
            assertThat(has("pauth.auth_summary", "UPDATE", column)).as(column).isTrue();
        }
        for (String column : new String[] {"credit_limit", "cash_limit", "cash_balance", "cust_id"}) {
            assertThat(has("pauth.auth_summary", "UPDATE", column)).as(column).isFalse();
        }
        for (String column : new String[] {"match_status", "matched_tran_pk", "matched_tran_id", "matched_cycle_id",
                "matched_at"}) {
            assertThat(has("pauth.auth_detail", "UPDATE", column)).as(column).isTrue();
        }
        for (String column : new String[] {"auth_fraud", "fraud_rpt_date", "approved_amt", "card_num"}) {
            assertThat(has("pauth.auth_detail", "UPDATE", column)).as(column).isFalse();
        }
    }

    @Test
    void noFraudTableAndNoUnmask() {
        for (String permission : new String[] {"SELECT", "INSERT", "UPDATE", "DELETE"}) {
            assertThat(has("pauth.auth_fraud", permission)).as(permission + " pauth.auth_fraud").isFalse();
        }
        for (String table : new String[] {"pauth.auth_detail", "pauth.processed_message", "pauth.outbox"}) {
            assertThat(has(table, "UNMASK")).as("UNMASK " + table).isFalse();
        }
    }
}
