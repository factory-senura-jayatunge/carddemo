package org.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.carddemo.db.ServiceLogin;
import org.junit.jupiter.api.Test;

class AccountServiceSetupTest extends AccountTestBase {

    /**
     * The grants of R__grants_account.sql: no CVV, no balance writes, no audit reads, of a card only
     * the three columns card update changes, and of an account and its customer only the columns
     * account update changes.
     */
    @Test
    void theLoginIsRefusedItsProbes() throws Exception {
        try (Connection c = DB.dataSource(ServiceLogin.ACCOUNT).getConnection(); Statement s = c.createStatement()) {
            s.execute("SELECT TOP 0 card_num, card_id, acct_id, embossed_name, expiration_date, active_status FROM core.card");
            s.execute("UPDATE core.card SET embossed_name = embossed_name, active_status = active_status,"
                    + " expiration_date = expiration_date WHERE 1 = 0");
            s.execute("UPDATE core.account SET active_status = active_status, credit_limit = credit_limit,"
                    + " cash_credit_limit = cash_credit_limit, open_date = open_date,"
                    + " expiration_date = expiration_date, reissue_date = reissue_date, group_id = group_id"
                    + " WHERE 1 = 0");
            s.execute("UPDATE core.customer SET first_name = first_name, middle_name = middle_name,"
                    + " last_name = last_name, addr_line_1 = addr_line_1, addr_line_2 = addr_line_2,"
                    + " addr_line_3 = addr_line_3, state_cd = state_cd, zip = zip, phone_1 = phone_1,"
                    + " phone_2 = phone_2, ssn = ssn, govt_issued_id = govt_issued_id, dob = dob,"
                    + " eft_account_id = eft_account_id, pri_card_holder_ind = pri_card_holder_ind,"
                    + " fico_score = fico_score WHERE 1 = 0");
            for (String probe : ServiceLogin.ACCOUNT.forbiddenProbes()) {
                assertThatThrownBy(() -> s.execute(probe)).as(probe)
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isIn(229, 230));
            }
        }
    }

    /** make up and make status poll it before any user has signed on. */
    @Test
    void healthNeedsNoSession() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
    }

    /** The running service serves every unit of the contract, and no route the contract does not name. */
    @Test
    void theServiceServesTheContractsRoutes() throws Exception {
        String json = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> served = new ArrayList<>();
        new ObjectMapper().readTree(json).path("paths").fieldNames().forEachRemaining(served::add);
        assertThat(served).containsExactlyInAnyOrder("/api/accounts/{acctId}", "/api/accounts/{acctId}/unmask",
                "/api/cards/search", "/api/cards/lookup", "/api/cards/{cardId}", "/api/cards/{cardId}/unmask",
                "/api/accounts/{acctId}/update-form", "/api/accounts/{acctId}/validate",
                "/api/cards/update-lookup", "/api/cards/{cardId}/update-form", "/api/cards/{cardId}/validate");
    }
}
