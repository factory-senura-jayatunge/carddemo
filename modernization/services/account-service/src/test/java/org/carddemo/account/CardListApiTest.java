package org.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestTokens;
import org.carddemo.platform.web.Cursors;
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
 * Section 7's CRDL scenarios against the API. Start data: the 50 cards of
 * {@code app/data/ASCII/carddata.txt}, one per account, signed on as USER0001 unless the test says
 * otherwise. Every test ends by checking that no {@code core.*} row changed.
 */
class CardListApiTest extends AccountTestBase {

    static final Path SAMPLE = Path.of("../../../app/data/ASCII/carddata.txt");
    static final String USER = "USER0001";
    static final String ADMIN = "ADMIN001";
    static final String CARD_NUM_COLUMN = "SELECT ON OBJECT::core.card (card_num)";
    static final List<String> PAGE_MEMBERS = List.of("firstKey", "hasNext", "hasPrevious", "items", "lastKey",
            "message", "severity");
    static final List<String> ROW_MEMBERS = List.of("acctId", "activeStatus", "cardId", "cardNum");

    record SampleCard(String cardNum, String acctId, String status) {
    }

    /** The CVACT02Y fields the list uses: card number 1-16, account 17-27, status 91. */
    static final List<SampleCard> SAMPLE_CARDS = sampleCards();

    private final JdbcClient admin = DB.admin();

    @Autowired
    DataSource serviceDataSource;
    private final ObjectMapper json = new ObjectMapper();
    private List<String> before;

    static List<SampleCard> sampleCards() {
        try {
            return Files.readAllLines(SAMPLE, StandardCharsets.US_ASCII).stream().filter(l -> !l.isEmpty())
                    .map(l -> new SampleCard(l.substring(0, 16), l.substring(16, 27), l.substring(90, 91)))
                    .sorted(Comparator.comparing(SampleCard::cardNum)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void sampleData() {
        assertThat(SAMPLE_CARDS).hasSize(50).allSatisfy(c -> assertThat(c.status()).isEqualTo("Y"));
        CoreFixtures fixtures = DB.fixtures().deleteAll();
        for (SampleCard c : SAMPLE_CARDS) {
            String cust = c.acctId().substring(2);
            fixtures.account(CoreFixtures.Account.of(c.acctId(), "0.00")).customer(cust).card(c.cardNum(), c.acctId(), cust);
        }
        before = coreRows();
    }

    @AfterEach
    void nothingChanged() {
        assertThat(coreRows()).as("core.* rows").isEqualTo(before);
    }

    private List<String> coreRows() {
        List<String> rows = new ArrayList<>();
        for (String table : List.of("core.account", "core.customer", "core.card")) {
            rows.addAll(DB.fixtures().rowVersions(table));
        }
        rows.addAll(admin.sql("SELECT card_num + ' ' + cust_id + ' ' + acct_id FROM core.card_xref ORDER BY card_num")
                .query(String.class).list());
        return rows;
    }

    /** A request body from name-value pairs; a null value is sent as JSON null. */
    private String request(Object... pairs) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return json.writeValueAsString(body);
    }

    private ResultActions search(String body) throws Exception {
        return search(body, TestTokens.user(USER));
    }

    private ResultActions search(String body, String token) throws Exception {
        return mvc.perform(post("/api/cards/search").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode page(ResultActions result) throws Exception {
        result.andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode page(String body) throws Exception {
        return page(search(body));
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

    private String cardId(String cardNum) {
        return admin.sql("SELECT CAST(card_id AS VARCHAR(20)) FROM core.card WHERE card_num = :c").param("c", cardNum)
                .query(String.class).single();
    }

    private String token(String cardNum) {
        return Cursors.encode(cardId(cardNum));
    }

    private static String masked(String cardNum) {
        return "************" + cardNum.substring(12);
    }

    /** The rows a page shows, as "account card status". */
    private static List<String> rows(JsonNode page) {
        List<String> rows = new ArrayList<>();
        page.path("items").forEach(r -> rows.add(r.path("acctId").asText() + " " + r.path("cardNum").asText() + " "
                + r.path("activeStatus").asText()));
        return rows;
    }

    /** The sample cards {@code from} to {@code to} (1-based, inclusive) as {@link #rows} shows them. */
    private static List<String> sample(int from, int to) {
        return SAMPLE_CARDS.subList(from - 1, to).stream()
                .map(c -> c.acctId() + " " + masked(c.cardNum()) + " " + c.status()).toList();
    }

    private void expectPage(JsonNode page, List<String> rows, boolean hasNext, boolean hasPrevious, String message) {
        assertThat(rows(page)).isEqualTo(rows);
        assertThat(page.path("hasNext").asBoolean()).as("hasNext").isEqualTo(hasNext);
        assertThat(page.path("hasPrevious").asBoolean()).as("hasPrevious").isEqualTo(hasPrevious);
        if (message == null) {
            assertThat(page.path("message").isNull()).as("message %s", page.path("message")).isTrue();
            assertThat(page.path("severity").isNull()).isTrue();
        } else {
            assertThat(page.path("message").asText()).isEqualTo(message);
            assertThat(page.path("severity").asText()).isEqualTo("info");
        }
        if (rows.isEmpty()) {
            assertThat(page.path("firstKey").isNull()).isTrue();
            assertThat(page.path("lastKey").isNull()).isTrue();
        } else {
            JsonNode items = page.path("items");
            assertThat(page.path("firstKey").asText()).isEqualTo(Cursors.encode(items.get(0).path("cardId").asText()));
            assertThat(page.path("lastKey").asText())
                    .isEqualTo(Cursors.encode(items.get(items.size() - 1).path("cardId").asText()));
        }
    }

    private static TreeSet<String> members(JsonNode node) {
        TreeSet<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ---- S1 to S5, S39: paging through the sample cards ------------------------------------------

    @Test
    @DisplayName("CRDL-S1: page 1 is the first 7 cards by card number, masked, no notice")
    @SpecItem({"BR-CRDL-001", "BR-CRDL-002", "SE-CRDL-001", "NUM-CRDL-003", "DEF-CRDL-001", "DEF-CRDL-002"})
    void s1_firstPage() throws Exception {
        JsonNode page = page("{}");
        expectPage(page, sample(1, 7), true, false, null);
        assertThat(rows(page).getFirst()).isEqualTo("00000000050 ************5740 Y");
        assertThat(rows(page).getLast()).isEqualTo("00000000037 ************8931 Y");
        assertThat(members(page)).containsExactlyElementsOf(PAGE_MEMBERS);
        page.path("items").forEach(r -> assertThat(members(r)).containsExactlyElementsOf(ROW_MEMBERS));
        assertThat(page.path("items").get(0).path("cardId").asText()).isEqualTo(cardId("0500024453765740"));
        for (String body : new String[] {"", "null", request("acctId", null, "cardNum", null, "start", null,
            "after", null, "before", null, "size", null), request("size", 7)}) {
            expectPage(page(body), sample(1, 7), true, false, null);
        }
    }

    @Test
    @DisplayName("CRDL-S2, CRDL-S3: Next reads after the last key; Previous from page 2 is page 1 with no notice")
    @SpecItem({"BR-CRDL-005", "BR-CRDL-006", "SE-CRDL-001", "SE-CRDL-002"})
    void s2_s3_nextAndPrevious() throws Exception {
        JsonNode first = page("{}");
        JsonNode second = page(request("after", first.path("lastKey").asText()));
        expectPage(second, sample(8, 14), true, true, null);
        assertThat(rows(second).getFirst()).contains("************1600");
        JsonNode back = page(request("before", second.path("firstKey").asText()));
        expectPage(back, sample(1, 7), true, false, null);
        assertThat(back.path("firstKey")).isEqualTo(first.path("firstKey"));
    }

    @Test
    @DisplayName("CRDL-S4: on page 1 the client refreshes from the first key: the same rows, hasPrevious false")
    @SpecItem("BR-CRDL-006")
    void s4_refreshPageOne() throws Exception {
        JsonNode first = page("{}");
        expectPage(page(request("start", first.path("firstKey").asText())), sample(1, 7), true, false, null);
    }

    @Test
    @DisplayName("CRDL-S5: page 8 holds the last card and the end notice")
    @SpecItem({"BR-CRDL-005", "ERR-CRDL-001"})
    void s5_lastPage() throws Exception {
        JsonNode page = page("{}");
        for (int n = 2; n <= 7; n++) {
            page = page(request("after", page.path("lastKey").asText()));
            expectPage(page, sample(7 * n - 6, 7 * n), true, true, null);
        }
        page = page(request("after", page.path("lastKey").asText()));
        expectPage(page, List.of("00000000040 ************6588 Y"), false, true, "NO MORE RECORDS TO SHOW");
        expectPage(page(request("start", page.path("firstKey").asText())), sample(50, 50), false, true,
                "NO MORE RECORDS TO SHOW");
    }

    @Test
    @DisplayName("CRDL-S39: Enter on page 2 refreshes from its first key, page 2 again")
    @SpecItem("FLOW-CRDL-011")
    void s39_refresh() throws Exception {
        JsonNode second = page(request("after", page("{}").path("lastKey").asText()));
        expectPage(page(request("start", second.path("firstKey").asText())), sample(8, 14), true, true, null);
    }

    // ---- S7, S8, S12, S22, S32, S33: filters -------------------------------------------------------

    @Test
    @DisplayName("CRDL-S7, CRDL-S22: an account filter finds its one card from the start of the list")
    @SpecItem({"BR-CRDL-003", "BR-CRDL-004", "NUM-CRDL-004", "DEF-CRDL-006"})
    void s7_s22_accountFilter() throws Exception {
        expectPage(page(request("acctId", "00000000001")), List.of("00000000001 ************3697 Y"), false, false,
                "NO MORE RECORDS TO SHOW");
        JsonNode third = page(request("after", page(request("after", page("{}").path("lastKey").asText()))
                .path("lastKey").asText()));
        expectPage(third, sample(15, 21), true, true, null);
        expectPage(page(request("acctId", "00000000002")), List.of("00000000002 ************7330 Y"), false, false,
                "NO MORE RECORDS TO SHOW");
    }

    @Test
    @DisplayName("CRDL-S8: no matching card is the no-records notice")
    @SpecItem("ERR-CRDL-001")
    void s8_noMatch() throws Exception {
        expectPage(page(request("acctId", "99999999999")), List.of(), false, false,
                "NO RECORDS FOUND FOR THIS SEARCH CONDITION.");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("CRDL-S12: a card filter finds that card; no log line carries the clear number")
    @SpecItem({"BR-CRDL-003", "NUM-CRDL-004"})
    void s12_cardFilter(CapturedOutput output) throws Exception {
        expectPage(page(request("cardNum", "0683586198171516")), List.of("00000000027 ************1516 Y"), false,
                false, "NO MORE RECORDS TO SHOW");
        assertThat(output.getAll()).doesNotContain("0683586198171516");
    }

    @Test
    @DisplayName("CRDL-S32: spaces and zeros are a blank filter; anything else not 11 or 16 digits is invalid")
    @SpecItem({"VAL-CRDL-001", "VAL-CRDL-002", "NUM-CRDL-004"})
    void s32_blankAndInvalidFilters() throws Exception {
        for (String acct : new String[] {null, "", "           ", "00000000000", "0          ", "0", "000   "}) {
            for (String card : new String[] {null, "0000000000000000", "                ", "0"}) {
                expectPage(page(request("acctId", acct, "cardNum", card)), sample(1, 7), true, false, null);
            }
        }
        deny(CARD_NUM_COLUMN, () -> {
            for (String acct : new String[] {"*", "0000000005A", "00000000050 ", " 0000000005", "12345",
                "000000000500", "-0000000050"}) {
                expectProblem(search(request("acctId", acct)), 400, "INVALID_ACCT_FILTER",
                        "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER", "acctId");
            }
            for (String card : new String[] {"*", "ABC", "068358619817151", "0683586198171516 ", "06835861981715160"}) {
                expectProblem(search(request("cardNum", card)), 400, "INVALID_CARD_FILTER",
                        "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER", "cardNum");
            }
            for (Object notText : new Object[] {1L, 12345678901L, 0, true, List.of("00000000050"),
                Map.of("acctId", "00000000050")}) {
                expectProblem(search(request("acctId", notText, "cardNum", "ABC")), 400, "INVALID_ACCT_FILTER",
                        "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER", "acctId");
                expectProblem(search(request("acctId", "00000000050", "cardNum", notText)), 400, "INVALID_CARD_FILTER",
                        "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER", "cardNum");
                expectProblem(search(request("acctId", "12345", "cardNum", notText)), 400, "INVALID_ACCT_FILTER",
                        "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER", "acctId");
            }
        });
    }

    @Test
    @DisplayName("CRDL-S9 to CRDL-S11: an invalid filter is refused before any read; the account message wins")
    @SpecItem({"VAL-CRDL-001", "VAL-CRDL-002", "VAL-CRDL-007"})
    void s9_s11_invalidFilters() throws Exception {
        deny(CARD_NUM_COLUMN, () -> {
            expectProblem(search(request("acctId", "12345")), 400, "INVALID_ACCT_FILTER",
                    "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER", "acctId");
            expectProblem(search(request("cardNum", "ABC")), 400, "INVALID_CARD_FILTER",
                    "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER", "cardNum");
            expectProblem(search(request("acctId", "12345", "cardNum", "ABC")), 400, "INVALID_ACCT_FILTER",
                    "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER", "acctId");
        });
    }

    @Test
    @DisplayName("CRDL-S33: both filters must match the same card")
    @SpecItem("BR-CRDL-003")
    void s33_bothFilters() throws Exception {
        expectPage(page(request("acctId", "00000000027", "cardNum", "0683586198171516")),
                List.of("00000000027 ************1516 Y"), false, false, "NO MORE RECORDS TO SHOW");
        expectPage(page(request("acctId", "00000000050", "cardNum", "0683586198171516")), List.of(), false, false,
                "NO RECORDS FOUND FOR THIS SEARCH CONDITION.");
    }

    // ---- S28, S29, S41: the filter in the read-ahead, the start of the list, data changes --------

    /** Account 00000000100 with {@code n} cards 4000...0001 onwards, between cards of other accounts. */
    private void accountWithCards(int n) {
        CoreFixtures fixtures = DB.fixtures().deleteAll()
                .account(CoreFixtures.Account.of("00000000100", "0.00")).customer("000000100")
                .account(CoreFixtures.Account.of("00000000101", "0.00")).customer("000000101")
                .card("3000000000000001", "00000000101", "000000101")
                .card("5000000000000001", "00000000101", "000000101");
        for (int i = 1; i <= n; i++) {
            fixtures.card(String.format("40000000000000%02d", i), "00000000100", "000000100");
        }
        before = coreRows();
    }

    private static List<String> ofAccount(int from, int to) {
        List<String> rows = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            rows.add(String.format("00000000100 ************00%02d Y", i));
        }
        return rows;
    }

    @Test
    @DisplayName("CRDL-S28: exactly 7 matching cards fill page 1 with the end notice; there is no empty page 2")
    @SpecItem("DEF-CRDL-008")
    void s28_readAheadAppliesTheFilter() throws Exception {
        accountWithCards(7);
        JsonNode page = page(request("acctId", "00000000100"));
        expectPage(page, ofAccount(1, 7), false, false, "NO MORE RECORDS TO SHOW");
        expectPage(page(request("acctId", "00000000100", "start", page.path("firstKey").asText())), ofAccount(1, 7),
                false, false, "NO MORE RECORDS TO SHOW");
    }

    @Test
    @DisplayName("CRDL-S29: Previous with fewer than 7 matching cards before the key is the first page")
    @SpecItem({"BR-CRDL-006", "SE-CRDL-002", "DEF-CRDL-009"})
    void s29_previousToTheStart() throws Exception {
        accountWithCards(9);
        String acct = "00000000100";
        JsonNode first = page(request("acctId", acct));
        expectPage(first, ofAccount(1, 7), true, false, null);
        JsonNode second = page(request("acctId", acct, "after", first.path("lastKey").asText()));
        expectPage(second, ofAccount(8, 9), false, true, "NO MORE RECORDS TO SHOW");
        expectPage(page(request("acctId", acct, "before", second.path("firstKey").asText())), ofAccount(1, 7), true,
                false, null);

        JsonNode fromThird = page(request("acctId", acct, "start", token("4000000000000003")));
        expectPage(fromThird, ofAccount(3, 9), false, true, "NO MORE RECORDS TO SHOW");
        expectPage(page(request("acctId", acct, "before", fromThird.path("firstKey").asText())), ofAccount(1, 7), true,
                false, null);
    }

    @Test
    @DisplayName("CRDL: with more than 7 matching cards before the key, Previous has a previous page")
    @SpecItem({"BR-CRDL-006", "SE-CRDL-002"})
    void previousWithMoreBefore() throws Exception {
        accountWithCards(9);
        expectPage(page(request("acctId", "00000000100", "before", token("4000000000000009"))), ofAccount(2, 8), true,
                true, null);
    }

    @Test
    @DisplayName("CRDL: a key card outside the filter still pages exactly; only matching cards count")
    @SpecItem({"BR-CRDL-003", "DEF-CRDL-008"})
    void keyCardOutsideTheFilter() throws Exception {
        accountWithCards(9);
        String acct = "00000000100";
        String below = token("3000000000000001");
        String above = token("5000000000000001");
        expectPage(page(request("acctId", acct, "start", below)), ofAccount(1, 7), true, false, null);
        expectPage(page(request("acctId", acct, "after", below)), ofAccount(1, 7), true, false, null);
        expectPage(page(request("acctId", acct, "before", below)), List.of(), true, false,
                "NO PREVIOUS PAGES TO DISPLAY");
        expectPage(page(request("acctId", acct, "start", above)), List.of(), false, true,
                "NO RECORDS FOUND FOR THIS SEARCH CONDITION.");
        expectPage(page(request("acctId", acct, "after", above)), List.of(), false, true, "NO MORE RECORDS TO SHOW");
        expectPage(page(request("acctId", acct, "before", above)), ofAccount(3, 9), false, true,
                "NO MORE RECORDS TO SHOW");
    }

    @Test
    @DisplayName("CRDL-S41: a later page emptied by deletes answers with its notice")
    @SpecItem({"ERR-CRDL-001"})
    void s41_emptiedPages() throws Exception {
        JsonNode first = page("{}");
        String lastOfFirst = SAMPLE_CARDS.get(6).cardNum();
        admin.sql("DELETE FROM core.card_xref WHERE card_num > :k").param("k", lastOfFirst).update();
        admin.sql("DELETE FROM core.card WHERE card_num > :k").param("k", lastOfFirst).update();
        before = coreRows();
        expectPage(page(request("after", first.path("lastKey").asText())), List.of(), false, true,
                "NO MORE RECORDS TO SHOW");

        sampleData();
        String eighth = SAMPLE_CARDS.get(7).cardNum();
        String secondFirstKey = page(request("after", page("{}").path("lastKey").asText())).path("firstKey").asText();
        admin.sql("DELETE FROM core.card_xref WHERE card_num < :k").param("k", eighth).update();
        admin.sql("DELETE FROM core.card WHERE card_num < :k").param("k", eighth).update();
        before = coreRows();
        expectPage(page(request("before", secondFirstKey)), List.of(), true, false, "NO PREVIOUS PAGES TO DISPLAY");
    }

    // ---- S34: cursor and size, before any read ------------------------------------------------------

    @Test
    @DisplayName("CRDL-S34: two cursors or a bad token is an invalid cursor, a bad size a size problem, before any read")
    @SpecItem("ERR-CRDL-001")
    void s34_cursorAndSize() throws Exception {
        String key = token(SAMPLE_CARDS.get(0).cardNum());
        deny(CARD_NUM_COLUMN, () -> {
            for (String body : new String[] {request("start", key, "after", key), request("after", key, "before", key),
                request("start", key, "before", key, "size", 0), request("after", "!!"), request("start", "MQ=="),
                request("before", Cursors.encode("0")), request("after", Cursors.encode("-1")),
                request("after", Cursors.encode("01")), request("after", Cursors.encode("abc")),
                request("after", Cursors.encode("9223372036854775808")), request("after", key + "x", "size", "a")}) {
                expectProblem(search(body), 400, "INVALID_CURSOR",
                        "Paging position is no longer valid. Start again from page 1", "acctId");
            }
            for (Object size : new Object[] {0, 8, -1, "a", "7", 7.5, 1.0, 7.0, 1e20, true, 2147483648L,
                new java.math.BigInteger("100000000000000000000"), new java.math.BigDecimal("7.00"), List.of(7),
                Map.of("n", 7)}) {
                expectProblem(search(request("size", size, "acctId", "x")), 400, "INVALID_PAGE_SIZE",
                        "Page size must be between 1 and 7", "acctId");
            }
        });
        expectPage(page(request("size", 1)), sample(1, 1), true, false, null);
        expectPage(page(request("size", 3, "after", key)), sample(2, 4), true, true, null);
    }

    @Test
    @DisplayName("CRDL-S34: the token of a card that no longer exists is an invalid cursor")
    @SpecItem("ERR-CRDL-001")
    void s34_cursorOfADeletedCard() throws Exception {
        String gone = SAMPLE_CARDS.get(3).cardNum();
        String key = token(gone);
        admin.sql("DELETE FROM core.card_xref WHERE card_num = :k").param("k", gone).update();
        admin.sql("DELETE FROM core.card WHERE card_num = :k").param("k", gone).update();
        before = coreRows();
        for (String cursor : List.of("start", "after", "before")) {
            expectProblem(search(request(cursor, key)), 400, "INVALID_CURSOR",
                    "Paging position is no longer valid. Start again from page 1", "acctId");
        }
    }

    // ---- S20, S25, S26, S36: session, roles, CSRF, batch window -------------------------------------

    @Test
    @DisplayName("CRDL-S20: listCards needs a session")
    @SpecItem({"FLOW-CRDL-001", "DEF-CRDL-003"})
    void s20_noSession() throws Exception {
        mvc.perform(post("/api/cards/search").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        search("{}", "not-a-token").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/cards/search").cookie(new Cookie("cd_at", "not-a-token"), new Cookie("XSRF-TOKEN", "t"))
                        .header("X-XSRF-TOKEN", "t").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("CRDL-S25: an ADMIN gets the same page, masked")
    @SpecItem("BR-CRDL-002")
    void s25_admin() throws Exception {
        JsonNode page = page(search("{}", TestTokens.admin(ADMIN)));
        expectPage(page, sample(1, 7), true, false, null);
        SAMPLE_CARDS.forEach(c -> assertThat(page.toString()).doesNotContain(c.cardNum()));
    }

    @Test
    @DisplayName("CRDL-S26: a cookie session must send the CSRF token; without it nothing is read")
    @SpecItem("FLOW-CRDL-001")
    void s26_csrf() throws Exception {
        Cookie session = new Cookie("cd_at", TestTokens.user(USER));
        deny(CARD_NUM_COLUMN, () -> mvc.perform(post("/api/cards/search").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED")));
        expectPage(page(mvc.perform(post("/api/cards/search").cookie(session, new Cookie("XSRF-TOKEN", "t0k3n"))
                .header("X-XSRF-TOKEN", "t0k3n").contentType(MediaType.APPLICATION_JSON).content("{}"))),
                sample(1, 7), true, false, null);
    }

    @Test
    @DisplayName("CRDL-S36: an open batch window does not stop the list")
    @SpecItem("ERR-CRDL-002")
    void s36_batchWindow() throws Exception {
        DB.fixtures().openBatchWindow();
        expectPage(page("{}"), sample(1, 7), true, false, null);
    }

    // ---- S35, S37: read failure and what a page never carries ---------------------------------------

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("CRDL-S35: a read failure is the card-list problem; the log has the cause and the masked filter")
    @SpecItem({"ERR-CRDL-001", "NUM-CRDL-006"})
    void s35_readFails(CapturedOutput output) throws Exception {
        String key = token(SAMPLE_CARDS.get(0).cardNum());
        List<String> correlationIds = new ArrayList<>();
        deny(CARD_NUM_COLUMN, () -> {
            expectDriverNamesTheDenial("SELECT card_num FROM core.card");
            for (String body : new String[] {"{}", request("cardNum", "0683586198171516"), request("after", key),
                request("before", key), request("start", key, "acctId", "00000000050")}) {
                ResultActions call = expectProblem(search(body), 500, "CARD_LIST_FAILED", "Error reading card data",
                        "acctId");
                String text = call.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                assertThat(text).doesNotContain("CARDDAT", "RESP", "SELECT", "core.", "permission", "items",
                        "0683586198171516", "1516");
                correlationIds.add(call.andReturn().getResponse().getHeader("X-Correlation-Id"));
            }
        });
        String log = output.getAll();
        assertThat(log).contains("card list failed: card filter ************1516, correlation ID ",
                "card list failed: card filter blank")
                .containsPattern("SQLServerException SQL error 2(29|30) \\(state \\w+\\)")
                .doesNotContain("permission was denied");
        assertThat(correlationIds).hasSize(5).allSatisfy(c -> assertThat(log).contains("correlation ID " + c));
        assertThat(log).doesNotContain("0683586198171516");
        expectPage(page("{}"), sample(1, 7), true, false, null);
    }

    @Test
    @DisplayName("CRDL-S37: a page carries no CVV, name or expiry, only masked numbers, and the status as stored")
    @SpecItem({"BR-CRDL-001", "NUM-CRDL-003", "DEF-CRDL-002"})
    void s37_whatAPageCarries() throws Exception {
        admin.sql("UPDATE core.card SET active_status = 'N' WHERE card_num = '0683586198171516'").update();
        before = coreRows();
        JsonNode first = page("{}");
        assertThat(rows(first).get(1)).isEqualTo("00000000027 ************1516 N");
        List<JsonNode> pages = new ArrayList<>(List.of(first));
        JsonNode page = first;
        while (page.path("hasNext").asBoolean()) {
            page = page(request("after", page.path("lastKey").asText()));
            pages.add(page);
        }
        assertThat(pages).hasSize(8);
        for (JsonNode p : pages) {
            String text = p.toString();
            assertThat(text).doesNotContainIgnoringCase("cvv").doesNotContainIgnoringCase("embossed")
                    .doesNotContainIgnoringCase("expir").doesNotContain("TEST CUSTOMER", "2030-01-31");
            for (SampleCard c : SAMPLE_CARDS) {
                assertThat(text).doesNotContain(c.cardNum());
            }
            p.path("items").forEach(r -> assertThat(r.path("cardNum").asText()).matches("\\*{12}[0-9]{4}"));
        }
    }

    @Test
    @DisplayName("CRDL-S42: an ADMIN unmasks a listed card by its row's cardId; one audit row")
    @SpecItem("BR-CRDL-001")
    void s42_unmaskFromTheList() throws Exception {
        String id = page(search("{}", TestTokens.admin(ADMIN))).path("items").get(0).path("cardId").asText();
        mvc.perform(post("/api/cards/{cardId}/unmask", id).header("Authorization", bearer(TestTokens.admin(ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"cardNum\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.cardNum").value("0500024453765740"));
        List<Map<String, Object>> rows = admin.sql("""
                SELECT actor_user_id, action, subject_type, subject_key, field_names,
                    CASE WHEN occurred_at IS NOT NULL THEN 'set' END AS occurred
                FROM ops.audit""").query().listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("actor_user_id", ADMIN).containsEntry("action", "UNMASK")
                .containsEntry("subject_type", "CARD").containsEntry("subject_key", id)
                .containsEntry("field_names", "cardNum").containsEntry("occurred", "set");
        assertThat(rows.get(0).values().toString()).doesNotContain("0500024453765740", "5740");
    }
}
