package org.carddemo.platform.fidelity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the mode of each item from the decision ledger {@code ledger/fidelity.toml}. Two forms are
 * accepted:
 *
 * <pre>
 * [items]
 * "DEF-BILL-010" = { mode = "fixed", decision = "D-28", ... }
 *
 * [switch."DEF-BILL-010"]
 * value = "fixed"
 * </pre>
 *
 * An entry with {@code legacy_mode = false} or {@code code_switch = false} records a decision
 * without a code branch and is skipped. Only the subset of TOML these forms use is parsed:
 * one entry per line, inline tables on one line, as TOML requires.
 */
public final class FidelityLedger {

    public static final String FILE_NAME = "fidelity.toml";

    private static final Pattern TABLE = Pattern.compile("^\\[\\s*([^\\[\\]]+?)\\s*]$");
    private static final Pattern SWITCH_TABLE = Pattern.compile("^switch\\.\"([^\"]+)\"$");
    private static final Pattern ENTRY = Pattern.compile("^(\"[^\"]+\"|[A-Za-z0-9_-]+)\\s*=\\s*(.*)$");

    private FidelityLedger() {
    }

    /** The ledger under {@code configuredDir}, or the nearest {@code modernization/ledger} above {@code start}. */
    public static Optional<Path> locate(String configuredDir, Path start) {
        if (configuredDir != null && !configuredDir.isBlank()) {
            Path file = Path.of(configuredDir).resolve(FILE_NAME);
            return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
        }
        for (Path p = start.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            Path file = p.resolve("modernization").resolve("ledger").resolve(FILE_NAME);
            if (Files.isRegularFile(file)) {
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    public static Map<String, FidelityMode> read(Path file) {
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8), file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    static Map<String, FidelityMode> parse(List<String> lines, String source) {
        Map<String, FidelityMode> modes = new LinkedHashMap<>();
        String table = "";
        String switchId = null;
        Map<String, String> switchValues = new LinkedHashMap<>();
        for (int n = 0; n < lines.size(); n++) {
            String line = stripComment(lines.get(n)).trim();
            if (line.isEmpty()) {
                continue;
            }
            String where = source + ":" + (n + 1);
            Matcher t = TABLE.matcher(line);
            if (t.matches()) {
                addSwitch(modes, switchId, switchValues, where);
                table = t.group(1);
                Matcher s = SWITCH_TABLE.matcher(table);
                switchId = s.matches() ? s.group(1) : null;
                switchValues = new LinkedHashMap<>();
                continue;
            }
            Matcher e = ENTRY.matcher(line);
            if (!e.matches()) {
                continue;
            }
            String key = unquote(e.group(1));
            String value = e.group(2).trim();
            if (switchId != null) {
                switchValues.put(key, scalar(value));
            } else if (table.equals("items") && value.startsWith("{")) {
                addItem(modes, key, inlineTable(value, where), where);
            }
        }
        addSwitch(modes, switchId, switchValues, where(source, lines.size()));
        return modes;
    }

    private static String where(String source, int line) {
        return source + ":" + line;
    }

    private static void addSwitch(Map<String, FidelityMode> modes, String id, Map<String, String> values, String where) {
        if (id == null) {
            return;
        }
        Map<String, String> entry = new LinkedHashMap<>(values);
        if (entry.containsKey("value") && !entry.containsKey("mode")) {
            entry.put("mode", entry.get("value"));
        }
        addItem(modes, id, entry, where);
    }

    private static void addItem(Map<String, FidelityMode> modes, String id, Map<String, String> entry, String where) {
        if ("false".equals(entry.get("legacy_mode")) || "false".equals(entry.get("code_switch"))) {
            return;
        }
        String mode = entry.get("mode");
        if (mode == null) {
            return;
        }
        try {
            modes.put(SpecItemIds.require(id), FidelityMode.parse(mode));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(where + ": " + ex.getMessage());
        }
    }

    /** Key to raw scalar value of a one-line inline table; strings are unquoted. */
    static Map<String, String> inlineTable(String text, String where) {
        Map<String, String> result = new LinkedHashMap<>();
        int i = text.indexOf('{') + 1;
        int end = text.lastIndexOf('}');
        if (end < i) {
            throw new IllegalStateException(where + ": inline table without a closing brace");
        }
        while (i < end) {
            while (i < end && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) {
                i++;
            }
            if (i >= end) {
                break;
            }
            int eq = text.indexOf('=', i);
            if (eq < 0 || eq > end) {
                throw new IllegalStateException(where + ": expected key = value in " + text);
            }
            String key = unquote(text.substring(i, eq).trim());
            i = eq + 1;
            while (i < end && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            StringBuilder value = new StringBuilder();
            if (i < end && text.charAt(i) == '"') {
                i++;
                while (i < end && text.charAt(i) != '"') {
                    if (text.charAt(i) == '\\' && i + 1 < end) {
                        i++;
                    }
                    value.append(text.charAt(i++));
                }
                i++;
            } else {
                int depth = 0;
                while (i < end && (depth > 0 || text.charAt(i) != ',')) {
                    char c = text.charAt(i++);
                    depth += (c == '[' || c == '{') ? 1 : (c == ']' || c == '}') ? -1 : 0;
                    value.append(c);
                }
            }
            result.put(key, value.toString().trim());
        }
        return result;
    }

    private static String scalar(String value) {
        return value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2
                ? value.substring(1, value.length() - 1) : value;
    }

    private static String unquote(String key) {
        return key.startsWith("\"") && key.endsWith("\"") && key.length() >= 2 ? key.substring(1, key.length() - 1) : key;
    }

    /** Removes a # comment that is not inside a string. */
    private static String stripComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && inString) {
                i++;
            } else if (c == '"') {
                inString = !inString;
            } else if (c == '#' && !inString) {
                return line.substring(0, i);
            }
        }
        return line;
    }
}
