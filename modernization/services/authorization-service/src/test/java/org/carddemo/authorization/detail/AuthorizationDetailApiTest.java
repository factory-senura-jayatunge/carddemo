package org.carddemo.authorization.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.carddemo.authorization.AuthorizationTestBase;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestClock;
import org.carddemo.platform.testsupport.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract §11 A10 to A19, A22 and A24 through the API on the migrated database, as the
 * authorization login. Synthetic rows per test: account {@code 00000000091} with the summary and
 * details inserted as the admin login.
 */
@AutoConfigureMockMvc
class AuthorizationDetailApiTest extends AuthorizationTestBase {

    static final String ACCT = "00000000091";
    static final String CUST = "000000091";
    static final String CARD = "4111111111111111";
    static final String USER = "USER0001";
    static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 10, 9, 0, 0, 123_000_000);

    @Autowired
    MockMvc mvc;

    final JdbcClient admin = DB.admin();
    final String token = TestTokens.user(USER);

    @BeforeEach
    void fixtures() {
        TestClock.shared().reset();
        DB.fixtures().deleteAll().account(CoreFixtures.Account.of(ACCT, "200.00")).customer(CUST)
                .card(CARD, ACCT, CUST);
        admin.sql("""
                        INSERT INTO pauth.auth_summary (acct_id, cust_id, credit_limit, cash_limit, credit_balance,
                            cash_balance, approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt)
                        VALUES (:acct, :cust, 2020.00, 1020.00, 0.00, 0.00, 0, 0, 0.00, 0.00)""")
                .param("acct", ACCT).param("cust", CUST).update();
    }

    /** A detail of the account processed at {@code at}; returns its auth_id. */
    long detail(LocalDateTime at, int n, String resp, String reason) {
        return admin.sql("""
                        INSERT INTO pauth.auth_detail (acct_id, processed_at, auth_date_9c, auth_time_9c, orig_date,
                            orig_time, card_num, auth_type, card_expiry_date, message_type, message_source,
                            auth_id_code, auth_resp_code, auth_resp_reason, processing_code, transaction_amt,
                            approved_amt, merchant_category_code, acqr_country_code, pos_entry_mode, merchant_id,
                            merchant_name, merchant_city, merchant_state, merchant_zip, transaction_id, match_status)
                        OUTPUT inserted.auth_id
                        VALUES (:acct, :at, :date9c, :time9c, '240405', '143015', :card, 'SALE', '0426', '0100',
                            'POS   ', :code, :resp, :reason, '000000', 50.00, :approved, '5411', '840', '01',
                            'MERCH0000000001', 'GROCERY STORE', 'SPRINGFIELD', 'IL', '62701', :tran, 'P')""")
                .param("acct", ACCT).param("at", at).param("date9c", 99999 - n).param("time9c", 999_999_999 - n)
                .param("card", CARD).param("code", String.format("%06d", 1000 + n)).param("resp", resp)
                .param("reason", reason).param("approved", "00".equals(resp) ? "50.00" : "0.00")
                .param("tran", String.format("TRN%012d", n))
                .query(Long.class).single();
    }

    long detail(int n) {
        return detail(T0.plusMinutes(n), n, "00", "0000");
    }

    ResultActions view(long authId) throws Exception {
        return mvc.perform(get("/api/authorizations/{id}", authId).header("Authorization", "Bearer " + token));
    }

    ResultActions mark(long authId, String ifMatch, String action) throws Exception {
        var request = post("/api/authorizations/{id}/fraud", authId).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"" + action + "\"}");
        return mvc.perform(ifMatch == null ? request : request.header("If-Match", ifMatch));
    }

    String etag(long authId) {
        return '"' + admin.sql("SELECT row_version FROM pauth.auth_detail WHERE auth_id = :id").param("id", authId)
                .query((rs, n) -> HexFormat.of().formatHex(rs.getBytes(1))).single() + '"';
    }

    Map<String, Object> detailRow(long authId) {
        return admin.sql("SELECT auth_fraud, fraud_rpt_date, fraud_changed_by, match_status FROM pauth.auth_detail"
                + " WHERE auth_id = :id").param("id", authId).query().singleRow();
    }

    List<Map<String, Object>> fraudRows() {
        return admin.sql("SELECT auth_id, card_num, auth_ts, pos_entry_mode, auth_fraud, fraud_rpt_date, acct_id,"
                + " cust_id, reported_by, auth_id_code FROM pauth.auth_fraud ORDER BY auth_id").query().listOfRows();
    }

    List<Map<String, Object>> audits() {
        return admin.sql("SELECT service, actor_user_id, action, subject_type, subject_key, field_names FROM ops.audit"
                + " ORDER BY 1").query().listOfRows();
    }

    static String today() {
        return LocalDate.now(TestClock.shared()).toString();
    }

    static String todayShown() {
        return LocalDate.now(TestClock.shared()).format(DateTimeFormatter.ofPattern("MM/dd/yy"));
    }

    @Test
    @DisplayName("A10: every field; reasonText from the table; authIdCode, not the processing code; card masked")
    @SpecItem({"BR-AUDT-002", "BR-AUDT-003", "BR-AUDT-004", "DEF-AUDT-002"})
    void detailFields() throws Exception {
        long older = detail(1);
        long id = detail(2);

        view(id).andExpect(status().isOk())
                .andExpect(header().string("ETag", etag(id)))
                .andExpect(jsonPath("$.authId").value(id))
                .andExpect(jsonPath("$.acctId").value(ACCT))
                .andExpect(jsonPath("$.version").value(etag(id).replace("\"", "")))
                .andExpect(jsonPath("$.cardNum").value("************1111"))
                .andExpect(jsonPath("$.requestDate").value("04/05/24"))
                .andExpect(jsonPath("$.requestTime").value("14:30:15"))
                .andExpect(jsonPath("$.approval").value("A"))
                .andExpect(jsonPath("$.respCode").value("00"))
                .andExpect(jsonPath("$.reason").value("0000"))
                .andExpect(jsonPath("$.reasonText").value("0000-APPROVED"))
                .andExpect(jsonPath("$.authIdCode").value("001002"))
                .andExpect(jsonPath("$.processingCode").value("000000"))
                .andExpect(jsonPath("$.approvedAmount").value("50.00"))
                .andExpect(jsonPath("$.transactionAmount").value("50.00"))
                .andExpect(jsonPath("$.posEntryMode").value("01"))
                .andExpect(jsonPath("$.messageSource").value("POS   "))
                .andExpect(jsonPath("$.merchantCategoryCode").value("5411"))
                .andExpect(jsonPath("$.cardExpiry").value("04/26"))
                .andExpect(jsonPath("$.authType").value("SALE"))
                .andExpect(jsonPath("$.transactionId").value("TRN000000000002"))
                .andExpect(jsonPath("$.matchStatus").value("P"))
                .andExpect(jsonPath("$.matchedTranId").value(nullValue()))
                .andExpect(jsonPath("$.fraud.flag").value(nullValue()))
                .andExpect(jsonPath("$.fraud.reportDate").value(nullValue()))
                .andExpect(jsonPath("$.fraud.display").value("-"))
                .andExpect(jsonPath("$.merchant.name").value("GROCERY STORE"))
                .andExpect(jsonPath("$.merchant.id").value("MERCH0000000001"))
                .andExpect(jsonPath("$.merchant.city").value("SPRINGFIELD"))
                .andExpect(jsonPath("$.merchant.state").value("IL"))
                .andExpect(jsonPath("$.merchant.zip").value("62701"))
                .andExpect(jsonPath("$.nextOlderAuthId").value(older));
    }

    @Test
    @DisplayName("A11: an unknown reason is 9999-ERROR")
    @SpecItem("BR-AUDT-003")
    void unknownReason() throws Exception {
        long id = detail(T0, 1, "05", "1234");
        view(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.reasonText").value("9999-ERROR"))
                .andExpect(jsonPath("$.approval").value("D"))
                .andExpect(jsonPath("$.approvedAmount").value("0.00"));
    }

    @Test
    @DisplayName("A12: the oldest row has no next older; rows at the same time are ordered by auth_id")
    @SpecItem({"BR-AUDT-006", "VAL-AUDT-003"})
    void nextOlder() throws Exception {
        long first = detail(T0, 1, "00", "0000");
        long second = detail(T0, 2, "00", "0000");
        long third = detail(T0.plusMinutes(1), 3, "00", "0000");

        view(third).andExpect(jsonPath("$.nextOlderAuthId").value(second));
        view(second).andExpect(jsonPath("$.nextOlderAuthId").value(first));
        view(first).andExpect(status().isOk()).andExpect(jsonPath("$.nextOlderAuthId").value(nullValue()));
    }

    @Test
    @DisplayName("A13: a matched row shows M and matchedTranId")
    @SpecItem("BR-AUDT-002")
    void matchedRow() throws Exception {
        long id = detail(1);
        matchRow(id);
        view(id).andExpect(jsonPath("$.matchStatus").value("M"))
                .andExpect(jsonPath("$.matchedTranId").value("0000000000000301"));
    }

    void matchRow(long id) {
        admin.sql("""
                        UPDATE pauth.auth_detail SET match_status = 'M', matched_tran_pk = 301,
                            matched_tran_id = '0000000000000301', matched_cycle_id = 1, matched_at = SYSUTCDATETIME()
                        WHERE auth_id = :id""").param("id", id).update();
    }

    @Test
    @DisplayName("A14: F on a blank row, then R: one fraud row with the user, two audit rows, field names only")
    @SpecItem({"BR-AUDT-007", "BR-AUDT-008", "SE-AUDT-002", "BR-AUFR-002", "BR-AUFR-003", "DEF-AUDT-006",
        "DEF-JCSD2-001"})
    void markThenRemove() throws Exception {
        long id = detail(1);
        String before = etag(id);

        mark(id, before, "F").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("AUTH MARKED FRAUD..."))
                .andExpect(jsonPath("$.severity").value("info"))
                .andExpect(jsonPath("$.detail.fraud.flag").value("F"))
                .andExpect(jsonPath("$.detail.fraud.reportDate").value(today()))
                .andExpect(jsonPath("$.detail.fraud.display").value("F-" + todayShown()))
                .andExpect(header().string("ETag", etag(id)));
        assertThat(etag(id)).isNotEqualTo(before);
        assertThat(detailRow(id)).containsEntry("auth_fraud", "F").containsEntry("fraud_changed_by", USER);
        assertThat(fraudRows()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("auth_fraud", "F").containsEntry("reported_by", USER)
                    .containsEntry("acct_id", ACCT).containsEntry("cust_id", CUST).containsEntry("card_num", CARD)
                    .containsEntry("auth_id_code", "001001");
            assertThat(((Number) row.get("pos_entry_mode")).intValue()).isEqualTo(1);
            assertThat(row.get("fraud_rpt_date").toString()).isEqualTo(today());
        });

        mark(id, etag(id), "R").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("AUTH FRAUD REMOVED..."))
                .andExpect(jsonPath("$.detail.fraud.display").value("R-" + todayShown()));
        assertThat(fraudRows()).singleElement().satisfies(row -> assertThat(row).containsEntry("auth_fraud", "R"));
        assertThat(audits()).hasSize(2).allSatisfy(row -> assertThat(row)
                .containsEntry("service", "authorization-service").containsEntry("actor_user_id", USER)
                .containsEntry("action", "UPDATE").containsEntry("subject_type", "AUTHORIZATION")
                .containsEntry("subject_key", String.valueOf(id))
                .containsEntry("field_names", "fraud.flag,fraud.reportDate"));
    }

    @Test
    @DisplayName("A15: a stale If-Match is 409 RECORD_CHANGED with current and a fresh ETag")
    @SpecItem("BR-AUDT-007")
    void staleVersion() throws Exception {
        long id = detail(1);
        String stale = etag(id);
        matchRow(id);

        mark(id, stale, "F").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_CHANGED"))
                .andExpect(jsonPath("$.message").value("Record changed by some one else. Please review"))
                .andExpect(jsonPath("$.current.version").value(etag(id).replace("\"", "")))
                .andExpect(header().string("ETag", etag(id)));
        assertThat(fraudRows()).isEmpty();
        assertThat(audits()).isEmpty();
    }

    @Test
    @DisplayName("A16: R on a blank row is 409 RECORD_CHANGED; nothing is written")
    @SpecItem("BR-AUDT-007")
    void removeOnABlankRow() throws Exception {
        long id = detail(1);
        mark(id, etag(id), "R").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_CHANGED"))
                .andExpect(jsonPath("$.current.fraud.flag").value(nullValue()));
        assertThat(detailRow(id)).containsEntry("auth_fraud", " ");
        assertThat(fraudRows()).isEmpty();
    }

    @Test
    @DisplayName("A17: a failing fraud-row insert is 500 FRAUD_UPDATE_FAILED; the detail is unchanged")
    @SpecItem({"ERR-AUDT-006", "SE-AUDT-002"})
    void insertFails() throws Exception {
        long id = detail(1);
        String before = etag(id);
        admin.sql("DENY INSERT ON OBJECT::pauth.auth_fraud TO authorization_svc").update();
        try {
            mark(id, before, "F").andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("FRAUD_UPDATE_FAILED"))
                    .andExpect(jsonPath("$.message").value(" System error while FRAUD Tagging, ROLLBACK"));
        } finally {
            admin.sql("GRANT INSERT ON OBJECT::pauth.auth_fraud TO authorization_svc").update();
        }
        assertThat(etag(id)).isEqualTo(before);
        assertThat(detailRow(id)).containsEntry("auth_fraud", " ").containsEntry("fraud_changed_by", null);
        assertThat(fraudRows()).isEmpty();
        assertThat(audits()).isEmpty();
    }

    @Test
    @DisplayName("A18: a purged detail is 404 AUTHORIZATION_NOT_FOUND on the view and the toggle")
    @SpecItem("DEF-AUDT-004")
    void purged() throws Exception {
        long id = detail(1);
        String tag = etag(id);
        admin.sql("DELETE FROM pauth.auth_detail WHERE auth_id = :id").param("id", id).update();

        view(id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Authorization not found..."));
        mark(id, tag, "F").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_NOT_FOUND"));
        mvc.perform(get("/api/authorizations/{id}", "0").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AUTH_ID"));
    }

    @Test
    @DisplayName("A19: same card, request date and time of day on different days, both marked: two fraud rows")
    @SpecItem("DEF-AUFR-001")
    void twoFraudRows() throws Exception {
        long first = detail(T0, 1, "00", "0000");
        long second = detail(T0.plusDays(1), 2, "00", "0000");

        mark(first, etag(first), "F").andExpect(status().isOk());
        mark(second, etag(second), "F").andExpect(status().isOk());

        assertThat(fraudRows()).extracting(row -> row.get("auth_id")).containsExactly(first, second);
        assertThat(fraudRows()).extracting(row -> row.get("auth_ts").toString()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("A22: the fraud row's cust_id is the lowest card's customer, not the summary's")
    @SpecItem("BR-AUFR-002")
    void lowestCardsCustomer() throws Exception {
        DB.fixtures().customer("000000092").card("4000000000000001", ACCT, "000000092");
        admin.sql("UPDATE pauth.auth_summary SET cust_id = :cust WHERE acct_id = :acct")
                .param("cust", CUST).param("acct", ACCT).update();
        long id = detail(1);

        mark(id, etag(id), "F").andExpect(status().isOk());

        assertThat(fraudRows()).singleElement().satisfies(row -> assertThat(row).containsEntry("cust_id", "000000092"));
    }

    @Test
    @DisplayName("A24: a D-33 match committed between the view and the POST is 409; confirmed again it stays M")
    @SpecItem({"BR-AUDT-007", "SE-AUDT-001"})
    void matchSinceTheView() throws Exception {
        long id = detail(1);
        String viewed = view(id).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        matchRow(id);

        mark(id, viewed, "F").andExpect(status().isConflict())
                .andExpect(jsonPath("$.current.matchStatus").value("M"));
        mark(id, etag(id), "F").andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.matchStatus").value("M"))
                .andExpect(jsonPath("$.detail.fraud.flag").value("F"));
        assertThat(detailRow(id)).containsEntry("match_status", "M").containsEntry("auth_fraud", "F");
    }
}
