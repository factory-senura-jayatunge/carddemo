package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.carddemo.db.ServiceLogin;
import org.junit.jupiter.api.Test;

class TransactionServiceSetupTest extends TransactionTestBase {

    /**
     * What the list, the view and the unmask need of R__grants_transaction.sql works, and nothing
     * more of the master or the audit table: no master writes (DEF-TRNA-001, D-27), no audit reads,
     * no tran_pk, and no waiting add's card number.
     */
    @Test
    void theLoginHasItsGrantsAndIsRefusedItsProbes() throws Exception {
        DB.fixtures().deleteAll();
        SampleTransactions.insertDefault(admin, "0000000000000100", "1.00");
        try (Connection c = DB.dataSource(ServiceLogin.TRANSACTION).getConnection(); Statement s = c.createStatement()) {
            s.execute("SELECT TOP 0 tran_id, type_cd, cat_cd, source, description, amount, merchant_id, merchant_name,"
                    + " merchant_city, merchant_zip, card_num, orig_ts, proc_ts, in_master FROM core.[transaction]");
            s.execute("INSERT INTO ops.audit (service, actor_user_id, action, subject_type, subject_key)"
                    + " SELECT 'probe', 'PROBE001', 'UNMASK', 'TRANSACTION', 'x' WHERE 1 = 0");
            // UNMASK on the column: the service reads the clear value and masks it itself.
            try (ResultSet rs = s.executeQuery("SELECT card_num FROM core.[transaction]")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(SampleTransactions.F25_CARD);
            }
            List<String> probes = new ArrayList<>(ServiceLogin.TRANSACTION.forbiddenProbes());
            probes.addAll(List.of(
                    "INSERT INTO core.[transaction] (tran_id) SELECT '0000000000000001' WHERE 1 = 0",
                    "UPDATE core.[transaction] SET amount = amount WHERE 1 = 0",
                    "DELETE FROM core.[transaction] WHERE 1 = 0",
                    "SELECT TOP 0 tran_pk FROM core.[transaction]",
                    "SELECT TOP 0 card_num FROM core.daily_tran",
                    "UPDATE core.daily_tran SET amount = amount WHERE 1 = 0",
                    "DELETE FROM core.daily_tran WHERE 1 = 0",
                    "SELECT TOP 0 actor_user_id FROM ops.audit",
                    "UPDATE ops.audit SET field_names = field_names WHERE 1 = 0",
                    "SELECT TOP 0 card_num FROM core.card"));
            for (String probe : probes) {
                assertThatThrownBy(() -> s.execute(probe)).as(probe)
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isIn(229, 230));
            }
        } finally {
            DB.fixtures().deleteAll();
        }
    }

    /** make up and make status poll it before any user has signed on. */
    @Test
    void healthNeedsNoSession() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
    }

    /** The running service serves the built units' routes, and no route the contract does not name. */
    @Test
    void theServiceServesTheContractsRoutes() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> served = new ArrayList<>();
        json.readTree(body).path("paths").fieldNames().forEachRemaining(served::add);
        assertThat(served).containsExactlyInAnyOrder("/api/transactions", "/api/transactions/{tranId}",
                "/api/transactions/{tranId}/unmask", "/api/transactions/validate", "/api/transactions/latest",
                "/api/reports/transactions", "/api/reports/transactions/{runId}",
                "/api/reports/transactions/{runId}/file");
    }
}
