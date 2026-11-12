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
 * edits; its {@code shippedCases} run on the forms of the shipped {@code rules.json}. The TypeScript
 * mirror runs the same file.
 */
class FormRulesVectorsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void everyCaseGivesTheExpectedProblems() {
        JsonNode doc = MAPPER.readTree(vectors());
        RuleTable table = RuleTable.of(doc.get("rules").toString(), RuleTable.resource(RuleTable.LOOKUPS));
        assertThat(differences(table, cases("cases"), 40)).isEmpty();
    }

    @Test
    void everyCaseOfTheShippedFormsGivesTheExpectedProblems() {
        assertThat(RuleTable.standard().formIds())
                .containsExactly("accountView", "accountUpdateLookup", "accountUpdate", "cardSearch", "cardLookup", "cardUpdate",
                        "userAdd", "userUpdate", "userAddLegacy", "userUpdateLegacy", "signOn", "passwordChange",
                        "passwordChangeAnyMode");
        assertThat(differences(RuleTable.standard(), cases("shippedCases"), 70)).isEmpty();
    }

    private static List<String> differences(RuleTable table, List<JsonNode> cases, int minimum) {
        List<String> differences = new ArrayList<>();
        int n = 0;
        for (JsonNode c : cases) {
            n++;
            Map<String, String> input = strings(c.get("input"));
            List<String> changed = null;
            if (c.has("changedFields")) {
                changed = new ArrayList<>();
                for (JsonNode f : c.get("changedFields")) {
                    changed.add(f.asString());
                }
            }
            Map<String, String> stored = c.has("stored") ? strings(c.get("stored")) : Map.of();
            ValidationResult r = table.form(c.get("form").asString())
                    .validate(input, LocalDate.parse(c.get("today").asString()), changed, stored);
            List<List<String>> expected = new ArrayList<>();
            c.get("problems").forEach(p -> expected.add(List.of(p.get(0).asString(), p.get(1).asString(), p.get(2).asString())));
            List<List<String>> actual = r.problems().stream().map(p -> List.of(p.field(), p.code(), p.message())).toList();
            List<String> wrong = new ArrayList<>();
            c.get("wrongFields").forEach(f -> wrong.add(f.asString()));
            if (!actual.equals(expected) || !r.wrongFields().equals(wrong)) {
                differences.add(c.get("name").asString() + ": problems " + actual + " wrong " + r.wrongFields());
            }
        }
        assertThat(n).isGreaterThan(minimum);
        return differences;
    }

    private static Map<String, String> strings(JsonNode object) {
        Map<String, String> out = new LinkedHashMap<>();
        object.properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
        return out;
    }

    private static String vectors() {
        return RuleTable.resource("rule-vectors.json");
    }

    /** The cases under {@code key} of rule-vectors.json, then of lone-surrogate-vectors.json. */
    private static List<JsonNode> cases(String key) {
        List<JsonNode> all = new ArrayList<>();
        for (String file : List.of("rule-vectors.json", "lone-surrogate-vectors.json")) {
            MAPPER.readTree(RuleTable.resource(file)).get(key).forEach(all::add);
        }
        return all;
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
    void labelsOfFieldsAndParts() {
        FormRules form = RuleTable.standard().form("accountUpdate");
        assertThat(form.label("creditLimit")).contains("Credit Limit");
        assertThat(form.label("openMonth")).contains("Open Date");
        assertThat(form.label("phone2Line")).contains("Phone Number 2");
        assertThat(form.label("currentBalance")).isEmpty();
        assertThat(form.fields()).allSatisfy(f -> assertThat(form.label(f)).as(f).isPresent());
    }

    @Test
    void blankByTheFieldsRule() {
        FormRules search = RuleTable.standard().form("cardSearch");
        for (String blank : new String[] {null, "", "           ", "00000000000", "0          ", " 0 0"}) {
            assertThat(search.isBlank("acctId", blank)).as("[%s]", blank).isTrue();
        }
        for (String value : new String[] {"*", "00000000001", " 1", "0\t"}) {
            assertThat(search.isBlank("acctId", value)).as("[%s]", value).isFalse();
        }
        FormRules update = RuleTable.standard().form("accountUpdate");
        assertThat(update.isBlank("creditLimit", "*  ")).isTrue();
        assertThat(update.isBlank("creditLimit", "0")).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> search.isBlank("cvv", ""))
                .isInstanceOf(IllegalArgumentException.class);
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
        assertThatThrownBy(() -> RuleTable.of("{\"schema\":1,\"forms\":{\"f\":{\"fields\":[{\"kind\":\"dateOrder\",\"date\":\"x\","
                + "\"min\":\"y\"}]}}}", lookups)).hasMessageContaining("unknown date id x");
        assertThatThrownBy(() -> RuleTable.of("{\"schema\":1,\"forms\":{\"f\":{\"fields\":[{\"kind\":\"notAbove\",\"field\":\"a\","
                + "\"limit\":\"b\"}]}}}", lookups)).hasMessageContaining("notAbove names unknown field a");
        assertThat(RuleTable.standard().list("usStateCodes")).hasSize(56).contains("DC", "PR");
        assertThat(RuleTable.standard().list("usStateZipPrefixes")).hasSize(240).contains("NC27", "NC28");
        assertThat(RuleTable.standard().list("nanpGeneralPurposeAreaCodes")).hasSize(410).doesNotContain("800", "911");
        assertThat(RuleTable.standard().list("userTypes")).containsExactly("A", "U");
    }

    @Test
    void theTextChecksAndDiffersNeedTheirOwnCodeAndMessage() {
        String lookups = RuleTable.resource(RuleTable.LOOKUPS);
        String field = "{\"name\":\"a\",\"label\":\"A\",\"checks\":[%s]}";
        String b = "{\"name\":\"b\",\"label\":\"B\"}";
        assertThatThrownBy(() -> RuleTable.of(form(field.formatted("{\"kind\":\"codePoints\",\"max\":8,\"message\":\"m\"}")), lookups))
                .hasMessageContaining("codePoints needs a code and a message");
        assertThatThrownBy(() -> RuleTable.of(form(field.formatted("{\"kind\":\"noSpace\",\"code\":\"C\"}")), lookups))
                .hasMessageContaining("noSpace needs a code and a message");
        assertThatThrownBy(() -> RuleTable.of(form(field.formatted("") + "," + b
                + ",{\"kind\":\"differs\",\"field\":\"a\",\"other\":\"b\",\"code\":\"C\"}"), lookups))
                .hasMessageContaining("differs needs a code and a message");
        assertThatThrownBy(() -> RuleTable.of(form(field.formatted("")
                + ",{\"kind\":\"differs\",\"field\":\"a\",\"other\":\"b\",\"code\":\"C\",\"message\":\"m\"}"), lookups))
                .hasMessageContaining("differs names unknown field b");
        assertThatThrownBy(() -> RuleTable.of(form(field.formatted("") + "," + b
                + ",{\"kind\":\"differs\",\"field\":\"a\",\"other\":\"b\",\"code\":\"C\",\"message\":\"m\",\"fold\":\"upper\"}"),
                lookups)).hasMessageContaining("differs fold must be legacyUpper");
        assertThatThrownBy(() -> RuleTable.of(form("{\"name\":\"a\",\"label\":\"A\",\"trim\":{\"both\":\" \"}}"), lookups))
                .hasMessageContaining("trim takes leading and trailing strings");
    }

    private static String form(String fields) {
        return "{\"schema\":1,\"forms\":{\"f\":{\"fields\":[" + fields + "]}}}";
    }

    @Test
    void valueIsWhatTheChecksOfTheFieldSee() {
        JsonNode doc = MAPPER.readTree(vectors());
        FormRules form = RuleTable.of(doc.get("rules").toString(), RuleTable.resource(RuleTable.LOOKUPS)).form("textRules");
        assertThat(form.value("code", "  ab \u0000 ")).isEqualTo("ab");
        assertThat(form.value("secret", " Abcdefgh1 \u0000")).isEqualTo(" Abcdefgh1");
        assertThat(form.value("secret", "Abcdefgh1\t")).isEqualTo("Abcdefgh1\t");
        assertThat(form.value("secret", null)).isEmpty();
        assertThat(form.value("plain", " ab\t")).isEqualTo("ab");
        assertThatThrownBy(() -> form.value("nope", "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
