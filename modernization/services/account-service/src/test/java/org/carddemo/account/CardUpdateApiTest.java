package org.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestClock;
import org.carddemo.platform.testsupport.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Section 9's CRDU scenarios against the API. Start data: the sample card 0500024453765740 of
 * account 00000000050 (name {@code Aniya Von}, expiry 2023-03-09, status Y, CVV 747), signed on as
 * USER0001 unless the test says otherwise. Every test ends by checking that no card's account,
 * number, CVV or filler changed; a test without a save also checks that the card's row version
 * did not move.
 */
class CardUpdateApiTest extends AccountTestBase {

    static final String ACCT = "00000000050";
    static final String CUST = "000000050";
    static final String CARD = "0500024453765740";
    static final String MASKED = "************5740";
    static final String NAME = "Aniya Von";
    static final String CVV = "747";
    static final String FILLER = "KEEP-THIS-FILLER";
    // Fixed rather than derived: a row version with no hex letters upper-cases to itself.
    static final String UPPER_CASE_TAG = "00000000ABCDEF01";
    static final String OTHER_ACCT = "00000000001";
    static final String OTHER_CARD = "4000000000000001";
    static final String USER = "USER0001";
    static final String ADMIN = "ADMIN001";
    static final String CARD_COLUMN = "SELECT ON OBJECT::core.card (card_id)";
    // The column list of R__grants_account.sql: a table-wide GRANT would widen the login for later tests.
    static final String CARD_UPDATE = "UPDATE ON OBJECT::core.card (embossed_name, active_status, expiration_date)";

    static final List<String> FORM_MEMBERS = List.of("acctId", "activeStatus", "cardId", "cardNum", "embossedName",
            "expiryMonth", "expiryYear", "message", "severity", "version");

    private final JdbcClient admin = DB.admin();
    private final ObjectMapper json = new ObjectMapper();
    private List<String> protectedBefore;

    @Autowired
    DataSource serviceDataSource;

    @BeforeEach
    void sampleCards() {
        TestClock.shared().reset();
        DB.fixtures().deleteAll()
                .account(CoreFixtures.Account.of(ACCT, "0.00")).customer(CUST).card(CARD, ACCT, CUST)
                .account(CoreFixtures.Account.of(OTHER_ACCT, "194.00")).customer("000000001")
                .card(OTHER_CARD, OTHER_ACCT, "000000001");
        admin.sql("""
                UPDATE core.card SET embossed_name = :name, expiration_date = '2023-03-09', active_status = 'Y',
                    cvv_cd = :cvv, legacy_filler = :filler WHERE card_num = :card""")
                .param("name", NAME).param("cvv", CVV).param("filler", FILLER).param("card", CARD).update();
        protectedBefore = protectedColumns();
    }

    @AfterEach
    void neverWritten() {
        TestClock.shared().reset();
        DB.fixtures().closeBatchWindows();
        assertThat(protectedColumns()).as("acct_id, card_num, cvv_cd, legacy_filler").isEqualTo(protectedBefore);
    }

    private List<String> protectedColumns() {
        return admin.sql("SELECT card_id, card_num, acct_id, cvv_cd, legacy_filler FROM core.card ORDER BY card_id")
                .query((rs, n) -> rs.getLong(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4)
                        + "|" + rs.getString(5))
                .list();
    }

    // ---- requests -------------------------------------------------------------------------------

    private String keys(String acctId, String cardNum) {
        ObjectNode body = json.createObjectNode();
        body.put("acctId", acctId);
        body.put("cardNum", cardNum);
        return body.toString();
    }

    private ObjectNode changes(String name, String status, String month, String year) {
        ObjectNode changes = json.createObjectNode();
        changes.put("embossedName", name);
        changes.put("activeStatus", status);
        changes.put("expiryMonth", month);
        changes.put("expiryYear", year);
        return changes;
    }

    private ObjectNode stored() {
        return changes(NAME, "Y", "03", "2023");
    }

    private String validateBody(String version, ObjectNode changes) {
        ObjectNode body = json.createObjectNode();
        body.put("version", version);
        body.set("changes", changes);
        return body.toString();
    }

    private String putBody(ObjectNode changes) {
        ObjectNode body = json.createObjectNode();
        body.set("changes", changes);
        return body.toString();
    }

    private ResultActions updateLookup(String body) throws Exception {
        return updateLookup(body, TestTokens.user(USER));
    }

    private ResultActions updateLookup(String body, String token) throws Exception {
        return mvc.perform(post("/api/cards/update-lookup").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions fetch(String cardId) throws Exception {
        return fetch(cardId, TestTokens.user(USER));
    }

    private ResultActions fetch(String cardId, String token) throws Exception {
        return mvc.perform(get("/api/cards/{cardId}/update-form", cardId).header("Authorization", bearer(token)));
    }

    private ResultActions validate(String cardId, String body) throws Exception {
        return validate(cardId, body, TestTokens.user(USER));
    }

    private ResultActions validate(String cardId, String body, String token) throws Exception {
        return mvc.perform(post("/api/cards/{cardId}/validate", cardId).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions save(String cardId, String ifMatch, String body) throws Exception {
        return save(cardId, ifMatch, body, TestTokens.user(USER));
    }

    private ResultActions save(String cardId, String ifMatch, String body, String token) throws Exception {
        var request = put("/api/cards/{cardId}", cardId).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mvc.perform(request);
    }

    // ---- reading the results --------------------------------------------------------------------

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String cardId(String cardNum) {
        return admin.sql("SELECT CAST(card_id AS VARCHAR(20)) FROM core.card WHERE card_num = :c").param("c", cardNum)
                .query(String.class).single();
    }

    /** The stored version of the sample card, as the API writes it. */
    private String version() {
        return admin.sql("SELECT row_version FROM core.card WHERE card_num = :c").param("c", CARD)
                .query((rs, n) -> HexFormat.of().formatHex(rs.getBytes(1))).single();
    }

    private String etag() {
        return '"' + version() + '"';
    }

    private Map<String, Object> row() {
        return admin.sql("""
                SELECT embossed_name, active_status, expiration_date
                FROM core.card WHERE card_num = :c""").param("c", CARD).query().singleRow();
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

    private ResultActions expectProblem(ResultActions result, int status, String code, String message, String field)
            throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.field").value(field))
                .andExpect(jsonPath("$.severity").value("error"));
    }

    private ResultActions expectRule(ResultActions result, String code, String message, String field,
            String... invalidFields) throws Exception {
        JsonNode body = body(expectProblem(result, 400, code, message, field));
        List<String> marked = new ArrayList<>();
        body.path("invalidFields").forEach(f -> marked.add(f.asText()));
        assertThat(marked).as("invalidFields").containsExactly(invalidFields);
        return result;
    }

    /** The sample card's form as loaded (S7), with the stored version in body and ETag. */
    private JsonNode expectSampleForm(ResultActions result) throws Exception {
        String version = version();
        result.andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("ETag", '"' + version + '"'))
                .andExpect(jsonPath("$.cardId").value(cardId(CARD)))
                .andExpect(jsonPath("$.acctId").value(ACCT))
                .andExpect(jsonPath("$.cardNum").value(MASKED))
                .andExpect(jsonPath("$.embossedName").value(NAME))
                .andExpect(jsonPath("$.activeStatus").value("Y"))
                .andExpect(jsonPath("$.expiryMonth").value("03"))
                .andExpect(jsonPath("$.expiryYear").value("2023"))
                .andExpect(jsonPath("$.version").value(version))
                .andExpect(jsonPath("$.message").isEmpty())
                .andExpect(jsonPath("$.severity").isEmpty());
        JsonNode body = body(result);
        assertThat(members(body)).containsExactlyElementsOf(FORM_MEMBERS);
        return body;
    }

    private static TreeSet<String> members(JsonNode body) {
        TreeSet<String> names = new TreeSet<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ---- S7, S23, S20: the form -----------------------------------------------------------------

    @Test
    @DisplayName("CRDU-S7, CRDU-S23: the typed keys and the list row's ID give the same form, name as stored")
    @SpecItem({"BR-CRDU-001", "BR-CRDU-008", "SE-CRDU-001", "FLOW-CRDU-005", "DEF-CRDU-008"})
    void s7_s23_form() throws Exception {
        String version = version();
        expectSampleForm(updateLookup(keys(ACCT, CARD)));
        expectSampleForm(fetch(cardId(CARD)));
        assertThat(version()).isEqualTo(version);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("CRDU-S20: Cancel re-reads the stored values; the edits validated before are dropped")
    @SpecItem("FLOW-CRDU-011")
    void s20_cancel() throws Exception {
        String version = version();
        validate(cardId(CARD), validateBody(version, changes("Jane Doe", "N", "12", "2027"))).andExpect(status().isOk());
        expectSampleForm(fetch(cardId(CARD)));
        assertThat(version()).isEqualTo(version);
    }

    // ---- S3 to S6, S8, S26: the keys ------------------------------------------------------------

    @Test
    @DisplayName("CRDU-S3 to S5, CRDU-S26: the cardLookup form checks the keys before any read")
    @SpecItem({"VAL-CRDU-001", "VAL-CRDU-002", "VAL-CRDU-003", "NUM-CRDU-002", "DEF-CRDU-017"})
    void s3_keys() throws Exception {
        deny(CARD_COLUMN, () -> {
            expectProblem(updateLookup(keys("", "")), 400, "NO_INPUT", "No input received", "acctId");
            expectProblem(updateLookup("{}"), 400, "NO_INPUT", "No input received", "acctId");
            expectProblem(updateLookup(keys(ACCT, "")), 400, "CARD_NUM_REQUIRED", "Card number not provided",
                    "cardNum");
            for (String acct : new String[] {"50", "0"}) {
                expectProblem(updateLookup(keys(acct, CARD)), 400, "INVALID_ACCT_ID",
                        "Account number must be a non zero 11 digit number", "acctId");
            }
            expectProblem(updateLookup(keys(ACCT, "05000244537657")), 400, "INVALID_CARD_NUM",
                    "Card number must be a non zero 16 digit number", "cardNum");
        });
    }

    @Test
    @DisplayName("CRDU-S6, CRDU-S8: an unknown card and a card of another account give the same not-found body")
    @SpecItem({"VAL-CRDU-004", "BR-CRDU-002", "BR-CRDU-007", "DEF-CRDU-001"})
    void s6_s8_notFound() throws Exception {
        String unknown = expectProblem(updateLookup(keys(ACCT, "9999999999999999")), 404, "CARD_NOT_FOUND",
                "Did not find cards for this search condition", "acctId")
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String otherAccount = expectProblem(updateLookup(keys("00000000099", CARD)), 404, "CARD_NOT_FOUND",
                "Did not find cards for this search condition", "acctId")
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        expectProblem(updateLookup(keys(OTHER_ACCT, CARD)), 404, "CARD_NOT_FOUND",
                "Did not find cards for this search condition", "acctId");
        assertThat(otherAccount).isEqualTo(unknown).doesNotContain(NAME, "5740", "2023");
    }

    @Test
    @DisplayName("CRDU-S44: a card ID that is not a positive BIGINT, or names no card, is not found; a save of a"
            + " missing card cannot lock it")
    @SpecItem({"DEF-CRDU-021", "FLOW-CRDU-011"})
    void s44_unknownCardId() throws Exception {
        String etag = etag();
        String body = putBody(changes(NAME, "N", "03", "2023"));
        String unknown = String.valueOf(Long.parseLong(cardId(OTHER_CARD)) + 1000);
        for (String id : new String[] {"0", "abc", "-1", "+1", "9223372036854775808", unknown}) {
            expectProblem(fetch(id), 404, "CARD_NOT_FOUND", "Did not find cards for this search condition", "acctId");
            expectProblem(validate(id, validateBody(version(), changes(NAME, "N", "03", "2023"))), 404,
                    "CARD_NOT_FOUND", "Did not find cards for this search condition", "acctId");
            if (!id.equals(unknown)) {
                expectProblem(save(id, etag, body), 404, "CARD_NOT_FOUND",
                        "Did not find cards for this search condition", "acctId");
                expectProblem(save(id, null, body), 404, "CARD_NOT_FOUND",
                        "Did not find cards for this search condition", "acctId");
            }
        }
        expectProblem(save(unknown, null, body), 428, "PRECONDITION_REQUIRED", "If-Match header is required", null);
        expectProblem(save(unknown, etag, body), 409, "CARD_LOCK_FAILED", "Could not lock record for update", "acctId");
        assertThat(version()).isEqualTo(etag.substring(1, 17));
        assertThat(auditRows()).isZero();

        String id = cardId(CARD);
        admin.sql("DELETE FROM core.card_xref WHERE card_num = :c").param("c", CARD).update();
        admin.sql("DELETE FROM core.card WHERE card_num = :c").param("c", CARD).update();
        protectedBefore = protectedColumns();
        expectProblem(fetch(id), 404, "CARD_NOT_FOUND", "Did not find cards for this search condition", "acctId");
    }

    // ---- S9 to S13, S34 to S36: validate --------------------------------------------------------

    @Test
    @DisplayName("CRDU-S9, CRDU-S36: no change (spaces around the values and a lower-case status included) is NO_CHANGE")
    @SpecItem({"VAL-CRDU-010", "BR-CRDU-004", "VAL-CRDU-006", "Q-CRDU-008"})
    void s9_noChange() throws Exception {
        String version = version();
        String id = cardId(CARD);
        for (ObjectNode same : List.of(stored(), changes(NAME + "    ", "Y", "03", "2023"), changes(NAME, "y", "03", "2023"),
                changes(NAME, " y ", " 03", "2023 "), changes(NAME, "Y ", "03 ", " 2023"))) {
            expectProblem(validate(id, validateBody(version, same)), 400, "NO_CHANGE",
                    "No change detected with respect to values fetched.", "embossedName")
                    .andExpect(jsonPath("$.invalidFields").doesNotExist());
            expectProblem(save(id, etag(), putBody(same)), 400, "NO_CHANGE",
                    "No change detected with respect to values fetched.", "embossedName");
        }
        assertThat(version()).isEqualTo(version);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("CRDU-S10, CRDU-S13: a case-only name change and a status change validate; nothing is written")
    @SpecItem({"BR-CRDU-003", "BR-CRDU-004", "BR-CRDU-008", "DEF-CRDU-008", "FLOW-CRDU-009"})
    void s10_s13_validated() throws Exception {
        String version = version();
        for (ObjectNode edit : List.of(changes("aniya von", "Y", "03", "2023"), changes(NAME, "N", "03", "2023"))) {
            validate(cardId(CARD), validateBody(version, edit)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Changes validated.Press F5 to save"))
                    .andExpect(jsonPath("$.severity").value("info"))
                    .andExpect(jsonPath("$.length()").value(2));
        }
        assertThat(version()).isEqualTo(version);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("CRDU-S11, CRDU-S12: the first failure is the message; invalidFields marks every failing field")
    @SpecItem({"VAL-CRDU-005", "VAL-CRDU-006", "VAL-CRDU-007", "VAL-CRDU-008", "VAL-CRDU-009"})
    void s11_s12_rules() throws Exception {
        String version = version();
        String id = cardId(CARD);
        expectRule(validate(id, validateBody(version, changes("Aniya Von2", "Y", "03", "2023"))), "INVALID_CARD_NAME",
                "Card name can only contain alphabets and spaces", "embossedName", "embossedName");
        expectRule(validate(id, validateBody(version, changes(NAME, "y", "13", "2100"))), "INVALID_EXPIRY_MONTH",
                "Card expiry month must be between 1 and 12", "expiryMonth", "expiryMonth", "expiryYear");
        expectRule(validate(id, validateBody(version, changes("", "X", "00", "1949"))), "CARD_NAME_REQUIRED",
                "Card name not provided", "embossedName", "embossedName", "activeStatus", "expiryMonth", "expiryYear");
        expectRule(validate(id, validateBody(version, changes(NAME, "Y", "04", "2100"))), "INVALID_EXPIRY_YEAR",
                "Invalid card expiry year", "expiryYear", "expiryYear");
        expectRule(save(id, etag(), putBody(changes(NAME, "Q", "03", "2023"))), "INVALID_CARD_STATUS",
                "Card Active Status must be Y or N", "activeStatus", "activeStatus");
        assertThat(version()).isEqualTo(version);
    }

    @Test
    @DisplayName("CRDU-S34: the month must be two digits; 3, AB and ' 5' are invalid")
    @SpecItem({"NUM-CRDU-003", "VAL-CRDU-007"})
    void s34_monthDigits() throws Exception {
        String version = version();
        for (String month : new String[] {"3", "AB", " 5", "5 ", "+5"}) {
            expectRule(validate(cardId(CARD), validateBody(version, changes(NAME, "Y", month, "2023"))),
                    "INVALID_EXPIRY_MONTH", "Card expiry month must be between 1 and 12", "expiryMonth", "expiryMonth");
        }
        assertThat(version()).isEqualTo(version);
    }

    @Test
    @DisplayName("CRDU-S35: 51 letters, an apostrophe, a diaeresis, 50 zeros and * are refused")
    @SpecItem({"VAL-CRDU-005", "NUM-CRDU-007", "FLOW-CRDU-008"})
    void s35_names() throws Exception {
        String version = version();
        String id = cardId(CARD);
        expectRule(validate(id, validateBody(version, changes("A".repeat(51), "Y", "03", "2023"))), "CARD_NAME_TOO_LONG",
                "Card name must not be longer than 50 characters.", "embossedName", "embossedName");
        for (String name : new String[] {"O'Brien", "Zoë"}) {
            expectRule(validate(id, validateBody(version, changes(name, "Y", "03", "2023"))), "INVALID_CARD_NAME",
                    "Card name can only contain alphabets and spaces", "embossedName", "embossedName");
        }
        for (String name : new String[] {"0".repeat(50), "*", "*   ", "   "}) {
            expectRule(validate(id, validateBody(version, changes(name, "Y", "03", "2023"))), "CARD_NAME_REQUIRED",
                    "Card name not provided", "embossedName", "embossedName");
        }
        validate(id, validateBody(version, changes("A".repeat(50), "Y", "03", "2023"))).andExpect(status().isOk());
        assertThat(version()).isEqualTo(version);
    }

    @Test
    @DisplayName("CRDU-S32: a changed expiry may be this month but not earlier; an unchanged past expiry passes")
    @SpecItem("DEF-CRDU-009")
    void s32_expiryNotPast() throws Exception {
        TestClock.shared().set(Instant.parse("2026-03-15T12:00:00Z"));
        String version = version();
        String id = cardId(CARD);
        validate(id, validateBody(version, changes(NAME, "Y", "03", "2026"))).andExpect(status().isOk());
        expectRule(validate(id, validateBody(version, changes(NAME, "Y", "02", "2026"))), "EXPIRY_IN_PAST",
                "Card expiry date cannot be in the past", "expiryMonth", "expiryMonth", "expiryYear");
        validate(id, validateBody(version, changes(NAME, "N", "03", "2023"))).andExpect(status().isOk());
        assertThat(version()).isEqualTo(version);
    }

    @Test
    @DisplayName("CRDU-S30: validate with a stale, missing or malformed version")
    @SpecItem("BR-CRDU-005")
    void s30_validateVersion() throws Exception {
        String id = cardId(CARD);
        String stale = version();
        admin.sql("UPDATE core.card SET active_status = 'N' WHERE card_num = :c").param("c", CARD).update();
        String current = version();
        JsonNode body = body(expectProblem(validate(id, validateBody(stale, changes("Jane Doe", "Y", "03", "2023"))),
                409, "RECORD_CHANGED", "Record changed by some one else. Please review", "embossedName")
                .andExpect(header().string("ETag", '"' + current + '"')));
        assertThat(body.path("current").path("activeStatus").asText()).isEqualTo("N");
        assertThat(body.path("current").path("version").asText()).isEqualTo(current);
        assertThat(body.path("current").path("cardNum").asText()).isEqualTo(MASKED);
        assertThat(members(body.path("current"))).containsExactlyElementsOf(FORM_MEMBERS);

        ObjectNode edit = changes("Jane Doe", "Y", "03", "2023");
        for (String version : new String[] {null, "", "xyz", '"' + current + '"', UPPER_CASE_TAG,
            current + "." + current, "W/" + current}) {
            expectProblem(validate(id, validateBody(version, edit)), 400, "INVALID_IF_MATCH",
                    "If-Match header is not a version of this record", null);
        }
        expectProblem(validate(id, putBody(edit)), 400, "INVALID_IF_MATCH",
                "If-Match header is not a version of this record", null);
        assertThat(version()).isEqualTo(current);
    }

    // ---- S13, S14, S33, S36: save ---------------------------------------------------------------

    @Test
    @DisplayName("CRDU-S13, CRDU-S14: the status is saved; name, expiry day, account, CVV and filler unchanged")
    @SpecItem({"BR-CRDU-001", "BR-CRDU-003", "BR-CRDU-006", "BR-CRDU-007", "SE-CRDU-003", "NUM-CRDU-001",
        "NUM-CRDU-004", "DEF-CRDU-001", "DEF-CRDU-002", "DEF-CRDU-003", "DEF-CRDU-006", "FLOW-CRDU-010"})
    void s14_save() throws Exception {
        String id = cardId(CARD);
        String before = version();
        validate(id, validateBody(before, changes(NAME, "N", "03", "2023"))).andExpect(status().isOk());
        ResultActions saved = save(id, '"' + before + '"', putBody(changes(NAME, "N", "03", "2023")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Changes committed to database"))
                .andExpect(jsonPath("$.severity").value("success"))
                .andExpect(jsonPath("$.form.cardId").value(id))
                .andExpect(jsonPath("$.form.acctId").value(ACCT))
                .andExpect(jsonPath("$.form.cardNum").value(MASKED))
                .andExpect(jsonPath("$.form.embossedName").value(NAME))
                .andExpect(jsonPath("$.form.activeStatus").value("N"))
                .andExpect(jsonPath("$.form.expiryMonth").value("03"))
                .andExpect(jsonPath("$.form.expiryYear").value("2023"));
        String after = version();
        assertThat(after).isNotEqualTo(before);
        saved.andExpect(header().string("ETag", '"' + after + '"')).andExpect(jsonPath("$.form.version").value(after));
        assertThat(row()).containsEntry("embossed_name", NAME).containsEntry("active_status", "N")
                .containsEntry("expiration_date", "2023-03-09");

        String correlationId = saved.andReturn().getResponse().getHeader("X-Correlation-Id");
        List<Map<String, Object>> rows = admin.sql("""
                SELECT service, actor_user_id, action, subject_type, subject_key, field_names, correlation_id,
                    CASE WHEN ABS(DATEDIFF(SECOND, occurred_at, SYSUTCDATETIME())) < 120 THEN 'now' END AS occurred
                FROM ops.audit""").query().listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("service", "account-service").containsEntry("actor_user_id", USER)
                .containsEntry("action", "UPDATE").containsEntry("subject_type", "CARD")
                .containsEntry("subject_key", id).containsEntry("field_names", "activeStatus")
                .containsEntry("occurred", "now").containsEntry("correlation_id", correlationId);
    }

    @Test
    @DisplayName("CRDU-S14: every field changed: the name without trailing spaces, the stored day kept")
    @SpecItem({"BR-CRDU-006", "BR-CRDU-008", "SE-CRDU-003", "NUM-CRDU-001"})
    void s14_saveEveryField() throws Exception {
        String id = cardId(CARD);
        save(id, etag(), putBody(changes("jane Doe  ", "n", "12", "2027"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.form.embossedName").value("jane Doe"))
                .andExpect(jsonPath("$.form.activeStatus").value("N"))
                .andExpect(jsonPath("$.form.expiryMonth").value("12"))
                .andExpect(jsonPath("$.form.expiryYear").value("2027"));
        assertThat(row()).containsEntry("embossed_name", "jane Doe").containsEntry("active_status", "N")
                .containsEntry("expiration_date", "2027-12-09");
        assertThat(admin.sql("SELECT field_names FROM ops.audit").query(String.class).single())
                .isEqualTo("activeStatus,embossedName,expirationDate");
    }

    @Test
    @DisplayName("CRDU-S36: status n is saved as N; spaces around status, month and year are not saved")
    @SpecItem({"VAL-CRDU-006", "Q-CRDU-008"})
    void s36_lowerCaseStatus() throws Exception {
        save(cardId(CARD), etag(), putBody(changes(NAME, "n", "03", "2023"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.form.activeStatus").value("N"));
        assertThat(row()).containsEntry("active_status", "N");

        save(cardId(CARD), etag(), putBody(changes(NAME, " y ", " 05", "2027 "))).andExpect(status().isOk())
                .andExpect(jsonPath("$.form.activeStatus").value("Y"))
                .andExpect(jsonPath("$.form.expiryMonth").value("05"))
                .andExpect(jsonPath("$.form.expiryYear").value("2027"));
        assertThat(row()).containsEntry("active_status", "Y").containsEntry("expiration_date", "2027-05-09");
        assertThat(admin.sql("SELECT field_names FROM ops.audit ORDER BY audit_id").query(String.class).list())
                .containsExactly("activeStatus", "activeStatus,expirationDate");
    }

    @Test
    @DisplayName("CRDU-S33: a stored day beyond the new month's end becomes its last day, leap years included")
    @SpecItem({"NUM-CRDU-001", "DEF-CRDU-009"})
    void s33_dayClamped() throws Exception {
        String id = cardId(CARD);
        admin.sql("UPDATE core.card SET expiration_date = '2027-01-31' WHERE card_num = :c").param("c", CARD).update();
        save(id, etag(), putBody(changes(NAME, "Y", "02", "2027"))).andExpect(status().isOk());
        assertThat(row()).containsEntry("expiration_date", "2027-02-28");
        admin.sql("UPDATE core.card SET expiration_date = '2028-01-31' WHERE card_num = :c").param("c", CARD).update();
        save(id, etag(), putBody(changes(NAME, "Y", "02", "2028"))).andExpect(status().isOk());
        assertThat(row()).containsEntry("expiration_date", "2028-02-29");
        save(id, etag(), putBody(changes(NAME, "Y", "03", "2028"))).andExpect(status().isOk());
        assertThat(row()).containsEntry("expiration_date", "2028-03-29");
    }

    // ---- S18, S19, S29, S40, S42: concurrency ---------------------------------------------------

    @Test
    @DisplayName("CRDU-S18, CRDU-S29: a save with the ETag of a row another save changed is RECORD_CHANGED")
    @SpecItem({"BR-CRDU-005", "FLOW-CRDU-010"})
    void s18_s29_recordChanged() throws Exception {
        String id = cardId(CARD);
        String old = etag();
        save(id, old, putBody(changes(NAME, "N", "03", "2023")), TestTokens.user("USER0002")).andExpect(status().isOk());
        String current = version();
        JsonNode body = body(expectProblem(save(id, old, putBody(changes("Jane Doe", "Y", "03", "2023"))), 409,
                "RECORD_CHANGED", "Record changed by some one else. Please review", "embossedName")
                .andExpect(header().string("ETag", '"' + current + '"')));
        assertThat(body.path("current").path("activeStatus").asText()).isEqualTo("N");
        assertThat(body.path("current").path("embossedName").asText()).isEqualTo(NAME);
        assertThat(body.path("current").path("version").asText()).isEqualTo(current);
        assertThat(version()).isEqualTo(current);
        assertThat(row()).containsEntry("embossed_name", NAME);
        assertThat(auditRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("CRDU-S19: the card is deleted after validate: the save cannot lock it")
    @SpecItem({"ERR-CRDU-001", "FLOW-CRDU-010"})
    void s19_cardDeleted() throws Exception {
        String id = cardId(CARD);
        String etag = etag();
        validate(id, validateBody(version(), changes(NAME, "N", "03", "2023"))).andExpect(status().isOk());
        admin.sql("DELETE FROM core.card_xref WHERE card_num = :c").param("c", CARD).update();
        admin.sql("DELETE FROM core.card WHERE card_num = :c").param("c", CARD).update();
        protectedBefore = protectedColumns();
        expectProblem(save(id, etag, putBody(changes(NAME, "N", "03", "2023"))), 409, "CARD_LOCK_FAILED",
                "Could not lock record for update", "acctId");
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("CRDU-S40: two saves with the same ETag at once: one 200, one RECORD_CHANGED, one audit row")
    @SpecItem({"SE-CRDU-002", "SE-CRDU-005", "BR-CRDU-005"})
    @Timeout(60)
    void s40_concurrentSaves() throws Exception {
        String id = cardId(CARD);
        String etag = etag();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = DB.adminDataSource().getConnection()) {
            holder.setAutoCommit(false);
            holdUpdateLock(holder, id);
            Future<MvcResult> first = pool.submit(() ->
                    save(id, etag, putBody(changes(NAME, "N", "03", "2023"))).andReturn());
            Future<MvcResult> second = pool.submit(() ->
                    save(id, etag, putBody(changes("Jane Doe", "Y", "03", "2023"))).andReturn());
            awaitLockWaiters(2);
            holder.commit();
            List<MvcResult> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
            assertThat(statuses).containsExactly(200, 409);
            MvcResult conflict = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertThat(json.readTree(conflict.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("code").asText())
                    .isEqualTo("RECORD_CHANGED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(auditRows()).isEqualTo(1);
        assertThat(version()).isNotEqualTo(etag.substring(1, 17));
    }

    @Test
    @DisplayName("CRDU-S42: another connection holds the row lock: CARD_LOCK_FAILED after 5 seconds, then a save works")
    @SpecItem({"ERR-CRDU-001", "Q-CRDU-007", "SE-CRDU-002"})
    @Timeout(60)
    void s42_lockHeld() throws Exception {
        String id = cardId(CARD);
        String etag = etag();
        try (Connection holder = DB.adminDataSource().getConnection()) {
            holder.setAutoCommit(false);
            holdUpdateLock(holder, id);
            long start = System.nanoTime();
            expectProblem(save(id, etag, putBody(changes(NAME, "N", "03", "2023"))), 409, "CARD_LOCK_FAILED",
                    "Could not lock record for update", "acctId");
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(millis).as("waited for the lock").isBetween(4_500L, 9_000L);
            holder.commit();
        }
        assertThat(version()).isEqualTo(etag.substring(1, 17));
        assertThat(auditRows()).isZero();
        save(id, etag, putBody(changes(NAME, "N", "03", "2023"))).andExpect(status().isOk());
        assertThat(row()).containsEntry("active_status", "N");
        everyPooledConnectionWaitsForLocks();
    }

    private static void holdUpdateLock(Connection holder, String cardId) throws Exception {
        try (Statement s = holder.createStatement();
             ResultSet rs = s.executeQuery("SELECT card_id FROM core.card WITH (UPDLOCK, ROWLOCK) WHERE card_id = " + cardId)) {
            assertThat(rs.next()).isTrue();
        }
    }

    /** Both saves are queued on the row lock before it is released, so they really race for it. */
    private void awaitLockWaiters(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        int waiting = 0;
        while (System.nanoTime() < deadline) {
            waiting = admin.sql("""
                    SELECT COUNT(*) FROM sys.dm_exec_requests
                    WHERE database_id = DB_ID() AND wait_type LIKE 'LCK[_]M[_]U%'""").query(Integer.class).single();
            if (waiting >= count) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(waiting).as("saves waiting for the row lock").isGreaterThanOrEqualTo(count);
    }

    /** SET LOCK_TIMEOUT belongs to the connection: no pooled connection keeps the save's limit. */
    private void everyPooledConnectionWaitsForLocks() throws Exception {
        int size = serviceDataSource instanceof HikariDataSource hikari ? hikari.getMaximumPoolSize() : 1;
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int i = 0; i < size; i++) {
                Connection c = serviceDataSource.getConnection();
                borrowed.add(c);
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT @@LOCK_TIMEOUT")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).as("@@LOCK_TIMEOUT of pooled connection %d", i).isEqualTo(-1);
                }
            }
        } finally {
            for (Connection c : borrowed) {
                c.close();
            }
        }
    }

    // ---- S2, S27, S28, S31: access, preconditions, batch window ---------------------------------

    @Test
    @DisplayName("CRDU-S2: every endpoint needs a session")
    @SpecItem("DEF-CRDU-006")
    void s2_noSession() throws Exception {
        String id = cardId(CARD);
        String version = version();
        for (ResultActions call : List.of(
                mvc.perform(post("/api/cards/update-lookup").contentType(MediaType.APPLICATION_JSON).content(keys(ACCT, CARD))),
                mvc.perform(get("/api/cards/{c}/update-form", id)),
                mvc.perform(post("/api/cards/{c}/validate", id).contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody(version, changes(NAME, "N", "03", "2023")))),
                mvc.perform(put("/api/cards/{c}", id).header("If-Match", '"' + version + '"')
                        .contentType(MediaType.APPLICATION_JSON).content(putBody(changes(NAME, "N", "03", "2023")))))) {
            call.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        assertThat(version()).isEqualTo(version);
    }

    @Test
    @DisplayName("CRDU-S27: an ADMIN token is refused on every endpoint")
    void s27_adminRefused() throws Exception {
        String id = cardId(CARD);
        String version = version();
        String token = TestTokens.admin(ADMIN);
        for (ResultActions call : List.of(updateLookup(keys(ACCT, CARD), token), fetch(id, token),
                validate(id, validateBody(version, changes(NAME, "N", "03", "2023")), token),
                save(id, '"' + version + '"', putBody(changes(NAME, "N", "03", "2023")), token))) {
            expectProblem(call, 403, "ACCESS_DENIED", "You are not authorized to use this function ...", null);
        }
        assertThat(version()).isEqualTo(version);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("CRDU-S28: a save needs If-Match, one of this card's versions, and the CSRF token")
    void s28_preconditions() throws Exception {
        String id = cardId(CARD);
        String version = version();
        String body = putBody(changes(NAME, "N", "03", "2023"));
        expectProblem(save(id, null, body), 428, "PRECONDITION_REQUIRED", "If-Match header is required", null);
        for (String ifMatch : new String[] {"xyz", version, "W/\"" + version + "\"", "*",
            "\"" + version + "." + version + "\"", "\"" + UPPER_CASE_TAG + "\""}) {
            expectProblem(save(id, ifMatch, body), 400, "INVALID_IF_MATCH",
                    "If-Match header is not a version of this record", null);
        }
        Cookie session = new Cookie("cd_at", TestTokens.user(USER));
        mvc.perform(put("/api/cards/{c}", id).cookie(session).header("If-Match", '"' + version + '"')
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        assertThat(version()).isEqualTo(version);
        mvc.perform(put("/api/cards/{c}", id).cookie(session, new Cookie("XSRF-TOKEN", "t0k3n"))
                        .header("X-XSRF-TOKEN", "t0k3n").header("If-Match", '"' + version + '"')
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CRDU-S31: an open batch window lets validate through and stops the save")
    void s31_batchWindow() throws Exception {
        DB.fixtures().openBatchWindow();
        String id = cardId(CARD);
        String version = version();
        validate(id, validateBody(version, changes(NAME, "N", "03", "2023"))).andExpect(status().isOk());
        expectSampleForm(fetch(id));
        expectProblem(save(id, '"' + version + '"', putBody(changes(NAME, "N", "03", "2023"))), 503, "BATCH_WINDOW",
                "Card update is not available while the batch cycle runs. Try again later...", "embossedName")
                .andExpect(header().string("Retry-After", "300"));
        assertThat(version()).isEqualTo(version);
        assertThat(auditRows()).isZero();
    }

    // ---- S37 to S39: failures and PII -----------------------------------------------------------

    @Test
    @DisplayName("CRDU-S37: the update or the audit insert is refused: CARD_UPDATE_FAILED, nothing committed")
    @SpecItem({"SE-CRDU-003", "SE-CRDU-005", "ERR-CRDU-001"})
    void s37_writeFails() throws Exception {
        String id = cardId(CARD);
        String version = version();
        for (String permission : List.of(CARD_UPDATE, "INSERT ON OBJECT::ops.audit")) {
            deny(permission, () -> {
                JsonNode body = body(expectProblem(save(id, '"' + version + '"', putBody(changes(NAME, "N", "03", "2023"))),
                        500, "CARD_UPDATE_FAILED", "Update of record failed", "acctId"));
                assertThat(body.toString()).doesNotContain("permission", "denied", "core.", "ops.");
            });
            assertThat(version()).as(permission).isEqualTo(version);
            assertThat(row()).containsEntry("active_status", "Y");
        }
        assertThat(auditRows()).isZero();
        save(id, '"' + version + '"', putBody(changes(NAME, "N", "03", "2023"))).andExpect(status().isOk());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("CRDU-S38: a card read failure on lookup, fetch, validate and save is the read-error problem")
    @SpecItem({"ERR-CRDU-001", "NUM-CRDU-005"})
    void s38_readFails(CapturedOutput output) throws Exception {
        String id = cardId(CARD);
        String version = version();
        deny(CARD_COLUMN, () -> {
            for (ResultActions call : List.of(updateLookup(keys(ACCT, CARD)), fetch(id),
                    validate(id, validateBody(version, changes(NAME, "N", "03", "2023"))),
                    save(id, '"' + version + '"', putBody(changes(NAME, "N", "03", "2023"))))) {
                JsonNode body = body(expectProblem(call, 500, "CARD_LOOKUP_FAILED", "Error reading card data", "acctId"));
                assertThat(body.toString()).doesNotContain("CARDDAT", "RESP", "SELECT", "core.", "permission", CARD, NAME);
            }
        });
        assertThat(output.getAll()).contains("card update read failed: card " + MASKED,
                "card update read failed: card_id " + id).doesNotContain(CARD, NAME);
        assertThat(version()).isEqualTo(version);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("CRDU-S39: no CVV, clear card number or name in any response or log; extra members change nothing")
    @SpecItem({"DEF-CRDU-019", "BR-CRDU-006", "BR-CRDU-007"})
    void s39_noPiiLeaks(CapturedOutput output) throws Exception {
        String id = cardId(CARD);
        List<JsonNode> bodies = new ArrayList<>();
        bodies.add(body(updateLookup(keys(ACCT, CARD)).andExpect(status().isOk())));
        bodies.add(body(fetch(id).andExpect(status().isOk())));
        bodies.add(body(updateLookup(keys(OTHER_ACCT, CARD)).andExpect(status().isNotFound())));
        bodies.add(body(validate(id, validateBody(version(), changes("Aniya Von2", "Y", "03", "2023")))
                .andExpect(status().isBadRequest())));
        deny(CARD_COLUMN, () -> bodies.add(body(fetch(id).andExpect(status().isInternalServerError()))));
        deny(CARD_UPDATE, () -> bodies.add(body(save(id, etag(),
                putBody(changes(NAME, "N", "03", "2023"))).andExpect(status().isInternalServerError()))));

        ObjectNode edit = changes("Jane Doe", "N", "03", "2023");
        edit.put("acctId", OTHER_ACCT).put("cardNum", OTHER_CARD).put("cvvCd", "999").put("cvv", "999")
                .put("legacyFiller", "X").put("expiryDay", "01");
        ObjectNode request = json.createObjectNode();
        request.set("changes", edit);
        request.put("acctId", OTHER_ACCT).put("cardNum", OTHER_CARD).put("cvvCd", "999").put("version", "0");
        String stale = etag();
        bodies.add(body(save(id, stale, request.toString()).andExpect(status().isOk())));
        bodies.add(body(save(id, stale, putBody(changes(NAME, "Y", "03", "2023"))).andExpect(status().isConflict())));
        assertThat(row()).containsEntry("embossed_name", "Jane Doe").containsEntry("active_status", "N")
                .containsEntry("expiration_date", "2023-03-09");
        assertThat(admin.sql("SELECT acct_id FROM core.card_xref WHERE card_num = :c").param("c", CARD)
                .query(String.class).single()).isEqualTo(ACCT);
        assertThat(admin.sql("SELECT cvv_cd, acct_id, RTRIM(legacy_filler) AS filler FROM core.card WHERE card_num = :c")
                .param("c", CARD).query().singleRow())
                .containsEntry("cvv_cd", CVV).containsEntry("acct_id", ACCT).containsEntry("filler", FILLER);

        for (JsonNode body : bodies) {
            assertThat(body.toString()).doesNotContain(CARD, OTHER_CARD, FILLER).doesNotContainIgnoringCase("cvv");
            assertThat(textValues(body)).doesNotContain(CVV, "999", OTHER_ACCT);
        }
        assertThat(output.getAll()).doesNotContain(CARD, NAME, "Jane Doe", OTHER_CARD, FILLER)
                .contains("CARD_LOOKUP_FAILED", "CARD_UPDATE_FAILED");
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
