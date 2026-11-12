package org.carddemo.platform.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every text a form of the rule table can give, rendered with the label and arguments its entry
 * passes, fits the message line of the units whose endpoints return it: 75 characters for the
 * account and card units (NUM-ACVW-007, NUM-ACUP-103 and the 75-character work fields of COCRDLIC,
 * COCRDSLC and COCRDUPC), 78 for user administration and sign-on (ERRMSG LENGTH=78 in COUSR01,
 * COUSR02 and COSGN00; the password change page shows its texts on the sign-on line).
 */
class RuleTextsLengthTest {

    private static final Map<String, Integer> FORM_LIMIT = Map.ofEntries(Map.entry("accountView", 75),
            Map.entry("accountUpdateLookup", 75), Map.entry("accountUpdate", 75), Map.entry("cardSearch", 75),
            Map.entry("cardLookup", 75), Map.entry("cardUpdate", 75), Map.entry("userAdd", 78),
            Map.entry("userUpdate", 78), Map.entry("userAddLegacy", 78), Map.entry("userUpdateLegacy", 78),
            Map.entry("signOn", 78), Map.entry("passwordChange", 78), Map.entry("passwordChangeAnyMode", 78));

    /** Check kinds without a default text: the rule must give its own message. */
    private static final Set<String> OWN_TEXT_CHECKS = Set.of("noSpace", "codePoints");

    private static final Map<String, String> CHECK_CODE = Map.ofEntries(Map.entry("required", "REQUIRED"),
            Map.entry("maxLength", "TOO_LONG"), Map.entry("alphabetic", "ALPHABETIC"), Map.entry("numeric", "NUMERIC"),
            Map.entry("nonZero", "NON_ZERO"), Map.entry("yesNo", "YES_NO"), Map.entry("range", "RANGE"),
            Map.entry("notIn", "NOT_ALLOWED"), Map.entry("inList", "NOT_IN_LIST"), Map.entry("amount", "AMOUNT_INVALID"),
            Map.entry("amountRange", "AMOUNT_RANGE"), Map.entry("latin1", "UNSUPPORTED_CHARACTER"));

    private static final List<String> DATE_CODES = List.of("DATE_YEAR_REQUIRED", "DATE_YEAR_NOT_4_DIGITS",
            "DATE_CENTURY", "DATE_MONTH_REQUIRED", "DATE_MONTH_INVALID", "DATE_DAY_REQUIRED", "DATE_DAY_INVALID",
            "DATE_31_DAYS", "DATE_30_DAYS", "DATE_LEAP_YEAR", "DATE_CALENDAR", "DATE_FUTURE");

    private static final List<String> PHONE_CODES = List.of("PHONE_AREA_REQUIRED", "PHONE_AREA_NOT_3_DIGITS",
            "PHONE_AREA_ZERO", "PHONE_AREA_NOT_GENERAL_PURPOSE", "PHONE_PREFIX_REQUIRED", "PHONE_PREFIX_NOT_3_DIGITS",
            "PHONE_PREFIX_ZERO", "PHONE_PREFIX_INVALID", "PHONE_LINE_REQUIRED", "PHONE_LINE_NOT_4_DIGITS",
            "PHONE_LINE_ZERO");

    /** CSUTLDTC's codes are four digits each ({@link DateCheckResult#severityCode()}, {@code messageCode()}). */
    private static final Map<String, String> DATE_CALENDAR_ARGS = Map.of("severity", "0003", "messageNumber", "2521");

    @Test
    void everyRuleTextFitsTheMessageLine() {
        JsonNode forms = JsonMapper.builder().build().readTree(RuleTable.resource(RuleTable.RULES)).required("forms");
        assertThat(RuleTable.standard().formIds()).containsExactlyInAnyOrderElementsOf(FORM_LIMIT.keySet());
        List<String> rendered = new ArrayList<>();
        Set<String> kindsSeen = new java.util.HashSet<>();
        for (String formId : FORM_LIMIT.keySet()) {
            JsonNode form = forms.required(formId);
            Map<String, String> labels = new HashMap<>();
            Map<String, JsonNode> dates = new HashMap<>();
            for (JsonNode e : form.required("fields")) {
                if (e.has("name")) {
                    labels.put(e.get("name").asString(), e.get("label").asString());
                }
                if (e.has("id")) {
                    dates.put(e.get("id").asString(), e);
                }
            }
            List<String> texts = new ArrayList<>();
            for (JsonNode e : form.required("fields")) {
                String kind = e.path("kind").asString("field");
                kindsSeen.add(kind);
                switch (kind) {
                    case "field" -> {
                        for (JsonNode c : e.path("checks")) {
                            String checkKind = c.required("kind").asString();
                            String template;
                            if (OWN_TEXT_CHECKS.contains(checkKind)) {
                                template = c.required("message").asString();
                            } else {
                                String code = CHECK_CODE.get(checkKind);
                                assertThat(code).as(formId + " check " + c).isNotNull();
                                template = c.path("message").asString(FormRules.DEFAULT_MESSAGES.get(code));
                            }
                            texts.add(template.replace("{label}", e.get("label").asString())
                                    .replace("{maxLength}", c.path("length").asString("")));
                        }
                    }
                    case "date" -> DATE_CODES.forEach(code -> texts.add(render(entryTemplate(e, code),
                            e.get("label").asString(), DATE_CALENDAR_ARGS)));
                    case "phone" -> PHONE_CODES.forEach(code -> texts.add(render(entryTemplate(e, code),
                            e.get("label").asString(), Map.of())));
                    case "stateZip" -> texts.add(render(entryTemplate(e, "STATE_ZIP_MISMATCH"), "", Map.of()));
                    case "notAbove" -> texts.add(render(singleTemplate(e, "NOT_ABOVE"),
                            labels.get(e.get("field").asString()), Map.of("limitLabel", labels.get(e.get("limit").asString()))));
                    case "dateOrder" -> texts.add(render(singleTemplate(e, "DATE_ORDER"),
                            dates.get(e.get("date").asString()).get("label").asString(), Map.of()));
                    case "monthYearNotPast" -> texts.add(render(singleTemplate(e, "MONTH_YEAR_PAST"),
                            e.path("label").asString(""), Map.of()));
                    case "anyEntered" -> texts.add(render(singleTemplate(e, "NO_INPUT"), "", Map.of()));
                    case "differs" -> texts.add(render(e.required("message").asString(),
                            labels.get(e.get("field").asString()), Map.of()));
                    default -> throw new AssertionError(formId + ": entry kind " + kind + " has no texts listed here");
                }
            }
            int limit = FORM_LIMIT.get(formId);
            for (String t : texts) {
                assertThat(t).as(formId).doesNotContain("{");
                assertThat(t.length()).as(formId + ": " + t).isLessThanOrEqualTo(limit);
            }
            rendered.addAll(texts);
        }
        assertThat(kindsSeen).contains("field", "date", "phone", "stateZip", "notAbove", "dateOrder",
                "monthYearNotPast", "anyEntered", "differs");
        assertThat(rendered).contains("Phone Number 1: Not valid North America general purpose area code",
                "Date of Birth:Not a leap year.Cannot have 29 days in this month.",
                "Cash Credit Limit must not be more than the Credit Limit.",
                "Government Issued Id contains a character that is not supported.",
                "User ID must not be longer than 8 characters.", "New Password must be 8 to 64 characters ...");
    }

    /** What {@code fail} uses: the entry's {@code messages} override, else the default. */
    private static String entryTemplate(JsonNode e, String code) {
        return e.path("messages").path(code).asString(FormRules.DEFAULT_MESSAGES.get(code));
    }

    /** What {@code failEntry} uses: the entry's {@code message}, else the default. */
    private static String singleTemplate(JsonNode e, String code) {
        return e.path("message").asString(FormRules.DEFAULT_MESSAGES.get(code));
    }

    private static String render(String template, String label, Map<String, String> args) {
        String s = template.replace("{label}", label);
        for (Map.Entry<String, String> a : args.entrySet()) {
            s = s.replace("{" + a.getKey() + "}", a.getValue());
        }
        return s;
    }
}
