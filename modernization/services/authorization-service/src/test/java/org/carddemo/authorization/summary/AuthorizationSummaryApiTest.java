package org.carddemo.authorization.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import org.carddemo.authorization.AuthorizationTestBase;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.CoreFixtures.Customer;
import org.carddemo.platform.testsupport.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract §11 A1 to A9 through the API on the migrated database, as the authorization login.
 * Synthetic rows per test: account {@code 00000000091}, customer {@code 000000091}, and details
 * one minute apart, inserted as the admin login.
 */
@AutoConfigureMockMvc
class AuthorizationSummaryApiTest extends AuthorizationTestBase {

    static final String ACCT = "00000000091";
    static final String CUST = "000000091";
    static final String CARD = "4111111111111111";
    static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 10, 9, 0, 0, 123_000_000);

    @Autowired
    MockMvc mvc;

    final JdbcClient admin = DB.admin();
    final String token = TestTokens.user("USER0001");

    @BeforeEach
    void fixtures() {
        Customer customer = new Customer(CUST, "Immanuel", "Madeline", "Kessler", "618 Deshaun Route", "Apt. 802",
                "Altenwerthshire", "NC", "USA", "12546", "(908)119-8310", "", "000000000", "", null, "0000000000",
                "Y", 700);
        DB.fixtures().deleteAll().account(CoreFixtures.Account.of(ACCT, "200.00")).customer(customer)
                .card(CARD, ACCT, CUST);
    }

    ResultActions call(String path, Object... args) throws Exception {
        return mvc.perform(get(path, args).header("Authorization", "Bearer " + token));
    }

    void summary(String acctId, int approved, String approvedAmt, int declined, String declinedAmt, String credit) {
        admin.sql("""
                        INSERT INTO pauth.auth_summary (acct_id, cust_id, credit_limit, cash_limit, credit_balance,
                            cash_balance, approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt)
                        VALUES (:acct, :cust, 2020.00, 1020.00, :credit, 0.00, :a, :d, :aAmt, :dAmt)""")
                .param("acct", acctId).param("cust", CUST).param("credit", credit)
                .param("a", approved).param("d", declined).param("aAmt", approvedAmt).param("dAmt", declinedAmt)
                .update();
    }

    /** Detail {@code n} of the account, processed {@code n} minutes after T0; returns its auth_id. */
    long detail(int n, String resp) {
        LocalDateTime at = T0.plusMinutes(n);
        return admin.sql("""
                        INSERT INTO pauth.auth_detail (acct_id, processed_at, auth_date_9c, auth_time_9c, orig_date,
                            orig_time, card_num, auth_type, card_expiry_date, message_type, message_source,
                            auth_id_code, auth_resp_code, auth_resp_reason, processing_code, transaction_amt,
                            approved_amt, merchant_category_code, acqr_country_code, pos_entry_mode, merchant_id,
                            merchant_name, merchant_city, merchant_state, merchant_zip, transaction_id, match_status)
                        OUTPUT inserted.auth_id
                        VALUES (:acct, :at, 99999, :time9c, '261110', '090000', :card, 'SALE', '0426', '0100',
                            'POS   ', '000000', :resp, :reason, '000000', 50.00, :approved, '5411', '840', '01',
                            'MERCH0000000001', 'GROCERY STORE', 'SPRINGFIELD', 'IL', '62701', :tran, 'P')""")
                .param("acct", ACCT).param("at", at).param("time9c", 999_999_999 - n).param("card", CARD)
                .param("resp", resp).param("reason", "00".equals(resp) ? "0000" : "4100")
                .param("approved", "00".equals(resp) ? "50.00" : "0.00")
                .param("tran", String.format("TRN%012d", n))
                .query(Long.class).single();
    }

    List<String> transactionIds(String body) {
        return JsonPath.read(body, "$.items[*].transactionId");
    }

    String page(String query) throws Exception {
        return call("/api/accounts/{a}/authorizations" + query, ACCT).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("A1: header, limits and the six totals")
    @SpecItem({"BR-AUSM-001", "BR-AUSM-002", "DEF-AUSM-007", "DEF-JCSD2-001"})
    void summaryOfAnAccountWithASummary() throws Exception {
        summary(ACCT, 1, "50.00", 2, "1250.75", "50.00");

        call("/api/accounts/{a}/authorizations/summary", ACCT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acctId").value(ACCT))
                .andExpect(jsonPath("$.customer.custId").value(CUST))
                .andExpect(jsonPath("$.customer.name").value("Immanuel M Kessler"))
                .andExpect(jsonPath("$.customer.addressLine1").value("618 Deshaun Route,Apt. 802"))
                .andExpect(jsonPath("$.customer.addressLine2").value("Altenwerthshire,NC,12546"))
                .andExpect(jsonPath("$.customer.phone").value("(908)119-8310"))
                .andExpect(jsonPath("$.creditLimit").value("2020.00"))
                .andExpect(jsonPath("$.cashLimit").value("1020.00"))
                .andExpect(jsonPath("$.summaryFound").value(true))
                .andExpect(jsonPath("$.approvedCount").value(1))
                .andExpect(jsonPath("$.declinedCount").value(2))
                .andExpect(jsonPath("$.approvedAmount").value("50.00"))
                .andExpect(jsonPath("$.declinedAmount").value("1250.75"))
                .andExpect(jsonPath("$.creditBalance").value("50.00"))
                .andExpect(jsonPath("$.cashBalance").value("0.00"))
                .andExpect(jsonPath("$.message").value(nullValue()))
                .andExpect(jsonPath("$.severity").value(nullValue()));
    }

    @Test
    @DisplayName("A2: no summary: summaryFound false, zeros, empty list")
    @SpecItem("BR-AUSM-002")
    void summaryOfAnAccountWithoutASummary() throws Exception {
        call("/api/accounts/{a}/authorizations/summary", ACCT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summaryFound").value(false))
                .andExpect(jsonPath("$.approvedCount").value(0))
                .andExpect(jsonPath("$.approvedAmount").value("0.00"))
                .andExpect(jsonPath("$.creditBalance").value("0.00"));
        assertThat(transactionIds(page(""))).isEmpty();
    }

    @Test
    @DisplayName("A3, A4: account ID checks, then 404 with the ACCT text")
    @SpecItem({"VAL-AUSM-001", "VAL-AUSM-002", "ERR-AUSM-002"})
    void accountChecks() throws Exception {
        call("/api/accounts/{a}/authorizations/summary", " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ID_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Please enter Acct Id..."))
                .andExpect(jsonPath("$.field").value("acctId"));
        for (String bad : new String[] {"123", "0000000000A"}) {
            call("/api/accounts/{a}/authorizations", bad)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_ID_NOT_NUMERIC"))
                    .andExpect(jsonPath("$.message").value("Acct Id must be Numeric ..."));
        }
        call("/api/accounts/{a}/authorizations/summary", "99999999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Account:99999999999 not found in ACCT file."));
    }

    @Test
    @DisplayName("A5: an account with three cards shows the lowest card's customer, without an error")
    @SpecItem({"DEF-AUSM-010", "BR-AUSM-001"})
    void severalCards() throws Exception {
        DB.fixtures().customer("000000092").customer("000000093")
                .card("4111111111111112", ACCT, "000000092").card("4000000000000001", ACCT, "000000093");

        call("/api/accounts/{a}/authorizations/summary", ACCT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.custId").value("000000093"))
                .andExpect(jsonPath("$.message").value(nullValue()));
    }

    @Test
    @DisplayName("A6: no cross-reference row: customer null, the XREF text, totals still returned")
    @SpecItem("ERR-AUSM-001")
    void noCrossReference() throws Exception {
        DB.fixtures().account(CoreFixtures.Account.of("00000000092", "0.00"));
        admin.sql("""
                        INSERT INTO pauth.auth_summary (acct_id, cust_id, credit_limit, cash_limit, credit_balance,
                            cash_balance, approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt)
                        VALUES ('00000000092', :cust, 2020.00, 1020.00, 0.00, 0.00, 0, 3, 0.00, 75.00)""")
                .param("cust", CUST).update();

        call("/api/accounts/{a}/authorizations/summary", "00000000092")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer").value(nullValue()))
                .andExpect(jsonPath("$.message").value("Account:00000000092 not found in XREF file."))
                .andExpect(jsonPath("$.severity").value("error"))
                .andExpect(jsonPath("$.declinedCount").value(3))
                .andExpect(jsonPath("$.declinedAmount").value("75.00"));
    }

    @Test
    @DisplayName("A7: 12 details: 5, 5, 2 newest first; before twice back to the first page")
    @SpecItem({"BR-AUSM-003", "BR-AUSM-004", "BR-AUSM-005", "BR-AUSM-006", "DEF-AUSM-002"})
    void paging() throws Exception {
        summary(ACCT, 12, "600.00", 0, "0.00", "600.00");
        for (int n = 1; n <= 12; n++) {
            detail(n, "00");
        }
        String p1 = page("");
        assertThat(transactionIds(p1)).containsExactly(tran(12), tran(11), tran(10), tran(9), tran(8));
        assertFlags(p1, true, false);

        String p2 = page("?after=" + key(p1, "lastKey") + "&size=5");
        assertThat(transactionIds(p2)).containsExactly(tran(7), tran(6), tran(5), tran(4), tran(3));
        assertFlags(p2, true, true);

        String p3 = page("?after=" + key(p2, "lastKey"));
        assertThat(transactionIds(p3)).containsExactly(tran(2), tran(1));
        assertFlags(p3, false, true);

        String back2 = page("?before=" + key(p3, "firstKey"));
        assertThat(transactionIds(back2)).isEqualTo(transactionIds(p2));
        assertFlags(back2, true, true);

        String back1 = page("?before=" + key(back2, "firstKey"));
        assertThat(transactionIds(back1)).isEqualTo(transactionIds(p1));
        assertFlags(back1, true, false);

        assertThat(JsonPath.<String>read(p1, "$.items[0].requestDate")).isEqualTo("11/10/26");
        assertThat(JsonPath.<String>read(p1, "$.items[0].requestTime")).isEqualTo("09:00:00");
        assertThat(JsonPath.<String>read(p1, "$.items[0].approval")).isEqualTo("A");
        assertThat(JsonPath.<String>read(p1, "$.items[0].approvedAmount")).isEqualTo("50.00");
    }

    @Test
    @DisplayName("A8: the after cursor of a row purged since gives the next five older rows")
    @SpecItem("BR-AUSM-005")
    void cursorOfAPurgedRow() throws Exception {
        summary(ACCT, 12, "600.00", 0, "0.00", "600.00");
        long eighth = 0;
        for (int n = 1; n <= 12; n++) {
            long id = detail(n, "00");
            if (n == 8) {
                eighth = id;
            }
        }
        String p1 = page("");
        admin.sql("DELETE FROM pauth.auth_detail WHERE auth_id = :id").param("id", eighth).update();

        String p2 = page("?after=" + key(p1, "lastKey"));

        assertThat(transactionIds(p2)).containsExactly(tran(7), tran(6), tran(5), tran(4), tran(3));
    }

    @Test
    @DisplayName("A9: exactly five details: hasNext false")
    @SpecItem({"BR-AUSM-004", "DEF-AUSM-011"})
    void exactlyFive() throws Exception {
        summary(ACCT, 4, "200.00", 1, "0.00", "200.00");
        for (int n = 1; n <= 5; n++) {
            detail(n, n == 3 ? "05" : "00");
        }
        String p = page("");
        assertThat(transactionIds(p)).hasSize(5);
        assertFlags(p, false, false);
        assertThat(JsonPath.<String>read(p, "$.items[2].approval")).isEqualTo("D");
        assertThat(JsonPath.<String>read(p, "$.items[2].approvedAmount")).isEqualTo("0.00");
    }

    @Test
    @DisplayName("size other than 5 and malformed cursors are 400")
    @SpecItem("BR-AUSM-006")
    void pageRequestChecks() throws Exception {
        call("/api/accounts/{a}/authorizations?size=7", ACCT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));
        call("/api/accounts/{a}/authorizations?after=nonsense", ACCT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    static String tran(int n) {
        return String.format("TRN%012d", n);
    }

    static String key(String body, String name) {
        return JsonPath.read(body, "$." + name);
    }

    static void assertFlags(String body, boolean hasNext, boolean hasPrevious) {
        assertThat(JsonPath.<Boolean>read(body, "$.hasNext")).as("hasNext").isEqualTo(hasNext);
        assertThat(JsonPath.<Boolean>read(body, "$.hasPrevious")).as("hasPrevious").isEqualTo(hasPrevious);
    }
}
