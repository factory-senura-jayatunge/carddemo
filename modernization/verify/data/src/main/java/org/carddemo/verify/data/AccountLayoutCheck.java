package org.carddemo.verify.data;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.carddemo.platform.fidelity.FidelityMode;

/**
 * Check 6's own choice of the DEF-ACUP-002 layout of each account record (manifest contract
 * section 5), written apart from the loader so that a defect in the loader's manifest parser,
 * built-in list, override checks or layout lookup cannot pass both: legacy mode reads every
 * record as read; fixed mode uses the supplied manifest, whose SHA-256 must be the file's, else
 * the one built-in manifest, known here only by its hash (the ASCII sample, every record
 * ACUP_UPDATE, no overrides). Messages follow contract 1.5 and never quote an account ID.
 */
final class AccountLayoutCheck {

    /** SHA-256 of {@code app/data/ASCII/acctdata.txt}, the built-in manifest's file. */
    static final String BUILT_IN_SHA256 = "c2a97b6a32dc4a87a7aafdf7f72e6712e560412d30b00c5526cca80fc9dfd260";
    static final int MAX_BYTES = 1 << 20;

    private static final String UPDATE = "ACUP_UPDATE";
    private static final String CVACT01Y = "CVACT01Y";
    private static final Set<String> KEYS = Set.of("schema", "sha256", "default_layout", "other_layout_accounts");
    private static final Pattern KEY_VALUE = Pattern.compile("([A-Za-z0-9_-]+)[ \\t]*=[ \\t]*(.*)");
    private static final Pattern STRING = Pattern.compile("\"([^\"\\\\]*)\"");
    /** No leading zero, as TOML: {@code schema = 01} is refused. */
    private static final Pattern INTEGER = Pattern.compile("[+-]?(0|[1-9][0-9]{0,17})");

    /** A manifest or file the rules refuse; the message is contract 1.5's. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /** The layouts of one file: {@code source} as the load report names it. */
    record Layouts(String source, boolean defaultUpdate, Set<String> others) {

        /** Whether the record with this account ID is in the ACUP_UPDATE layout. */
        boolean update(String acctId) {
            return defaultUpdate != others.contains(acctId);
        }

        /** Whether rows stored as read are warned about: fixed mode only. */
        boolean fixedMode() {
            return !source.equals("ignored");
        }
    }

    private AccountLayoutCheck() {
    }

    /**
     * @param ids the account IDs of the file's records
     * @param manifest the supplied manifest's bytes, at most {@link #MAX_BYTES} + 1 of them, or {@code null}
     */
    static Layouts resolve(FidelityMode mode, byte[] acctdata, Set<String> ids, byte[] manifest) {
        if (mode != FidelityMode.FIXED) {
            return new Layouts("ignored", false, Set.of());
        }
        String hash = sha256(acctdata);
        if (manifest == null) {
            return hash.equals(BUILT_IN_SHA256) ? new Layouts("builtin", true, Set.of())
                    : new Layouts("none", false, Set.of());
        }
        Manifest m = parse(manifest);
        if (!m.sha256().equals(hash)) {
            throw new Refused("layout manifest does not match acctdata.txt");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < m.others().size(); i++) {
            String id = m.others().get(i);
            String where = "layout manifest override " + (i + 1) + ": ";
            if (id.length() != 11 || !id.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new Refused(where + "not 11 digits");
            }
            if (!seen.add(id)) {
                throw new Refused(where + "listed twice");
            }
            if (!ids.contains(id)) {
                throw new Refused(where + "not in acctdata.txt");
            }
        }
        return new Layouts("supplied", m.defaultLayout().equals(UPDATE), Set.copyOf(seen));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record Manifest(String sha256, String defaultLayout, List<String> others) {
    }

    private record Value(int line, Object value) {
    }

    /** The manifest subset of contract 1.2, line by line. */
    static Manifest parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw new Refused("layout manifest is larger than 1 MiB");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new Refused("layout manifest is not UTF-8 text");
        }
        Map<String, Value> values = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);
        String arrayKey = null;
        int arrayLine = 0;
        List<String> items = null;
        boolean needSeparator = false;
        for (int i = 0; i < lines.length; i++) {
            int n = i + 1;
            // CRLF only: a CR not followed by LF is refused like any control character.
            boolean crlf = i < lines.length - 1 && lines[i].endsWith("\r");
            String line = crlf ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            String content = blanksStripped(withoutComment(line, n));
            if (items != null) {
                ArrayState state = items(content, n, items, needSeparator);
                needSeparator = state.needSeparator();
                if (state.closed()) {
                    values.put(arrayKey, new Value(arrayLine, List.copyOf(items)));
                    items = null;
                }
                continue;
            }
            if (content.isEmpty()) {
                continue;
            }
            Matcher kv = KEY_VALUE.matcher(content);
            if (!kv.matches()) {
                throw line(n, "not a key = value line");
            }
            String key = kv.group(1);
            String value = kv.group(2);
            if (values.containsKey(key)) {
                throw line(n, "duplicate key");
            }
            if (value.startsWith("[")) {
                arrayKey = key;
                arrayLine = n;
                items = new ArrayList<>();
                ArrayState state = items(blanksStripped(value.substring(1)), n, items, false);
                needSeparator = state.needSeparator();
                if (state.closed()) {
                    values.put(key, new Value(n, List.copyOf(items)));
                    items = null;
                }
            } else if (STRING.matcher(value).matches()) {
                values.put(key, new Value(n, value.substring(1, value.length() - 1)));
            } else if (INTEGER.matcher(value).matches()) {
                values.put(key, new Value(n, Long.parseLong(value)));
            } else {
                throw line(n, "not a string, integer or array");
            }
        }
        if (items != null) {
            throw line(arrayLine, "array not closed");
        }
        for (Map.Entry<String, Value> e : values.entrySet()) {
            if (!KEYS.contains(e.getKey())) {
                throw line(e.getValue().line(), "unknown key");
            }
        }
        Value schema = required(values, "schema");
        if (!(schema.value() instanceof Long s) || s != 1) {
            throw line(schema.line(), "schema must be 1");
        }
        Value sha = required(values, "sha256");
        if (!(sha.value() instanceof String h) || !h.matches("[0-9a-f]{64}")) {
            throw line(sha.line(), "sha256 must be 64 lowercase hex digits");
        }
        Value layout = required(values, "default_layout");
        if (!(layout.value() instanceof String l) || !(l.equals(UPDATE) || l.equals(CVACT01Y))) {
            throw line(layout.line(), "default_layout must be CVACT01Y or ACUP_UPDATE");
        }
        List<String> others = List.of();
        Value other = values.get("other_layout_accounts");
        if (other != null) {
            if (!(other.value() instanceof List<?> list)) {
                throw line(other.line(), "other_layout_accounts must be an array of strings");
            }
            others = list.stream().map(String.class::cast).toList();
        }
        return new Manifest(h, l, others);
    }

    private record ArrayState(boolean closed, boolean needSeparator) {
    }

    /** Reads array items from {@code s} into {@code items}; strings only, comma-separated, a trailing comma allowed. */
    private static ArrayState items(String s, int n, List<String> items, boolean needSeparator) {
        int pos = 0;
        while (true) {
            while (pos < s.length() && (s.charAt(pos) == ' ' || s.charAt(pos) == '\t')) {
                pos++;
            }
            if (pos == s.length()) {
                return new ArrayState(false, needSeparator);
            }
            char c = s.charAt(pos);
            if (c == ']') {
                if (!blanksStripped(s.substring(pos + 1)).isEmpty()) {
                    throw line(n, "text after the array");
                }
                return new ArrayState(true, false);
            }
            if (needSeparator) {
                if (c != ',') {
                    throw line(n, "array items must be separated by commas");
                }
                needSeparator = false;
                pos++;
                continue;
            }
            if (c != '"') {
                throw line(n, "array items must be strings");
            }
            int end = s.indexOf('"', pos + 1);
            if (end < 0) {
                throw line(n, "string not closed");
            }
            items.add(s.substring(pos + 1, end));
            needSeparator = true;
            pos = end + 1;
        }
    }

    /** The line without its comment; refuses control characters, a bare CR and backslashes. */
    private static String withoutComment(String line, int n) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\r' || (c < 0x20 && c != '\t') || c == 0x7f) {
                throw line(n, "control character");
            }
            if (c == '\\') {
                throw line(n, "escapes are not accepted");
            }
            if (c == '"') {
                inString = !inString;
            } else if (c == '#' && !inString) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /**
     * Without leading and trailing spaces and tabs, the only blanks of the subset: other Unicode
     * whitespace is text, so a line that holds it is refused.
     */
    private static String blanksStripped(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == ' ' || s.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
            end--;
        }
        return s.substring(start, end);
    }

    private static Value required(Map<String, Value> values, String key) {
        Value v = values.get(key);
        if (v == null) {
            throw new Refused("layout manifest: missing " + key);
        }
        return v;
    }

    private static Refused line(int n, String reason) {
        return new Refused("layout manifest line " + n + ": " + reason);
    }
}
