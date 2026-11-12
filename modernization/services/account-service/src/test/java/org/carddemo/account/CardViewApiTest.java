package org.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
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
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Section 8's CRDV scenarios against the API. Start data: the sample card of account 00000000050,
 * signed on as USER0001 unless the test says otherwise. Every test ends by checking that no
 * {@code core.*} row changed.
 */
class CardViewApiTest extends AccountTestBase {

    static final String ACCT = "00000000050";
    static final String CUST = "000000050";
    static final String CARD = "0500024453765740";
    static final String MASKED = "************5740";
    static final String NAME = "Aniya Von";
    static final String CVV = "743";
    static final String OTHER_ACCT = "00000000001";
    static final String OTHER_CARD = "4000000000000001";
    static final String USER = "USER0001";
    static final String ADMIN = "ADMIN001";
    static final String CARD_COLUMN = "SELECT ON OBJECT::core.card (card_id)";

    static final List<String> VIEW_MEMBERS = List.of("acctId", "activeStatus", "cardId", "cardNum", "embossedName",
            "expiryMonth", "expiryYear", "message", "severity");

    private final JdbcClient admin = DB.admin();

    @Autowired
    DataSource serviceDataSource;
    private final ObjectMapper json = new ObjectMapper();
    private List<String> before;

    @BeforeEach
    void sampleCards() {
        DB.fixtures().deleteAll()
                .account(CoreFixtures.Account.of(ACCT, "0.00")).customer(CUST).card(CARD, ACCT, CUST)
                .account(CoreFixtures.Account.of(OTHER_ACCT, "194.00")).customer("000000001")
                .card(OTHER_CARD, OTHER_ACCT, "000000001");
        admin.sql("""
                UPDATE core.card SET embossed_name = :name, expiration_date = '2023-03-09', active_status = 'Y',
                    cvv_cd = :cvv WHERE card_num = :card""")
                .param("name", NAME).param("cvv", CVV).param("card", CARD).update();
        before = coreRows();
    }

    @AfterEach
    void nothingChanged() {
        assertThat(coreRows()).as("core.* rows").isEqualTo(before);
    }

    /** Every account, customer and card row version, and every cross-reference row. */
    private List<String> coreRows() {
        List<String> rows = new ArrayList<>();
        for (String table : List.of("core.account", "core.customer", "core.card")) {
            rows.addAll(DB.fixtures().rowVersions(table));
        }
        rows.addAll(admin.sql("SELECT card_num + ' ' + cust_id + ' ' + acct_id FROM core.card_xref ORDER BY card_num")
                .query(String.class).list());
        return rows;
    }

    private String keys(String acctId, String cardNum) {
        ObjectNode body = json.createObjectNode();
        body.put("acctId", acctId);
        body.put("cardNum", cardNum);
        return body.toString();
    }

    private ResultActions lookup(String body) throws Exception {
        return lookup(body, TestTokens.user(USER));
    }

    private ResultActions lookup(String body, String token) throws Exception {
        return mvc.perform(post("/api/cards/lookup").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions view(String cardId) throws Exception {
        return view(cardId, TestTokens.user(USER));
    }

    private ResultActions view(String cardId, String token) throws Exception {
        return mvc.perform(get("/api/cards/{cardId}", cardId).header("Authorization", bearer(token)));
    }

    private ResultActions unmask(String cardId, String fields) throws Exception {
        return unmask(cardId, fields, TestTokens.admin(ADMIN));
    }

    private ResultActions unmask(String cardId, String fields, String token) throws Exception {
        return mvc.perform(post("/api/cards/{cardId}/unmask", cardId).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":" + fields + "}"));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String cardId(String cardNum) {
        return admin.sql("SELECT CAST(card_id AS VARCHAR(20)) FROM core.card WHERE card_num = :c").param("c", cardNum)
                .query(String.class).single();
    }

    private int auditRows() {
        return admin.sql("SELECT COUNT(*) FROM ops.audit").query(Integer.class).single();
    }

    private void deny(String permission, ThrowingRunnable call) throws Exception {
        admin.sql("DENY " + permission + " TO account_svc").update();
        try {
            call.run();
        } finally {
            admin.sql("GRANT " + permission + " TO account_svc").update();
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** The same read as the service login fails with a driver message that names the denial. */
    private void expectDriverNamesTheDenial(String sql) {
        Throwable driver = catchThrowable(() -> JdbcClient.create(serviceDataSource).sql(sql).query().listOfRows());
        assertThat(driver).as("driver message for " + sql).rootCause().hasMessageContaining("permission was denied");
    }

    /** The sample card as S3 and S13 show it. */
    private ResultActions expectSampleCard(ResultActions result) throws Exception {
        return result.andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardId").value(cardId(CARD)))
                .andExpect(jsonPath("$.acctId").value(ACCT))
                .andExpect(jsonPath("$.cardNum").value(MASKED))
                .andExpect(jsonPath("$.embossedName").value(NAME))
                .andExpect(jsonPath("$.activeStatus").value("Y"))
                .andExpect(jsonPath("$.expiryMonth").value("03"))
                .andExpect(jsonPath("$.expiryYear").value("2023"))
                .andExpect(jsonPath("$.message").value("   Displaying requested details"))
                .andExpect(jsonPath("$.severity").value("info"));
    }

    private static TreeSet<String> members(JsonNode body) {
        TreeSet<String> names = new TreeSet<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private ResultActions expectProblem(ResultActions result, int status, String code, String message, String field)
            throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.field").value(field))
                .andExpect(jsonPath("$.severity").value("error"));
    }

    // ---- S3, S4, S13, S20: the card -------------------------------------------------------------

    @Test
    @DisplayName("CRDV-S3, CRDV-S4: the list row's card by ID, masked, the same on a re-read")
    @SpecItem({"BR-CRDV-002", "BR-CRDV-003", "BR-CRDV-004", "FLOW-CRDV-005", "NUM-CRDV-004", "DEF-CRDV-011"})
    void s3_viewById() throws Exception {
        for (int read = 0; read < 2; read++) {
            JsonNode body = body(expectSampleCard(view(cardId(CARD))));
            assertThat(members(body)).containsExactlyElementsOf(VIEW_MEMBERS);
        }
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("CRDV-S13: the typed keys find the card of that account")
    @SpecItem({"BR-CRDV-001", "BR-CRDV-002", "BR-CRDV-004", "FLOW-CRDV-007", "SE-CRDV-001", "NUM-CRDV-004",
        "DEF-CRDV-002"})
    void s13_lookup() throws Exception {
        JsonNode body = body(expectSampleCard(lookup(keys(ACCT, CARD))));
        assertThat(members(body)).containsExactlyElementsOf(VIEW_MEMBERS);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("CRDV-S20: an ADMIN reads both paths masked, and no audit row is written")
    @SpecItem("DEF-CRDV-011")
    void s20_adminReadsMasked() throws Exception {
        expectSampleCard(lookup(keys(ACCT, CARD), TestTokens.admin(ADMIN)));
        expectSampleCard(view(cardId(CARD), TestTokens.admin(ADMIN)));
        assertThat(auditRows()).isZero();
    }

    // ---- S7 to S12: the typed keys, checked before any read --------------------------------------

    @Test
    @DisplayName("CRDV-S7: both keys blank (missing, empty, spaces, * or zeros) is no input")
    @SpecItem({"VAL-CRDV-005", "VAL-CRDV-001", "VAL-CRDV-003", "FLOW-CRDV-007"})
    void s7_noInput() throws Exception {
        List<String> bodies = List.of("{}", keys("", ""), keys(null, "   "), keys("*", "0000000000000000"),
                keys("00000000000", "*   "), "");
        deny(CARD_COLUMN, () -> {
            for (String body : bodies) {
                expectProblem(lookup(body), 400, "NO_INPUT", "No input received", "acctId");
            }
        });
    }

    @Test
    @DisplayName("CRDV-S8, CRDV-S9: a blank or all-zero account is not provided")
    @SpecItem({"VAL-CRDV-001", "DEF-CRDV-007"})
    void s8_accountBlank() throws Exception {
        deny(CARD_COLUMN, () -> {
            for (String acct : new String[] {"", "*", "*   ", "           ", null, "00000000000"}) {
                expectProblem(lookup(keys(acct, CARD)), 400, "ACCT_ID_REQUIRED", "Account number not provided",
                        "acctId");
            }
            expectProblem(lookup(keys("", "ABCD")), 400, "ACCT_ID_REQUIRED", "Account number not provided", "acctId");
        });
    }

    @Test
    @DisplayName("CRDV-S10: an account that is not exactly 11 digits is invalid, before the card is checked")
    @SpecItem({"VAL-CRDV-002", "DEF-CRDV-007", "DEF-CRDV-012"})
    void s10_accountInvalid() throws Exception {
        deny(CARD_COLUMN, () -> {
            for (String acct : new String[] {"123", "0000000005A", "00000000050 ", " 0000000050", "000000000500",
                "-0000000050"}) {
                expectProblem(lookup(keys(acct, CARD)), 400, "INVALID_ACCT_ID",
                        "Account number must be a non zero 11 digit number", "acctId");
            }
            expectProblem(lookup(keys("123", "")), 400, "INVALID_ACCT_ID",
                    "Account number must be a non zero 11 digit number", "acctId");
        });
    }

    @Test
    @DisplayName("CRDV-S11: a blank or all-zero card is not provided")
    @SpecItem({"VAL-CRDV-003", "DEF-CRDV-007"})
    void s11_cardBlank() throws Exception {
        deny(CARD_COLUMN, () -> {
            for (String card : new String[] {"", "*", "*  ", null, "0000000000000000"}) {
                expectProblem(lookup(keys(ACCT, card)), 400, "CARD_NUM_REQUIRED", "Card number not provided",
                        "cardNum");
            }
        });
    }

    @Test
    @DisplayName("CRDV-S12: a card that is not exactly 16 digits is invalid")
    @SpecItem({"VAL-CRDV-004", "DEF-CRDV-007", "DEF-CRDV-012"})
    void s12_cardInvalid() throws Exception {
        deny(CARD_COLUMN, () -> {
            for (String card : new String[] {"ABCD", "050002445376574", "0500024453765740 ", "05000244537657401",
                "000000000000000A"}) {
                expectProblem(lookup(keys(ACCT, card)), 400, "INVALID_CARD_NUM",
                        "Card number must be a non zero 16 digit number", "cardNum");
            }
        });
    }

    // ---- S14 to S17, S28: not found and read failures --------------------------------------------

    @Test
    @DisplayName("CRDV-S14, CRDV-S15: a card of another account and an unknown card give the same not-found body")
    @SpecItem({"BR-CRDV-001", "VAL-CRDV-007", "DEF-CRDV-001", "ERR-CRDV-001"})
    void s14_notFound() throws Exception {
        String otherAccount = expectProblem(lookup(keys(OTHER_ACCT, CARD)), 404, "CARD_NOT_FOUND",
                "Did not find cards for this search condition", "acctId")
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String unknownCard = expectProblem(lookup(keys(ACCT, "9999999999999999")), 404, "CARD_NOT_FOUND",
                "Did not find cards for this search condition", "acctId")
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(otherAccount).isEqualTo(unknownCard).doesNotContain(NAME, "5740", "2023");
        expectProblem(lookup(keys("99999999999", CARD)), 404, "CARD_NOT_FOUND",
                "Did not find cards for this search condition", "acctId");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("CRDV-S16: a card read failure on either path is the read-error problem; the log has the cause")
    @SpecItem({"ERR-CRDV-001", "NUM-CRDV-003", "DEF-CRDV-013"})
    void s16_readFails(CapturedOutput output) throws Exception {
        String id = cardId(CARD);
        List<String> correlationIds = new ArrayList<>();
        for (String permission : List.of(CARD_COLUMN, "SELECT ON OBJECT::core.card_xref")) {
            deny(permission, () -> {
                expectDriverNamesTheDenial(permission.equals(CARD_COLUMN)
                        ? "SELECT card_id FROM core.card" : "SELECT acct_id FROM core.card_xref");
                List<ResultActions> calls = new ArrayList<>(List.of(lookup(keys(ACCT, CARD))));
                if (permission.equals(CARD_COLUMN)) {
                    calls.add(view(id));
                }
                for (ResultActions call : calls) {
                    JsonNode body = body(expectProblem(call, 500, "CARD_LOOKUP_FAILED", "Error reading card data",
                            "acctId").andExpect(content().string(not(containsString("permission")))));
                    assertThat(body.toString()).doesNotContain("CARDDAT", "RESP", "SELECT", "core.", CARD, NAME);
                    correlationIds.add(call.andReturn().getResponse().getHeader("X-Correlation-Id"));
                }
            });
        }
        String log = output.getAll();
        assertThat(log).contains("card read failed: card " + MASKED, "card read failed: card_id " + id)
                .containsPattern("SQLServerException SQL error 2(29|30) \\(state \\w+\\)")
                .doesNotContain("permission was denied");
        assertThat(correlationIds).hasSize(3).allSatisfy(c -> assertThat(log).contains("correlation ID " + c));
        assertThat(log).doesNotContain(CARD, NAME);
        expectSampleCard(lookup(keys(ACCT, CARD)));
    }

    @Test
    @DisplayName("CRDV-S17: a card ID that is not a positive BIGINT, or names no card, is not found")
    @SpecItem({"FLOW-CRDV-014", "ERR-CRDV-003"})
    void s17_badCardId() throws Exception {
        long unknown = Long.parseLong(cardId(OTHER_CARD)) + 1000;
        for (String id : new String[] {"0", "abc", "-1", "+1", "1.0", "9223372036854775808", "99999999999999999999",
            String.valueOf(unknown)}) {
            expectProblem(view(id), 404, "CARD_NOT_FOUND", "Did not find cards for this search condition", "acctId");
            expectProblem(unmask(id, "[\"cardNum\"]"), 404, "CARD_NOT_FOUND",
                    "Did not find cards for this search condition", "acctId");
        }
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("CRDV-S28: the cross-reference, not core.card.acct_id, decides the card's account")
    @SpecItem("BR-CRDV-001")
    void s28_xrefOwnsTheLink() throws Exception {
        DB.fixtures().account(CoreFixtures.Account.of("00000000051", "0.00"));
        admin.sql("UPDATE core.card_xref SET acct_id = '00000000051' WHERE card_num = :c").param("c", CARD).update();
        before = coreRows();
        lookup(keys("00000000051", CARD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.cardId").value(cardId(CARD)))
                .andExpect(jsonPath("$.acctId").value(ACCT))
                .andExpect(jsonPath("$.cardNum").value(MASKED));
        expectProblem(lookup(keys(ACCT, CARD)), 404, "CARD_NOT_FOUND", "Did not find cards for this search condition",
                "acctId");
    }

    // ---- S19, S25 to S27: session, batch window, stored values -----------------------------------

    @Test
    @DisplayName("CRDV-S19: every endpoint needs a session")
    @SpecItem("DEF-CRDV-002")
    void s19_noSession() throws Exception {
        String id = cardId(CARD);
        for (ResultActions call : List.of(
                mvc.perform(post("/api/cards/lookup").contentType(MediaType.APPLICATION_JSON).content(keys(ACCT, CARD))),
                mvc.perform(get("/api/cards/{c}", id)),
                mvc.perform(post("/api/cards/{c}/unmask", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[\"cardNum\"]}")))) {
            call.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("CRDV-S25: an open batch window does not stop the reads or the unmask")
    @SpecItem("BR-CRDV-004")
    void s25_batchWindow() throws Exception {
        DB.fixtures().openBatchWindow();
        expectSampleCard(lookup(keys(ACCT, CARD)));
        expectSampleCard(view(cardId(CARD)));
        unmask(cardId(CARD), "[\"cardNum\"]").andExpect(status().isOk());
    }

    @Test
    @DisplayName("CRDV-S26: a cookie session must send the CSRF token with the lookup")
    @SpecItem("FLOW-CRDV-007")
    void s26_lookupNeedsTheCsrfToken() throws Exception {
        Cookie session = new Cookie("cd_at", TestTokens.user(USER));
        deny(CARD_COLUMN, () -> mvc.perform(post("/api/cards/lookup").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content(keys(ACCT, CARD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED")));
        expectSampleCard(mvc.perform(post("/api/cards/lookup").cookie(session, new Cookie("XSRF-TOKEN", "t0k3n"))
                .header("X-XSRF-TOKEN", "t0k3n").contentType(MediaType.APPLICATION_JSON).content(keys(ACCT, CARD))));
    }

    @Test
    @DisplayName("CRDV-S27: status and expiry as stored: X, month 11, year 2025")
    @SpecItem({"BR-CRDV-002", "NUM-CRDV-001"})
    void s27_storedValues() throws Exception {
        admin.sql("UPDATE core.card SET active_status = 'X', expiration_date = '2025-11-30' WHERE card_num = :c")
                .param("c", CARD).update();
        before = coreRows();
        for (ResultActions call : List.of(lookup(keys(ACCT, CARD)), view(cardId(CARD)))) {
            call.andExpect(status().isOk())
                    .andExpect(jsonPath("$.activeStatus").value("X"))
                    .andExpect(jsonPath("$.expiryMonth").value("11"))
                    .andExpect(jsonPath("$.expiryYear").value("2025"));
        }
    }

    // ---- S21 to S23: unmask and PII --------------------------------------------------------------

    @Test
    @DisplayName("CRDV-S21: an ADMIN unmasks the card number; one audit row without the value")
    @SpecItem("DEF-CRDV-011")
    void s21_unmask() throws Exception {
        String id = cardId(CARD);
        String correlationId = unmask(id, "[\"cardNum\"]").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.fields.cardNum").value(CARD))
                .andExpect(jsonPath("$.fields.length()").value(1))
                .andReturn().getResponse().getHeader("X-Correlation-Id");
        List<Map<String, Object>> rows = admin.sql("""
                SELECT service, actor_user_id, action, subject_type, subject_key, field_names, correlation_id,
                    CASE WHEN ABS(DATEDIFF(SECOND, occurred_at, SYSUTCDATETIME())) < 120 THEN 'now' END AS occurred
                FROM ops.audit""").query().listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("service", "account-service").containsEntry("actor_user_id", ADMIN)
                .containsEntry("action", "UNMASK").containsEntry("subject_type", "CARD")
                .containsEntry("subject_key", id).containsEntry("field_names", "cardNum")
                .containsEntry("occurred", "now").containsEntry("correlation_id", correlationId);
        assertThat(rows.get(0).values().toString()).doesNotContain(CARD, NAME, CVV);

        unmask(id, "[\"cardNum\",\"cardNum\"]").andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.cardNum").value(CARD));
        assertThat(auditRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("CRDV-S21: a USER cannot unmask; other or no field names are refused; no audit row")
    @SpecItem("DEF-CRDV-011")
    void s21_unmaskRefused() throws Exception {
        String id = cardId(CARD);
        unmask(id, "[\"cardNum\"]", TestTokens.user(USER)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        for (String fields : new String[] {"[\"cvv\"]", "[]", "null", "[\"cardNum\",\"cvv\"]", "[\"ssn\"]"}) {
            expectProblem(unmask(id, fields), 400, "INVALID_UNMASK_FIELD", "Unmask one or more of: cardNum", "fields");
        }
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("CRDV-S22: the card read or the audit insert fails: no clear value, nothing committed")
    @SpecItem("DEF-CRDV-011")
    void s22_unmaskFailures() throws Exception {
        String id = cardId(CARD);
        deny("INSERT ON OBJECT::ops.audit", () -> {
            JsonNode body = body(expectProblem(unmask(id, "[\"cardNum\"]"), 500, "UNMASK_AUDIT_FAILED",
                    "Unable to record the unmask request", null));
            assertThat(body.has("fields")).isFalse();
            assertThat(body.toString()).doesNotContain(CARD);
        });
        deny(CARD_COLUMN, () -> {
            JsonNode body = body(expectProblem(unmask(id, "[\"cardNum\"]"), 500, "CARD_LOOKUP_FAILED",
                    "Error reading card data", "acctId"));
            assertThat(body.has("fields")).isFalse();
        });
        assertThat(auditRows()).isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("CRDV-S23: no clear card number or CVV in any view, problem or log; the name is never logged")
    @SpecItem({"DEF-CRDV-011", "SE-CRDV-001"})
    void s23_noPiiLeaks(CapturedOutput output) throws Exception {
        String id = cardId(CARD);
        List<JsonNode> bodies = new ArrayList<>();
        bodies.add(body(lookup(keys(ACCT, CARD)).andExpect(status().isOk())));
        bodies.add(body(view(id).andExpect(status().isOk())));
        bodies.add(body(view(id, TestTokens.admin(ADMIN)).andExpect(status().isOk())));
        bodies.add(body(lookup(keys(OTHER_ACCT, CARD)).andExpect(status().isNotFound())));
        bodies.add(body(lookup(keys(ACCT, CARD + " ")).andExpect(status().isBadRequest())));
        deny(CARD_COLUMN, () -> {
            bodies.add(body(lookup(keys(ACCT, CARD)).andExpect(status().isInternalServerError())));
            bodies.add(body(view(id).andExpect(status().isInternalServerError())));
            bodies.add(body(unmask(id, "[\"cardNum\"]").andExpect(status().isInternalServerError())));
        });
        deny("INSERT ON OBJECT::ops.audit", () ->
                bodies.add(body(unmask(id, "[\"cardNum\"]").andExpect(status().isInternalServerError()))));
        for (JsonNode body : bodies) {
            assertThat(body.toString()).doesNotContain(CARD).doesNotContainIgnoringCase("cvv");
            assertThat(textValues(body)).doesNotContain(CVV);
        }
        assertThat(output.getAll()).doesNotContain(CARD, NAME).contains("CARD_LOOKUP_FAILED");
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
