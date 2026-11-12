package org.carddemo.platform.validation;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.carddemo.platform.legacyformat.LegacyCase;
import tools.jackson.databind.JsonNode;

/**
 * The edits of one form, in edit order (which is also screen order). Every entry is edited, even
 * after an earlier one failed; within one field the edits stop at its first failure, and a field's
 * follow-on edits run only when its basic edits passed (BR-ACUP-104). A field without a
 * {@code required} edit is optional: when it is empty its other edits are skipped. A field counts as
 * empty when it holds only spaces, or {@code *} followed by spaces (BR-ACUP-105); a field with
 * {@code blankChars} counts as empty instead when it holds only those characters (CRDL: spaces and
 * {@code 0}).
 *
 * <p>Entry kinds:
 * <ul>
 * <li>a plain field with {@code checks}. Check options beyond the obvious: {@code numeric} with
 *     {@code exact} takes the text untrimmed, so a leading or trailing space makes it invalid;</li>
 * <li>{@code date} (year, month and day parts, the CSUTLDPY routine and the CSUTLDTC check), with
 *     {@code minYear}/{@code maxYear} (default 1900-2099; 9999 means no upper limit for a 4-digit
 *     year), {@code notFuture}, {@code allowToday}, and an optional {@code id} that
 *     {@code dateOrder} refers to;</li>
 * <li>{@code phone} (area code, prefix and line number); option {@code nanpPrefix} rejects a prefix
 *     starting with 0 or 1 ({@code PHONE_PREFIX_INVALID});</li>
 * <li>{@code stateZip} (state and the first two ZIP digits against a list);</li>
 * <li>{@code notAbove} ({@code field} must not be more than {@code limit}, both amounts; marks both);</li>
 * <li>{@code dateOrder} (the date {@code date} must not be before {@code min} nor after {@code max},
 *     or strictly between them with {@code exclusive}; marks the parts of {@code date}). A bound
 *     that is a stored NULL date (unchanged, all parts empty) is left out; the entry does not run
 *     when {@code date} is NULL or every bound is;</li>
 * <li>{@code monthYearNotPast} ({@code parts} month and year must not be before the month of
 *     {@code today}; runs only when the pair differs from the stored values);</li>
 * <li>{@code anyEntered} (fails when none of {@code fields} is entered; a field is not entered when
 *     its edits stop at a check giving the code of its {@code required} check, so an all-zero key
 *     that maps to the required message counts as not entered);</li>
 * <li>{@code differs} ({@code field} must not equal {@code other}, each as its own checks see it;
 *     with {@code "fold": "legacyUpper"} both are compared after {@link LegacyCase#upper}, else
 *     exactly; marks {@code field}). It is skipped when {@code field} is blank by its own rule.
 *     {@code code} and {@code message} are required.</li>
 * </ul>
 * The cross-field kinds ({@code stateZip}, {@code notAbove}, {@code dateOrder},
 * {@code monthYearNotPast}, {@code differs}) run only when the fields they compare passed their own
 * edits. Their {@code code} and {@code message} replace the default code and text.
 *
 * <p>A plain field's {@code trim} option ({@code {"leading": chars, "trailing": chars}}, each a set
 * of UTF-16 units, default none) sets the value its checks see: the raw text with those characters
 * removed at the end, then at the start. Without {@code trim} that value is {@link String#strip()}
 * and {@code maxLength} measures {@link String#stripTrailing()}. The checks that read this value are
 * {@code required} (and its {@code emptyValues}), {@code latin1}, {@code numeric} without
 * {@code exact}, {@code nonZero}, {@code yesNo}, {@code range}, {@code notIn}, {@code inList},
 * {@code noSpace}, {@code codePoints} and, with {@code trim}, {@code maxLength}. The others read the
 * raw text: {@code alphabetic}, {@code numeric} with {@code exact}, {@code amount} and
 * {@code amountRange}. Blankness always comes from {@code blankChars} or the default empty rule.
 * {@code noSpace} fails when the value holds U+0020. {@code codePoints} ({@code min}, {@code max},
 * {@code printable}) counts code points, a lone surrogate as one, and with {@code printable} fails on
 * an ISO control code point (U+0000 to U+001F, U+007F to U+009F). These two have no default text:
 * {@code code} and {@code message} are required.
 *
 * <p>Changed-fields validation (D-49): with {@code changedFields}, only the entries that hold a
 * changed field run. A change to one part of a date or phone, or to one member of a form-level
 * {@code groups} list, counts as a change to every part. A field that did not change counts as
 * valid, and its value is taken from {@code stored}, so a cross-field rule compares a changed value
 * with the stored value of the other field. Without {@code changedFields} every entry runs on
 * {@code input}. The TypeScript mirror implements the same semantics; both run
 * {@code rule-vectors.json}.
 */
public final class FormRules {

    private static final Pattern EMPTY = Pattern.compile("^\\*? *$");
    private static final Set<Integer> LONG_MONTHS = Set.of(1, 3, 5, 7, 8, 10, 12);
    private static final Set<String> CHECK_KINDS = Set.of("required", "maxLength", "alphabetic", "numeric",
            "nonZero", "yesNo", "range", "notIn", "inList", "amount", "amountRange", "latin1", "noSpace", "codePoints");
    /** Check kinds with no default code or text: the rule gives both. */
    private static final Set<String> OWN_TEXT_CHECKS = Set.of("noSpace", "codePoints");
    private static final String FOLD_LEGACY_UPPER = "legacyUpper";

    static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
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
            Map.entry("DATE_ORDER", "{label} is not valid"),
            Map.entry("PHONE_AREA_REQUIRED", "{label}: Area code must be supplied."),
            Map.entry("PHONE_AREA_NOT_3_DIGITS", "{label}: Area code must be A 3 digit number."),
            Map.entry("PHONE_AREA_ZERO", "{label}: Area code cannot be zero"),
            Map.entry("PHONE_AREA_NOT_GENERAL_PURPOSE", "{label}: Not valid North America general purpose area code"),
            Map.entry("PHONE_PREFIX_REQUIRED", "{label}: Prefix code must be supplied."),
            Map.entry("PHONE_PREFIX_NOT_3_DIGITS", "{label}: Prefix code must be A 3 digit number."),
            Map.entry("PHONE_PREFIX_ZERO", "{label}: Prefix code cannot be zero"),
            Map.entry("PHONE_PREFIX_INVALID", "{label}: Prefix code must not start with 0 or 1"),
            Map.entry("PHONE_LINE_REQUIRED", "{label}: Line number code must be supplied."),
            Map.entry("PHONE_LINE_NOT_4_DIGITS", "{label}: Line number code must be A 4 digit number."),
            Map.entry("PHONE_LINE_ZERO", "{label}: Line number code cannot be zero"),
            Map.entry("STATE_ZIP_MISMATCH", "Invalid zip code for state"),
            Map.entry("NOT_ABOVE", "{label} must not be more than the {limitLabel}."),
            Map.entry("MONTH_YEAR_PAST", "{label} cannot be in the past"),
            Map.entry("NO_INPUT", "No input received"),
            Map.entry("UNSUPPORTED_CHARACTER", "{label} contains a character that is not supported."));

    private final String id;
    private final List<JsonNode> entries;
    /** Per entry, the fields it holds: an entry runs under changed-fields validation when one changed. */
    private final List<List<String>> held;
    private final List<String> fieldOrder;
    private final Map<String, List<String>> lists;
    private final List<List<String>> groups;
    private final Map<String, JsonNode> fieldEntries;
    private final Map<String, JsonNode> dates;

    private FormRules(String id, List<JsonNode> entries, List<List<String>> held, List<String> fieldOrder,
            Map<String, List<String>> lists, List<List<String>> groups, Map<String, JsonNode> fieldEntries,
            Map<String, JsonNode> dates) {
        this.id = id;
        this.entries = entries;
        this.held = held;
        this.fieldOrder = fieldOrder;
        this.lists = lists;
        this.groups = groups;
        this.fieldEntries = fieldEntries;
        this.dates = dates;
    }

    static FormRules parse(String id, JsonNode form, Map<String, List<String>> lists) {
        List<JsonNode> entries = new ArrayList<>();
        List<String> order = new ArrayList<>();
        Map<String, JsonNode> fieldEntries = new HashMap<>();
        Map<String, JsonNode> dates = new HashMap<>();
        for (JsonNode e : form.required("fields")) {
            String kind = kind(e);
            switch (kind) {
                case "field" -> {
                    String name = e.required("name").asString();
                    order.add(name);
                    fieldEntries.put(name, e);
                    e.required("label");
                    if (e.has("trim")) {
                        JsonNode trim = e.get("trim");
                        boolean ok = trim.isObject();
                        for (Map.Entry<String, JsonNode> p : trim.properties()) {
                            ok &= (p.getKey().equals("leading") || p.getKey().equals("trailing")) && p.getValue().isString();
                        }
                        if (!ok) {
                            throw new IllegalArgumentException("form " + id + ": field " + name
                                    + ": trim takes leading and trailing strings");
                        }
                    }
                    for (JsonNode c : e.path("checks")) {
                        String checkKind = c.required("kind").asString();
                        requireKnownCheck(id, checkKind);
                        if (OWN_TEXT_CHECKS.contains(checkKind)) {
                            requireText(id, checkKind, c);
                        }
                        if (c.has("list")) {
                            requireList(id, lists, c.get("list").asString());
                        }
                    }
                }
                case "date" -> {
                    e.required("label");
                    order.addAll(parts(e, "year", "month", "day"));
                    if (e.has("id")) {
                        dates.put(e.get("id").asString(), e);
                    }
                }
                case "phone" -> {
                    e.required("label");
                    order.addAll(parts(e, "area", "prefix", "line"));
                    requireList(id, lists, e.path("areaList").asString("nanpGeneralPurposeAreaCodes"));
                }
                case "stateZip" -> {
                    e.required("state");
                    e.required("zip");
                    requireList(id, lists, e.path("list").asString("usStateZipPrefixes"));
                }
                case "notAbove" -> {
                    e.required("field");
                    e.required("limit");
                }
                case "dateOrder" -> {
                    e.required("date");
                    if (!e.has("min") && !e.has("max")) {
                        throw new IllegalArgumentException("form " + id + ": dateOrder needs min or max");
                    }
                }
                case "monthYearNotPast" -> parts(e, "month", "year");
                case "anyEntered" -> e.required("fields");
                case "differs" -> {
                    e.required("field");
                    e.required("other");
                    requireText(id, kind, e);
                    if (e.has("fold") && !FOLD_LEGACY_UPPER.equals(e.get("fold").asString())) {
                        throw new IllegalArgumentException("form " + id + ": differs fold must be " + FOLD_LEGACY_UPPER);
                    }
                }
                default -> throw new IllegalArgumentException("form " + id + ": unknown entry kind " + kind);
            }
            entries.add(e);
        }
        List<List<String>> held = new ArrayList<>();
        for (JsonNode e : entries) {
            List<String> fields = switch (kind(e)) {
                case "field" -> List.of(e.get("name").asString());
                case "date" -> parts(e, "year", "month", "day");
                case "phone" -> parts(e, "area", "prefix", "line");
                case "stateZip" -> List.of(e.get("state").asString(), e.get("zip").asString());
                case "notAbove" -> List.of(e.get("field").asString(), e.get("limit").asString());
                case "differs" -> List.of(e.get("field").asString(), e.get("other").asString());
                case "dateOrder" -> {
                    List<String> all = new ArrayList<>();
                    for (String key : List.of("date", "min", "max")) {
                        if (e.has(key)) {
                            JsonNode date = dates.get(e.get(key).asString());
                            if (date == null) {
                                throw new IllegalArgumentException("form " + id + ": unknown date id " + e.get(key).asString());
                            }
                            all.addAll(parts(date, "year", "month", "day"));
                        }
                    }
                    yield all;
                }
                case "monthYearNotPast" -> parts(e, "month", "year");
                case "anyEntered" -> {
                    List<String> all = new ArrayList<>();
                    e.get("fields").forEach(f -> all.add(f.asString()));
                    yield all;
                }
                default -> throw new IllegalStateException();
            };
            String kind = kind(e);
            for (String f : fields) {
                boolean needsEntry = kind.equals("notAbove") || kind.equals("anyEntered") || kind.equals("differs");
                if (needsEntry ? !fieldEntries.containsKey(f) : !order.contains(f)) {
                    throw new IllegalArgumentException("form " + id + ": " + kind + " names unknown field " + f);
                }
            }
            held.add(List.copyOf(fields));
        }
        List<List<String>> groups = new ArrayList<>();
        for (JsonNode g : form.path("groups")) {
            List<String> members = new ArrayList<>();
            g.forEach(f -> members.add(f.asString()));
            for (String f : members) {
                if (!order.contains(f)) {
                    throw new IllegalArgumentException("form " + id + ": group names unknown field " + f);
                }
            }
            groups.add(List.copyOf(members));
        }
        return new FormRules(id, List.copyOf(entries), List.copyOf(held), List.copyOf(order), lists,
                List.copyOf(groups), Map.copyOf(fieldEntries), Map.copyOf(dates));
    }

    private static String kind(JsonNode e) {
        return e.path("kind").asString("field");
    }

    private static List<String> parts(JsonNode e, String... names) {
        List<String> out = new ArrayList<>();
        for (String part : names) {
            out.add(e.required("parts").required(part).asString());
        }
        return out;
    }

    private static void requireKnownCheck(String form, String kind) {
        if (!CHECK_KINDS.contains(kind)) {
            throw new IllegalArgumentException("form " + form + ": unknown check kind " + kind);
        }
    }

    private static void requireText(String form, String kind, JsonNode node) {
        if (!node.path("code").isString() || !node.path("message").isString()) {
            throw new IllegalArgumentException("form " + form + ": " + kind + " needs a code and a message");
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

    /**
     * The label of the entry that edits {@code field}: a field, or a part of a date or phone.
     * Empty when the form does not edit it.
     */
    public Optional<String> label(String field) {
        for (JsonNode e : entries) {
            List<String> names = switch (kind(e)) {
                case "field" -> List.of(e.get("name").asString());
                case "date" -> parts(e, "year", "month", "day");
                case "phone" -> parts(e, "area", "prefix", "line");
                default -> List.of();
            };
            if (names.contains(field)) {
                return Optional.of(e.get("label").asString());
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code value} counts as empty for the plain field {@code field}, by the rule its edits
     * use ({@code blankChars} or spaces); a search drops such a filter.
     */
    public boolean isBlank(String field, String value) {
        for (JsonNode e : entries) {
            if (kind(e).equals("field") && e.get("name").asString().equals(field)) {
                return blank(e, value);
            }
        }
        throw new IllegalArgumentException("the form has no field " + field);
    }

    /**
     * The value the checks of the plain field {@code field} see for {@code raw}: without its
     * {@code trim} characters, or stripped when it has none. A page compares two inputs of the same
     * field (a confirmation) on this value, as the service stores it.
     */
    public String value(String field, String raw) {
        JsonNode e = fieldEntries.get(field);
        if (e == null) {
            throw new IllegalArgumentException("the form has no field " + field);
        }
        return trimmed(e, raw);
    }

    /** Every input field the form edits, in screen order. */
    public List<String> fields() {
        return fieldOrder;
    }

    /**
     * Edits {@code input} (field name to text; a missing field is empty); every entry runs.
     *
     * @param today the date "not in the future" compares with (the legacy edit uses the system date)
     */
    public ValidationResult validate(Map<String, String> input, LocalDate today) {
        return validate(input, today, null, Map.of());
    }

    /** As {@link #validate(Map, LocalDate, Collection, Map)} with no stored values. */
    public ValidationResult validate(Map<String, String> input, LocalDate today, Collection<String> changedFields) {
        return validate(input, today, changedFields, Map.of());
    }

    /**
     * Edits the changed fields (D-49).
     *
     * @param input         the typed values; only the changed fields are read from it
     * @param today         the date "not in the future" and "not in the past" compare with
     * @param changedFields the fields the user changed, or null when every field counts as changed
     * @param stored        the stored values by field name, for the fields that did not change and for
     *                      the "differs from stored" test of {@code monthYearNotPast}
     */
    public ValidationResult validate(Map<String, String> input, LocalDate today, Collection<String> changedFields,
            Map<String, String> stored) {
        Set<String> changed = changedFields == null ? null : expand(changedFields);
        Run run = new Run(input, stored == null ? Map.of() : stored, changed, today);
        for (int i = 0; i < entries.size(); i++) {
            if (changed != null && Collections.disjoint(held.get(i), changed)) {
                continue;
            }
            JsonNode e = entries.get(i);
            switch (kind(e)) {
                case "field" -> run.field(e);
                case "date" -> run.date(e);
                case "phone" -> run.phone(e);
                case "stateZip" -> run.stateZip(e);
                case "notAbove" -> run.notAbove(e);
                case "dateOrder" -> run.dateOrder(e);
                case "monthYearNotPast" -> run.monthYearNotPast(e);
                case "anyEntered" -> run.anyEntered(e);
                case "differs" -> run.differs(e);
                default -> throw new IllegalStateException();
            }
        }
        List<String> wrong = fieldOrder.stream().filter(run.wrong::contains).toList();
        return new ValidationResult(run.problems, wrong);
    }

    private Set<String> expand(Collection<String> changedFields) {
        Set<String> changed = new HashSet<>(changedFields);
        for (int i = 0; i < entries.size(); i++) {
            String kind = kind(entries.get(i));
            if ((kind.equals("date") || kind.equals("phone")) && !Collections.disjoint(held.get(i), changed)) {
                changed.addAll(held.get(i));
            }
        }
        for (List<String> group : groups) {
            if (!Collections.disjoint(group, changed)) {
                changed.addAll(group);
            }
        }
        return changed;
    }

    static boolean empty(String value) {
        return value == null || EMPTY.matcher(value).matches();
    }

    private static boolean blank(JsonNode fieldEntry, String raw) {
        if (!fieldEntry.has("blankChars")) {
            return empty(raw);
        }
        String chars = fieldEntry.get("blankChars").asString();
        return raw == null || raw.chars().allMatch(ch -> chars.indexOf(ch) >= 0);
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    /** The value a plain field's checks see: see the class comment on {@code trim}. */
    private static String trimmed(JsonNode fieldEntry, String raw) {
        if (!fieldEntry.has("trim")) {
            return strip(raw);
        }
        String s = raw == null ? "" : raw;
        String leading = fieldEntry.get("trim").path("leading").asString("");
        String trailing = fieldEntry.get("trim").path("trailing").asString("");
        int end = s.length();
        while (end > 0 && trailing.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        int start = 0;
        while (start < end && leading.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        return s.substring(start, end);
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

    /** The value as a number when it is 1 to 9 digits after trimming, else null. */
    private static Integer number(String value) {
        String s = strip(value);
        for (int n = 1; n <= 9; n++) {
            if (digits(s, n)) {
                return Integer.parseInt(s);
            }
        }
        return null;
    }

    /** A failed check of a field: the check and the code it gives by default. */
    private record Failure(JsonNode check, String defaultCode) {
        String code() {
            return check.path("code").asString(defaultCode);
        }
    }

    private final class Run {

        final Map<String, String> input;
        final Map<String, String> stored;
        final Set<String> changed;
        final LocalDate today;
        final List<FieldProblem> problems = new ArrayList<>();
        final Set<String> wrong = new LinkedHashSet<>();
        final Set<String> passed = new HashSet<>();

        Run(Map<String, String> input, Map<String, String> stored, Set<String> changed, LocalDate today) {
            this.input = new HashMap<>(input);
            this.stored = new HashMap<>(stored);
            this.changed = changed;
            this.today = today;
            if (changed != null) {
                fieldOrder.stream().filter(f -> !changed.contains(f)).forEach(passed::add);
            }
        }

        /** The typed value of a changed field, the stored value of an unchanged one. */
        String value(String field) {
            String v = (changed == null || changed.contains(field) ? input : stored).get(field);
            return v == null ? "" : v;
        }

        String label(String field) {
            return fieldEntries.get(field).get("label").asString();
        }

        void fail(JsonNode entry, String code, String label, Map<String, String> args, String... fields) {
            String template = entry.path("messages").path(code).asString(DEFAULT_MESSAGES.get(code));
            report(code, template, label, args, fields);
        }

        /** A failure of a single-failure entry kind, whose code and message the entry may replace. */
        void failEntry(JsonNode entry, String defaultCode, String label, Map<String, String> args, String... fields) {
            String code = entry.path("code").asString(defaultCode);
            String template = entry.path("message").asString(DEFAULT_MESSAGES.get(defaultCode));
            report(code, template, label, args, fields);
        }

        void report(String code, String template, String label, Map<String, String> args, String... fields) {
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
            Failure failure = firstFailure(e, value(name));
            if (failure == null) {
                passed.add(name);
                return;
            }
            JsonNode c = failure.check();
            String template = c.path("message").asString(DEFAULT_MESSAGES.get(failure.defaultCode()));
            String message = template.replace("{label}", label).replace("{maxLength}", c.path("length").asString(""));
            problems.add(new FieldProblem(name, failure.code(), message));
            wrong.add(name);
        }

        /** The first failed check of a field entry on {@code raw}, or null when every check passed. */
        Failure firstFailure(JsonNode e, String raw) {
            JsonNode checks = e.path("checks");
            boolean isBlank = blank(e, raw);
            boolean required = checks.size() > 0 && "required".equals(checks.get(0).get("kind").asString());
            if (!required && isBlank) {
                return null;
            }
            for (JsonNode c : checks) {
                String failure = check(e, c, raw, isBlank);
                if (failure != null) {
                    return new Failure(c, failure);
                }
            }
            return null;
        }

        /** The default code of the failed check of field entry {@code e}, or null when it passed. */
        String check(JsonNode e, JsonNode c, String raw, boolean isBlank) {
            String v = trimmed(e, raw);
            return switch (c.get("kind").asString()) {
                case "required" -> {
                    boolean none = isBlank;
                    for (JsonNode ev : c.path("emptyValues")) {
                        none |= v.equals(ev.asString());
                    }
                    yield none ? "REQUIRED" : null;
                }
                case "maxLength" -> (e.has("trim") ? v : raw.stripTrailing()).length() > c.required("length").asInt()
                        ? "TOO_LONG" : null;
                case "noSpace" -> v.indexOf(' ') >= 0 ? "NO_SPACE" : null;
                case "codePoints" -> {
                    int n = v.codePointCount(0, v.length());
                    boolean shortOrLong = (c.has("min") && n < c.get("min").asInt()) || (c.has("max") && n > c.get("max").asInt());
                    boolean control = c.path("printable").asBoolean(false) && v.codePoints().anyMatch(Character::isISOControl);
                    yield shortOrLong || control ? "CODE_POINTS" : null;
                }
                // A legacy X(n) byte holds U+0000 to U+00FF only (ISO-8859-1, code page 037).
                case "latin1" -> v.chars().anyMatch(ch -> ch > 0xFF) ? "UNSUPPORTED_CHARACTER" : null;
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
                case "numeric" -> digits(c.path("exact").asBoolean(false) ? raw : v, c.required("length").asInt())
                        ? null : "NUMERIC";
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
            int before = problems.size();
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
            if (problems.size() == before) {
                passed.addAll(List.of(yf, mf, df));
            }
        }

        void phone(JsonNode e) {
            int before = problems.size();
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
                passed.addAll(List.of(af, pf, lf));
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
            } else if (e.path("nanpPrefix").asBoolean(false) && strip(p).charAt(0) < '2') {
                fail(e, "PHONE_PREFIX_INVALID", label, none, pf);
            }
            if (empty(l)) {
                fail(e, "PHONE_LINE_REQUIRED", label, none, lf);
            } else if (!digits(strip(l), 4)) {
                fail(e, "PHONE_LINE_NOT_4_DIGITS", label, none, lf);
            } else if (strip(l).equals("0000")) {
                fail(e, "PHONE_LINE_ZERO", label, none, lf);
            }
            if (problems.size() == before) {
                passed.addAll(List.of(af, pf, lf));
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

        void notAbove(JsonNode e) {
            String f = e.get("field").asString();
            String l = e.get("limit").asString();
            if (!passed.contains(f) || !passed.contains(l)) {
                return;
            }
            Optional<BigDecimal> amount = LegacyAmount.parse(value(f), Integer.MAX_VALUE, Integer.MAX_VALUE);
            Optional<BigDecimal> limit = LegacyAmount.parse(value(l), Integer.MAX_VALUE, Integer.MAX_VALUE);
            if (amount.isPresent() && limit.isPresent() && amount.get().compareTo(limit.get()) > 0) {
                failEntry(e, "NOT_ABOVE", label(f), Map.of("limitLabel", label(l)), f, l);
            }
        }

        void dateOrder(JsonNode e) {
            JsonNode target = dates.get(e.get("date").asString());
            Optional<LocalDate> date = validDate(target);
            if (date.isEmpty()) {
                return;
            }
            JsonNode minEntry = e.has("min") ? dates.get(e.get("min").asString()) : null;
            JsonNode maxEntry = e.has("max") ? dates.get(e.get("max").asString()) : null;
            // A NULL bound is left out of the comparison; an invalid one already has its own problem.
            boolean useMin = minEntry != null && !nullDate(minEntry);
            boolean useMax = maxEntry != null && !nullDate(maxEntry);
            Optional<LocalDate> min = useMin ? validDate(minEntry) : Optional.empty();
            Optional<LocalDate> max = useMax ? validDate(maxEntry) : Optional.empty();
            if ((!useMin && !useMax) || (useMin && min.isEmpty()) || (useMax && max.isEmpty())) {
                return;
            }
            LocalDate d = date.get();
            boolean exclusive = e.path("exclusive").asBoolean(false);
            boolean low = min.isPresent() && (exclusive ? !d.isAfter(min.get()) : d.isBefore(min.get()));
            boolean high = max.isPresent() && (exclusive ? !d.isBefore(max.get()) : d.isAfter(max.get()));
            if (low || high) {
                failEntry(e, "DATE_ORDER", target.get("label").asString(), Map.of(),
                        parts(target, "year", "month", "day").toArray(String[]::new));
            }
        }

        /**
         * A stored NULL date: every part unchanged (so counted as passed) and empty. A changed date
         * is never NULL here, because its own checks reject empty parts.
         */
        boolean nullDate(JsonNode dateEntry) {
            List<String> parts = parts(dateEntry, "year", "month", "day");
            return passed.containsAll(parts) && parts.stream().allMatch(p -> empty(value(p)));
        }

        /** The date of a date entry when its parts passed (or did not change) and form a calendar date. */
        Optional<LocalDate> validDate(JsonNode dateEntry) {
            List<String> parts = parts(dateEntry, "year", "month", "day");
            if (!passed.containsAll(parts)) {
                return Optional.empty();
            }
            String y = strip(value(parts.get(0)));
            String m = strip(value(parts.get(1)));
            String d = strip(value(parts.get(2)));
            if (!digits(y, 4) || !shortNumber(m) || !shortNumber(d)) {
                return Optional.empty();
            }
            try {
                return Optional.of(LocalDate.of(Integer.parseInt(y), Integer.parseInt(m), Integer.parseInt(d)));
            } catch (DateTimeException notADate) {
                return Optional.empty();
            }
        }

        void monthYearNotPast(JsonNode e) {
            String mf = e.get("parts").get("month").asString();
            String yf = e.get("parts").get("year").asString();
            if (!passed.contains(mf) || !passed.contains(yf)) {
                return;
            }
            Integer month = number(value(mf));
            Integer year = number(value(yf));
            if (month == null || year == null) {
                return;
            }
            if (month.equals(number(stored.get(mf))) && year.equals(number(stored.get(yf)))) {
                return;
            }
            if (year * 12 + month < today.getYear() * 12 + today.getMonthValue()) {
                failEntry(e, "MONTH_YEAR_PAST", e.path("label").asString(""), Map.of(), mf, yf);
            }
        }

        void anyEntered(JsonNode e) {
            List<String> fields = new ArrayList<>();
            e.get("fields").forEach(f -> fields.add(f.asString()));
            for (String f : fields) {
                if (entered(fieldEntries.get(f), value(f))) {
                    return;
                }
            }
            failEntry(e, "NO_INPUT", "", Map.of(), fields.toArray(String[]::new));
        }

        void differs(JsonNode e) {
            String f = e.get("field").asString();
            String o = e.get("other").asString();
            JsonNode fieldEntry = fieldEntries.get(f);
            if (!passed.contains(f) || !passed.contains(o) || blank(fieldEntry, value(f))) {
                return;
            }
            String a = trimmed(fieldEntry, value(f));
            String b = trimmed(fieldEntries.get(o), value(o));
            if (e.has("fold")) {
                a = LegacyCase.upper(a);
                b = LegacyCase.upper(b);
            }
            if (a.equals(b)) {
                failEntry(e, "DIFFERS", label(f), Map.of(), f);
            }
        }

        /** False when the field is blank, or its edits stop with the code of its required check. */
        boolean entered(JsonNode fieldEntry, String raw) {
            JsonNode checks = fieldEntry.path("checks");
            boolean required = checks.size() > 0 && "required".equals(checks.get(0).get("kind").asString());
            if (!required) {
                return !blank(fieldEntry, raw);
            }
            Failure failure = firstFailure(fieldEntry, raw);
            return failure == null || !failure.code().equals(new Failure(checks.get(0), "REQUIRED").code());
        }
    }
}
