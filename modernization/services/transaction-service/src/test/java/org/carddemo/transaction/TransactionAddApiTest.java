package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.TestClock;
import org.carddemo.platform.testsupport.TestTokens;
import org.carddemo.platform.web.MessageCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The TRNA scenarios of the contract that do not depend on the ID mode, against the API. The ones
 * that do (S21, S22, S29, S34, S37) are in {@link TransactionAddIdTest}.
 */
class TransactionAddApiTest extends TransactionAddTestBase {

    static final String TYPE_EMPTY = "Type CD can NOT be empty...";
    static final String CONFIRM = "Confirm to add this transaction...";

    /** A validate call that passes the keys and fails on the empty data, with the resolved keys. */
    private ResultActions keysThenEmptyData(Map<String, String> form) throws Exception {
        Map<String, String> empty = new java.util.LinkedHashMap<>();
        empty.put("acctId", form.get("acctId"));
        empty.put("cardNum", form.get("cardNum"));
        return expectProblem(validate(empty), 400, "FIELD_REQUIRED", TYPE_EMPTY, "typeCd");
    }

    // ---- S1, S32: session, role and CSRF ----------------------------------------------------------

    @Test
    @DisplayName("TRNA-S1: every endpoint needs a session")
    @SpecItem("FLOW-TRNA-001")
    void s1_noSession() throws Exception {
        mvc.perform(post("/api/transactions/validate").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(valid())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(addRequest(valid(), key())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/transactions/latest"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(dailyRows()).isZero();
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("TRNA-S32: an ADMIN token is refused on all three; a cookie session needs the CSRF token")
    @SpecItem("DEF-TRNA-012")
    void s32_adminAndCsrf() throws Exception {
        String adminToken = TestTokens.admin(ADMIN);
        validate(valid(), adminToken).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        add(valid(), key(), adminToken).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        latest(adminToken).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(addRequest(valid(), key()).cookie(new Cookie("cd_at", TestTokens.user(USER))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        assertThat(dailyRows()).isZero();
        assertThat(auditRows()).isZero();
    }

    // ---- S2 to S16: the checks in edit order ------------------------------------------------------

    @Test
    @DisplayName("TRNA-S3: account and card empty")
    @SpecItem("VAL-TRNA-001")
    void s3_keyRequired() throws Exception {
        expectProblem(validate(valid(" ", "")), 400, "KEY_REQUIRED", "Account or Card Number must be entered...",
                "acctId");
        expectProblem(validate(Map.of()), 400, "KEY_REQUIRED", "Account or Card Number must be entered...", "acctId");
    }

    @Test
    @DisplayName("TRNA-S4, S6, S7: one card, the typed account, the card alone; padded and masked keys")
    @SpecItem({"BR-TRNA-001", "VAL-TRNA-002", "DEF-TRNA-006", "SE-TRNA-003"})
    void s4_s6_s7_keysResolve() throws Exception {
        keysThenEmptyData(valid("50", null)).andExpect(jsonPath("$.acctId").value(ACCT))
                .andExpect(jsonPath("$.cardNum").value(MASKED));
        keysThenEmptyData(valid(ACCT, "")).andExpect(jsonPath("$.cardNum").value(MASKED));
        keysThenEmptyData(valid(null, OTHER_CARD)).andExpect(jsonPath("$.acctId").value(CARD_ACCT))
                .andExpect(jsonPath("$.cardNum").value("************1516"));
    }

    @Test
    @DisplayName("TRNA-S5, S8: an unknown account, a letter in the account")
    @SpecItem({"VAL-TRNA-002", "VAL-TRNA-004"})
    void s5_s8_account() throws Exception {
        expectProblem(validate(valid("99999999999", null)), 404, "ACCOUNT_NOT_FOUND", "Account ID NOT found...",
                "acctId");
        expectProblem(validate(valid("5O", null)), 400, "INVALID_ACCT_ID", "Account ID must be Numeric...",
                "acctId");
        expectProblem(validate(valid(null, "4000000000009999")), 404, "CARD_NOT_FOUND", "Card Number NOT found...",
                "cardNum");
    }

    @Test
    @DisplayName("TRNA-S2, S9, S16, S17: a padded type and merchant ID; the confirmation prompt; nothing written")
    @SpecItem({"VAL-TRNA-007", "VAL-TRNA-011", "BR-TRNA-002", "FLOW-TRNA-003", "DEF-TRNA-008"})
    void s9_s16_s17_validate() throws Exception {
        Map<String, String> form = with(with(valid(), "typeCd", "1"), "merchantId", "123");
        form.put("confirm", "");
        validate(form).andExpect(status().isOk())
                .andExpect(jsonPath("$.acctId").value(ACCT))
                .andExpect(jsonPath("$.cardNum").value(MASKED))
                .andExpect(jsonPath("$.typeCd").value("01"))
                .andExpect(jsonPath("$.catCd").value("0001"))
                .andExpect(jsonPath("$.merchantId").value("000000123"))
                .andExpect(jsonPath("$.amount").value("-12.50"))
                .andExpect(jsonPath("$.amountDisplay").value("-00000012.50"))
                .andExpect(jsonPath("$.procDate").value("2026-11-07"))
                .andExpect(jsonPath("$.message").value(CONFIRM))
                .andExpect(jsonPath("$.severity").value("error"))
                .andExpect(jsonPath("$.field").value("confirm"));
        expectProblem(validate(with(valid(), "typeCd", "A1")), 400, "TYPE_NOT_NUMERIC", "Type CD must be Numeric...",
                "typeCd");
        expectProblem(validate(with(valid(), "merchantId", "12A")), 400, "MERCHANT_ID_NOT_NUMERIC",
                "Merchant ID must be Numeric...", "merchantId")
                .andExpect(jsonPath("$.typeCd").value("01")).andExpect(jsonPath("$.amountDisplay").value("-00000012.50"));
        assertThat(dailyRows()).isZero();
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("TRNA-S10: unknown type, category not of the type")
    @SpecItem("DEF-TRNA-002")
    void s10_references() throws Exception {
        expectProblem(validate(with(valid(), "typeCd", "09")), 400, "TYPE_NOT_FOUND",
                "Type CD is not a valid transaction type...", "typeCd");
        expectProblem(validate(with(with(valid(), "typeCd", "02"), "catCd", "0005")), 400, "CATEGORY_NOT_FOUND",
                "Category CD is not valid for this Type CD...", "catCd");
    }

    @Test
    @DisplayName("TRNA-S11 to S14, S18: amount and date forms, invalid dates, the negative zero")
    @SpecItem({"VAL-TRNA-008", "VAL-TRNA-009", "VAL-TRNA-010", "DEF-TRNA-007", "NUM-TRNA-001"})
    void s11_to_s14_s18_amountAndDate() throws Exception {
        expectProblem(validate(with(valid(), "amount", "12.50")), 400, "INVALID_AMOUNT",
                "Amount should be in format -99999999.99", "amount");
        for (String date : List.of("2022-02-30", "1500-01-01", "0000-01-01")) {
            expectProblem(validate(with(valid(), "origDate", date)), 400, "INVALID_ORIG_DATE",
                    "Orig Date - Not a valid date...", "origDate");
        }
        expectProblem(validate(with(valid(), "origDate", "2022/07/06")), 400, "INVALID_ORIG_DATE_FORMAT",
                "Orig Date should be in format YYYY-MM-DD", "origDate");
        validate(with(valid(), "amount", "-00000000.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.amountDisplay").value("+00000000.00"));
    }

    @Test
    @DisplayName("TRNA-S35, S40, S41: lengths, the character set, and the check order")
    @SpecItem({"VAL-TRNA-006", "NUM-TRNA-005"})
    void s35_s40_s41_textChecks() throws Exception {
        expectProblem(validate(with(valid(), "description", "D".repeat(61))), 400, "TOO_LONG",
                "Description must not be longer than 60 characters.", "description");
        Map<String, String> labels = Map.of("source", "Source", "description", "Description", "merchantName",
                "Merchant Name", "merchantCity", "Merchant City", "merchantZip", "Merchant Zip");
        labels.forEach((name, label) -> {
            try {
                expectProblem(validate(with(valid(), name, "\u4e2d")), 400, "UNSUPPORTED_CHARACTER",
                        label + " contains a character that is not supported.", name);
            } catch (Exception e) {
                throw new AssertionError(name, e);
            }
        });
        expectProblem(validate(with(with(valid(), "description", "\u4e2d"), "typeCd", "")), 400, "FIELD_REQUIRED",
                TYPE_EMPTY, "typeCd");
        expectProblem(validate(with(valid(), "description", "\u4e2d" + "x".repeat(60))), 400,
                "UNSUPPORTED_CHARACTER", "Description contains a character that is not supported.", "description");
        expectProblem(validate(with(with(valid(), "description", "D".repeat(61)), "merchantZip", "")), 400,
                "FIELD_REQUIRED", "Merchant Zip can NOT be empty...", "merchantZip");
        expectProblem(validate(valid(TWO_CARDS_ACCT, "12A")), 400, "INVALID_CARD_NUM",
                "Card Number must be Numeric...", "cardNum");
        assertThat(dailyRows()).isZero();
    }

    // ---- S15, S19, S24, S30, S39: adds --------------------------------------------------------------

    @Test
    @DisplayName("TRNA-S19: a confirm other than Y or y adds nothing")
    @SpecItem("VAL-TRNA-012")
    void s19_invalidConfirm() throws Exception {
        for (String confirm : new String[] {"X", ""}) {
            expectProblem(add(with(valid(), "confirm", confirm), key()), 400, "INVALID_CONFIRM",
                    "Invalid value. Valid values are (Y/N)...", "confirm");
        }
        assertThat(dailyRows()).isZero();
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("TRNA-S15, S24: Y with the data adds at once; no limit on the original date")
    @SpecItem({"BR-TRNA-002", "DEF-TRNA-008", "BR-TRNA-005"})
    void s15_s24_addAtOnce() throws Exception {
        JsonNode first = body(add(valid(), key()).andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.severity").value("success"))
                .andExpect(jsonPath("$.field").value("acctId"))
                .andExpect(jsonPath("$.hint.message").value(HINT))
                .andExpect(jsonPath("$.hint.severity").value("info")));
        assertThat(first.path("message").asText()).isEqualTo(ADDED.formatted(first.path("tranId").asText()));
        add(with(valid(), "origDate", "2026-11-08"), key()).andExpect(status().isCreated());
        assertThat(dailyRows()).isEqualTo(2);
        assertThat(auditRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("TRNA-S30: several cards: the user chooses; the pair is checked")
    @SpecItem({"BR-TRNA-001", "DEF-TRNA-004", "DEF-TRNA-005", "VAL-TRNA-004", "VAL-TRNA-005"})
    void s30_severalCards() throws Exception {
        expectProblem(validate(valid(TWO_CARDS_ACCT, "")), 400, "CARD_CHOICE_REQUIRED",
                MessageCatalog.CARD_CHOICE_REQUIRED.text(), "cardNum")
                .andExpect(jsonPath("$.cards[0]").value("************0001"))
                .andExpect(jsonPath("$.cards[1]").value("************0002"))
                .andExpect(jsonPath("$.cards.length()").value(2));
        expectProblem(validate(valid(TWO_CARDS_ACCT, CARD)), 400, "CARD_ACCOUNT_MISMATCH",
                MessageCatalog.CARD_ACCOUNT_MISMATCH.text(), "cardNum");
        add(valid(TWO_CARDS_ACCT, SECOND_CARD), key()).andExpect(status().isCreated());
        assertThat(admin.sql("SELECT card_num FROM core.daily_tran").query(String.class).single())
                .isEqualTo(SECOND_CARD);
    }

    @Test
    @DisplayName("TRNA-S39: Latin-1 text is added unchanged, one byte a character in raw_record")
    @SpecItem("VAL-TRNA-006")
    void s39_latin1() throws Exception {
        String description = "\u00c9".repeat(60);
        add(with(valid(), "description", description), key()).andExpect(status().isCreated());
        add(with(valid(), "merchantName", "A\u00a0B\u0085C"), key()).andExpect(status().isCreated());
        Map<String, Object> row = admin.sql("""
                        SELECT description, raw_record FROM core.daily_tran WHERE merchant_name = 'Shop'""")
                .query().singleRow();
        assertThat(row.get("description")).isEqualTo(description);
        assertThat(String.valueOf(row.get("raw_record")).substring(32, 92)).isEqualTo(description);
        assertThat(admin.sql("SELECT merchant_name FROM core.daily_tran WHERE merchant_name <> 'Shop'")
                .query(String.class).single()).isEqualTo("A\u00a0B\u0085C");
    }

    // ---- S25, S27, S31: copy last and the batch window -----------------------------------------------

    @Test
    @DisplayName("TRNA-S25: copy last reads the union; the waiting add wins; the card is not copied")
    @SpecItem({"BR-TRNA-006", "DEF-TRNA-009", "FLOW-TRNA-006", "NUM-TRNA-005"})
    void s25_copyLast() throws Exception {
        DB.fixtures().transaction("0000000000000300", OTHER_CARD, "10.00")
                .waitingOnlineAdd("0000000000000301", CARD, "-3.00");
        startDataDone();
        JsonNode copied = body(latest().andExpect(status().isOk())
                .andExpect(jsonPath("$.typeCd").value("03"))
                .andExpect(jsonPath("$.catCd").value("0005"))
                .andExpect(jsonPath("$.source").value("OPERATOR"))
                .andExpect(jsonPath("$.description").value("ONLINE ADD 0000000000000301"))
                .andExpect(jsonPath("$.amount").value("-3.00"))
                .andExpect(jsonPath("$.amountDisplay").value("-00000003.00"))
                .andExpect(jsonPath("$.origDate").value("2026-11-05"))
                .andExpect(jsonPath("$.procDate").value("2026-11-07"))
                .andExpect(jsonPath("$.merchantId").value("000000002"))
                .andExpect(jsonPath("$.merchantName").value("ADDED MERCHANT"))
                .andExpect(jsonPath("$.merchantCity").value("CITY"))
                .andExpect(jsonPath("$.merchantZip").value("20002")));
        assertThat(members(copied)).containsExactly("amount", "amountDisplay", "catCd", "description",
                "merchantCity", "merchantId", "merchantName", "merchantZip", "origDate", "procDate", "source",
                "typeCd");
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("TRNA-S27: copy last with no transaction at all")
    @SpecItem("DEF-TRNA-015")
    void s27_noTransaction() throws Exception {
        latest().andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NO_TRANSACTION"))
                .andExpect(jsonPath("$.message").value("Transaction ID NOT found..."));
    }

    @Test
    @DisplayName("TRNA-S31: during a batch window validate and copy last answer; the add is refused")
    @SpecItem("SE-TRNA-002")
    void s31_batchWindow() throws Exception {
        DB.fixtures().transaction("0000000000000300", OTHER_CARD, "10.00").openBatchWindow();
        startDataDone();
        validate(valid()).andExpect(status().isOk());
        latest().andExpect(status().isOk());
        String text = "Transaction add is not available during the batch cycle. Try again later...";
        assertThat(text).hasSize(75);
        expectProblem(add(valid(), key()), 503, "BATCH_WINDOW", text, "acctId")
                .andExpect(header().string("Retry-After", "300"));
        assertThat(dailyRows()).isZero();
        assertThat(auditRows()).isZero();
    }

    // ---- S33, S38, S42: failures and the idempotency key ------------------------------------------

    /**
     * The add inserts through core.daily_tran_online_add, which owns the table, so a DENY on the
     * table's INSERT does not reach it: the procedure's EXECUTE is denied instead.
     */
    @Test
    @DisplayName("TRNA-S33: the insert or the audit row fails: 500 ADD_FAILED, nothing committed")
    @SpecItem({"SE-TRNA-002", "ERR-TRNA-001"})
    void s33_writeFailures() throws Exception {
        deny("EXECUTE ON OBJECT::core.daily_tran_online_add", () ->
                expectProblem(add(valid(), key()), 500, "ADD_FAILED", ADD_FAILED, "acctId"));
        deny(AUDIT_INSERT, () -> expectProblem(add(valid(), key()), 500, "ADD_FAILED", ADD_FAILED, "acctId"));
        assertThat(dailyRows()).isZero();
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("TRNA-S38: the Idempotency-Key is required; a used key with another body is refused")
    @SpecItem("SE-TRNA-002")
    void s38_idempotencyKey() throws Exception {
        for (String missing : new String[] {null, "abc"}) {
            expectProblem(add(valid(), missing), 400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required",
                    null);
        }
        assertThat(dailyRows()).isZero();
        String used = key();
        add(valid(), used).andExpect(status().isCreated());
        add(with(valid(), "description", "Another purchase"), used)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TRAN_IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.message").value("Idempotency-Key was used for another transaction"))
                .andExpect(jsonPath("$.field").value(nullValue()));
        assertThat(dailyRows()).isEqualTo(1);
        assertThat(auditRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("TRNA-S42: a retry replays only the same body, card number included, even the next day")
    @SpecItem("SE-TRNA-002")
    void s42_replayComparesTheWholeBody() throws Exception {
        // The card is typed: the second card added below would otherwise make the account ask for one.
        Map<String, String> base = valid(ACCT, CARD);
        String used = key();
        String tranId = body(add(base, used).andExpect(status().isCreated())).path("tranId").asText();
        add(base, used).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.tranId").value(tranId))
                .andExpect(jsonPath("$.message").value(ADDED.formatted(tranId)))
                .andExpect(jsonPath("$.hint.message").value(HINT))
                .andExpect(jsonPath("$.hint.severity").value("info"));

        DB.fixtures().card("4000000000005741", ACCT, CUST);
        startDataDone();
        List<Map<String, String>> changed = new ArrayList<>();
        changed.add(with(base, "cardNum", "4000000000005741"));
        Map<String, String> other = Map.ofEntries(Map.entry("typeCd", "02"), Map.entry("catCd", "0002"),
                Map.entry("source", "OPERATOR"), Map.entry("description", "Another purchase"),
                Map.entry("amount", "-00000012.51"), Map.entry("merchantId", "123456780"),
                Map.entry("merchantName", "Store"), Map.entry("merchantCity", "City"),
                Map.entry("merchantZip", "12346"), Map.entry("origDate", "2022-07-07"));
        for (String field : List.of("typeCd", "catCd", "source", "description", "amount", "merchantId",
                "merchantName", "merchantCity", "merchantZip", "origDate")) {
            changed.add(with(base, field, other.get(field)));
        }
        for (Map<String, String> form : changed) {
            add(form, used).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("TRAN_IDEMPOTENCY_KEY_REUSED"));
        }

        TestClock.shared().advance(Duration.ofDays(1));
        add(base, used).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.tranId").value(tranId))
                .andExpect(jsonPath("$.hint.message").value(HINT));

        assertThat(dailyRows()).isEqualTo(1);
        List<Map<String, Object>> audit = addAuditRows();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).containsEntry("service", "transaction-service")
                .containsEntry("actor_user_id", USER).containsEntry("action", "CREATE")
                .containsEntry("subject_type", "DAILY_TRAN").containsEntry("subject_key", tranId)
                .containsEntry("field_names",
                        "amount,cardNum,catCd,description,merchantCity,merchantId,merchantName,merchantZip,origDate,source,typeCd");
    }

    // ---- S36: no clear card number anywhere -----------------------------------------------------------

    @Test
    @DisplayName("TRNA-S36: no response and no log line holds a clear card number")
    @ExtendWith(OutputCaptureExtension.class)
    @SpecItem("SE-TRNA-002")
    void s36_noClearCardNumber(CapturedOutput output) throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        bodies.add(body(validate(valid())));
        bodies.add(body(validate(valid(null, CARD))));
        bodies.add(body(validate(valid(TWO_CARDS_ACCT, ""))));
        bodies.add(body(validate(valid(TWO_CARDS_ACCT, CARD))));
        bodies.add(body(validate(with(valid(null, CARD), "typeCd", "A1"))));
        deny("SELECT ON OBJECT::core.card_xref", () -> {
            bodies.add(body(validate(valid()).andExpect(status().isInternalServerError())));
            bodies.add(body(validate(valid(null, CARD)).andExpect(status().isInternalServerError())));
        });
        for (JsonNode body : bodies) {
            for (String card : List.of(CARD, FIRST_CARD, SECOND_CARD)) {
                assertThat(textValues(body)).noneSatisfy(v -> assertThat(v).contains(card));
            }
        }
        assertThat(output.getAll()).doesNotContain(CARD).doesNotContain(FIRST_CARD).doesNotContain(SECOND_CARD);
    }
}
