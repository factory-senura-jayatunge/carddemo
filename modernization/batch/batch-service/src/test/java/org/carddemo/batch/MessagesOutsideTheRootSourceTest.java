package org.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Messages outside the root (batch-execution-contract.md section 4.10): no main source of
 * batch-service puts an exception's message or text, or a path, into a log line or an exception
 * message, except the uses in {@link #ALLOWED}, each with the reason it names no path below
 * CARDDEMO_VAR_DIR. The check reads the sources with comments and string literals blanked. A
 * path is a variable, parameter, field or record component declared {@code Path} in the same
 * file, a method of any main source that returns {@code Path}, or a call that derives a path or
 * a name from a path. Each allowed use must still occur exactly as often as listed, so the list
 * cannot outlive the code.
 */
class MessagesOutsideTheRootSourceTest {

    static final Path MAIN = Path.of("src", "main", "java");

    record Allowed(int count, String reason) { }

    record Finding(String key, String where) { }

    static final Map<String, Allowed> ALLOWED = allowed(
            "org/carddemo/batch/cycle/CycleExitDescriptions.java getMessage", 1,
            "a CycleJobException's own CODE: detail, built from codes, IDs and record numbers",
            "org/carddemo/batch/cycle/StageDailyFileTasklet.java getMessage", 2,
            "LegacyFormatException and ColumnCapacityException of the daily file read from memory under its"
                    + " name: they name the name, a line, a field or a column, never a path",
            "org/carddemo/batch/execution/FolderPublication.java path folder", 1,
            "the count of the folder's entries, not a name",
            "org/carddemo/batch/execution/FolderPublication.java path work", 1,
            "the count of the folder's entries, not a name",
            "org/carddemo/batch/execution/PublicationException.java getMessage", 1,
            "its own message, a code with a fixed reason or a detail that names no path",
            "org/carddemo/batch/execution/PublicationFailures.java getMessage", 4,
            "compared with its own fixed messages, never passed on",
            "org/carddemo/batch/execution/SafeFailures.java getMessage", 1,
            "a redacted copy's message, which is only its class description",
            "org/carddemo/batch/internal/CycleRerunCommand.java getMessage", 1,
            "an InternalApiException, whose message is a code and a fixed text",
            "org/carddemo/batch/internal/InternalApiException.java getMessage", 1,
            "its own message, a code and a fixed text",
            "org/carddemo/batch/load/SourceFile.java getMessage", 2,
            "LegacyFormatException of a load file, naming the file name, a line, a field or a position",
            "org/carddemo/batch/load/TableLoadTasklet.java getMessage", 1,
            "ColumnCapacityException, naming a table, a column and a length",
            "org/carddemo/batch/pauth/LoadPendingAuthorizationsTasklet.java getMessage", 2,
            "LegacyFormatException of the unload file, naming the file name, a record and a field",
            "org/carddemo/batch/pauth/PurgeParameters.java getMessage", 1,
            "InvalidJobParametersException of the purge's own checks, naming parameters and dates");

    static final Pattern MESSAGE_CALL = Pattern.compile("\\bget(?:Localized)?Message\\(\\)");
    static final Pattern CATCH = Pattern.compile(
            "catch\\s*\\(\\s*(?:final\\s+)?[\\w.]+(?:\\s*\\|\\s*[\\w.]+)*\\s+(\\w+)\\s*\\)");
    static final Pattern PATH_NAME = Pattern.compile("\\bPath\\s+(\\w+)\\s*[=;,)]");
    static final Pattern PATH_METHOD = Pattern.compile("\\bPath\\s+(\\w+)\\s*\\(");
    static final Pattern MESSAGE_START = Pattern.compile(
            "\\b(?:log|LOG)\\.(?:trace|debug|info|warn|error|log)\\(|\\bnew\\s+\\w*(?:Exception|Error|Failure)\\(");
    static final Pattern PATH_CALL = Pattern.compile(
            "\\.(?:getFileName|getParent|toAbsolutePath|toRealPath|resolve|resolveSibling|relativize)\\(");

    @Test
    void noMainSourcePutsAnExceptionTextOrAPathIntoAMessage() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertThat(files).as("the sources this check reads").hasSizeGreaterThan(50)
                .contains(MAIN.resolve(Path.of("org", "carddemo", "batch", "cycle", "SafeTasklet.java")));
        Map<String, String> sources = new LinkedHashMap<>();
        for (Path file : files) {
            sources.put(MAIN.relativize(file).toString().replace('\\', '/'), Files.readString(file, StandardCharsets.UTF_8));
        }

        List<Finding> findings = findings(sources);

        Map<String, Integer> counts = new TreeMap<>();
        findings.forEach(f -> counts.merge(f.key(), 1, Integer::sum));
        Map<String, Integer> allowed = new TreeMap<>();
        ALLOWED.forEach((key, a) -> allowed.put(key, a.count()));
        assertThat(counts).as("findings: %s", findings.stream().map(Finding::where).collect(Collectors.joining(", ")))
                .isEqualTo(allowed);
    }

    @Test
    void theCheckFindsEachFormAndIgnoresCommentsAndLiterals() {
        String source = """
                class X {
                    private Path work;
                    Path root(long id) { return null; }
                    void a(Path dir) {
                        try {
                            run();
                        } catch (IOException | RuntimeException e) {
                            log.warn("kept {}", dir, e);
                            throw new IllegalStateException("failed: " + e);
                        }
                    }
                    void b() {
                        // log.info("a comment {}", work);
                        log.info("root {} \\" {}", root(1).getFileName());
                        throw new CycleJobException("X", work.toString() + ex().getMessage());
                    }
                    void c() {
                        log.info("{} rows", count(), SafeFailures.describe(new IllegalStateException("x")));
                        throw new IllegalStateException("fixed text, work and root(2) inside a literal");
                    }
                }
                """;

        List<String> keys = findings(Map.of("X.java", source)).stream().map(Finding::key).sorted().toList();

        assertThat(keys).containsExactly("X.java exception text e", "X.java getMessage", "X.java logged exception e",
                "X.java path call .getFileName(", "X.java path dir", "X.java path method root", "X.java path work");
    }

    static List<Finding> findings(Map<String, String> sources) {
        Map<String, String> codes = new LinkedHashMap<>();
        Set<String> methods = new TreeSet<>();
        sources.forEach((name, source) -> {
            String code = strip(source);
            codes.put(name, code);
            methods.addAll(groups(PATH_METHOD, code));
        });
        List<Finding> found = new ArrayList<>();
        codes.forEach((name, code) -> {
            Matcher m = MESSAGE_CALL.matcher(code);
            while (m.find()) {
                found.add(finding(name, "getMessage", code, m.start()));
            }
            Set<String> catches = groups(CATCH, code);
            for (String v : catches) {
                Matcher text = Pattern.compile("\\b" + v + "\\.toString\\(\\)|String\\.valueOf\\(\\s*" + v + "\\s*\\)|\\+\\s*"
                        + v + "\\b(?!\\s*[.(])|\\b(?<!\\.)" + v + "\\s*\\+").matcher(code);
                while (text.find()) {
                    found.add(finding(name, "exception text " + v, code, text.start()));
                }
            }
            Set<String> names = groups(PATH_NAME, code);
            Matcher start = MESSAGE_START.matcher(code);
            while (start.find()) {
                String span = span(code, start.end() - 1);
                int at = start.start();
                for (String v : names) {
                    if (Pattern.compile("(?<![.\\w])" + v + "\\b(?!\\s*\\()").matcher(span).find()) {
                        found.add(finding(name, "path " + v, code, at));
                    }
                }
                for (String v : methods) {
                    if (Pattern.compile("\\b" + v + "\\s*\\(").matcher(span).find()) {
                        found.add(finding(name, "path method " + v, code, at));
                    }
                }
                Matcher call = PATH_CALL.matcher(span);
                if (call.find()) {
                    found.add(finding(name, "path call " + call.group(), code, at));
                }
                if (start.group().startsWith("log") || start.group().startsWith("LOG")) {
                    for (String v : catches) {
                        if (Pattern.compile(",\\s*" + v + "\\s*[,)]").matcher(span).find()) {
                            found.add(finding(name, "logged exception " + v, code, at));
                        }
                    }
                }
            }
        });
        return found;
    }

    private static Finding finding(String name, String kind, String code, int position) {
        long line = code.substring(0, position).chars().filter(c -> c == '\n').count() + 1;
        return new Finding(name + " " + kind, name + ":" + line + " " + kind);
    }

    private static Set<String> groups(Pattern pattern, String code) {
        Set<String> found = new TreeSet<>();
        Matcher m = pattern.matcher(code);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    /** The text from the opening parenthesis at {@code open} to the one that closes it. */
    private static String span(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        return code.substring(open);
    }

    /** The source with comments removed and every string, text block and character literal emptied; lines are kept. */
    static String strip(String src) {
        StringBuilder out = new StringBuilder();
        int n = src.length();
        int i = 0;
        while (i < n) {
            if (src.startsWith("\"\"\"", i)) {
                int close = src.indexOf("\"\"\"", i + 3);
                int end = close < 0 ? n : close + 3;
                out.append("\"\"").append(newlines(src, i, end));
                i = end;
                continue;
            }
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c) {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                out.append(c == '"' ? "\"\"" : "' '");
                i = j + 1;
                continue;
            }
            if (src.startsWith("//", i)) {
                int eol = src.indexOf('\n', i);
                i = eol < 0 ? n : eol;
                continue;
            }
            if (src.startsWith("/*", i)) {
                int close = src.indexOf("*/", i + 2);
                int end = close < 0 ? n : close + 2;
                out.append(newlines(src, i, end));
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String newlines(String src, int from, int to) {
        return "\n".repeat((int) src.substring(from, to).chars().filter(c -> c == '\n').count());
    }

    private static Map<String, Allowed> allowed(Object... entries) {
        Map<String, Allowed> allowed = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 3) {
            allowed.put((String) entries[i], new Allowed((Integer) entries[i + 1], (String) entries[i + 2]));
        }
        return allowed;
    }
}
