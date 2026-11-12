package org.carddemo.batch.data;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A DEF-ACUP-002 layout manifest, version 1: the SHA-256 of the account file it describes, the
 * layout of its records, and the account IDs that use the other layout. The format and its
 * messages are fixed by build-data's contract "DEF-ACUP-002 v4: layout manifest schema and export
 * contract v2":
 *
 * <pre>
 * schema = 1
 * sha256 = "64 lowercase hex digits"
 * default_layout = "CVACT01Y" | "ACUP_UPDATE"
 * other_layout_accounts = ["00000000001", ...]   # optional
 * </pre>
 *
 * <p>Only this subset of TOML is read, strictly: top-level {@code key = value} pairs, {@code #}
 * comments, basic strings without escapes, decimal integers, and arrays of strings that may span
 * lines. Everything else is refused, naming the manifest line. No message quotes a value: an
 * override is an account ID (D-NFR-04), so it is named by its 1-based position.
 */
public record AccountLayoutManifest(String sha256, AccountLayout defaultLayout, List<String> otherLayoutAccounts) {

    public static final int MAX_BYTES = 1 << 20;

    private static final String SCHEMA = "schema";
    private static final String SHA256 = "sha256";
    private static final String DEFAULT_LAYOUT = "default_layout";
    private static final String OTHER = "other_layout_accounts";
    private static final List<String> KEYS = List.of(SCHEMA, SHA256, DEFAULT_LAYOUT, OTHER);

    public AccountLayoutManifest {
        otherLayoutAccounts = List.copyOf(otherLayoutAccounts);
    }

    /** The layout of the record with this account ID. */
    public AccountLayout layoutOf(String acctId) {
        return otherLayoutAccounts.contains(acctId) ? defaultLayout.other() : defaultLayout;
    }

    /**
     * Each override must be 11 digits, listed once and present in the file; the first failure in
     * array order is reported by its position.
     */
    public void requireOverridesIn(Set<String> fileAccountIds) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < otherLayoutAccounts.size(); i++) {
            String id = otherLayoutAccounts.get(i);
            String where = "layout manifest override " + (i + 1) + ": ";
            if (!id.matches("[0-9]{11}")) {
                throw new AccountLayoutException(where + "not 11 digits");
            }
            if (!seen.add(id)) {
                throw new AccountLayoutException(where + "listed twice");
            }
            if (!fileAccountIds.contains(id)) {
                throw new AccountLayoutException(where + "not in acctdata.txt");
            }
        }
    }

    public static AccountLayoutManifest parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw new AccountLayoutException("layout manifest is larger than 1 MiB");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new AccountLayoutException("layout manifest is not UTF-8 text");
        }
        Map<String, Entry> entries = new Parser(text).document();
        for (String key : entries.keySet()) {
            if (!KEYS.contains(key)) {
                throw entries.get(key).error("unknown key");
            }
        }
        Entry schema = required(entries, SCHEMA);
        if (!(schema.value() instanceof Long n) || n != 1) {
            throw schema.error("schema must be 1");
        }
        Entry sha = required(entries, SHA256);
        if (!(sha.value() instanceof String s) || !s.matches("[0-9a-f]{64}")) {
            throw sha.error("sha256 must be 64 lowercase hex digits");
        }
        Entry layout = required(entries, DEFAULT_LAYOUT);
        AccountLayout defaultLayout = layout.value() instanceof String l && KEYS_OF_LAYOUTS.contains(l)
                ? AccountLayout.valueOf(l) : null;
        if (defaultLayout == null) {
            throw layout.error("default_layout must be CVACT01Y or ACUP_UPDATE");
        }
        List<String> others = List.of();
        Entry other = entries.get(OTHER);
        if (other != null) {
            if (!(other.value() instanceof List<?> list)) {
                throw other.error("other_layout_accounts must be an array of strings");
            }
            others = list.stream().map(String.class::cast).toList();
        }
        return new AccountLayoutManifest((String) sha.value(), defaultLayout, others);
    }

    private static final List<String> KEYS_OF_LAYOUTS = List.of("CVACT01Y", "ACUP_UPDATE");

    private static Entry required(Map<String, Entry> entries, String key) {
        Entry e = entries.get(key);
        if (e == null) {
            throw new AccountLayoutException("layout manifest: missing " + key);
        }
        return e;
    }

    private record Entry(int line, Object value) {
        AccountLayoutException error(String reason) {
            return Parser.error(line, reason);
        }
    }

    /** The accepted subset, character by character; {@code line} is 1-based. */
    private static final class Parser {
        private final String s;
        private int pos;
        private int line = 1;

        Parser(String s) {
            this.s = s;
        }

        static AccountLayoutException error(int line, String reason) {
            return new AccountLayoutException("layout manifest line " + line + ": " + reason);
        }

        private AccountLayoutException error(String reason) {
            return error(line, reason);
        }

        Map<String, Entry> document() {
            Map<String, Entry> entries = new LinkedHashMap<>();
            while (true) {
                skipBlanks();
                if (atEnd()) {
                    return entries;
                }
                char c = s.charAt(pos);
                if (c == '\n' || c == '\r') {
                    endOfLine();
                    continue;
                }
                if (c == '#') {
                    skipComment();
                    continue;
                }
                if (c == '[') {
                    throw error("tables are not supported");
                }
                int keyLine = line;
                String key = bareKey();
                skipBlanks();
                if (!atEnd() && s.charAt(pos) == '.') {
                    throw error("dotted keys are not supported");
                }
                expect('=', "a key must be followed by =");
                skipBlanks();
                Object value = value();
                if (entries.containsKey(key)) {
                    throw error(keyLine, "key given twice");
                }
                entries.put(key, new Entry(keyLine, value));
                skipBlanks();
                if (!atEnd() && s.charAt(pos) == '#') {
                    skipComment();
                }
                if (!atEnd()) {
                    if (s.charAt(pos) != '\n' && s.charAt(pos) != '\r') {
                        throw error("only a comment may follow a value");
                    }
                    endOfLine();
                }
            }
        }

        private String bareKey() {
            int start = pos;
            while (!atEnd() && isKeyChar(s.charAt(pos))) {
                pos++;
            }
            if (pos == start) {
                throw error(s.charAt(pos) == '"' || s.charAt(pos) == '\'' ? "quoted keys are not supported"
                        : "a line must hold key = value");
            }
            return s.substring(start, pos);
        }

        private static boolean isKeyChar(char c) {
            return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-';
        }

        private Object value() {
            if (atEnd()) {
                throw error("a value is missing");
            }
            char c = s.charAt(pos);
            if (c == '"') {
                return string();
            }
            if (c == '[') {
                return array();
            }
            if (c >= '0' && c <= '9' || c == '+' || c == '-') {
                return integer();
            }
            throw error("only strings, integers and arrays of strings are supported");
        }

        private String string() {
            if (s.startsWith("\"\"\"", pos)) {
                throw error("multi-line strings are not supported");
            }
            pos++;
            int start = pos;
            while (true) {
                if (atEnd() || s.charAt(pos) == '\n' || s.charAt(pos) == '\r') {
                    throw error("a string is not closed on its line");
                }
                char c = s.charAt(pos);
                if (c == '"') {
                    String value = s.substring(start, pos);
                    pos++;
                    return value;
                }
                if (c == '\\') {
                    throw error("escapes are not supported");
                }
                if (c < 0x20 && c != '\t' || c == 0x7F) {
                    throw error("a string holds a control character");
                }
                pos++;
            }
        }

        private Long integer() {
            int start = pos;
            if (s.charAt(pos) == '+' || s.charAt(pos) == '-') {
                pos++;
            }
            int digits = pos;
            while (!atEnd() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
            }
            String text = s.substring(start, pos);
            String unsigned = s.substring(digits, pos);
            if (unsigned.isEmpty() || unsigned.length() > 1 && unsigned.charAt(0) == '0' || unsigned.length() > 18) {
                throw error("not a supported integer");
            }
            if (!atEnd() && !isValueEnd(s.charAt(pos))) {
                throw error("not a supported integer");
            }
            return Long.parseLong(text);
        }

        private boolean isValueEnd(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '#' || c == ',' || c == ']';
        }

        private List<String> array() {
            pos++;
            List<String> items = new ArrayList<>();
            while (true) {
                skipBlanksCommentsAndLines();
                if (atEnd()) {
                    throw error("an array is not closed");
                }
                if (s.charAt(pos) == ']') {
                    pos++;
                    return items;
                }
                if (s.charAt(pos) != '"') {
                    throw error("an array holds only strings");
                }
                items.add(string());
                skipBlanksCommentsAndLines();
                if (atEnd()) {
                    throw error("an array is not closed");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c != ']') {
                    throw error("array items are separated by commas");
                }
            }
        }

        private void skipBlanksCommentsAndLines() {
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t') {
                    pos++;
                } else if (c == '\n' || c == '\r') {
                    endOfLine();
                } else if (c == '#') {
                    skipComment();
                } else {
                    return;
                }
            }
        }

        private void skipBlanks() {
            while (!atEnd() && (s.charAt(pos) == ' ' || s.charAt(pos) == '\t')) {
                pos++;
            }
        }

        private void skipComment() {
            while (!atEnd() && s.charAt(pos) != '\n' && s.charAt(pos) != '\r') {
                char c = s.charAt(pos);
                if (c < 0x20 && c != '\t' || c == 0x7F) {
                    throw error("a comment holds a control character");
                }
                pos++;
            }
        }

        private void endOfLine() {
            if (s.charAt(pos) == '\r') {
                if (pos + 1 >= s.length() || s.charAt(pos + 1) != '\n') {
                    throw error("a carriage return must be followed by a line feed");
                }
                pos++;
            }
            pos++;
            line++;
        }

        private void expect(char c, String reason) {
            if (atEnd() || s.charAt(pos) != c) {
                throw error(reason);
            }
            pos++;
        }

        private boolean atEnd() {
            return pos >= s.length();
        }
    }
}
