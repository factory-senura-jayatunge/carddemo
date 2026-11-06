package org.carddemo.platform.validation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The validation rule table: the forms and their field edits ({@code rules.json}) and the lookup
 * lists from CSLKPCDY ({@code lookups.json}). The TypeScript mirror ({@code src/main/ts/rules.ts})
 * reads the same two files, so the web app and the services run the same edits (D-ARCH-14).
 */
public final class RuleTable {

    public static final String RULES = "rules.json";
    public static final String LOOKUPS = "lookups.json";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static volatile RuleTable standard;

    private final Map<String, List<String>> lists;
    private final Map<String, FormRules> forms;

    private RuleTable(Map<String, List<String>> lists, Map<String, FormRules> forms) {
        this.lists = lists;
        this.forms = forms;
    }

    /** The table shipped in this library. */
    public static RuleTable standard() {
        RuleTable table = standard;
        if (table == null) {
            synchronized (RuleTable.class) {
                table = standard;
                if (table == null) {
                    table = of(resource(RULES), resource(LOOKUPS));
                    standard = table;
                }
            }
        }
        return table;
    }

    /** A table from the text of a rules file and a lookups file. */
    public static RuleTable of(String rulesJson, String lookupsJson) {
        JsonNode lookups = MAPPER.readTree(lookupsJson);
        Map<String, List<String>> lists = new LinkedHashMap<>();
        lookups.required("lists").properties().forEach(e -> {
            List<String> values = new ArrayList<>();
            e.getValue().forEach(v -> values.add(v.asString()));
            lists.put(e.getKey(), List.copyOf(values));
        });
        JsonNode rules = MAPPER.readTree(rulesJson);
        if (rules.path("schema").asInt() != 1) {
            throw new IllegalArgumentException("rules: schema 1 expected");
        }
        Map<String, FormRules> forms = new LinkedHashMap<>();
        rules.path("forms").properties().forEach(e -> forms.put(e.getKey(), FormRules.parse(e.getKey(), e.getValue(), lists)));
        return new RuleTable(Collections.unmodifiableMap(lists), Collections.unmodifiableMap(forms));
    }

    public FormRules form(String id) {
        FormRules form = forms.get(id);
        if (form == null) {
            throw new IllegalArgumentException("no form " + id + " in the rule table; forms: " + forms.keySet());
        }
        return form;
    }

    public Set<String> formIds() {
        return forms.keySet();
    }

    /** A lookup list, for example {@code usStateCodes}. */
    public List<String> list(String name) {
        return Objects.requireNonNull(lists.get(name), () -> "no lookup list " + name);
    }

    static String resource(String name) {
        try (InputStream in = RuleTable.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
