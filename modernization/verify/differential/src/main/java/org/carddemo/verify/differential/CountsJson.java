package org.carddemo.verify.differential;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/** {@code counts.json}: one flat object of names to whole numbers or plain text, in insertion order. */
final class CountsJson {

    private CountsJson() {
    }

    static byte[] of(Map<String, Object> counts) {
        String body = counts.entrySet().stream()
                .map(e -> "  \"" + e.getKey() + "\": " + value(e.getValue()))
                .collect(Collectors.joining(",\n", "{\n", "\n}\n"));
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static String value(Object v) {
        if (v instanceof Long || v instanceof Integer) {
            return v.toString();
        }
        String s = String.valueOf(v);
        if (!s.matches("[A-Za-z0-9_.:/ -]*")) {
            throw new IllegalArgumentException("counts.json holds only plain text values");
        }
        return "\"" + s + "\"";
    }
}
