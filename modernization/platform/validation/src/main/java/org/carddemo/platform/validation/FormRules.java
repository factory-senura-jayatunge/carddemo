package org.carddemo.platform.validation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;

/**
 * The edits of one form, in edit order (which is also screen order). Every entry is edited, even
 * after an earlier one failed; within one field the edits stop at its first failure, and a field's
 * follow-on edits run only when its basic edits passed (BR-ACUP-104). A field without a
 * {@code required} edit is optional: when it is empty its other edits are skipped. A field counts as
 * empty when it holds only spaces, or {@code *} followed by spaces (BR-ACUP-105).
 *
 * <p>Entry kinds: a plain field with {@code checks}; {@code date} (year, month and day parts, the
 * CSUTLDPY routine and the CSUTLDTC check); {@code phone} (area code, prefix and line number);
 * {@code stateZip} (state and the first two ZIP digits against a list). The TypeScript mirror
 * implements the same semantics; both run {@code rule-vectors.json}.
 */
public final class FormRules {

    private static final Pattern EMPTY = Pattern.compile("^\\*? *$");
    private static final Set<Integer> LONG_MONTHS = Set.of(1, 3, 5, 7, 8, 10, 12);

    private static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
            Map.entry("REQUIRED", "{label} must be supplied."),
            Map.entry("TOO_LONG", "{label} must not be longer than {maxLength} characters."),
            Map.entry("ALPHABETIC", "{label} can have alphabets only."),
            Map.entry("NUMERIC", "{label} must be all numeric."),
            Map.entry("NON_ZERO", "{label} must not be zero."),
            Map.entry("YES_NO", "{label} must be Y or N."),
            Map.entry("AMOUNT_INVALID", "{label} is not valid"),
            Map.entry("AMOUNT_RANGE", "{label} is not valid"),
            Map.entry("RANGE", "{label} is not valid"),
            Map.entry("NOT_ALLOWED", "{label} is not valid"),
            Map.entry("NOT_IN_LIST", "{label} is not valid"),
            Map.entry("DATE_YEAR_REQUIRED", "{label} : Year must be supplied."),
            Map.entry("DATE_YEAR_NOT_4_DIGITS", "{label} must be 4 digit number."),
            Map.entry("DATE_CENTURY", "{label} : Century is not valid."),
            Map.entry("DATE_MONTH_REQUIRED", "{label} : Month must be supplied."),
            Map.entry("DATE_MONTH_INVALID", "{label}: Month must be a number between 1 and 12."),
            Map.entry("DATE_DAY_REQUIRED", "{label} : Day must be supplied."),
            Map.entry("DATE_DAY_INVALID", "{label}:day must be a number between 1 and 31."),
            Map.entry("DATE_31_DAYS", "{label}:Cannot have 31 days in this month."),
            Map.entry("DATE_30_DAYS", "{label}:Cannot have 30 days in this month."),
            Map.entry("DATE_LEAP_YEAR", "{label}:Not a leap year.Cannot have 29 days in this month."),
            Map.entry("DATE_CALENDAR", "{label} validation error Sev code: {severity} Message code: {messageNumber}"),
            Map.entry("DATE_FUTURE", "{label}:cannot be in the future "),
            Map.entry("PHONE_AREA_REQUIRED", "{label}: Area code must be supplied."),
            Map.entry("PHONE_AREA_NOT_3_DIGITS", "{label}: Area code must be A 3 digit number."),
            Map.entry("PHONE_AREA_ZERO", "{label}: Area code cannot be zero"),
            Map.entry("PHONE_AREA_NOT_GENERAL_PURPOSE", "{label}: Not valid North America general purpose area code"),
            Map.entry("PHONE_PREFIX_REQUIRED", "{label}: Prefix code must be supplied."),
            Map.entry("PHONE_PREFIX_NOT_3_DIGITS", "{label}: Prefix code must be A 3 digit number."),
            Map.entry("PHONE_PREFIX_ZERO", "{label}: Prefix code cannot be zero"),
            Map.entry("PHONE_LINE_REQUIRED", "{label}: Line number code must be supplied."),
            Map.entry("PHONE_LINE_NOT_4_DIGITS", "{label}: Line number code must be A 4 digit number."),
            Map.entry("PHONE_LINE_ZERO", "{label}: Line number code cannot be zero"),
            Map.entry("STATE_ZIP_MISMATCH", "Invalid zip code for state"));

    private final String id;
    private final List<JsonNode> entries;
    private final List<String> fieldOrder;
    private final Map<String, List<String>> lists;

    private FormRules(String id, List<JsonNode> entries, List<String> fieldOrder, Map<String, List<String>> lists) {
        this.id = id;
        this.entries = entries;
        this.fieldOrder = fieldOrder;
        this.lists = lists;
    }

    static FormRules parse(String id, JsonNode form, Map<String, List<String>> lists) {
        List<JsonNode> entries = new ArrayList<>();
        List<String> order = new ArrayList<>();
        for (JsonNode e : form.required("fields")) {
            String kind = e.path("kind").asString("field");
            switch (kind) {
                case "field" -> {
                    order.add(e.required("name").asString());
                    e.required("label");
                    for (JsonNode c : e.path("checks")) {
                        requireKnownCheck(id, c.required("kind").asString());
                        if (c.has("list")) {
                            requireList(id, lists, c.get("list").asString());
                        }
                    }
                }
                case "date" -> {
                    e.required("label");
                    for (String part : List.of("year", "month", "day")) {
                        order.add(e.required("parts").required(part).asString());
                    }
                }
                case "phone" -> {
                    e.required("label");
                    for (String part : List.of("area", "prefix", "line")) {
                        order.add(e.required("parts").required(part).asString());
                    }
                    requireList(id, lists, e.path("areaList").asString("nanpGeneralPurposeAreaCodes"));
                }
                case "stateZip" -> {
                    e.required("state");
                    e.required("zip");
                    requireList(id, lists, e.path("list").asString("usStateZipPrefixes"));
                }
                default -> throw new IllegalArgumentException("form " + id + ": unknown entry kind " + kind);
            }
            entries.add(e);
        }
        return new FormRules(id, List.copyOf(entries), List.copyOf(order), lists);
    }

    private static void requireKnownCheck(String form, String kind) {
        if (!Set.of("required", "maxLength", "alphabetic", "numeric", "nonZero", "yesNo", "range", "notIn",
                "inList", "amount", "amountRange").contains(kind)) {
            throw new IllegalArgumentException("form " + form + ": unknown check kind " + kind);
        }
    }

    private static void requireList(String form, Map<String, List<String>> lists, String name) {
        if (!lists.containsKey(name)) {
            throw new IllegalArgumentException("form " + form + ": unknown lookup list " + name);
        }
    }

    public String id() {
        return id;
    }

    /** Every input field the form edits, in screen order. */
    public List<String> fields() {
        return fieldOrder;
    }

    /**
     * Edits {@code input} (field name to text; a missing field is empty).
     *
     * @param today the date "not in the future" compares with (the legacy edit uses the system date)
     */
    public ValidationResult validate(Map<String, String> input, LocalDate today) {
        Run run = new Run(input, today);
        for (JsonNode e : entries) {
            switch (e.path("kind").asString("field")) {
                case "field" -> run.field(e);
                case "date" -> run.date(e);
                case "phone" -> run.phone(e);
                case "stateZip" -> run.stateZip(e);
                default -> throw new IllegalStateException();
            }
        }
        List<String> wrong = fieldOrder.stream().filter(run.wrong::contains).toList();
        return new ValidationResult(run.problems, wrong);
    }

    static boolean empty(String value) {
        return value == null || EMPTY.matcher(value).matches();
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean digits(String s, int length) {
        if (s.length() != length) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean shortNumber(String s) {
        return digits(s, 1) || digits(s, 2);
    }

    private final class Run {

        final Map<String, String> input;
        final LocalDate today;
        final List<FieldProblem> problems = new ArrayList<>();
        final Set<String> wrong = new LinkedHashSet<>();
        final Set<String> passed = new HashSet<>();

        Run(Map<String, String> input, LocalDate today) {
            this.input = new HashMap<>(input);
            this.today = today;
        }

        String value(String field) {
            String v = input.get(field);
            return v == null ? "" : v;
        }

        void fail(JsonNode entry, String code, String label, Map<String, String> args, String... fields) {
            String template = entry.path("messages").path(code).asString(DEFAULT_MESSAGES.get(code));
            String message = template.replace("{label}", label);
            for (Map.Entry<String, String> a : args.entrySet()) {
                message = message.replace("{" + a.getKey() + "}", a.getValue());
            }
            String first = fieldOrder.stream().filter(f -> List.of(fields).contains(f)).findFirst().orElse(fields[0]);
            problems.add(new FieldProblem(first, code, message));
            wrong.addAll(List.of(fields));
        }

        void field(JsonNode e) {
            String name = e.get("name").asString();
            String label = e.get("label").asString();
            for (JsonNode dep : e.path("requiresValid")) {
                if (!passed.contains(dep.asString())) {
                    return;
                }
            }
            String raw = value(name);
            JsonNode checks = e.path("checks");
            boolean required = checks.size() > 0 && "required".equals(checks.get(0).get("kind").asString());
            if (!required && empty(raw)) {
                passed.add(name);
                return;
            }
            for (JsonNode c : checks) {
                String failure = check(c, raw);
                if (failure != null) {
                    String template = c.path("message").asString(DEFAULT_MESSAGES.get(failure));
                    String code = c.path("code").asString(failure);
                    String message = template.replace("{label}", label)
                            .replace("{maxLength}", c.path("length").asString(""));
                    problems.add(new FieldProblem(name, code, message));
                    wrong.add(name);
                    return;
                }
            }
            passed.add(name);
        }

        /** The default code of the failed check, or null when it passed. */
        String check(JsonNode c, String raw) {
            String v = strip(raw);
            return switch (c.get("kind").asString()) {
                case "required" -> {
                    boolean isEmpty = empty(raw);
                    for (JsonNode ev : c.path("emptyValues")) {
                        isEmpty |= v.equals(ev.asString());
                    }
                    yield isEmpty ? "REQUIRED" : null;
                }
                case "maxLength" -> raw.stripTrailing().length() > c.required("length").asInt() ? "TOO_LONG" : null;
                case "alphabetic" -> {
                    String extra = c.path("extra").asString("");
                    for (int i = 0; i < raw.length(); i++) {
                        char ch = raw.charAt(i);
                        boolean ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || ch == ' '
                                || extra.indexOf(ch) >= 0;
                        if (!ok) {
                            yield "ALPHABETIC";
                        }
                    }
                    yield null;
                }
                case "numeric" -> digits(v, c.required("length").asInt()) ? null : "NUMERIC";
                case "nonZero" -> v.chars().allMatch(ch -> ch == '0') ? "NON_ZERO" : null;
                case "yesNo" -> {
                    String s = c.path("ignoreCase").asBoolean(false) ? v.toUpperCase(Locale.ROOT) : v;
                    yield s.equals("Y") || s.equals("N") ? null : "YES_NO";
                }
                case "range" -> {
                    long n = Long.parseLong(v);
                    yield n < c.required("min").asLong() || n > c.required("max").asLong() ? "RANGE" : null;
                }
                case "notIn" -> {
                    long n = Long.parseLong(v);
                    boolean hit = false;
                    for (JsonNode x : c.path("values")) {
                        hit |= x.asLong() == n;
                    }
                    for (JsonNode r : c.path("ranges")) {
                        hit |= n >= r.get(0).asLong() && n <= r.get(1).asLong();
                    }
                    yield hit ? "NOT_ALLOWED" : null;
                }
                case "inList" -> {
                    String s = c.path("ignoreCase").asBoolean(false) ? v.toUpperCase(Locale.ROOT) : v;
                    yield lists.get(c.required("list").asString()).contains(s) ? null : "NOT_IN_LIST";
                }
                case "amount" -> LegacyAmount.parse(raw, c.required("maxIntegerDigits").asInt(),
                        c.required("maxScale").asInt()).isPresent() ? null : "AMOUNT_INVALID";
                case "amountRange" -> {
                    BigDecimal n = LegacyAmount.parse(raw, Integer.MAX_VALUE, Integer.MAX_VALUE).orElseThrow();
                    boolean low = c.has("min") && n.compareTo(new BigDecimal(c.get("min").asString())) < 0;
                    boolean high = c.has("max") && n.compareTo(new BigDecimal(c.get("max").asString())) > 0;
                    yield low || high ? "AMOUNT_RANGE" : null;
                }
                default -> throw new IllegalStateException(c.toString());
            };
        }

        void date(JsonNode e) {
            String label = e.get("label").asString();
            JsonNode parts = e.get("parts");
            String yf = parts.get("year").asString();
            String mf = parts.get("month").asString();
            String df = parts.get("day").asString();
            String y = value(yf);
            String m = value(mf);
            String d = value(df);
            Map<String, String> none = Map.of();
            boolean yearOk = false;
            boolean monthOk = false;
            boolean dayOk = false;
            if (empty(y)) {
                fail(e, "DATE_YEAR_REQUIRED", label, none, yf);
            } else if (!digits(strip(y), 4)) {
                fail(e, "DATE_YEAR_NOT_4_DIGITS", label, none, yf);
            } else {
                int year = Integer.parseInt(strip(y));
                if (year < e.path("minYear").asInt(1900) || year > e.path("maxYear").asInt(2099)) {
                    fail(e, "DATE_CENTURY", label, none, yf);
                } else {
                    yearOk = true;
                }
            }
            if (empty(m)) {
                fail(e, "DATE_MONTH_REQUIRED", label, none, mf);
            } else if (!shortNumber(strip(m)) || Integer.parseInt(strip(m)) < 1 || Integer.parseInt(strip(m)) > 12) {
                fail(e, "DATE_MONTH_INVALID", label, none, mf);
            } else {
                monthOk = true;
            }
            if (empty(d)) {
                fail(e, "DATE_DAY_REQUIRED", label, none, df);
            } else if (!shortNumber(strip(d)) || Integer.parseInt(strip(d)) < 1 || Integer.parseInt(strip(d)) > 31) {
                fail(e, "DATE_DAY_INVALID", label, none, df);
            } else {
                dayOk = true;
            }
            if (!(yearOk && monthOk && dayOk)) {
                return;
            }
            int year = Integer.parseInt(strip(y));
            int month = Integer.parseInt(strip(m));
            int day = Integer.parseInt(strip(d));
            boolean leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
            if (day == 31 && !LONG_MONTHS.contains(month)) {
                fail(e, "DATE_31_DAYS", label, none, mf, df);
                return;
            }
            if (month == 2 && day == 30) {
                fail(e, "DATE_30_DAYS", label, none, mf, df);
                return;
            }
            if (month == 2 && day == 29 && !leap) {
                fail(e, "DATE_LEAP_YEAR", label, none, yf, mf, df);
                return;
            }
            DateCheckResult check = LegacyDateCheck.check("%04d%02d%02d".formatted(year, month, day), LegacyDateCheck.COMPACT);
            if (!check.valid()) {
                fail(e, "DATE_CALENDAR", label,
                        Map.of("severity", check.severityCode(), "messageNumber", check.messageCode()), yf, mf, df);
                return;
            }
            if (e.path("notFuture").asBoolean(false)) {
                LocalDate date = check.value().orElseThrow();
                boolean allowToday = e.path("allowToday").asBoolean(false);
                if (allowToday ? date.isAfter(today) : !date.isBefore(today)) {
                    fail(e, "DATE_FUTURE", label, none, yf, mf, df);
                }
            }
        }

        void phone(JsonNode e) {
            String label = e.get("label").asString();
            JsonNode parts = e.get("parts");
            String af = parts.get("area").asString();
            String pf = parts.get("prefix").asString();
            String lf = parts.get("line").asString();
            String a = value(af);
            String p = value(pf);
            String l = value(lf);
            Map<String, String> none = Map.of();
            if (empty(a) && empty(p) && empty(l) && !e.path("required").asBoolean(false)) {
                return;
            }
            List<String> areaCodes = lists.get(e.path("areaList").asString("nanpGeneralPurposeAreaCodes"));
            if (empty(a)) {
                fail(e, "PHONE_AREA_REQUIRED", label, none, af);
            } else if (!digits(strip(a), 3)) {
                fail(e, "PHONE_AREA_NOT_3_DIGITS", label, none, af);
            } else if (strip(a).equals("000")) {
                fail(e, "PHONE_AREA_ZERO", label, none, af);
            } else if (!areaCodes.contains(strip(a))) {
                fail(e, "PHONE_AREA_NOT_GENERAL_PURPOSE", label, none, af);
            }
            if (empty(p)) {
                fail(e, "PHONE_PREFIX_REQUIRED", label, none, pf);
            } else if (!digits(strip(p), 3)) {
                fail(e, "PHONE_PREFIX_NOT_3_DIGITS", label, none, pf);
            } else if (strip(p).equals("000")) {
                fail(e, "PHONE_PREFIX_ZERO", label, none, pf);
            }
            if (empty(l)) {
                fail(e, "PHONE_LINE_REQUIRED", label, none, lf);
            } else if (!digits(strip(l), 4)) {
                fail(e, "PHONE_LINE_NOT_4_DIGITS", label, none, lf);
            } else if (strip(l).equals("0000")) {
                fail(e, "PHONE_LINE_ZERO", label, none, lf);
            }
        }

        void stateZip(JsonNode e) {
            String sf = e.get("state").asString();
            String zf = e.get("zip").asString();
            if (!passed.contains(sf) || !passed.contains(zf) || empty(value(sf)) || empty(value(zf))) {
                return;
            }
            String state = strip(value(sf));
            if (e.path("ignoreCase").asBoolean(false)) {
                state = state.toUpperCase(Locale.ROOT);
            }
            String zip = strip(value(zf));
            String key = state + (zip.length() >= 2 ? zip.substring(0, 2) : zip);
            if (!lists.get(e.path("list").asString("usStateZipPrefixes")).contains(key)) {
                fail(e, "STATE_ZIP_MISMATCH", "", Map.of(), sf, zf);
            }
        }
    }
}
