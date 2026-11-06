package org.carddemo.billpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.ledger.NewTransaction;
import org.carddemo.platform.ledger.Origin;
import org.carddemo.platform.ledger.TransactionIds;
import org.carddemo.platform.ledger.TransactionWriter;
import org.carddemo.platform.testsupport.BothModes;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.FidelityOverrides;
import org.carddemo.platform.testsupport.TestClock;
import org.carddemo.platform.testsupport.TestTokens;
import org.carddemo.platform.web.MessageCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Section 10's BILL scenarios against the API, each in both modes: the stored sign and posting of
 * the payment (DEF-BILL-010) and the source of its transaction ID (BR-TRNA-003) switch together.
 */
@BothModes({"DEF-BILL-010", "BR-TRNA-003"})
class BillPayApiTest extends BillPayTestBase {

    static final String ACCT = "00000000001";
    static final String OTHER_ACCT = "00000000002";
    static final String CUST = "000000001";
    static final String CARD = "4111111111111111";
    static final String HIGHEST_ID = "0000000000000041";
    static final String NEXT_ID = "0000000000000042";

    private final JdbcClient admin = DB.admin();

    @Autowired
    TransactionIds transactionIds;

    @Autowired
    TransactionWriter transactionWriter;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void sampleAccount() {
        TestClock.shared().reset();
        DB.fixtures().deleteAll()
                .account(CoreFixtures.Account.of(ACCT, "194.00").withCycleTotals("10.00", "-5.00"))
                .customer(CUST)
                .card(CARD, ACCT, CUST)
                .categoryBalance(ACCT, "01", "0001", "194.00")
                .transaction(HIGHEST_ID, CARD, "194.00")
                .restartTranIdSequence(Long.parseLong(HIGHEST_ID) + 1);
    }

    @AfterEach
    void resetClock() {
        TestClock.shared().reset();
    }

    private static boolean fixed() {
        return FidelityOverrides.current() == FidelityMode.FIXED;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    // ---- S1, S20: session and role -------------------------------------------------------------

    @Test
    @DisplayName("BILL-S1: both APIs need a session")
    @SpecItem("FLOW-BILL-001")
    void s1_noSession() throws Exception {
        mvc.perform(get("/api/billpay/accounts/{a}", ACCT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value(MessageCatalog.UNAUTHENTICATED.text()));
        mvc.perform(paymentRequest(ACCT, "Y", key()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/billpay/accounts/{a}", ACCT)
                        .header("Authorization", bearer(TestTokens.builder(USER).signedWithUnknownKey().build())))
                .andExpect(status().isUnauthorized());
        assertNoWrites();
    }

    @Test
    @DisplayName("BILL-S20: an ADMIN token is refused")
    @SpecItem("FLOW-BILL-001")
    void s20_adminIsDenied() throws Exception {
        String adminToken = TestTokens.admin("ADMIN001");
        preview(ACCT, adminToken).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        pay(ACCT, "Y", key(), adminToken).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertNoWrites();
    }

    @Test
    @DisplayName("BILL-S1: a cookie session must send the CSRF token on the payment")
    void cookieSessionsNeedTheCsrfToken() throws Exception {
        Cookie session = new Cookie("cd_at", TestTokens.user(USER));
        mvc.perform(paymentRequest(ACCT, "Y", key()).cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        assertNoWrites();
        mvc.perform(get("/api/billpay/accounts/{a}", ACCT).cookie(session)).andExpect(status().isOk());
        mvc.perform(paymentRequest(ACCT, "Y", key()).cookie(session, new Cookie("XSRF-TOKEN", "t0k3n"))
                        .header("X-XSRF-TOKEN", "t0k3n"))
                .andExpect(status().isCreated());
    }

    // ---- S3 to S8: validation ---------------------------------------------------------------

    @Test
    @DisplayName("BILL-S3: a blank Account ID is refused by both endpoints, before any other check")
    @SpecItem("VAL-BILL-001")
    void s3_blankAccountId() throws Exception {
        for (String blank : new String[] {" ", "           "}) {
            preview(blank).andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("ACCT_ID_REQUIRED"))
                    .andExpect(jsonPath("$.message").value("Acct ID can NOT be empty..."))
                    .andExpect(jsonPath("$.field").value("acctId"))
                    .andExpect(jsonPath("$.severity").value("error"));
            pay(blank, "X", null).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ACCT_ID_REQUIRED"));
        }
        assertNoWrites();
    }

    @Test
    @DisplayName("BILL-S4: an unknown account")
    @SpecItem("VAL-BILL-003")
    void s4_accountNotFound() throws Exception {
        preview("99999999999").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Account ID NOT found..."))
                .andExpect(jsonPath("$.field").value("acctId"));
        pay("99999999999", "Y", key()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        assertNoWrites();
    }

    @Test
    @DisplayName("BILL DEF-BILL-004: the key is used as typed, so 123 and ABC are not found")
    @SpecItem({"DEF-BILL-004", "VAL-BILL-003"})
    void def004_keyAsTyped() throws Exception {
        DB.fixtures().account(CoreFixtures.Account.of("00000000123", "50.00"));
        for (String typed : new String[] {"123", "ABC", "00000000001 ", "000000000011", "0000000001"}) {
            preview(typed).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
            pay(typed, "Y", key()).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        }
        assertNoWrites();
    }

    @Test
    @DisplayName("BILL-S5: preview of the sample account")
    @SpecItem({"VAL-BILL-005", "NUM-BILL-001"})
    void s5_preview() throws Exception {
        Map<String, Object> before = account(ACCT);
        preview(ACCT).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.acctId").value(ACCT))
                .andExpect(jsonPath("$.currentBalance").value("194.00"))
                .andExpect(jsonPath("$.currentBalanceDisplay").value("+0000000194.00"))
                .andExpect(jsonPath("$.payable").value(true))
                .andExpect(jsonPath("$.message").value("Confirm to make a bill payment..."))
                .andExpect(jsonPath("$.severity").value("error"))
                .andExpect(jsonPath("$.field").value("confirm"));
        assertThat(account(ACCT)).isEqualTo(before);
        assertNoWrites();
    }

    @Test
    @DisplayName("BILL-S6: nothing to pay at 0.00 and -25.50")
    @SpecItem({"VAL-BILL-004", "BR-BILL-002", "NUM-BILL-001"})
    void s6_nothingToPay() throws Exception {
        DB.fixtures().account(CoreFixtures.Account.of(OTHER_ACCT, "0.00")).card("4111111111110002", OTHER_ACCT, CUST)
                .account(CoreFixtures.Account.of("00000000003", "-25.50")).card("4111111111110003", "00000000003", CUST);
        String[][] cases = {{OTHER_ACCT, "0.00", "+0000000000.00"}, {"00000000003", "-25.50", "-0000000025.50"}};
        for (String[] c : cases) {
            Map<String, Object> before = account(c[0]);
            preview(c[0]).andExpect(status().isOk())
                    .andExpect(jsonPath("$.currentBalance").value(c[1]))
                    .andExpect(jsonPath("$.currentBalanceDisplay").value(c[2]))
                    .andExpect(jsonPath("$.payable").value(false))
                    .andExpect(jsonPath("$.message").value("You have nothing to pay..."))
                    .andExpect(jsonPath("$.severity").value("error"))
                    .andExpect(jsonPath("$.field").value("acctId"));
            pay(c[0], "Y", key()).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("NOTHING_TO_PAY"))
                    .andExpect(jsonPath("$.message").value("You have nothing to pay..."))
                    .andExpect(jsonPath("$.field").value("acctId"))
                    .andExpect(jsonPath("$.currentBalance").value(c[1]))
                    .andExpect(jsonPath("$.currentBalanceDisplay").value(c[2]));
            assertThat(account(c[0])).isEqualTo(before);
        }
        assertNoWrites();
    }

    @Test
    @DisplayName("BILL-S7, S8: a confirmation other than Y or y is refused")
    @SpecItem("VAL-BILL-002")
    void s8_invalidConfirm() throws Exception {
        for (String confirm : new String[] {"X", "N", "n", "", " ", "YES"}) {
            pay(ACCT, confirm, key()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CONFIRM"))
                    .andExpect(jsonPath("$.message").value("Invalid value. Valid values are (Y/N)..."))
                    .andExpect(jsonPath("$.field").value("confirm"));
        }
        pay(ACCT, null, key()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIRM"));
        pay(ACCT, "X", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIRM"));
        assertNoWrites();
    }

    @Test
    @DisplayName("BILL: the payment needs a UUID Idempotency-Key")
    void idempotencyKeyRequired() throws Exception {
        for (String bad : new String[] {null, "", "not-a-uuid", "12345"}) {
            pay(ACCT, "y", bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))
                    .andExpect(jsonPath("$.message").value("Idempotency-Key header is required"))
                    .andExpect(jsonPath("$.field").value(nullValue()));
        }
        assertNoWrites();
    }

    // ---- S9, S16 to S18: payments -----------------------------------------------------------

    @Test
    @DisplayName("BILL-S9: pay the full balance of the sample account")
    @SpecItem({"BR-BILL-001", "BR-BILL-003", "BR-BILL-004", "BR-BILL-005", "BR-BILL-006", "SE-BILL-001",
        "SE-BILL-002", "SE-BILL-003", "SE-BILL-004", "SE-BILL-005", "NUM-BILL-003", "DEF-BILL-009", "DEF-BILL-010",
        "BR-TRNA-003"})
    void s9_payment() throws Exception {
        Map<String, Object> before = account(ACCT);
        String key = key();
        pay(ACCT, "Y", key).andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.tranId").value(NEXT_ID))
                .andExpect(jsonPath("$.amount").value("194.00"))
                .andExpect(jsonPath("$.acctId").value(ACCT))
                .andExpect(jsonPath("$.message").value("Payment successful.  Your Transaction ID is " + NEXT_ID + "."))
                .andExpect(jsonPath("$.severity").value("success"))
                .andExpect(jsonPath("$.field").value("acctId"));

        String stored = fixed() ? "-194.00" : "194.00";
        Map<String, Object> tran = transaction(NEXT_ID);
        assertThat(tran).containsEntry("type_cd", "02").containsEntry("cat_cd", "0002")
                .containsEntry("source", "POS TERM").containsEntry("description", "BILL PAYMENT - ONLINE")
                .containsEntry("merchant_id", "999999999").containsEntry("merchant_name", "BILL PAYMENT")
                .containsEntry("merchant_city", "N/A").containsEntry("merchant_zip", "N/A")
                .containsEntry("card_num", CARD)
                .containsEntry("orig_ts", "2026-11-06 09:30:15.000000")
                .containsEntry("proc_ts", "2026-11-06 09:30:15.000000")
                .containsEntry("legacy_filler", null).containsEntry("origin", "BILL_PAY")
                .containsEntry("cycle_id", null).containsEntry("in_master", true)
                .containsEntry("created_by", USER);
        assertThat((BigDecimal) tran.get("amount")).isEqualByComparingTo(stored);
        assertThat(count("core.[transaction]")).isEqualTo(2);

        Map<String, Object> after = account(ACCT);
        Map<String, Object> expected = new java.util.LinkedHashMap<>(before);
        expected.put("curr_bal", new BigDecimal("0.00"));
        if (fixed()) {
            expected.put("curr_cyc_debit", new BigDecimal("-199.00"));
        }
        assertThat(after).isEqualTo(expected);
        if (fixed()) {
            assertThat(categoryBalances()).containsExactly(
                    Map.entry("01/0001", new BigDecimal("194.00")), Map.entry("02/0002", new BigDecimal("-194.00")));
        } else {
            assertThat(categoryBalances()).containsExactly(Map.entry("01/0001", new BigDecimal("194.00")));
        }

        Map<String, Object> payment = admin.sql("""
                        SELECT p.tran_id, p.acct_id, p.paid_by, p.amount, p.balance_before,
                               CAST(p.idempotency_key AS VARCHAR(36)) AS idempotency_key,
                               CASE WHEN p.tran_pk = t.tran_pk THEN 1 ELSE 0 END AS same_row
                        FROM billpay.payment p JOIN core.[transaction] t ON t.tran_id = p.tran_id AND t.in_master = 1""")
                .query().singleRow();
        assertThat(payment).containsEntry("tran_id", NEXT_ID).containsEntry("acct_id", ACCT)
                .containsEntry("paid_by", USER).containsEntry("same_row", 1);
        assertThat(((String) payment.get("idempotency_key")).toLowerCase()).isEqualTo(key);
        assertThat((BigDecimal) payment.get("amount")).isEqualByComparingTo(stored);
        assertThat((BigDecimal) payment.get("balance_before")).isEqualByComparingTo("194.00");

        // BR-BILL-001: a second payment re-reads the balance it left.
        pay(ACCT, "Y", key()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_PAY"))
                .andExpect(jsonPath("$.currentBalanceDisplay").value("+0000000000.00"));
        assertThat(count("core.[transaction]")).isEqualTo(2);
    }

    @Test
    @DisplayName("BILL-S9 (fixed): an existing category balance is decreased")
    @SpecItem({"DEF-BILL-010", "BR-BILL-006"})
    void s9_existingCategoryBalance() throws Exception {
        DB.fixtures().categoryBalance(ACCT, "02", "0002", "-10.00");
        pay(ACCT, "Y", key()).andExpect(status().isCreated());
        assertThat(categoryBalances()).containsEntry("02/0002",
                fixed() ? new BigDecimal("-204.00") : new BigDecimal("-10.00"));
    }

    @Test
    @DisplayName("BILL-S16: one-step payment without a preview")
    @SpecItem("BR-BILL-007")
    void s16_oneStepPayment() throws Exception {
        pay(ACCT, "y", key()).andExpect(status().isCreated()).andExpect(jsonPath("$.tranId").value(NEXT_ID));
        assertThat(account(ACCT).get("curr_bal")).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("BILL-S17: a balance of 1,234,567,890.12 pays 234,567,890.12")
    @SpecItem("NUM-BILL-002")
    void s17_amountTruncatedToNineDigits() throws Exception {
        DB.fixtures().account(CoreFixtures.Account.of(OTHER_ACCT, "1234567890.12")).card("4111111111110002", OTHER_ACCT, CUST);
        preview(OTHER_ACCT).andExpect(jsonPath("$.currentBalanceDisplay").value("+1234567890.12"));
        pay(OTHER_ACCT, "Y", key()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("234567890.12"));
        assertThat((BigDecimal) transaction(NEXT_ID).get("amount"))
                .isEqualByComparingTo(fixed() ? "-234567890.12" : "234567890.12");
        Map<String, Object> after = account(OTHER_ACCT);
        assertThat(after.get("curr_bal")).isEqualTo(new BigDecimal("1000000000.00"));
        assertThat(after.get("curr_cyc_debit")).isEqualTo(fixed() ? new BigDecimal("-234567890.12") : new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("BILL-S18: the payment goes to the lowest card number")
    @SpecItem({"BR-BILL-005", "DEF-BILL-002"})
    void s18_lowestCard() throws Exception {
        DB.fixtures().account(CoreFixtures.Account.of(OTHER_ACCT, "20.00"))
                .card("4000000000000002", OTHER_ACCT, CUST).card("4000000000000001", OTHER_ACCT, CUST);
        pay(OTHER_ACCT, "Y", key()).andExpect(status().isCreated());
        assertThat(transaction(NEXT_ID)).containsEntry("card_num", "4000000000000001");
    }

    @Test
    @DisplayName("BILL-S19: an account without a cross-reference row")
    @SpecItem({"SE-BILL-002", "VAL-BILL-003"})
    void s19_noCrossReference() throws Exception {
        DB.fixtures().account(CoreFixtures.Account.of(OTHER_ACCT, "20.00"));
        preview(OTHER_ACCT).andExpect(status().isOk());
        pay(OTHER_ACCT, "Y", key()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Account ID NOT found..."));
        assertNoWrites();
        assertThat(account(OTHER_ACCT).get("curr_bal")).isEqualTo(new BigDecimal("20.00"));
    }

    // ---- S13 to S15: batch window, idempotency, concurrency ---------------------------------

    @Test
    @DisplayName("BILL-S13: an open batch window blocks the payment, not the preview")
    void s13_batchWindow() throws Exception {
        DB.fixtures().openBatchWindow();
        preview(ACCT).andExpect(status().isOk());
        pay(ACCT, "Y", key()).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "300"))
                .andExpect(jsonPath("$.code").value("BATCH_WINDOW"))
                .andExpect(jsonPath("$.message").value(
                        "Bill payment is not available while the batch cycle runs. Try again later..."))
                .andExpect(jsonPath("$.field").value("acctId"));
        assertNoWrites();
        DB.fixtures().closeBatchWindows();
        pay(ACCT, "Y", key()).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("BILL-S14: the same Idempotency-Key replays the first answer")
    void s14_idempotentReplay() throws Exception {
        DB.fixtures().account(CoreFixtures.Account.of(OTHER_ACCT, "20.00")).card("4111111111110002", OTHER_ACCT, CUST);
        String key = key();
        String first = pay(ACCT, "Y", key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = pay(ACCT, "Y", key.toUpperCase()).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(count("core.[transaction]")).isEqualTo(2);
        assertThat(count("billpay.payment")).isEqualTo(1);

        pay(OTHER_ACCT, "Y", key).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.message").value("Idempotency-Key was used for another payment"));
        pay(ACCT, "Y", key, TestTokens.user("USER0002")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("core.[transaction]")).isEqualTo(2);
        assertThat(account(OTHER_ACCT).get("curr_bal")).isEqualTo(new BigDecimal("20.00"));
    }

    @Test
    @DisplayName("BILL-S15: two payments at once on one account")
    @SpecItem("BR-BILL-001")
    void s15_concurrentPayments() throws Exception {
        List<MvcResult> results = whileAccountLocked(2, () -> pay(ACCT, "Y", key()), () -> pay(ACCT, "Y", key()));
        List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
        assertThat(statuses).containsExactly(201, 422);
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 422).findFirst().orElseThrow()
                .getResponse().getContentAsString()).contains("NOTHING_TO_PAY");
        assertThat(count("core.[transaction]")).isEqualTo(2);
        assertThat(account(ACCT).get("curr_bal")).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("BILL-S14: a retry that races the first call with the same key gets the first answer")
    void s14_concurrentRetry() throws Exception {
        String key = key();
        List<MvcResult> results = whileAccountLocked(2, () -> pay(ACCT, "Y", key), () -> pay(ACCT, "Y", key));
        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
        assertThat(results.stream().filter(r -> "true".equals(r.getResponse().getHeader("Idempotent-Replayed"))))
                .hasSize(1);
        assertThat(results.get(0).getResponse().getContentAsString())
                .isEqualTo(results.get(1).getResponse().getContentAsString());
        assertThat(count("core.[transaction]")).isEqualTo(2);
    }

    // ---- S21, S22: failures and ID allocation -----------------------------------------------

    @Test
    @DisplayName("BILL-S21: a database failure at each step rolls everything back")
    @SpecItem({"SE-BILL-001", "SE-BILL-002", "SE-BILL-003", "SE-BILL-004", "SE-BILL-005", "ERR-BILL-001",
        "DEF-BILL-001"})
    void s21_failureAtEachStep() throws Exception {
        Map<String, Object> before = account(ACCT);
        List<String[]> steps = new ArrayList<>();
        steps.add(new String[] {"6", "SELECT ON OBJECT::core.account", "ACCOUNT_LOOKUP_FAILED"});
        steps.add(new String[] {"8", "SELECT ON OBJECT::core.card_xref", "XREF_LOOKUP_FAILED"});
        steps.add(fixed()
                ? new String[] {"9", "UPDATE ON OBJECT::core.tran_id_seq", "TRAN_LOOKUP_FAILED"}
                : new String[] {"9", "SELECT ON OBJECT::core.[transaction]", "TRAN_LOOKUP_FAILED"});
        steps.add(new String[] {"10", "INSERT ON OBJECT::core.[transaction]", "TRAN_ADD_FAILED"});
        steps.add(new String[] {"11", "UPDATE ON OBJECT::core.account (curr_bal)", "ACCOUNT_UPDATE_FAILED"});
        steps.add(new String[] {"12", "INSERT ON OBJECT::billpay.payment", "TRAN_ADD_FAILED"});
        for (String[] step : steps) {
            admin.sql("DENY " + step[1] + " TO billpay_svc").update();
            try {
                pay(ACCT, "Y", key()).andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value(step[2]))
                        .andExpect(jsonPath("$.message").value(MessageCatalog.get(step[2]).text()))
                        .andExpect(jsonPath("$.field").value("acctId"))
                        .andExpect(content().string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsStringIgnoringCase("permission"))));
            } finally {
                admin.sql("GRANT " + step[1] + " TO billpay_svc").update();
            }
            assertThat(account(ACCT)).as("step %s", step[0]).isEqualTo(before);
            assertNoWrites();
            assertThat(categoryBalances()).as("step %s", step[0]).hasSize(1);
        }
        pay(ACCT, "Y", key()).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("BILL-S21: an ID already in the master is TRAN_ID_EXISTS")
    @SpecItem({"SE-BILL-004", "ERR-BILL-001", "BR-TRNA-003"})
    void s21_duplicateTranId() throws Exception {
        DB.fixtures().restartTranIdSequence(Long.parseLong(HIGHEST_ID));
        if (fixed()) {
            pay(ACCT, "Y", key()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRAN_ID_EXISTS"))
                    .andExpect(jsonPath("$.message").value("Tran ID already exist..."));
            assertNoWrites();
        } else {
            // The legacy ID is the highest master ID + 1, which cannot exist.
            pay(ACCT, "Y", key()).andExpect(status().isCreated()).andExpect(jsonPath("$.tranId").value(NEXT_ID));
        }
    }

    @Test
    @DisplayName("BILL-S22: a payment and another writer at once get distinct IDs")
    @SpecItem({"BR-BILL-004", "DEF-BILL-003", "BR-TRNA-003"})
    void s22_concurrentWriters() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch idTaken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<String> other = CompletableFuture.supplyAsync(() -> tx.execute(s -> {
                String id = transactionIds.next();
                idTaken.countDown();
                await(release);
                java.time.LocalDateTime ts = java.time.LocalDateTime.of(2026, 11, 6, 9, 0);
                transactionWriter.insert(new NewTransaction(id, "01", "0001", "OPERATOR", "OTHER WRITER",
                        new BigDecimal("1.00"), CARD, "000000001", "OTHER", "TOWN", "10001", ts, ts,
                        Origin.ONLINE_ADD, "USER0002"));
                return id;
            }), pool);
            await(idTaken);
            CompletableFuture<MvcResult> payment = CompletableFuture.supplyAsync(
                    () -> call(() -> pay(ACCT, "Y", key())), pool);
            if (fixed()) {
                payment.get(30, TimeUnit.SECONDS);
            } else {
                awaitBlockedRequests(1);
                assertThat(payment).isNotDone();
            }
            release.countDown();
            String otherId = other.get(30, TimeUnit.SECONDS);
            MvcResult paid = payment.get(30, TimeUnit.SECONDS);
            assertThat(paid.getResponse().getStatus()).isEqualTo(201);
            String paymentId = com.jayway.jsonpath.JsonPath.read(paid.getResponse().getContentAsString(), "$.tranId");
            assertThat(paymentId).isNotEqualTo(otherId);
            assertThat(List.of(otherId, paymentId)).containsExactlyInAnyOrder(NEXT_ID, "0000000000000043");
            assertThat(count("core.[transaction]")).isEqualTo(3);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    @FunctionalInterface
    interface Call {
        ResultActions run() throws Exception;
    }

    private static MvcResult call(Call call) {
        try {
            return call.run().andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Holds an update lock on the sample account from the admin login while the calls start, and
     * releases it once {@code waiting} of them wait for a lock, so the calls really overlap. Update
     * locks queue, so the second call waits behind the first, not behind the admin session.
     */
    private List<MvcResult> whileAccountLocked(int waiting, Call... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        List<CompletableFuture<MvcResult>> futures = new ArrayList<>();
        try (Connection c = DB.adminDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s = c.prepareStatement(
                        "SELECT curr_bal FROM core.account WITH (UPDLOCK, ROWLOCK) WHERE acct_id = ?")) {
                    s.setString(1, ACCT);
                    s.executeQuery().close();
                }
                for (Call call : calls) {
                    futures.add(CompletableFuture.supplyAsync(() -> call(call), pool));
                }
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (blockedBy(c) < waiting) {
                    if (System.nanoTime() > deadline) {
                        throw new IllegalStateException("the calls never waited for the account lock: " + requests(c));
                    }
                    Thread.sleep(25);
                }
            } finally {
                c.commit();
            }
            List<MvcResult> results = new ArrayList<>();
            for (CompletableFuture<MvcResult> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            // A failed run must not leave a payment running into the next test's fixtures.
            for (CompletableFuture<MvcResult> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // reported by the assertion above
                }
            }
            pool.shutdownNow();
        }
    }

    private static int blockedBy(Connection c) throws Exception {
        try (PreparedStatement s = c.prepareStatement(
                """
                SELECT COUNT(*) FROM sys.dm_exec_requests
                WHERE database_id = DB_ID() AND wait_type LIKE 'LCK[_]M[_]%' AND session_id <> @@SPID""");
             ResultSet rs = s.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String requests(Connection c) throws Exception {
        StringBuilder sb = new StringBuilder("spid ");
        try (PreparedStatement s = c.prepareStatement("""
                SELECT @@SPID, r.session_id, r.blocking_session_id, r.wait_type, r.wait_resource, r.command,
                       DB_NAME(r.database_id), SUSER_SNAME()
                FROM sys.dm_exec_requests r WHERE r.session_id > 50""");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                sb.append(rs.getInt(1)).append(" [").append(rs.getInt(2)).append(" blocked by ").append(rs.getInt(3))
                        .append(' ').append(rs.getString(4)).append(' ').append(rs.getString(5)).append(' ')
                        .append(rs.getString(6)).append(' ').append(rs.getString(7)).append(" as ")
                        .append(rs.getString(8)).append("] ");
            }
        }
        return sb.toString();
    }

    private void awaitBlockedRequests(int n) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (admin.sql("""
                        SELECT COUNT(*) FROM sys.dm_exec_requests
                        WHERE database_id = DB_ID() AND blocking_session_id <> 0""")
                .query(Integer.class).single() < n) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("no request waited for the transaction ID lock");
            }
            Thread.sleep(25);
        }
    }

    private Map<String, Object> account(String acctId) {
        return admin.sql("""
                        SELECT acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date,
                               expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id,
                               legacy_filler
                        FROM core.account WHERE acct_id = :id""")
                .param("id", acctId).query().singleRow();
    }

    private Map<String, Object> transaction(String tranId) {
        return admin.sql("""
                        SELECT tran_id, type_cd, cat_cd, source, description, amount, merchant_id, merchant_name,
                               merchant_city, merchant_zip, card_num,
                               CONVERT(VARCHAR(26), orig_ts, 121) AS orig_ts, CONVERT(VARCHAR(26), proc_ts, 121) AS proc_ts,
                               legacy_filler, origin, cycle_id, in_master, created_by
                        FROM core.[transaction] WHERE tran_id = :id AND in_master = 1""")
                .param("id", tranId).query().singleRow();
    }

    private Map<String, BigDecimal> categoryBalances() {
        Map<String, BigDecimal> balances = new java.util.LinkedHashMap<>();
        admin.sql("SELECT type_cd, cat_cd, balance FROM core.tcat_bal WHERE acct_id = :id ORDER BY type_cd, cat_cd")
                .param("id", ACCT)
                .query(rs -> {
                    balances.put(rs.getString(1) + "/" + rs.getString(2), rs.getBigDecimal(3));
                });
        return balances;
    }

    private int count(String table) {
        return admin.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    /** Only the fixture's transaction, no payment record. */
    private void assertNoWrites() {
        assertThat(count("core.[transaction]")).isEqualTo(1);
        assertThat(count("billpay.payment")).isZero();
    }
}
