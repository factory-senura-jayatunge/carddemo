package org.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestTokens;
import org.carddemo.platform.web.MessageCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Section 5's ACVW scenarios against the API. Signed on as USER0001 unless the test says otherwise. */
class AccountViewApiTest extends AccountTestBase {

    static final String ACCT = "00000000001";
    static final String CUST = "000000001";
    static final String CARD = "9680294154603697";
    static final String ACCT3 = "00000000003";
    static final String CUST3 = "000000003";
    static final String USER = "USER0001";
    static final String ADMIN = "ADMIN001";
    static final String CVV = "927";

    /** Customer 000000001 of app/data/ASCII/custdata.txt. */
    static final CoreFixtures.Customer SAMPLE_CUSTOMER = new CoreFixtures.Customer(CUST, "Immanuel", "Madeline",
            "Kessler", "618 Deshaun Route", "Apt. 802", "Altenwerthshire", "NC", "USA", "12546", "(908)119-8310",
            "(373)693-8684", "020973888", "00000000000049368437", LocalDate.of(1961, 6, 8), "0053581756", "Y", 274);

    /** Every clear PII value of the sample rows; none may leave the service except through unmask. */
    static final List<String> CLEAR_VALUES = List.of("020973888", "020-97-3888", "1961-06-08",
            "00000000000049368437", "0053581756", CARD);

    static final String ALL_FIELDS = "[\"ssn\",\"dob\",\"govtIssuedId\",\"eftAccountId\",\"cardNum\"]";

    private final JdbcClient admin = DB.admin();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void sampleAccounts() {
        DB.fixtures().deleteAll()
                .account(CoreFixtures.Account.of(ACCT, "194.00"))
                .customer(SAMPLE_CUSTOMER)
                .card(CARD, ACCT, CUST)
                .account(CoreFixtures.Account.of(ACCT3, "50.00"))
                .customer(CoreFixtures.Customer.of(CUST3).withZip("19852-6716"))
                .card("4000000000000003", ACCT3, CUST3);
        admin.sql("UPDATE core.card SET cvv_cd = :cvv").param("cvv", CVV).update();
    }

    private ResultActions view(String acctId) throws Exception {
        return view(acctId, TestTokens.user(USER));
    }

    private ResultActions view(String acctId, String token) throws Exception {
        return mvc.perform(get("/api/accounts/{acctId}", acctId).header("Authorization", bearer(token)));
    }

    private ResultActions unmask(String acctId, String fields) throws Exception {
        return unmask(acctId, fields, TestTokens.admin(ADMIN));
    }

    private ResultActions unmask(String acctId, String fields, String token) throws Exception {
        return mvc.perform(post("/api/accounts/{acctId}/unmask", acctId).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":" + fields + "}"));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int auditRows() {
        return admin.sql("SELECT COUNT(*) FROM ops.audit").query(Integer.class).single();
    }

    /** The row version of every account, customer and card row: a read must change none. */
    private List<String> rowVersions() {
        List<String> versions = new ArrayList<>();
        for (String table : List.of("core.account", "core.customer", "core.card")) {
            versions.addAll(DB.fixtures().rowVersions(table));
        }
        return versions;
    }

    private String cardId(String cardNum) {
        return admin.sql("SELECT CAST(card_id AS VARCHAR(20)) FROM core.card WHERE card_num = :c").param("c", cardNum)
                .query(String.class).single();
    }

    private void deny(String permission, ThrowingRunnable call) throws Exception {
        admin.sql("DENY " + permission + " TO account_svc").update();
        try {
            call.run();
        } finally {
            admin.sql("GRANT " + permission + " TO account_svc").update();
        }
    }

    /** Inserts rows the foreign key would refuse, runs the call, then removes them and re-trusts the key. */
    private void withoutForeignKey(String constraint, String insert, String delete, ThrowingRunnable call)
            throws Exception {
        admin.sql("ALTER TABLE core.card_xref NOCHECK CONSTRAINT " + constraint).update();
        try {
            admin.sql(insert).update();
            call.run();
        } finally {
            admin.sql(delete).update();
            admin.sql("ALTER TABLE core.card_xref WITH CHECK CHECK CONSTRAINT " + constraint).update();
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void assertNoAccountData(JsonNode body) {
        assertThat(body.has("customer")).as("customer in %s", body).isFalse();
        assertThat(body.has("crossReference")).as("crossReference in %s", body).isFalse();
        assertThat(body.has("creditLimit")).as("creditLimit in %s", body).isFalse();
        assertThat(body.toString()).doesNotContain("Kessler", "3888", "3697", "2020.00");
    }

    // ---- S1, S17: session ------------------------------------------------------------------------

    @Test
    @DisplayName("ACVW-S1, ACVW-S17: the view and the unmask need a session")
    @SpecItem({"DEF-ACVW-009", "Q-ACVW-001"})
    void s1_noSession() throws Exception {
        mvc.perform(get("/api/accounts/{a}", ACCT)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value(MessageCatalog.UNAUTHENTICATED.text()));
        mvc.perform(post("/api/accounts/{a}/unmask", ACCT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[\"ssn\"]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("ACVW unmask: a cookie session must send the CSRF token")
    @SpecItem("DEF-ACVW-010")
    void unmaskNeedsTheCsrfToken() throws Exception {
        Cookie session = new Cookie("cd_at", TestTokens.admin(ADMIN));
        mvc.perform(post("/api/accounts/{a}/unmask", ACCT).cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[\"ssn\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        assertThat(auditRows()).isZero();
        mvc.perform(post("/api/accounts/{a}/unmask", ACCT).cookie(session, new Cookie("XSRF-TOKEN", "t0k3n"))
                        .header("X-XSRF-TOKEN", "t0k3n").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[\"ssn\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.ssn").value("020-97-3888"));
        assertThat(auditRows()).isOne();
    }

    // ---- S3 to S5, S26: the account ID ----------------------------------------------------------

    @Test
    @DisplayName("ACVW-S3: spaces and * are no input")
    @SpecItem({"VAL-ACVW-001", "DEF-ACVW-006"})
    void s3_blank() throws Exception {
        for (String blank : new String[] {" ", "           ", "*", "*          "}) {
            view(blank).andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("ACCT_ID_REQUIRED"))
                    .andExpect(jsonPath("$.message").value("No input received"))
                    .andExpect(jsonPath("$.field").value("acctId"))
                    .andExpect(jsonPath("$.severity").value("error"));
            unmask(blank, "[\"ssn\"]").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ACCT_ID_REQUIRED"));
        }
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("ACVW-S4, ACVW-S5, ACVW-S26: anything but 11 digits, or all zeros, is not an account number")
    @SpecItem({"VAL-ACVW-002", "DEF-ACVW-006"})
    void s4_invalidFormat() throws Exception {
        for (String bad : new String[] {"12345", "0000000000A", "00000000000", "000000000012", "1234567890 ",
            "-0000000001", " 0000000001", "*0000000001"}) {
            view(bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ACCT_ID"))
                    .andExpect(jsonPath("$.message").value("Account Filter must  be a non-zero 11 digit number"))
                    .andExpect(jsonPath("$.field").value("acctId"));
            unmask(bad, "[\"ssn\"]").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ACCT_ID"));
        }
        assertThat(auditRows()).isZero();
    }

    // ---- S6 to S11, S16, S18, S22 to S25: the view ---------------------------------------------

    @Test
    @DisplayName("ACVW-S6: the sample account with its customer and card, PII masked, nothing written")
    @SpecItem({"BR-ACVW-001", "BR-ACVW-002", "BR-ACVW-003", "BR-ACVW-004", "SE-ACVW-001", "SE-ACVW-002",
        "SE-ACVW-003", "NUM-ACVW-001", "NUM-ACVW-002", "NUM-ACVW-003", "DEF-ACVW-008", "DEF-ACVW-010",
        "Q-ACVW-001"})
    void s6_sampleAccount() throws Exception {
        List<String> before = rowVersions();
        JsonNode body = body(view(ACCT).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.acctId").value(ACCT))
                .andExpect(jsonPath("$.activeStatus").value("Y"))
                .andExpect(jsonPath("$.openDate").value("2014-11-20"))
                .andExpect(jsonPath("$.expirationDate").value("2025-05-20"))
                .andExpect(jsonPath("$.reissueDate").value("2025-05-20"))
                .andExpect(jsonPath("$.creditLimit").value("2020.00"))
                .andExpect(jsonPath("$.creditLimitDisplay").value("+      2,020.00"))
                .andExpect(jsonPath("$.cashCreditLimit").value("1020.00"))
                .andExpect(jsonPath("$.cashCreditLimitDisplay").value("+      1,020.00"))
                .andExpect(jsonPath("$.currentBalance").value("194.00"))
                .andExpect(jsonPath("$.currentBalanceDisplay").value("+        194.00"))
                .andExpect(jsonPath("$.currCycCredit").value("0.00"))
                .andExpect(jsonPath("$.currCycCreditDisplay").value("+           .00"))
                .andExpect(jsonPath("$.currCycDebit").value("0.00"))
                .andExpect(jsonPath("$.currCycDebitDisplay").value("+           .00"))
                .andExpect(jsonPath("$.groupId").value(""))
                .andExpect(jsonPath("$.crossReference.custId").value(CUST))
                .andExpect(jsonPath("$.crossReference.cardId").value(cardId(CARD)))
                .andExpect(jsonPath("$.crossReference.cardNum").value("************3697"))
                .andExpect(jsonPath("$.customer.custId").value(CUST))
                .andExpect(jsonPath("$.customer.ssn").value("***-**-3888"))
                .andExpect(jsonPath("$.customer.dob").value("****-06-08"))
                .andExpect(jsonPath("$.customer.ficoScore").value("274"))
                .andExpect(jsonPath("$.customer.firstName").value("Immanuel"))
                .andExpect(jsonPath("$.customer.middleName").value("Madeline"))
                .andExpect(jsonPath("$.customer.lastName").value("Kessler"))
                .andExpect(jsonPath("$.customer.addrLine1").value("618 Deshaun Route"))
                .andExpect(jsonPath("$.customer.addrLine2").value("Apt. 802"))
                .andExpect(jsonPath("$.customer.city").value("Altenwerthshire"))
                .andExpect(jsonPath("$.customer.stateCd").value("NC"))
                .andExpect(jsonPath("$.customer.zip").value("12546"))
                .andExpect(jsonPath("$.customer.countryCd").value("USA"))
                .andExpect(jsonPath("$.customer.phone1").value("(908)119-8310"))
                .andExpect(jsonPath("$.customer.phone2").value("(373)693-8684"))
                .andExpect(jsonPath("$.customer.govtIssuedId").value("****************8437"))
                .andExpect(jsonPath("$.customer.eftAccountId").value("******1756"))
                .andExpect(jsonPath("$.customer.priCardHolderInd").value("Y")));
        for (String member : List.of("code", "message", "severity", "field")) {
            assertThat(body.has(member) && body.get(member).isNull()).as(member).isTrue();
        }
        assertThat(body.toString()).doesNotContain("A000000000", "addrZip");
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("ACVW-S7: the customer's full ZIP")
    @SpecItem({"NUM-ACVW-004", "DEF-ACVW-017"})
    void s7_fullZip() throws Exception {
        view(ACCT3).andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.custId").value(CUST3))
                .andExpect(jsonPath("$.customer.zip").value("19852-6716"));
    }

    @Test
    @DisplayName("ACVW-S8, ACVW-S16: an unknown account gives only the not-found problem, after a full view too")
    @SpecItem({"VAL-ACVW-004", "ERR-ACVW-002", "NUM-ACVW-005", "DEF-ACVW-016", "Q-ACVW-008"})
    void s8_unknownAccount() throws Exception {
        view(ACCT).andExpect(status().isOk());
        JsonNode body = body(view("99999999999").andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Account 99999999999 not found."))
                .andExpect(jsonPath("$.field").value("acctId"))
                .andExpect(jsonPath("$.severity").value("error")));
        assertNoAccountData(body);
        unmask("99999999999", "[\"ssn\"]").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("ACVW-S9: a cross-reference row of a missing account: account not found, nothing else read")
    @SpecItem({"VAL-ACVW-004", "DEF-ACVW-002", "ERR-ACVW-002", "Q-ACVW-004"})
    void s9_xrefWithoutAccount() throws Exception {
        withoutForeignKey("FK_card_xref_account",
                "INSERT INTO core.card_xref (card_num, cust_id, acct_id) VALUES ('4000000000000009', '" + CUST
                        + "', '00000000009')",
                "DELETE FROM core.card_xref WHERE acct_id = '00000000009'",
                () -> deny("SELECT ON OBJECT::core.card_xref", () -> deny("SELECT ON OBJECT::core.customer", () -> {
                    JsonNode body = body(view("00000000009").andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                            .andExpect(jsonPath("$.message").value("Account 00000000009 not found."))
                            .andExpect(jsonPath("$.field").value("acctId")));
                    assertNoAccountData(body);
                })));
    }

    @Test
    @DisplayName("ACVW-S10: account and cross-reference present, customer missing: only the not-found problem")
    @SpecItem({"VAL-ACVW-005", "ERR-ACVW-002", "NUM-ACVW-005", "Q-ACVW-004"})
    void s10_customerMissing() throws Exception {
        DB.fixtures().account(CoreFixtures.Account.of("00000000010", "10.00"));
        admin.sql("""
                INSERT INTO core.card (card_num, acct_id, cvv_cd, embossed_name, expiration_date, active_status)
                VALUES ('4000000000000010', '00000000010', '000', 'NO CUSTOMER', '2030-01-31', 'Y')""").update();
        withoutForeignKey("FK_card_xref_customer",
                "INSERT INTO core.card_xref (card_num, cust_id, acct_id) VALUES ('4000000000000010', '000000010',"
                        + " '00000000010')",
                "DELETE FROM core.card_xref WHERE cust_id = '000000010'",
                () -> {
                    JsonNode body = body(view("00000000010").andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
                            .andExpect(jsonPath("$.message").value("Customer 000000010 not found."))
                            .andExpect(jsonPath("$.field").value("acctId")));
                    assertNoAccountData(body);
                    assertThat(body.toString()).doesNotContain("10.00");
                    // Unmask is not stopped by the missing customer: its fields are null.
                    JsonNode fields = body(unmask("00000000010", ALL_FIELDS).andExpect(status().isOk())).get("fields");
                    assertThat(fields.get("cardNum").asText()).isEqualTo("4000000000000010");
                    for (String f : List.of("ssn", "dob", "govtIssuedId", "eftAccountId")) {
                        assertThat(fields.has(f) && fields.get(f).isNull()).as(f).isTrue();
                    }
                });
    }

    @Test
    @DisplayName("ACVW-S11: several cards give the lowest card number's customer")
    @SpecItem({"BR-ACVW-002", "DEF-ACVW-003", "Q-ACVW-003"})
    void s11_lowestCard() throws Exception {
        DB.fixtures().account(CoreFixtures.Account.of("00000000011", "11.00"))
                .customer("000000012").customer(CoreFixtures.Customer.of("000000011").withZip("11111"))
                .card("5000000000000002", "00000000011", "000000012")
                .card("5000000000000001", "00000000011", "000000011");
        view("00000000011").andExpect(status().isOk())
                .andExpect(jsonPath("$.crossReference.custId").value("000000011"))
                .andExpect(jsonPath("$.crossReference.cardNum").value("************0001"))
                .andExpect(jsonPath("$.crossReference.cardId").value(cardId("5000000000000001")))
                .andExpect(jsonPath("$.customer.custId").value("000000011"))
                .andExpect(jsonPath("$.customer.zip").value("11111"));
    }

    @Test
    @DisplayName("ACVW-S12: the account read fails: only its problem, the lookup stops")
    @SpecItem({"ERR-ACVW-001", "NUM-ACVW-005", "DEF-ACVW-016"})
    void s12_accountReadFails() throws Exception {
        deny(ACCOUNT_READ, () -> deny("SELECT ON OBJECT::core.customer", () -> {
            JsonNode body = body(view(ACCT).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_LOOKUP_FAILED"))
                    .andExpect(jsonPath("$.message").value("Error reading account data"))
                    .andExpect(jsonPath("$.field").value("acctId")));
            assertNoAccountData(body);
        }));
        view(ACCT).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ACVW-S21: a database failure at each read and at the audit insert")
    @SpecItem({"SE-ACVW-001", "SE-ACVW-002", "SE-ACVW-003", "ERR-ACVW-001", "DEF-ACVW-006", "DEF-ACVW-016"})
    void s21_failureAtEachStep() throws Exception {
        String[][] reads = {
            {ACCOUNT_READ, "ACCOUNT_LOOKUP_FAILED", "Error reading account data"},
            {"SELECT ON OBJECT::core.card_xref", "XREF_LOOKUP_FAILED", "Error reading card cross-reference data"},
            {"SELECT ON OBJECT::core.customer", "CUSTOMER_LOOKUP_FAILED", "Error reading customer data"},
        };
        for (String[] step : reads) {
            deny(step[0], () -> {
                for (ResultActions call : List.of(view(ACCT), unmask(ACCT, ALL_FIELDS))) {
                    JsonNode body = body(call.andExpect(status().isInternalServerError())
                            .andExpect(jsonPath("$.code").value(step[1]))
                            .andExpect(jsonPath("$.message").value(step[2]))
                            .andExpect(jsonPath("$.field").value("acctId"))
                            .andExpect(content().string(not(containsString("permission")))));
                    assertNoAccountData(body);
                    assertThat(body.has("fields")).isFalse();
                    assertThat(body.toString()).doesNotContain(CLEAR_VALUES);
                }
            });
        }
        deny("INSERT ON OBJECT::ops.audit", () -> {
            JsonNode body = body(unmask(ACCT, ALL_FIELDS).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("UNMASK_AUDIT_FAILED"))
                    .andExpect(jsonPath("$.message").value("Unable to record the unmask request")));
            assertThat(body.has("fields")).isFalse();
            assertThat(body.toString()).doesNotContain(CLEAR_VALUES);
        });
        assertThat(auditRows()).isZero();
        view(ACCT).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ACVW-S18: an ADMIN reads the view masked, and no audit row is written")
    @SpecItem({"DEF-ACVW-010", "Q-ACVW-002"})
    void s18_adminView() throws Exception {
        view(ACCT, TestTokens.admin(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.ssn").value("***-**-3888"))
                .andExpect(jsonPath("$.customer.dob").value("****-06-08"))
                .andExpect(jsonPath("$.customer.govtIssuedId").value("****************8437"))
                .andExpect(jsonPath("$.customer.eftAccountId").value("******1756"))
                .andExpect(jsonPath("$.crossReference.cardNum").value("************3697"));
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("ACVW-S22: an account with no card is viewable, with the information text")
    @SpecItem({"BR-ACVW-002", "VAL-ACVW-003", "DEF-ACVW-004", "Q-ACVW-005"})
    void s22_noCard() throws Exception {
        DB.fixtures().account(CoreFixtures.Account.of("00000000022", "22.00"));
        JsonNode body = body(view("00000000022").andExpect(status().isOk())
                .andExpect(jsonPath("$.acctId").value("00000000022"))
                .andExpect(jsonPath("$.currentBalance").value("22.00"))
                .andExpect(jsonPath("$.code").value("NO_CARD_LINKED"))
                .andExpect(jsonPath("$.message").value("No card is linked to this account."))
                .andExpect(jsonPath("$.severity").value("info"))
                .andExpect(jsonPath("$.field").value("acctId")));
        assertThat(body.has("crossReference") && body.get("crossReference").isNull()).isTrue();
        assertThat(body.has("customer") && body.get("customer").isNull()).isTrue();

        JsonNode fields = body(unmask("00000000022", ALL_FIELDS).andExpect(status().isOk())).get("fields");
        assertThat(fields.size()).isEqualTo(5);
        fields.forEach(value -> assertThat(value.isNull()).isTrue());
        assertThat(auditRows()).isOne();
    }

    @Test
    @DisplayName("ACVW-S23: amounts of a billion or more keep every digit; decimal strings are exact")
    @SpecItem({"NUM-ACVW-001", "DEF-ACVW-005", "Q-ACVW-006"})
    void s23_wideAmounts() throws Exception {
        DB.fixtures().account(new CoreFixtures.Account("00000000023", "Y", new BigDecimal("999999999.99"),
                new BigDecimal("1234567890.12"), new BigDecimal("-9999999999.99"), LocalDate.of(2014, 11, 20),
                LocalDate.of(2025, 5, 20), LocalDate.of(2025, 5, 20), new BigDecimal("-25.50"),
                new BigDecimal("0.05"), "", ""));
        view("00000000023").andExpect(status().isOk())
                .andExpect(jsonPath("$.creditLimit").value("1234567890.12"))
                .andExpect(jsonPath("$.creditLimitDisplay").value("+1,234,567,890.12"))
                .andExpect(jsonPath("$.cashCreditLimit").value("-9999999999.99"))
                .andExpect(jsonPath("$.cashCreditLimitDisplay").value("-9,999,999,999.99"))
                .andExpect(jsonPath("$.currentBalance").value("999999999.99"))
                .andExpect(jsonPath("$.currentBalanceDisplay").value("+999,999,999.99"))
                .andExpect(jsonPath("$.currCycCredit").value("-25.50"))
                .andExpect(jsonPath("$.currCycCreditDisplay").value("-         25.50"))
                .andExpect(jsonPath("$.currCycDebit").value("0.05"))
                .andExpect(jsonPath("$.currCycDebitDisplay").value("+           .05"));
    }

    @Test
    @DisplayName("ACVW S6 forms: dates not stored are empty, the group ID loses its trailing spaces")
    @SpecItem({"NUM-ACVW-002", "BR-ACVW-003", "BR-ACVW-004"})
    void datesNotStoredAndGroupId() throws Exception {
        admin.sql("UPDATE core.account SET open_date = NULL, expiration_date = NULL, reissue_date = NULL,"
                + " group_id = 'GRP1' WHERE acct_id = :a").param("a", ACCT).update();
        admin.sql("UPDATE core.customer SET dob = NULL WHERE cust_id = :c").param("c", CUST).update();
        view(ACCT).andExpect(status().isOk())
                .andExpect(jsonPath("$.openDate").value(""))
                .andExpect(jsonPath("$.expirationDate").value(""))
                .andExpect(jsonPath("$.reissueDate").value(""))
                .andExpect(jsonPath("$.groupId").value("GRP1"))
                .andExpect(jsonPath("$.customer.dob").value(""));
        unmask(ACCT, "[\"dob\"]").andExpect(status().isOk()).andExpect(jsonPath("$.fields.dob").value(""));
    }

    @Test
    @DisplayName("ACVW-S24: an open batch window does not stop the view or the unmask")
    @SpecItem("BR-ACVW-001")
    void s24_batchWindow() throws Exception {
        DB.fixtures().openBatchWindow();
        view(ACCT).andExpect(status().isOk());
        unmask(ACCT, "[\"ssn\"]").andExpect(status().isOk());
    }

    @Test
    @DisplayName("ACVW-S25: a 15-character phone and a ZIP+4 are shown in full")
    @SpecItem({"NUM-ACVW-004", "DEF-ACVW-017"})
    void s25_fullPhoneAndZip() throws Exception {
        DB.fixtures().account(CoreFixtures.Account.of("00000000025", "25.00"))
                .customer(CoreFixtures.Customer.of("000000025").withZip("12345-6789")
                        .withPhones("+1(908)119-8310", "(373)693-8684 x"))
                .card("4000000000000025", "00000000025", "000000025");
        view("00000000025").andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.zip").value("12345-6789"))
                .andExpect(jsonPath("$.customer.phone1").value("+1(908)119-8310"))
                .andExpect(jsonPath("$.customer.phone2").value("(373)693-8684 x"));
    }

    // ---- S19, S20: unmask ------------------------------------------------------------------------

    @Test
    @DisplayName("ACVW-S19: an ADMIN unmasks all five fields; one audit row without values")
    @SpecItem({"DEF-ACVW-010", "NUM-ACVW-003", "Q-ACVW-002"})
    void s19_unmaskAll() throws Exception {
        String correlationId = unmask(ACCT, ALL_FIELDS).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.fields.ssn").value("020-97-3888"))
                .andExpect(jsonPath("$.fields.dob").value("1961-06-08"))
                .andExpect(jsonPath("$.fields.govtIssuedId").value("00000000000049368437"))
                .andExpect(jsonPath("$.fields.eftAccountId").value("0053581756"))
                .andExpect(jsonPath("$.fields.cardNum").value(CARD))
                .andReturn().getResponse().getHeader("X-Correlation-Id");
        List<Map<String, Object>> rows = admin.sql("""
                SELECT service, actor_user_id, action, subject_type, subject_key, field_names, correlation_id,
                    CASE WHEN ABS(DATEDIFF(SECOND, occurred_at, SYSUTCDATETIME())) < 120 THEN 'now' END AS occurred
                FROM ops.audit""").query().listOfRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row).containsEntry("service", "account-service").containsEntry("actor_user_id", ADMIN)
                .containsEntry("action", "UNMASK").containsEntry("subject_type", "ACCOUNT")
                .containsEntry("subject_key", ACCT)
                .containsEntry("field_names", "cardNum,dob,eftAccountId,govtIssuedId,ssn")
                .containsEntry("occurred", "now");
        assertThat(correlationId).isNotBlank();
        assertThat(row.get("correlation_id")).isEqualTo(correlationId);
        assertThat(row.values().toString()).doesNotContain(CLEAR_VALUES);

        unmask(ACCT, "[\"ssn\"]").andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.ssn").value("020-97-3888"))
                .andExpect(jsonPath("$.fields.length()").value(1));
        assertThat(admin.sql("SELECT field_names FROM ops.audit ORDER BY audit_id").query(String.class).list())
                .containsExactly("cardNum,dob,eftAccountId,govtIssuedId,ssn", "ssn");
    }

    @Test
    @DisplayName("ACVW-S20: a USER cannot unmask; unknown and empty field lists are refused")
    @SpecItem({"DEF-ACVW-010"})
    void s20_unmaskRefused() throws Exception {
        unmask(ACCT, "[\"ssn\"]", TestTokens.user(USER)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        for (String fields : new String[] {"[\"cvv\"]", "[]", "[\"ssn\",\"cvv\"]", "null"}) {
            unmask(ACCT, fields).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_UNMASK_FIELD"))
                    .andExpect(jsonPath("$.message").value(
                            "Unmask one or more of: ssn, dob, govtIssuedId, eftAccountId, cardNum"))
                    .andExpect(jsonPath("$.field").value("fields"));
        }
        assertThat(auditRows()).isZero();
    }

    // ---- S27: no PII outside the unmask ---------------------------------------------------------

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("ACVW-S27: no CVV anywhere; no clear PII in views, problems, logs or audit rows")
    @SpecItem({"DEF-ACVW-010"})
    void s27_noPiiLeaks(CapturedOutput output) throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        bodies.add(body(view(ACCT).andExpect(status().isOk())));
        bodies.add(body(view(ACCT, TestTokens.admin(ADMIN)).andExpect(status().isOk())));
        bodies.add(body(view("99999999999").andExpect(status().isNotFound())));
        for (String permission : List.of("SELECT ON OBJECT::core.card_xref", "SELECT ON OBJECT::core.customer")) {
            deny(permission, () -> {
                bodies.add(body(view(ACCT).andExpect(status().isInternalServerError())));
                bodies.add(body(unmask(ACCT, ALL_FIELDS).andExpect(status().isInternalServerError())));
            });
        }
        deny("INSERT ON OBJECT::ops.audit", () ->
                bodies.add(body(unmask(ACCT, ALL_FIELDS).andExpect(status().isInternalServerError()))));
        JsonNode unmasked = body(unmask(ACCT, ALL_FIELDS).andExpect(status().isOk()));
        assertThat(unmasked.get("fields").get("cardNum").asText()).isEqualTo(CARD);

        for (JsonNode body : bodies) {
            assertThat(body.toString()).doesNotContain(CLEAR_VALUES).doesNotContainIgnoringCase("cvv");
            assertThat(textValues(body)).doesNotContain(CVV);
        }
        assertThat(textValues(unmasked)).doesNotContain(CVV);
        assertThat(output.getAll()).doesNotContain(CLEAR_VALUES).contains("XREF_LOOKUP_FAILED");
        assertThat(admin.sql("SELECT * FROM ops.audit").query().listOfRows().toString()).doesNotContain(CLEAR_VALUES);
    }

    private static List<String> textValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isValueNode()) {
            values.add(node.asText());
        }
        node.forEach(child -> values.addAll(textValues(child)));
        return values;
    }
}
