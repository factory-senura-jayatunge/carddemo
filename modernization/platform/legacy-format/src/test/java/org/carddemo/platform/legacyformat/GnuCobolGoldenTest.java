package org.carddemo.platform.legacyformat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every case of {@code golden/gnucobol-codecs.txt}: what GnuCOBOL 3.2 with
 * {@code -std=ibm -fsign=EBCDIC} stores for a MOVE or COMPUTE into a zoned,
 * packed, binary or edited item. The file is produced by
 * {@code src/test/golden/gen_golden.py}, which compiles and runs a COBOL
 * program; the expectations do not come from this library.
 */
class GnuCobolGoldenTest {

    private static final Pic WIDE_SOURCE = Pic.parse("S9(14)V9(4)");
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    record Case(int line, String verb, String target, String picture, String value, String expected) {
        @Override
        public String toString() {
            return verb + " " + value + " -> " + target + " " + picture;
        }
    }

    static Stream<Arguments> cases() throws IOException {
        Map<String, String> pictures = new LinkedHashMap<>();
        List<Case> cases = new ArrayList<>();
        try (InputStream in = GnuCobolGoldenTest.class.getResourceAsStream("/golden/gnucobol-codecs.txt");
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                n++;
                if (line.startsWith("# ") && line.matches("# [A-Z]{1,2}\\d{2}(V\\d)? = .*")) {
                    int eq = line.indexOf(" = ");
                    pictures.put(line.substring(2, eq), line.substring(eq + 3).replaceAll(" COMP(-3)?$", ""));
                    continue;
                }
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }
                String[] p = line.split("\\|", 4);
                cases.add(new Case(n, p[0], p[1], pictures.get(p[1]), p[2], p[3]));
            }
        }
        assertThat(cases).hasSizeGreaterThan(3000);
        assertThat(cases).allSatisfy(c -> assertThat(c.picture()).as("picture of " + c.target()).isNotNull());
        return cases.stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matchesGnuCobol(Case c) {
        BigDecimal v = new BigDecimal(c.value());
        String actual = switch (c.verb()) {
            case "MOVE" -> store(c, v, WIDE_SOURCE);
            case "MOVE-S12" -> store(c, v, Pic.parse("S9(10)V99"));
            case "MOVE-S11" -> store(c, v, Pic.parse("S9(09)V99"));
            case "COMPUTE" -> {
                assertThat(c.target()).startsWith("Z");
                yield Zoned.encodeCompute(v, Pic.parse(c.picture()));
            }
            default -> throw new IllegalArgumentException("unknown verb on line " + c.line());
        };
        assertThat(actual).as("line %d", c.line()).isEqualTo(c.expected());
    }

    private static String store(Case c, BigDecimal v, Pic source) {
        // The value is first held in the source item, as the generated program does.
        String sourceDigits = Zoned.encodeMove(v, source);
        BigDecimal held = Zoned.decode(sourceDigits, source);
        boolean heldNegativeZero = Zoned.isNegativeZero(sourceDigits);
        return switch (c.target().charAt(0)) {
            case 'Z' -> heldNegativeZero && Pic.parse(c.picture()).signed()
                    ? Zoned.encodeNegativeZero(Pic.parse(c.picture()))
                    : Zoned.encodeMove(held, Pic.parse(c.picture()));
            case 'P' -> HEX.formatHex(heldNegativeZero ? negativeZeroPacked(c) : Packed.encodeMove(held, Pic.parse(c.picture())));
            case 'B' -> HEX.formatHex(Binary.encodeMove(held, Pic.parse(c.picture())));
            case 'E' -> NumericEdit.of(c.picture()).format(held, source);
            default -> throw new IllegalArgumentException("unknown target on line " + c.line());
        };
    }

    private static byte[] negativeZeroPacked(Case c) {
        byte[] b = Packed.encodeMove(BigDecimal.ZERO, Pic.parse(c.picture()));
        if (Pic.parse(c.picture()).signed()) {
            b[b.length - 1] = (byte) ((b[b.length - 1] & 0xF0) | 0x0D);
        }
        return b;
    }
}
