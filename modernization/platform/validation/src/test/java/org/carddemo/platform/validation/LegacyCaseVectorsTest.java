package org.carddemo.platform.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.carddemo.platform.legacyformat.LegacyCase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * LegacyCase.upper on the vectors its TypeScript mirror ({@code src/main/ts/legacyCase.ts}) runs, so
 * the page and the service upper-case a value the same way.
 */
class LegacyCaseVectorsTest {

    @Test
    void everyVectorGivesTheLegacyUpperCase() throws Exception {
        List<String> differences = new ArrayList<>();
        int count = 0;
        try (InputStream in = LegacyCaseVectorsTest.class.getResourceAsStream("legacy-case-vectors.json")) {
            JsonNode root = JsonMapper.builder().build().readTree(in);
            for (JsonNode v : root.get("vectors")) {
                count++;
                String actual = LegacyCase.upper(v.get("in").asString());
                if (!actual.equals(v.get("out").asString())) {
                    differences.add(v.get("note").asString() + ": " + actual);
                }
            }
        }
        assertThat(count).isGreaterThanOrEqualTo(17);
        assertThat(differences).isEmpty();
    }
}
