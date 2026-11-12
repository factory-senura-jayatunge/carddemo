package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestClock;
import org.carddemo.platform.testsupport.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The TRNA start data: three accounts, one with one card, one reached by its card, one with two
 * cards; the test clock on 2026-11-07; the sequence at its first fixed-mode value. Every test ends
 * by checking that no master, account, card, balance or reject row changed: an add writes only
 * {@code core.daily_tran} and {@code ops.audit}.
 */
abstract class TransactionAddTestBase extends TransactionTestBase {

    static final String ACCT = "00000000050";
    static final String CARD = "4000000000005740";
    static final String MASKED = "************5740";
    static final String CARD_ACCT = "00000000027";
    static final String OTHER_CARD = "4000000000001516";
    static final String TWO_CARDS_ACCT = "00000000060";
    static final String FIRST_CARD = "4000000000000001";
    static final String SECOND_CARD = "4000000000000002";
    static final String CUST = "000000001";
    static final Instant TODAY = Instant.parse("2026-11-07T10:00:00Z");
    static final long FIXED_FIRST_ID = 9_000_000_000_000_000L;
    static final String ADDED = "Transaction added successfully.  Your Tran ID is %s.";
    static final String HINT = "The transaction will be posted in the next batch cycle.";
    static final String ADD_FAILED = "Unable to Add Transaction...";

    static final Path REF_DATA = Path.of("../../../app/data/ASCII");

    private List<String> untouched;

    @BeforeEach
    void addStartData() {
        TestClock.shared().set(TODAY);
        DB.fixtures().deleteAll()
                .account(CoreFixtures.Account.of(ACCT, "0.00"))
                .account(CoreFixtures.Account.of(CARD_ACCT, "0.00"))
                .account(CoreFixtures.Account.of(TWO_CARDS_ACCT, "0.00"))
                .customer(CUST)
                .card(CARD, ACCT, CUST)
                .card(OTHER_CARD, CARD_ACCT, CUST)
                .card(FIRST_CARD, TWO_CARDS_ACCT, CUST)
                .card(SECOND_CARD, TWO_CARDS_ACCT, CUST)
                .restartTranIdSequence(FIXED_FIRST_ID);
        referenceData();
        untouched = untouchedRows();
    }

    /**
     * The 7 types and 18 categories of the sample files, inserted where missing: deleteAll keeps the
     * ref tables, and other suites of the shared database may have left them empty or loaded.
     */
    void referenceData() {
        try {
            for (String line : Files.readAllLines(REF_DATA.resolve("trantype.txt"), StandardCharsets.ISO_8859_1)) {
                admin.sql("""
                                IF NOT EXISTS (SELECT 1 FROM ref.tran_type WHERE type_cd = :type)
                                    INSERT INTO ref.tran_type (type_cd, description) VALUES (:type, :description)""")
                        .param("type", line.substring(0, 2)).param("description", line.substring(2, 52).trim())
                        .update();
            }
            for (String line : Files.readAllLines(REF_DATA.resolve("trancatg.txt"), StandardCharsets.ISO_8859_1)) {
                admin.sql("""
                                IF NOT EXISTS (SELECT 1 FROM ref.tran_category WHERE type_cd = :type AND cat_cd = :cat)
                                    INSERT INTO ref.tran_category (type_cd, cat_cd, description)
                                    VALUES (:type, :cat, :description)""")
                        .param("type", line.substring(0, 2)).param("cat", line.substring(2, 6))
                        .param("description", line.substring(6, 56).trim())
                        .update();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterEach
    void nothingElseWritten() {
        TestClock.shared().reset();
        DB.fixtures().closeBatchWindows();
        assertThat(untouchedRows()).as("rows an add never writes").isEqualTo(untouched);
    }

    /** Called again by a test that adds start data of its own after the common start data. */
    void startDataDone() {
        untouched = untouchedRows();
    }

    List<String> untouchedRows() {
        List<String> rows = new ArrayList<>();
        for (String table : List.of("core.[transaction]", "core.account", "core.card", "core.card_xref",
                "core.tcat_bal", "core.daily_reject")) {
            rows.add(table + " " + admin.sql("SELECT CONCAT(COUNT(*), ' ', CHECKSUM_AGG(BINARY_CHECKSUM(*))) FROM "
                    + table).query(String.class).single());
        }
        return rows;
    }

    /** The contract's valid data for the keys given; confirm {@code Y}. */
    static Map<String, String> valid(String acctId, String cardNum) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("acctId", acctId);
        form.put("cardNum", cardNum);
        form.put("typeCd", "01");
        form.put("catCd", "0001");
        form.put("source", "POS TERM");
        form.put("description", "Test purchase");
        form.put("amount", "-00000012.50");
        form.put("origDate", "2022-07-06");
        form.put("merchantId", "123456789");
        form.put("merchantName", "Shop");
        form.put("merchantCity", "Town");
        form.put("merchantZip", "12345");
        form.put("confirm", "Y");
        return form;
    }

    static Map<String, String> valid() {
        return valid(ACCT, null);
    }

    static Map<String, String> with(Map<String, String> form, String member, String value) {
        Map<String, String> copy = new LinkedHashMap<>(form);
        copy.put(member, value);
        return copy;
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    ResultActions validate(Map<String, String> form) throws Exception {
        return validate(form, TestTokens.user(USER));
    }

    ResultActions validate(Map<String, String> form, String token) throws Exception {
        return mvc.perform(post("/api/transactions/validate").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(form)));
    }

    MockHttpServletRequestBuilder addRequest(Map<String, String> form, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(form));
        return idempotencyKey == null ? request : request.header("Idempotency-Key", idempotencyKey);
    }

    ResultActions add(Map<String, String> form, String idempotencyKey) throws Exception {
        return add(form, idempotencyKey, TestTokens.user(USER));
    }

    ResultActions add(Map<String, String> form, String idempotencyKey, String token) throws Exception {
        return mvc.perform(addRequest(form, idempotencyKey).header("Authorization", bearer(token)));
    }

    ResultActions latest() throws Exception {
        return latest(TestTokens.user(USER));
    }

    ResultActions latest(String token) throws Exception {
        return mvc.perform(get("/api/transactions/latest").header("Authorization", bearer(token)));
    }

    int dailyRows() {
        return admin.sql("SELECT COUNT(*) FROM core.daily_tran").query(Integer.class).single();
    }

    List<Map<String, Object>> addAuditRows() {
        return admin.sql("""
                SELECT service, actor_user_id, action, subject_type, subject_key, field_names
                FROM ops.audit ORDER BY audit_id""").query().listOfRows();
    }
}
