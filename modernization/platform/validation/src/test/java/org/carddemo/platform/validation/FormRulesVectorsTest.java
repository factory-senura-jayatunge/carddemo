package org.carddemo.platform.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rule-table semantics on {@code rule-vectors.json}, whose test table follows the account update
 * edits; the TypeScript mirror runs the same file. The spec items are proved by the services' tests
 * on their own forms.
 */
class FormRulesVectorsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void everyCaseGivesTheExpectedProblems() {
        JsonNode doc = MAPPER.readTree(vectors());
        RuleTable table = RuleTable.of(doc.get("rules").toString(), RuleTable.resource(RuleTable.LOOKUPS));
        List<String> differences = new ArrayList<>();
        int n = 0;
        for (JsonNode c : doc.get("cases")) {
            n++;
            Map<String, String> input = new LinkedHashMap<>();
            c.get("input").properties().forEach(e -> input.put(e.getKey(), e.getValue().asString()));
            ValidationResult r = table.form(c.get("form").asString()).validate(input, LocalDate.parse(c.get("today").asString()));
            List<List<String>> expected = new ArrayList<>();
            c.get("problems").forEach(p -> expected.add(List.of(p.get(0).asString(), p.get(1).asString(), p.get(2).asString())));
            List<List<String>> actual = r.problems().stream().map(p -> List.of(p.field(), p.code(), p.message())).toList();
            List<String> wrong = new ArrayList<>();
            c.get("wrongFields").forEach(f -> wrong.add(f.asString()));
            if (!actual.equals(expected) || !r.wrongFields().equals(wrong)) {
                differences.add(c.get("name").asString() + ": problems " + actual + " wrong " + r.wrongFields());
            }
        }
        assertThat(n).isGreaterThan(40);
        assertThat(differences).isEmpty();
    }

    private static String vectors() {
        return RuleTable.resource("rule-vectors.json");
    }

    @Test
    void firstProblemAndCursor() {
        JsonNode doc = MAPPER.readTree(vectors());
        RuleTable table = RuleTable.of(doc.get("rules").toString(), RuleTable.resource(RuleTable.LOOKUPS));
        Map<String, String> input = new LinkedHashMap<>();
        input.put("acctStatus", "Y");
        ValidationResult r = table.form("account").validate(input, LocalDate.of(2026, 11, 6));
        assertThat(r.valid()).isFalse();
        assertThat(r.first().orElseThrow().message()).isEqualTo("Open Date : Year must be supplied.");
        assertThat(r.cursorField()).contains("openYear");
        assertThat(table.form("account").fields()).startsWith("acctStatus", "openYear", "openMonth", "openDay");
    }

    @Test
    void amountValues() {
        assertThat(LegacyAmount.parse("$1,000.50", 10, 2)).contains(new BigDecimal("1000.50"));
        assertThat(LegacyAmount.parse("1000.50CR", 10, 2)).contains(new BigDecimal("-1000.50"));
        assertThat(LegacyAmount.parse("$-1,000.50", 10, 2)).contains(new BigDecimal("-1000.50"));
        assertThat(LegacyAmount.parse(".5", 10, 2)).contains(new BigDecimal("0.5"));
        assertThat(LegacyAmount.parse("12345678901", 10, 2)).isEmpty();
        assertThat(LegacyAmount.parse(null, 10, 2)).isEmpty();
    }

    @Test
    void unknownKindsAndListsAreRejectedWhenTheTableLoads() {
        String lookups = RuleTable.resource(RuleTable.LOOKUPS);
        assertThatThrownBy(() -> RuleTable.of("{\"schema\":1,\"forms\":{\"f\":{\"fields\":[{\"name\":\"a\",\"label\":\"A\","
                + "\"checks\":[{\"kind\":\"bogus\"}]}]}}}", lookups)).hasMessageContaining("unknown check kind bogus");
        assertThatThrownBy(() -> RuleTable.of("{\"schema\":1,\"forms\":{\"f\":{\"fields\":[{\"name\":\"a\",\"label\":\"A\","
                + "\"checks\":[{\"kind\":\"inList\",\"list\":\"nope\"}]}]}}}", lookups)).hasMessageContaining("unknown lookup list nope");
        assertThat(RuleTable.standard().list("usStateCodes")).hasSize(56).contains("DC", "PR");
        assertThat(RuleTable.standard().list("usStateZipPrefixes")).hasSize(240).contains("NC27", "NC28");
        assertThat(RuleTable.standard().list("nanpGeneralPurposeAreaCodes")).hasSize(410).doesNotContain("800", "911");
    }
}
