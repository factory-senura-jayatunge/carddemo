package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The mode's outcome as the driver sees it: exit status, stdout and stderr. Each code of the set
 * has its own case, and the production wiring is shown to stop at {@code work-folder-unsupported}.
 */
class RefusalModeTest {

    @TempDir
    Path tmp;

    private Path var;
    private Path dest;
    private RefusalStubs.Reads reads;
    private RefusalStubs.Environment env;
    private RefusalStubs.Walk walk;
    private RefusalRun.WorkFolder step3;
    private RefusalOptions options;

    /** One run's outcome: exit status, stdout and stderr. */
    private record Outcome(int exit, String out, String err) {
    }

    @BeforeEach
    void setUp() throws IOException {
        var = Files.createDirectory(tmp.resolve("var"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        dest = tmp.resolve("refusal");
        reset();
    }

    private void reset() {
        reads = new RefusalStubs.Reads();
        env = new RefusalStubs.Environment(var);
        walk = new RefusalStubs.Walk();
        step3 = walk;
        options = RefusalStubs.options(dest);
    }

    /** The helper enabled through the constructor, as only tests do. */
    private Outcome enabled() {
        return capture(err -> RefusalMode.report(new RefusalRun(reads, env, true, step3), options, err));
    }

    private Outcome production() {
        return capture(err -> RefusalMode.run(options, reads, env, err));
    }

    private interface Mode {
        int run(PrintStream err);
    }

    private static Outcome capture(Mode mode) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stdout = System.out;
        int exit;
        try (PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setOut(o);
            exit = mode.run(e);
        } finally {
            System.setOut(stdout);
        }
        return new Outcome(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private void assertRefused(Outcome outcome, String code) {
        assertThat(outcome).isEqualTo(new Outcome(1, "", "verify-differential: FAILED " + code
                + System.lineSeparator()));
        assertThat(outcome.err()).doesNotContain(RefusalStubs.CANARY).doesNotContain(tmp.toString());
        assertThat(dest.resolve(RefusalJson.FILE)).doesNotExist();
    }

    @Test
    void aCompletedModeWritesRefusalJsonAndPrintsNothing() {
        assertThat(enabled()).isEqualTo(new Outcome(0, "", ""));
        assertThat(dest.resolve(RefusalJson.FILE)).exists();
    }

    @Test
    void eachCodeOfTheSetHasItsCase() {
        Map<String, Consumer<RefusalModeTest>> cases = new LinkedHashMap<>();
        cases.put("execution-refused", t -> t.reads.execution = n -> Optional.empty());
        cases.put("execution-not-final", t -> t.reads.execution = n -> Optional.of(new RefusalReads.Execution(900, true,
                "STARTED", RefusalStubs.END, "FAILED", "unrecognised")));
        cases.put("execution-changed", t -> t.walk.hook = () -> t.reads.execution = n -> n >= 3 ? Optional.empty()
                : Optional.of(RefusalStubs.FAILED));
        cases.put("database-read-failed", t -> t.reads.failing.put("cycle",
                new DataAccessResourceFailureException(RefusalStubs.CANARY)));
        cases.put("instance-read-refused", t -> t.reads.instance = () -> org.carddemo.platform.outputroot.OutputRoot
                .Instance.of(RefusalStubs.CANARY, RefusalStubs.CANARY));
        cases.put("var-dir-refused", t -> t.env.values.put(org.carddemo.platform.outputroot.OutputRoot.VAR_DIR_VARIABLE,
                RefusalStubs.CANARY));
        cases.put("destination-refused", t -> t.options = RefusalStubs.options(t.tmp.resolve(RefusalStubs.CANARY)
                .resolve("refusal")));
        for (String code : RefusalCodes.HELPER) {
            cases.put(code, t -> t.walk.failure = new RefusalException(code));
        }
        // The real step 3, enabled through its constructor, with no interpreter setting: no process starts.
        cases.put("work-folder-helper-failed", t -> t.step3 = new WorkFolderHelper(t.env, ProcessUid::current));
        cases.put("refusal-internal-error", t -> t.reads.failing.put("execution",
                new IllegalStateException(RefusalStubs.CANARY)));

        // refusal-write-failed needs a user other than root, so it has its own test below; an
        // assumption here would skip every case after it.
        assertThat(Stream.concat(cases.keySet().stream(), Stream.of("refusal-write-failed")))
                .containsExactlyInAnyOrderElementsOf(RefusalCodes.ALL);
        for (Map.Entry<String, Consumer<RefusalModeTest>> c : cases.entrySet()) {
            reset();
            c.getValue().accept(this);
            assertRefused(enabled(), c.getKey());
        }
    }

    @Test
    void refusalWriteFailedHasItsOwnCase() throws IOException {
        // Root can write into a read-only folder, so a root run must fail here, not skip.
        OptionalLong uid = ProcessUid.current();
        assertTrue(uid.isPresent() && uid.getAsLong() != 0,
                "this test must run as a user other than root, with a known user ID");
        Files.createDirectory(dest, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-x------")));
        assertRefused(enabled(), "refusal-write-failed");
    }

    @Test
    void anUnexpectedThrowableIsOnlyTheInternalErrorLine() {
        // AR-2: no message, cause or trace of the throwable leaves, whatever it carries.
        List<Runnable> throwing = List.of(
                () -> walk.hook = () -> {
                    throw new RuntimeException(RefusalStubs.CANARY, new IOException(RefusalStubs.CANARY));
                },
                () -> walk.hook = () -> {
                    throw new AssertionError(RefusalStubs.CANARY);
                },
                () -> walk.hook = () -> {
                    throw new StackOverflowError(RefusalStubs.CANARY);
                },
                () -> reads.instance = () -> {
                    throw new NullPointerException(RefusalStubs.CANARY);
                });
        for (Runnable setup : throwing) {
            reset();
            setup.run();
            assertRefused(enabled(), "refusal-internal-error");
        }
    }

    @Test
    void theProductionWiringStopsAtUnsupportedAfterStepsOneAndTwo() {
        env.values.put(WorkFolderHelper.INTERPRETER_VARIABLE, "/" + RefusalStubs.CANARY + "/python3");
        long children = ProcessHandle.current().children().count();

        assertRefused(production(), "work-folder-unsupported");

        assertThat(RefusalMode.HELPER_ENABLED).isFalse();
        assertThat(env.asked).as("the interpreter setting is never read").doesNotContain(
                WorkFolderHelper.INTERPRETER_VARIABLE);
        assertThat(reads.calls).containsExactly("execution", "cycle", "above", "runs", "instance", "execution");
        assertThat(ProcessHandle.current().children().count()).as("no process is started").isEqualTo(children);
        assertThat(dest).doesNotExist();
    }

    @Test
    void theCodesTheDisabledProductionPathCanReach() throws IOException {
        Map<String, Runnable> paths = new LinkedHashMap<>();
        paths.put("valid", () -> { });
        paths.put("no execution", () -> reads.execution = n -> Optional.empty());
        paths.put("not final", () -> reads.execution = n -> Optional.of(new RefusalReads.Execution(900, true,
                "STARTED", RefusalStubs.END, "FAILED", "unrecognised")));
        paths.put("read failure", () -> reads.failing.put("runs", new DataAccessResourceFailureException("x")));
        paths.put("instance", () -> reads.instance = () -> org.carddemo.platform.outputroot.OutputRoot.Instance.of("db",
                "x"));
        paths.put("var dir", () -> env.values.clear());
        paths.put("internal", () -> reads.failing.put("cycle", new IllegalStateException("x")));
        paths.put("bad destination", () -> options = RefusalStubs.options(tmp.resolve("missing").resolve("refusal")));
        paths.put("change after step 2", () -> reads.execution = n -> n >= 3 ? Optional.empty()
                : Optional.of(RefusalStubs.FAILED));
        Set<String> reached = new LinkedHashSet<>();
        for (Runnable path : paths.values()) {
            reset();
            path.run();
            Outcome outcome = production();
            assertThat(outcome.exit()).isEqualTo(1);
            reached.add(outcome.err().substring("verify-differential: FAILED ".length()).strip());
        }
        // destination-refused and refusal-write-failed are unreachable: the destination is prepared only at step 5.
        assertThat(reached).containsExactlyInAnyOrder("work-folder-unsupported", "execution-refused",
                "execution-not-final", "database-read-failed", "instance-read-refused", "var-dir-refused",
                "refusal-internal-error");
        assertThat(tmp.resolve("missing")).doesNotExist();
    }

    @Test
    void theGateIsOneCompileTimeConstantAtTheProductionWiring() throws IOException {
        Path main = Path.of("src/main/java/org/carddemo/verify/differential");
        assertThat(Files.readString(main.resolve("RefusalMode.java")))
                .contains("    static final boolean HELPER_ENABLED = false;\n")
                .contains("new RefusalRun(reads, environment, HELPER_ENABLED,");
        try (Stream<Path> sources = Files.list(main)) {
            for (Path source : sources.toList()) {
                String name = source.getFileName().toString();
                if (!name.equals("RefusalMode.java")) {
                    assertThat(Files.readString(source)).as(name).doesNotContain("new RefusalRun(")
                            .doesNotContain("HELPER_ENABLED =");
                }
            }
        }
    }

    @Test
    void aBadCommandLineIsUsageWithExitTwo() {
        Outcome outcome = capture(err -> RefusalMode.main(new String[] {"--refusal", "--job", "CREASTMT",
            "--cycle-id", RefusalStubs.CANARY}, err));
        assertThat(outcome.exit()).isEqualTo(2);
        assertThat(outcome.out()).isEmpty();
        assertThat(outcome.err()).startsWith("verify-differential: ").contains(RefusalOptions.USAGE)
                .doesNotContain(RefusalStubs.CANARY).doesNotContain("FAILED");
    }

    @Test
    void theInterpreterUidSourceIsReal() {
        OptionalLong uid = ProcessUid.current();
        assertThat(uid).isPresent();
    }

    /** WARN and ERROR through SLF4J and JUL, a stack trace and a plain line, each carrying the canary. */
    private static void noise(org.slf4j.Logger slf4j, java.util.logging.Logger jul) {
        IllegalStateException cause = new IllegalStateException(RefusalStubs.CANARY);
        slf4j.warn(RefusalStubs.CANARY, cause);
        slf4j.error(RefusalStubs.CANARY, cause);
        jul.log(Level.WARNING, RefusalStubs.CANARY, cause);
        jul.log(Level.SEVERE, RefusalStubs.CANARY, cause);
        cause.printStackTrace();
        System.err.println(RefusalStubs.CANARY);
    }

    /** A connection whose every call makes noise and then throws; {@code calls} records the calls. */
    private static Connection noisyConnection(Runnable noise, List<String> calls) {
        return (Connection) Proxy.newProxyInstance(RefusalModeTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == arguments[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "connection";
                        };
                    }
                    calls.add(method.getName());
                    noise.run();
                    throw new SQLException(RefusalStubs.CANARY);
                });
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    @Test
    void fromTheConnectionOnStderrIsOnlyTheFailedLine() {
        // C-1: Logback, JUL and plain stderr writes while connecting, reading and closing never pass.
        PrintStream original = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        org.slf4j.Logger slf4j = LoggerFactory.getLogger(RefusalModeTest.class);
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger(RefusalModeTest.class.getName());
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        java.util.logging.Logger late = java.util.logging.Logger.getLogger(RefusalModeTest.class.getName()
                + ".late");
        System.setErr(capture);
        // Created before the mode, both keep the capture stream, as the JDK's root handler keeps the real stderr.
        ConsoleHandler onRoot = new ConsoleHandler();
        ConsoleHandler onNamed = new ConsoleHandler();
        Level rootLevel = onRoot.getLevel();
        Level namedLevel = onNamed.getLevel();
        root.addHandler(onRoot);
        jul.addHandler(onNamed);
        List<String> calls = new ArrayList<>();
        try {
            slf4j.warn("BEFORE-SLF4J");
            jul.warning("BEFORE-JUL");
            String before = bytes.toString(StandardCharsets.UTF_8);
            assertThat(before).as("Logback writes to the capture stream").contains("BEFORE-SLF4J");
            assertThat(count(before, "BEFORE-JUL")).as("both JUL handlers write to the capture stream")
                    .isGreaterThanOrEqualTo(2);
            bytes.reset();

            Runnable inMode = () -> {
                noise(slf4j, jul);
                ConsoleHandler created = new ConsoleHandler();
                late.addHandler(created);
                try {
                    late.severe(RefusalStubs.CANARY);
                } finally {
                    late.removeHandler(created);
                }
            };
            int refused = RefusalMode.quietly(capture, out -> RefusalMode.connected(() -> {
                inMode.run();
                throw new SQLException(RefusalStubs.CANARY);
            }, options, out));
            assertThat(refused).isEqualTo(1);
            assertThat(bytes.toString(StandardCharsets.UTF_8))
                    .isEqualTo("verify-differential: FAILED database-read-failed" + System.lineSeparator());
            assertThat(System.err).isSameAs(capture);
            bytes.reset();

            int read = RefusalMode.quietly(capture, out -> RefusalMode.connected(() -> {
                inMode.run();
                return noisyConnection(inMode, calls);
            }, options, out));
            String err = bytes.toString(StandardCharsets.UTF_8);
            assertThat(read).isEqualTo(1);
            assertThat(err).endsWith(System.lineSeparator()).doesNotContain(RefusalStubs.CANARY);
            assertThat(err.strip()).startsWith("verify-differential: FAILED ").doesNotContain("\n");
            assertThat(RefusalCodes.ALL).contains(err.strip().substring("verify-differential: FAILED ".length()));
            assertThat(calls).as("the connection is closed inside the mode").contains("close");
            assertThat(System.err).isSameAs(capture);
            assertThat(onRoot.getLevel()).isEqualTo(rootLevel);
            assertThat(onNamed.getLevel()).isEqualTo(namedLevel);
            assertThat(dest).doesNotExist();

            bytes.reset();
            slf4j.warn("AFTER-SLF4J");
            jul.warning("AFTER-JUL");
            String after = bytes.toString(StandardCharsets.UTF_8);
            assertThat(after).as("Logback writes to stderr again").contains("AFTER-SLF4J");
            assertThat(count(after, "AFTER-JUL")).as("both JUL handlers write again").isGreaterThanOrEqualTo(2);
        } finally {
            root.removeHandler(onRoot);
            jul.removeHandler(onNamed);
            System.setErr(original);
        }
    }

    @Test
    void aFailedCloseChangesNoResult() {
        // S-1: whatever closing throws, an error included, nothing leaves close.
        List<Throwable> failures = List.of(new SQLException(RefusalStubs.CANARY),
                new IllegalStateException(RefusalStubs.CANARY), new AssertionError(RefusalStubs.CANARY),
                new StackOverflowError(RefusalStubs.CANARY));
        for (Throwable failure : failures) {
            Connection connection = (Connection) Proxy.newProxyInstance(RefusalModeTest.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                        throw failure;
                    });
            assertThatCode(() -> RefusalMode.close(connection)).doesNotThrowAnyException();
        }
    }
}
