package org.carddemo.platform.legacyformat.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.carddemo.platform.legacyformat.record.FieldSpec;
import org.carddemo.platform.legacyformat.record.FieldType;
import org.carddemo.platform.legacyformat.record.RecordLayout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Each layout against its copybook, as {@code tests/oracle.py} parses it from
 * {@code app/cpy} ({@code golden/copybook-layouts.json}, written by
 * {@code src/test/golden/gen_layouts.py}). The oracle lists named fields only;
 * every gap between them must be a FILLER of the Java layout.
 */
class LayoutsTest {

    private static final Map<String, RecordLayout> BY_COPYBOOK = Map.ofEntries(
            Map.entry("CVACT01Y", Layouts.ACCOUNT),
            Map.entry("CVACT02Y", Layouts.CARD),
            Map.entry("CVACT03Y", Layouts.CARD_XREF),
            Map.entry("CVCUS01Y", Layouts.CUSTOMER),
            Map.entry("CVTRA01Y", Layouts.TCAT_BAL),
            Map.entry("CVTRA02Y", Layouts.DISC_GROUP),
            Map.entry("CVTRA03Y", Layouts.TRAN_TYPE),
            Map.entry("CVTRA04Y", Layouts.TRAN_CATEGORY),
            Map.entry("CVTRA05Y", Layouts.TRANSACTION),
            Map.entry("CVTRA06Y", Layouts.DAILY_TRAN),
            Map.entry("CSUSR01Y", Layouts.USER_SECURITY));

    record Expected(String name, int offset, int length, boolean numeric, boolean signed, int scale) {
    }

    static Stream<Arguments> copybooks() throws IOException {
        JsonNode root;
        try (InputStream in = LayoutsTest.class.getResourceAsStream("/golden/copybook-layouts.json")) {
            root = JsonMapper.builder().build().readTree(in);
        }
        assertThat(root.propertyNames()).containsExactlyInAnyOrderElementsOf(BY_COPYBOOK.keySet());
        List<Arguments> out = new ArrayList<>();
        for (String copybook : root.propertyNames()) {
            JsonNode cb = root.get(copybook);
            List<Expected> fields = new ArrayList<>();
            for (JsonNode f : cb.get("fields")) {
                fields.add(new Expected(f.get("name").asString(), f.get("offset").asInt(), f.get("length").asInt(),
                        f.get("numeric").asBoolean(), f.get("signed").asBoolean(), f.get("scale").asInt()));
            }
            out.add(Arguments.of(copybook, cb.get("length").asInt(), fields));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copybooks")
    void layoutMatchesCopybook(String copybook, int length, List<Expected> expected) {
        RecordLayout layout = BY_COPYBOOK.get(copybook);
        assertThat(layout.length()).isEqualTo(length);

        List<Expected> actualNamed = new ArrayList<>();
        int covered = 0;
        for (FieldSpec f : layout.fields()) {
            assertThat(f.offset()).as("%s starts where the previous field ends", f.name()).isEqualTo(covered);
            covered = f.end();
            boolean inCopybook = expected.stream().anyMatch(e -> e.name().equals(f.name()));
            if (f.type() == FieldType.FILLER && !inCopybook) {
                continue;
            }
            boolean numeric = f.type() != FieldType.TEXT && f.type() != FieldType.FILLER;
            actualNamed.add(new Expected(f.name(), f.offset(), f.length(), numeric,
                    f.pic() != null && f.pic().signed(), f.pic() == null ? 0 : f.pic().scale()));
        }
        assertThat(covered).isEqualTo(length);
        assertThat(actualNamed).containsExactlyElementsOf(expected);
    }
}
