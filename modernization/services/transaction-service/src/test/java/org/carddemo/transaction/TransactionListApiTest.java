package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.carddemo.transaction.SampleTransactions.f25;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The TRNL scenarios of the contract against the API. Start data: F25 (I1 to I25) unless the test
 * loads SAMPLE; signed on as USER0001 unless the test says otherwise. Every test ends by checking
 * that no {@code core.*} row changed and that no audit row was written.
 */
class TransactionListApiTest extends TransactionTestBase {

    static final List<String> PAGE_MEMBERS = List.of("firstKey", "hasNext", "hasPrevious", "items", "lastKey",
            "message", "severity");
    static final List<String> ROW_MEMBERS = List.of("amount", "amountDisplay", "description", "origDate", "tranId");
    static final String BOTTOM = "You have reached the bottom of the page...";
    static final String TOP = "You have reached the top of the page...";
    static final String NO_ROWS = "You are at the top of the page...";
    static final String SEARCH_INVALID = "Tran ID must be Numeric ...";
    static final String CURSOR_INVALID = "Paging position is no longer valid. Start again from page 1";
    static final String SIZE_INVALID = "Page size must be between 1 and 10";

    private List<String> before;

    @BeforeEach
    void loadTheF25Rows() {
        DB.fixtures().deleteAll();
        SampleTransactions.loadF25(admin);
        before = coreRows();
    }

    @AfterEach
    void nothingChanged() {
        assertThat(coreRows()).as("core.* rows").isEqualTo(before);
        assertThat(auditRows()).as("ops.audit rows").isZero();
    }

    /** Reloads the start data the test needs, then takes the snapshot the test ends with. */
    private void sample() {
        DB.fixtures().deleteAll();
        SampleTransactions.loadSample(admin);
        before = coreRows();
    }

    /** Query parameters from name-value pairs. */
    private ResultActions list(String... params) throws Exception {
        return listAs(TestTokens.user(USER), params);
    }

    private ResultActions listAs(String token, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/transactions").header("Authorization", bearer(token));
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private JsonNode page(ResultActions result) throws Exception {
        result.andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        JsonNode page = body(result);
        assertThat(members(page)).containsExactlyElementsOf(PAGE_MEMBERS);
        page.path("items").forEach(row -> assertThat(members(row)).containsExactlyElementsOf(ROW_MEMBERS));
        return page;
    }

    private JsonNode page(String... params) throws Exception {
        return page(list(params));
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(row -> ids.add(row.path("tranId").asText()));
        return ids;
    }

    /** I{from} to I{to}. */
    private static List<String> f25Range(int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(SampleTransactions::f25).toList();
    }

    private static void expectPage(JsonNode page, List<String> ids, boolean hasNext, boolean hasPrevious,
                                   String message) {
        assertThat(ids(page)).isEqualTo(ids);
        assertThat(page.path("hasNext").asBoolean()).as("hasNext").isEqualTo(hasNext);
        assertThat(page.path("hasPrevious").asBoolean()).as("hasPrevious").isEqualTo(hasPrevious);
        if (ids.isEmpty()) {
            assertThat(page.path("firstKey").isNull()).as("firstKey").isTrue();
            assertThat(page.path("lastKey").isNull()).as("lastKey").isTrue();
        } else {
            assertThat(page.path("firstKey").asText()).isEqualTo(ids.getFirst());
            assertThat(page.path("lastKey").asText()).isEqualTo(ids.getLast());
        }
        if (message == null) {
            assertThat(page.path("message").isNull()).as("message").isTrue();
            assertThat(page.path("severity").isNull()).as("severity").isTrue();
        } else {
            assertThat(page.path("message").asText()).isEqualTo(message);
            assertThat(page.path("severity").asText()).isEqualTo("info");
        }
    }

    // ---- S1, S19, S22: access and the batch window ------------------------------------------------

    @Test
    @DisplayName("TRNL-S1: the list needs a session")
    @SpecItem("FLOW-TRNL-001")
    void s1_noSession() throws Exception {
        mvc.perform(get("/api/transactions")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("TRNL-S19: an ADMIN reads the same list (admin option 7)")
    @SpecItem({"BR-TRNL-006", "DEF-TRNL-007"})
    void s19_adminReadsTheList() throws Exception {
        expectPage(page(listAs(TestTokens.admin(ADMIN))), f25Range(1, 10), true, false, null);
    }

    @Test
    @DisplayName("TRNL-S22: an open batch window does not stop the list")
    @SpecItem("SE-TRNL-001")
    void s22_batchWindow() throws Exception {
        DB.fixtures().openBatchWindow();
        expectPage(page(), f25Range(1, 10), true, false, null);
    }

    // ---- S2 to S9, S24, S26, S27: paging -----------------------------------------------------------

    @Test
    @DisplayName("TRNL-S2, TRNL-S14: the first page is I1 to I10, read one more for hasNext")
    @SpecItem({"BR-TRNL-001", "BR-TRNL-005", "BR-TRNL-006", "DEF-TRNL-007", "SE-TRNL-001", "FLOW-TRNL-002"})
    void s2_firstPage() throws Exception {
        expectPage(page(), f25Range(1, 10), true, false, null);
        expectPage(page("start", ""), f25Range(1, 10), true, false, null);
        expectPage(page("start", "   "), f25Range(1, 10), true, false, null);
    }

    @Test
    @DisplayName("TRNL-S3, TRNL-S4: Next reads after the last key; the last page has the bottom notice")
    @SpecItem({"BR-TRNL-005", "SE-TRNL-001", "VAL-TRNL-005", "ERR-TRNL-001"})
    void s3_s4_next() throws Exception {
        JsonNode page2 = page("after", f25(10));
        expectPage(page2, f25Range(11, 20), true, true, null);
        JsonNode page3 = page("after", page2.path("lastKey").asText());
        expectPage(page3, f25Range(21, 25), false, true, BOTTOM);
    }

    @Test
    @DisplayName("TRNL-S6, TRNL-S7: Previous reads before the first key; reaching I1 gives the top notice")
    @SpecItem({"SE-TRNL-002", "VAL-TRNL-005"})
    void s6_s7_previous() throws Exception {
        expectPage(page("before", f25(21)), f25Range(11, 20), true, true, null);
        expectPage(page("before", f25(11)), f25Range(1, 10), true, false, TOP);
    }

    @Test
    @DisplayName("TRNL-S9: a 16-digit search starts at the first ID equal or greater")
    @SpecItem({"BR-TRNL-001", "NUM-TRNL-004"})
    void s9_search() throws Exception {
        expectPage(page("start", f25(13)), f25Range(13, 22), true, true, null);
        expectPage(page("start", "0000000000001301"), f25Range(14, 23), true, true, null);
    }

    @Test
    @DisplayName("TRNL-S24: Enter with a blank search refreshes the page from its first key")
    @SpecItem("DEF-TRNL-005")
    void s24_refresh() throws Exception {
        JsonNode page2 = page("after", f25(10));
        expectPage(page("start", page2.path("firstKey").asText()), f25Range(11, 20), true, true, null);
    }

    @Test
    @DisplayName("TRNL-S26: Previous after a search shows the ten rows before it")
    @SpecItem("DEF-TRNL-008")
    void s26_previousAfterSearch() throws Exception {
        JsonNode searched = page("start", f25(13));
        expectPage(page("before", searched.path("firstKey").asText()), f25Range(3, 12), true, true, null);
    }

    @Test
    @DisplayName("TRNL-S27: fewer than a page before the key gives the first page, no blank rows")
    @SpecItem("DEF-TRNL-008")
    void s27_previousNearTheStart() throws Exception {
        JsonNode searched = page("start", f25(5));
        expectPage(searched, f25Range(5, 14), true, true, null);
        expectPage(page("before", searched.path("firstKey").asText()), f25Range(1, 10), true, false, TOP);
        expectPage(page("before", f25(1)), f25Range(1, 10), true, false, TOP);
    }

    @Test
    @DisplayName("TRNL: Next with no row after the key keeps the page: no rows, the bottom notice")
    @SpecItem({"VAL-TRNL-005", "ERR-TRNL-001"})
    void nextAfterTheLastRow() throws Exception {
        expectPage(page("after", f25(25)), List.of(), false, true, BOTTOM);
        expectPage(page("after", "9999999999999999"), List.of(), false, true, BOTTOM);
    }

    @Test
    @DisplayName("TRNL: a smaller page size serves the same keyset reads")
    @SpecItem("BR-TRNL-005")
    void smallerPages() throws Exception {
        expectPage(page("size", "3"), f25Range(1, 3), true, false, null);
        expectPage(page("after", f25(22), "size", "3"), f25Range(23, 25), false, true, BOTTOM);
        expectPage(page("before", f25(4), "size", "3"), f25Range(1, 3), true, false, TOP);
        expectPage(page("size", "10"), f25Range(1, 10), true, false, null);
    }

    @Test
    @DisplayName("TRNL-S30: a key that no longer exists still pages from its position")
    @SpecItem("DEF-TRNL-013")
    void s30_deletedKey() throws Exception {
        JsonNode page1 = page();
        admin.sql("DELETE FROM core.[transaction] WHERE tran_id = :id").param("id", f25(10)).update();
        before = coreRows();
        expectPage(page("after", page1.path("lastKey").asText()), f25Range(11, 20), true, true, null);
        List<String> firstPage = new ArrayList<>(f25Range(1, 9));
        firstPage.add(f25(11));
        expectPage(page("before", f25(10)), firstPage, true, false, TOP);
    }

    // ---- S29: no rows -------------------------------------------------------------------------------

    @Test
    @DisplayName("TRNL-S29: a search above the highest ID, and an empty master, give the top-of-file notice")
    @SpecItem({"DEF-TRNL-004", "ERR-TRNL-003", "VAL-TRNL-005"})
    void s29_noRows() throws Exception {
        expectPage(page("start", "9999999999999999"), List.of(), false, true, NO_ROWS);
        DB.fixtures().deleteAll();
        before = coreRows();
        expectPage(page(), List.of(), false, false, NO_ROWS);
        expectPage(page("before", f25(5)), List.of(), false, false, NO_ROWS);
        expectPage(page("after", f25(5)), List.of(), false, false, BOTTOM);
    }

    @Test
    @DisplayName("TRNL: exactly one page of rows has the bottom notice on the first page")
    @SpecItem({"BR-TRNL-005", "VAL-TRNL-005"})
    void onePageOnly() throws Exception {
        admin.sql("DELETE FROM core.[transaction] WHERE tran_id > :id").param("id", f25(10)).update();
        before = coreRows();
        expectPage(page(), f25Range(1, 10), false, false, BOTTOM);
    }

    // ---- S10, S20, S25, S31: the checks, none of which reads ---------------------------------------

    @Test
    @DisplayName("TRNL-S10, TRNL-S31: a search that is not 1 to 16 digits is refused before any read")
    @SpecItem({"VAL-TRNL-001", "DEF-TRNL-009"})
    void s10_s31_invalidSearch() throws Exception {
        deny(TRAN_ID_COLUMN, () -> {
            for (String search : new String[] {"12A", "ABC", " 683580", "68 3580", "12345678901234567", "-1", "+1",
                "1.5", "\t1"}) {
                expectProblem(list("start", search), 400, "INVALID_SEARCH_TRAN_ID", SEARCH_INVALID, "searchTranId");
            }
        });
    }

    @Test
    @DisplayName("TRNL-S20: two cursors, a malformed cursor or a bad size are refused before any read")
    @SpecItem("BR-TRNL-005")
    void s20_invalidRequest() throws Exception {
        deny(TRAN_ID_COLUMN, () -> {
            for (String[] params : List.of(new String[] {"start", "1", "after", f25(1)},
                    new String[] {"after", f25(1), "before", f25(2)},
                    new String[] {"start", "12A", "before", f25(2)},
                    new String[] {"after", "123"}, new String[] {"before", "000000000000000A"},
                    new String[] {"after", "00000000000001000"}, new String[] {"after", " 000000000000100"},
                    new String[] {"before", "   "}, new String[] {"start", "1", "after", f25(1), "size", "0"})) {
                expectProblem(list(params), 400, "INVALID_CURSOR", CURSOR_INVALID, "searchTranId");
            }
            for (String size : new String[] {"0", "11", "a", "", "1.5", "-1", "+3", " 3", "99999999999"}) {
                expectProblem(list("size", size), 400, "INVALID_PAGE_SIZE", SIZE_INVALID, "searchTranId");
            }
            expectProblem(list("start", "12A", "size", "0"), 400, "INVALID_PAGE_SIZE", SIZE_INVALID, "searchTranId");
        });
    }

    @Test
    @DisplayName("TRNL-S25: a shorter numeric search is padded; trailing spaces are the field's, a leading one is not")
    @SpecItem({"DEF-TRNL-006", "NUM-TRNL-004", "VAL-TRNL-001"})
    void s25_padding() throws Exception {
        sample();
        for (String search : new String[] {"683580", "683580   ", "0000000000683580"}) {
            JsonNode page = page("start", search);
            assertThat(ids(page).getFirst()).as(search).isEqualTo("0000000000683580");
        }
        expectProblem(list("start", " 683580 "), 400, "INVALID_SEARCH_TRAN_ID", SEARCH_INVALID, "searchTranId");
    }

    // ---- S16, S23, S28: the rows ---------------------------------------------------------------------

    @Test
    @DisplayName("TRNL-S16: the sample's first page, and every page of it, as the screen shows the rows")
    @SpecItem({"BR-TRNL-002", "NUM-TRNL-001", "NUM-TRNL-002", "NUM-TRNL-004", "SE-TRNL-001"})
    void s16_sample() throws Exception {
        sample();
        JsonNode first = page();
        JsonNode row1 = first.path("items").get(0);
        assertThat(row1.path("tranId").asText()).isEqualTo("0000000000683580");
        assertThat(row1.path("origDate").asText()).isEqualTo("06/10/22");
        assertThat(row1.path("description").asText()).isEqualTo("Purchase at Abshire-Lowe");
        assertThat(row1.path("amount").asText()).isEqualTo("504.77");
        assertThat(row1.path("amountDisplay").asText()).isEqualTo("+00000504.77");
        assertThat(first.path("items").get(9).path("tranId").asText()).isEqualTo("0000000021711604");
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        assertThat(first.toString()).doesNotContain(SampleTransactions.SAMPLE_CARD, "7065");

        List<Row> expected = SampleTransactions.sampleById();
        List<JsonNode> rows = new ArrayList<>();
        JsonNode page = first;
        while (true) {
            page.path("items").forEach(rows::add);
            if (!page.path("hasNext").asBoolean()) {
                break;
            }
            page = page("after", page.path("lastKey").asText());
        }
        assertThat(page.path("message").asText()).isEqualTo(BOTTOM);
        assertThat(rows).hasSize(expected.size()).hasSize(300);
        DateTimeFormatter date = DateTimeFormatter.ofPattern("MM/dd/yy");
        for (int i = 0; i < rows.size(); i++) {
            Row e = expected.get(i);
            JsonNode row = rows.get(i);
            String description = e.record().description();
            description = description.length() > 26 ? description.substring(0, 26) : description;
            assertThat(row.path("tranId").asText()).isEqualTo(e.record().tranId());
            assertThat(row.path("origDate").asText()).isEqualTo(date.format(e.origTs()));
            assertThat(row.path("description").asText()).isEqualTo(description.replaceAll(" +$", ""));
            assertThat(new BigDecimal(row.path("amount").asText())).isEqualByComparingTo(e.record().amount());
            assertThat(row.path("amountDisplay").asText()).isEqualTo(String.format("%+012.2f", e.record().amount()));
        }
    }

    @Test
    @DisplayName("TRNL-S28: amounts from 100,000,000.00 show nine integer digits")
    @SpecItem({"NUM-TRNL-001", "DEF-TRNL-002", "Q-TRNL-004"})
    void s28_amounts() throws Exception {
        DB.fixtures().deleteAll();
        String[][] cases = {{"123456789.12", "+123456789.12"}, {"-5.50", "-00000005.50"}, {"0", "+00000000.00"},
            {"99999999.99", "+99999999.99"}, {"-123456789.12", "-123456789.12"}, {"100000000.00", "+100000000.00"},
            {"999999999.99", "+999999999.99"}};
        for (int i = 0; i < cases.length; i++) {
            SampleTransactions.insertDefault(admin, f25(i + 1), cases[i][0]);
        }
        before = coreRows();
        JsonNode page = page();
        for (int i = 0; i < cases.length; i++) {
            JsonNode row = page.path("items").get(i);
            assertThat(row.path("amountDisplay").asText()).as(cases[i][0]).isEqualTo(cases[i][1]);
            assertThat(row.path("amount").asText()).isEqualTo(new BigDecimal(cases[i][0]).setScale(2).toPlainString());
        }
    }

    @Test
    @DisplayName("TRNL-S23: a waiting online add and a row outside the master are not listed")
    @SpecItem("BR-TRNL-001")
    void s23_onlyTheMaster() throws Exception {
        sample();
        DB.fixtures().waitingOnlineAdd("0000000000000001", SampleTransactions.SAMPLE_CARD, "9.99");
        Row archived = SampleTransactions.sample().getFirst();
        SampleTransactions.insert(admin, new Row(new TransactionRecord(
                "0000000000000002", "01", "0001", "POS TERM", "ARCHIVED", new BigDecimal("1.00"), "000000001",
                "OLD", "TOWN", "10001", SampleTransactions.SAMPLE_CARD, null, null, null), archived.origTs(),
                archived.procTs()), false);
        before = coreRows();
        JsonNode first = page();
        assertThat(ids(first).getFirst()).isEqualTo("0000000000683580");
        assertThat(first.toString()).doesNotContain("ONLINE ADD", "ARCHIVED");

        // A cycle posts the add: it becomes a master row and is listed first.
        SampleTransactions.insertDefault(admin, "0000000000000001", "9.99");
        before = coreRows();
        assertThat(ids(page()).getFirst()).isEqualTo("0000000000000001");
    }

    // ---- S21: read failure --------------------------------------------------------------------------

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("TRNL-S21: a read failure on any query is the list error; the log has the cause and correlation ID")
    @SpecItem("ERR-TRNL-001")
    void s21_readFails(CapturedOutput output) throws Exception {
        List<String> correlationIds = new ArrayList<>();
        deny(TRAN_ID_COLUMN, () -> {
            for (String[] params : List.of(new String[] {}, new String[] {"start", "13"},
                    new String[] {"after", f25(10)}, new String[] {"before", f25(21)})) {
                ResultActions call = list(params);
                JsonNode body = body(expectProblem(call, 500, "TRAN_LIST_FAILED", "Unable to lookup transaction...",
                        "searchTranId").andExpect(content().string(not(containsString("permission")))));
                assertThat(body.has("items")).isFalse();
                assertThat(body.toString()).doesNotContain("TRANSACT", "RESP", "SELECT", "core.");
                correlationIds.add(call.andReturn().getResponse().getHeader("X-Correlation-Id"));
            }
        });
        String log = output.getAll();
        assertThat(log).contains("transaction list failed: FIRST -", "transaction list failed: START 0000000000000013",
                        "transaction list failed: AFTER " + f25(10), "transaction list failed: BEFORE " + f25(21))
                .containsPattern("SQLServerException SQL error 2(29|30) \\(state \\w+\\)")
                .doesNotContain("permission was denied");
        assertThat(correlationIds).hasSize(4).allSatisfy(c -> assertThat(log).contains("correlation ID " + c));
        expectPage(page(), f25Range(1, 10), true, false, null);
    }
}
