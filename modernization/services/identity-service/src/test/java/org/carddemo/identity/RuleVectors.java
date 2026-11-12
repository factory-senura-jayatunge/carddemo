package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The shipped-form cases of platform/validation's rule vectors, read from the module directory. */
public final class RuleVectors {

    private static final Path DIR = Path.of("../../platform/validation/src/test/resources/org/carddemo/platform/validation");
    private static final List<String> FILES = List.of("rule-vectors.json", "lone-surrogate-vectors.json");

    private RuleVectors() {
    }

    /** The shipped cases of {@code form}, from both vector files. */
    public static List<JsonNode> shippedCases(String form) {
        JsonMapper mapper = JsonMapper.builder().build();
        List<JsonNode> cases = new ArrayList<>();
        for (String name : FILES) {
            Path file = DIR.resolve(name);
            assertThat(file).as("the shared rule vectors").exists();
            try {
                mapper.readTree(Files.readString(file)).get("shippedCases").forEach(c -> {
                    if (c.get("form").asString().equals(form)) {
                        cases.add(c);
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return cases;
    }

    public static Map<String, String> input(JsonNode c) {
        Map<String, String> out = new LinkedHashMap<>();
        c.get("input").properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
        return out;
    }
}
