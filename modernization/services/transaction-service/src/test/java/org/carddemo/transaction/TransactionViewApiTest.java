package org.carddemo.transaction;

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
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.legacyformat.entity.TransactionRecord;
import org.carddemo.platform.testsupport.TestTokens;
import org.carddemo.transaction.SampleTransactions.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The TRNV scenarios of the contract against the API, and the ADMIN unmask of the card number.
 * Start data: SAMPLE; signed on as USER0001 unless the test says otherwise. Every test ends by
 * checking that no {@code core.*} row changed and that only an unmask wrote an audit row.
 */
class TransactionViewApiTest extends TransactionTestBase {

    static final String TRAN = "0000000000683580";
    static final String CARD = SampleTransactions.SAMPLE_CARD;
    static final String MASKED = "************7065";
    static final String NOT_FOUND = "Transaction ID NOT found...";
    static final String REQUIRED = "Tran ID can NOT be empty...";
    static final String LOOKUP_FAILED = "Unable to lookup Transaction...";
    static final List<String> VIEW_MEMBERS = List.of("amount", "amountDisplay", "cardNum", "catCd", "description",
            "merchantCity", "merchantId", "merchantName", "merchantZip", "origTs", "procTs", "source", "tranId",
            "typeCd");

    private List<String> before;
    private int expectedAuditRows;

    @BeforeEach
    void sample() {
        DB.fixtures().deleteAll();
        SampleTransactions.loadSample(admin);
        before = coreRows();
        expectedAuditRows = 0;
    }

    @AfterEach
    void nothingChanged() {
        assertThat(coreRows()).as("core.* rows").isEqualTo(before);
        assertThat(auditRows()).as("ops.audit rows").isEqualTo(expectedAuditRows);
    }

    private ResultActions view(String tranId) throws Exception {
        return view(tranId, TestTokens.user(USER));
    }

    private ResultActions view(String tranId, String token) throws Exception {
        return mvc.perform(get("/api/transactions/{tranId}", tranId).header("Authorization", bearer(token)));
    }

    private ResultActions unmask(String tranId, String fields) throws Exception {
        return unmask(tranId, fields, TestTokens.admin(ADMIN));
    }

    private ResultActions unmask(String tranId, String fields, String token) throws Exception {
        return mvc.perform(post("/api/transactions/{tranId}/unmask", tranId).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":" + fields + "}"));
    }

    /** A master row of the test's own, with the sample's first row's codes and card. */
    private void row(String tranId, String description, String amount, String merchantName, String merchantCity,
                     LocalDateTime origTs, LocalDateTime procTs, boolean inMaster) {
        SampleTransactions.insert(admin, new Row(new TransactionRecord(tranId, "01", "0001", "POS TERM", description,
                new BigDecimal(amount), "800000000", merchantName, merchantCity, "72112", CARD, null, null, null),
                origTs, procTs), inMaster);
        before = coreRows();
    }

    /** The sample's first row as S3 shows it. */
    private JsonNode expectSampleRow(ResultActions result) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.tranId").value(TRAN))
                .andExpect(jsonPath("$.cardNum").value(MASKED))
                .andExpect(jsonPath("$.typeCd").value("01"))
                .andExpect(jsonPath("$.catCd").value("0001"))
                .andExpect(jsonPath("$.source").value("POS TERM"))
                .andExpect(jsonPath("$.description").value("Purchase at Abshire-Lowe"))
                .andExpect(jsonPath("$.amount").value("504.77"))
                .andExpect(jsonPath("$.amountDisplay").value("+00000504.77"))
                .andExpect(jsonPath("$.origTs").value("2022-06-10 19:27:53.000000"))
                .andExpect(jsonPath("$.procTs").value("2022-06-10 19:27:53.000000"))
                .andExpect(jsonPath("$.merchantId").value("800000000"))
                .andExpect(jsonPath("$.merchantName").value("Abshire-Lowe"))
                .andExpect(jsonPath("$.merchantCity").value("North Enoshaven"))
                .andExpect(jsonPath("$.merchantZip").value("72112"));
        JsonNode body = body(result);
        assertThat(members(body)).containsExactlyElementsOf(VIEW_MEMBERS);
        return body;
    }

    // ---- S1, S13, S15: access and the batch window -----------------------------------------------

    @Test
    @DisplayName("TRNV-S1: the view and the unmask need a session")
    @SpecItem("FLOW-TRNV-001")
    void s1_noSession() throws Exception {
        for (ResultActions call : List.of(mvc.perform(get("/api/transactions/{t}", TRAN)),
                mvc.perform(post("/api/transactions/{t}/unmask", TRAN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[\"cardNum\"]}")))) {
            call.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    @DisplayName("TRNV-S13: an ADMIN views the transaction masked, and no audit row is written")
    @SpecItem("DEF-TRNV-004")
    void s13_adminViewsMasked() throws Exception {
        expectSampleRow(view(TRAN, TestTokens.admin(ADMIN)));
    }

    @Test
    @DisplayName("TRNV-S15: an open batch window does not stop the view or the unmask")
    @SpecItem("SE-TRNV-001")
    void s15_batchWindow() throws Exception {
        DB.fixtures().openBatchWindow();
        expectSampleRow(view(TRAN));
        unmask(TRAN, "[\"cardNum\"]").andExpect(status().isOk());
        expectedAuditRows = 1;
    }

    // ---- S3 to S7, S17, S18, S20, S21: the read ----------------------------------------------------

    @Test
    @DisplayName("TRNV-S3: the sample transaction, card masked, both timestamps with time; the same on a re-read")
    @SpecItem({"BR-TRNV-002", "SE-TRNV-001", "NUM-TRNV-001", "NUM-TRNV-002", "NUM-TRNV-003", "DEF-TRNV-001",
        "DEF-TRNV-004", "DEF-TRNV-006"})
    void s3_view() throws Exception {
        for (int read = 0; read < 2; read++) {
            expectSampleRow(view(TRAN));
        }
    }

    @Test
    @DisplayName("TRNV-S4: an ID of spaces only is required, before any read")
    @SpecItem("VAL-TRNV-001")
    void s4_blank() throws Exception {
        deny(TRAN_ID_COLUMN, () -> {
            for (String id : new String[] {" ", "   ", " ".repeat(16), " ".repeat(20)}) {
                expectProblem(view(id), 400, "TRAN_ID_REQUIRED", REQUIRED, "tranId");
            }
        });
    }

    @Test
    @DisplayName("TRNV-S5: an ID with no master row is not found")
    @SpecItem({"VAL-TRNV-002", "ERR-TRNV-001"})
    void s5_notFound() throws Exception {
        expectProblem(view("9999999999999999"), 404, "TRAN_NOT_FOUND", NOT_FOUND, "tranId");
    }

    @Test
    @DisplayName("TRNV-S6: a shorter numeric ID is padded on the left with zeros")
    @SpecItem({"DEF-TRNV-003", "BR-TRNV-001"})
    void s6_padding() throws Exception {
        expectSampleRow(view("683580"));
        expectSampleRow(view("0683580"));
    }

    @Test
    @DisplayName("TRNV-S7: long texts and both timestamps are shown in full; trailing data spaces other than U+0020 stay")
    @SpecItem({"DEF-TRNV-006", "BR-TRNV-002", "NUM-TRNV-002"})
    void s7_fullValues() throws Exception {
        String description = "\u00c9".repeat(60) + "0123456789".repeat(4);
        String name = "M\u00a0\u0085" + "N".repeat(47);
        String city = "C".repeat(49) + "\u00a0";
        LocalDateTime ts = LocalDateTime.of(2022, 6, 10, 19, 27, 53);
        row("0000000000000777", description, "504.77", name, city, ts, ts.plusNanos(123_456_000), true);
        view("777").andExpect(status().isOk())
                .andExpect(jsonPath("$.tranId").value("0000000000000777"))
                .andExpect(jsonPath("$.description").value(description))
                .andExpect(jsonPath("$.merchantName").value(name))
                .andExpect(jsonPath("$.merchantCity").value(city))
                .andExpect(jsonPath("$.amountDisplay").value("+00000504.77"))
                .andExpect(jsonPath("$.origTs").value("2022-06-10 19:27:53.000000"))
                .andExpect(jsonPath("$.procTs").value("2022-06-10 19:27:53.123456"));
    }

    @Test
    @DisplayName("TRNV-S17: an amount from 100,000,000.00 shows nine integer digits")
    @SpecItem({"NUM-TRNV-001", "DEF-TRNV-002"})
    void s17_wideAmount() throws Exception {
        LocalDateTime ts = LocalDateTime.of(2022, 6, 10, 19, 27, 53);
        row("0000000000000778", "WIDE", "123456789.12", "M", "C", ts, ts, true);
        row("0000000000000779", "NEGATIVE", "-5.50", "M", "C", ts, ts, true);
        view("778").andExpect(status().isOk()).andExpect(jsonPath("$.amount").value("123456789.12"))
                .andExpect(jsonPath("$.amountDisplay").value("+123456789.12"));
        view("779").andExpect(status().isOk()).andExpect(jsonPath("$.amount").value("-5.50"))
                .andExpect(jsonPath("$.amountDisplay").value("-00000005.50"));
    }

    @Test
    @DisplayName("TRNV-S18: no numeric check, only trailing spaces removed; longer than 16 is not found without a read")
    @SpecItem({"BR-TRNV-001", "DEF-TRNV-003", "VAL-TRNV-002"})
    void s18_typedValues() throws Exception {
        for (String id : new String[] {"ABC", " 683580", "68 3580", "000000000068358A", "-683580"}) {
            expectProblem(view(id), 404, "TRAN_NOT_FOUND", NOT_FOUND, "tranId");
        }
        expectSampleRow(view("683580   "));
        expectSampleRow(view(TRAN + "    "));
        deny(TRAN_ID_COLUMN, () -> {
            for (String id : new String[] {"00000000006835801", "1".repeat(40), TRAN + "x", "\u00c9" + TRAN}) {
                expectProblem(view(id), 404, "TRAN_NOT_FOUND", NOT_FOUND, "tranId");
            }
        });
    }

    @Test
    @DisplayName("TRNV-S20: a failed lookup after a good one carries nothing of the earlier transaction")
    @SpecItem("VAL-TRNV-002")
    void s20_noCarryOver() throws Exception {
        expectSampleRow(view(TRAN));
        JsonNode body = body(expectProblem(view("9999999999999999"), 404, "TRAN_NOT_FOUND", NOT_FOUND, "tranId"));
        assertThat(body.toString()).doesNotContain(TRAN, "Abshire", "504.77", "7065");
    }

    @Test
    @DisplayName("TRNV-S21: a row outside the master (archived) is not found")
    @SpecItem("BR-TRNV-001")
    void s21_outsideTheMaster() throws Exception {
        LocalDateTime ts = LocalDateTime.of(2022, 6, 10, 19, 27, 53);
        row("0000000000000780", "ARCHIVED", "1.00", "M", "C", ts, ts, false);
        expectProblem(view("780"), 404, "TRAN_NOT_FOUND", NOT_FOUND, "tranId");
        expectProblem(unmask("780", "[\"cardNum\"]"), 404, "TRAN_NOT_FOUND", NOT_FOUND, "tranId");
    }

    @Test
    @DisplayName("TRNV-S16: an online add waiting for its cycle is not found until a cycle posts it")
    @SpecItem("VAL-TRNV-002")
    void s16_waitingAdd() throws Exception {
        DB.fixtures().waitingOnlineAdd("0000000000000781", CARD, "9.99");
        before = coreRows();
        expectProblem(view("781"), 404, "TRAN_NOT_FOUND", NOT_FOUND, "tranId");
        SampleTransactions.insertDefault(admin, "0000000000000781", "9.99");
        before = coreRows();
        view("781").andExpect(status().isOk()).andExpect(jsonPath("$.tranId").value("0000000000000781"));
    }

    // ---- S14, S19: failures and PII ----------------------------------------------------------------

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("TRNV-S14: a read failure is the lookup error; the log has the cause and the correlation ID")
    @SpecItem("ERR-TRNV-001")
    void s14_readFails(CapturedOutput output) throws Exception {
        List<String> correlationIds = new ArrayList<>();
        deny(TRAN_ID_COLUMN, () -> {
            for (String id : new String[] {TRAN, "683580"}) {
                ResultActions call = view(id);
                JsonNode body = body(expectProblem(call, 500, "TRAN_LOOKUP_FAILED", LOOKUP_FAILED, "tranId")
                        .andExpect(content().string(not(containsString("permission")))));
                assertThat(body.toString()).doesNotContain("TRANSACT", "RESP", "SELECT", "core.", "7065");
                correlationIds.add(call.andReturn().getResponse().getHeader("X-Correlation-Id"));
            }
        });
        String log = output.getAll();
        assertThat(log).contains("transaction read failed: tran_id " + TRAN)
                .containsPattern("SQLServerException SQL error 2(29|30) \\(state \\w+\\)")
                .doesNotContain("permission was denied", CARD);
        assertThat(correlationIds).hasSize(2).allSatisfy(c -> assertThat(log).contains("correlation ID " + c));
        expectSampleRow(view(TRAN));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("TRNV-S19: no clear card number in any view, problem or log")
    @SpecItem({"DEF-TRNV-004", "Q-TRNV-001"})
    void s19_noClearCardNumber(CapturedOutput output) throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        bodies.add(body(view(TRAN).andExpect(status().isOk())));
        bodies.add(body(view(TRAN, TestTokens.admin(ADMIN)).andExpect(status().isOk())));
        bodies.add(body(view("9999999999999999").andExpect(status().isNotFound())));
        bodies.add(body(view(" ").andExpect(status().isBadRequest())));
        bodies.add(body(unmask(TRAN, "[\"cardNum\"]", TestTokens.user(USER)).andExpect(status().isForbidden())));
        bodies.add(body(unmask(TRAN, "[\"ssn\"]").andExpect(status().isBadRequest())));
        deny(TRAN_ID_COLUMN, () -> {
            bodies.add(body(view(TRAN).andExpect(status().isInternalServerError())));
            bodies.add(body(unmask(TRAN, "[\"cardNum\"]").andExpect(status().isInternalServerError())));
        });
        deny(AUDIT_INSERT, () ->
                bodies.add(body(unmask(TRAN, "[\"cardNum\"]").andExpect(status().isInternalServerError()))));
        for (JsonNode body : bodies) {
            assertThat(textValues(body)).noneSatisfy(v -> assertThat(v).contains(CARD));
        }
        assertThat(output.getAll()).doesNotContain(CARD).contains("TRAN_LOOKUP_FAILED");
    }

    // ---- The ADMIN unmask (DEF-TRNV-004, D-09) ---------------------------------------------------------

    @Test
    @DisplayName("TRNV: an ADMIN unmasks the card number; one audit row with the field name, never the value")
    @SpecItem("DEF-TRNV-004")
    void adminUnmasks() throws Exception {
        String correlationId = unmask("683580", "[\"cardNum\"]").andExpect(status().isOk())
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
        assertThat(rows.get(0)).containsEntry("service", "transaction-service").containsEntry("actor_user_id", ADMIN)
                .containsEntry("action", "UNMASK").containsEntry("subject_type", "TRANSACTION")
                .containsEntry("subject_key", TRAN).containsEntry("field_names", "cardNum")
                .containsEntry("occurred", "now").containsEntry("correlation_id", correlationId);
        assertThat(rows.get(0).values().toString()).doesNotContain(CARD);

        unmask(TRAN, "[\"cardNum\",\"cardNum\"]").andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.cardNum").value(CARD));
        expectedAuditRows = 2;
    }

    @Test
    @DisplayName("TRNV: a USER cannot unmask; other or no field names, a blank or unknown ID are refused; no audit row")
    @SpecItem("DEF-TRNV-004")
    void unmaskRefused() throws Exception {
        unmask(TRAN, "[\"cardNum\"]", TestTokens.user(USER)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        for (String fields : new String[] {"[\"ssn\"]", "[]", "null", "[\"cardNum\",\"cvv\"]", "[\"tranId\"]"}) {
            expectProblem(unmask(TRAN, fields), 400, "INVALID_UNMASK_FIELD", "Unmask one or more of: cardNum",
                    "fields");
        }
        expectProblem(unmask(" ", "[\"cardNum\"]"), 400, "TRAN_ID_REQUIRED", REQUIRED, "tranId");
        expectProblem(unmask("9999999999999999", "[\"cardNum\"]"), 404, "TRAN_NOT_FOUND", NOT_FOUND, "tranId");
        expectProblem(unmask(" 683580", "[\"cardNum\"]"), 404, "TRAN_NOT_FOUND", NOT_FOUND, "tranId");
    }

    @Test
    @DisplayName("TRNV: the read or the audit insert of an unmask fails: no clear value, nothing committed")
    @SpecItem("DEF-TRNV-004")
    void unmaskFailures() throws Exception {
        deny(AUDIT_INSERT, () -> {
            JsonNode body = body(expectProblem(unmask(TRAN, "[\"cardNum\"]"), 500, "UNMASK_AUDIT_FAILED",
                    "Unable to record the unmask request", null));
            assertThat(body.has("fields")).isFalse();
        });
        deny(TRAN_ID_COLUMN, () -> {
            JsonNode body = body(expectProblem(unmask(TRAN, "[\"cardNum\"]"), 500, "TRAN_LOOKUP_FAILED", LOOKUP_FAILED,
                    "tranId"));
            assertThat(body.has("fields")).isFalse();
        });
    }

    @Test
    @DisplayName("TRNV: a cookie session must send the CSRF token with the unmask")
    @SpecItem("DEF-TRNV-004")
    void unmaskNeedsTheCsrfToken() throws Exception {
        Cookie session = new Cookie("cd_at", TestTokens.admin(ADMIN));
        mvc.perform(post("/api/transactions/{t}/unmask", TRAN).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"cardNum\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        mvc.perform(post("/api/transactions/{t}/unmask", TRAN).cookie(session, new Cookie("XSRF-TOKEN", "t0k3n"))
                        .header("X-XSRF-TOKEN", "t0k3n").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[\"cardNum\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.cardNum").value(CARD));
        expectedAuditRows = 1;
    }
}
