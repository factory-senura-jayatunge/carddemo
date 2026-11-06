package org.carddemo.platform.fidelity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * Writes one JSON line per test that carries {@link SpecItem} to
 * {@code <carddemo.specitems.dir>/<test class>.jsonl} (default {@code target/spec-items}):
 * {@code {"class","method","displayName","items","status","mode"}}. {@code mode} is the mode an
 * enclosing extension recorded with {@link #recordMode} (a class run in both modes), else the
 * {@link FidelityMode} argument of a parameterized invocation, else null. A malformed item ID
 * makes the test error before it runs. The directory is the configuration parameter or system
 * property {@code carddemo.specitems.dir}.
 */
public class SpecItemExtension implements BeforeEachCallback, TestWatcher {

    static final String DIR_PROPERTY = "carddemo.specitems.dir";

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(SpecItemExtension.class);

    /** Records the fidelity mode the tests below {@code context} run in. */
    public static void recordMode(ExtensionContext context, FidelityMode mode) {
        context.getStore(NAMESPACE).put("mode", mode);
    }

    private static final Set<Path> STARTED = ConcurrentHashMap.newKeySet();

    @Override
    public void beforeEach(ExtensionContext context) {
        items(context);
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        record(context, "SUCCESSFUL");
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        record(context, "FAILED");
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        record(context, "ABORTED");
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        record(context, "DISABLED");
    }

    static Set<String> items(ExtensionContext context) {
        Set<String> items = new LinkedHashSet<>();
        for (Class<?> c = context.getRequiredTestClass(); c != null; c = c.getEnclosingClass()) {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                collect(k, items);
            }
        }
        context.getTestMethod().ifPresent(m -> collect(m, items));
        return items;
    }

    private static void collect(AnnotatedElement element, Set<String> items) {
        SpecItem annotation = element.getAnnotation(SpecItem.class);
        if (annotation != null) {
            for (String id : annotation.value()) {
                items.add(SpecItemIds.require(id));
            }
        }
    }

    private static void record(ExtensionContext context, String status) {
        Set<String> items;
        try {
            items = items(context);
        } catch (IllegalArgumentException e) {
            // The test already errored in beforeEach; the bad ID must not reach the matrix.
            return;
        }
        if (items.isEmpty()) {
            return;
        }
        Class<?> testClass = context.getRequiredTestClass();
        String line = "{" + field("class", testClass.getName())
                + "," + field("method", context.getTestMethod().map(Method::getName).orElse(null))
                + "," + field("displayName", context.getDisplayName())
                + ",\"items\":" + array(List.copyOf(items))
                + "," + field("status", status)
                + "," + field("mode", mode(context).map(FidelityMode::name).orElse(null))
                + "}\n";
        write(directory(context).resolve(testClass.getName() + ".jsonl"), line);
    }

    private static Optional<FidelityMode> mode(ExtensionContext context) {
        FidelityMode recorded = context.getStore(NAMESPACE).get("mode", FidelityMode.class);
        if (recorded != null) {
            return Optional.of(recorded);
        }
        try {
            return ParameterizedMode.of(context);
        } catch (LinkageError e) {
            return Optional.empty();
        }
    }

    private static Path directory(ExtensionContext context) {
        return Path.of(context.getConfigurationParameter(DIR_PROPERTY)
                .orElseGet(() -> System.getProperty(DIR_PROPERTY, "target/spec-items")));
    }

    private static synchronized void write(Path file, String line) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            // The first write of a run replaces the file, so a rerun without clean leaves no stale lines.
            StandardOpenOption mode = STARTED.add(file.toAbsolutePath())
                    ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND;
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, mode);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String field(String name, String value) {
        return "\"" + name + "\":" + (value == null ? "null" : quote(value));
    }

    private static String array(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(quote(values.get(i)));
        }
        return sb.append(']').toString();
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
