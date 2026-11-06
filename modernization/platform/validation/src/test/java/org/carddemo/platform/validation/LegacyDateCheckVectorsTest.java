package org.carddemo.platform.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every vector the reference COBOL (CSUTLDTC with the CEEDAYS stand-in) produced, see
 * {@code src/test/cobol/gen_csutldtc_vectors.py}. The TypeScript mirror runs the same file.
 */
@SpecItem({"BR-DTCK-001", "BR-DTCK-002", "BR-DTCK-003", "BR-DTCK-006", "FLOW-DTCK-001", "NUM-DTCK-001",
        "NUM-DTCK-002", "ERR-DTCK-001"})
class LegacyDateCheckVectorsTest {

    record Vector(String date, String picture, int severity, int messageNumber, String line) {
    }

    static List<Vector> vectors() throws Exception {
        try (InputStream in = LegacyDateCheckVectorsTest.class.getResourceAsStream("csutldtc-vectors.json")) {
            JsonNode root = JsonMapper.builder().build().readTree(in);
            List<Vector> out = new ArrayList<>();
            for (JsonNode v : root.get("vectors")) {
                out.add(new Vector(v.get("date").asString(), v.get("picture").asString(), v.get("severity").asInt(),
                        v.get("messageNumber").asInt(), v.get("line").asString()));
            }
            return out;
        }
    }

    @Test
    void everyVectorGivesTheCobolResultLine() throws Exception {
        List<Vector> vectors = vectors();
        assertThat(vectors).hasSizeGreaterThan(800);
        List<String> differences = new ArrayList<>();
        for (Vector v : vectors) {
            DateCheckResult r = LegacyDateCheck.check(v.date(), v.picture());
            if (r.severity() != v.severity() || r.messageNumber() != v.messageNumber() || !r.line().equals(v.line())) {
                differences.add("[" + v.date() + "|" + v.picture() + "] cobol=" + v.line() + " java=" + r.line());
            }
            assertThat(r.line()).hasSize(80);
            assertThat(r.valid()).isEqualTo(v.severity() == 0);
        }
        assertThat(differences).isEmpty();
    }
}
