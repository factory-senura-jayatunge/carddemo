package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.carddemo.platform.outputroot.OutputRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step 3's Java side with a stub interpreter: a {@code /bin/sh} script that records what it was
 * given and answers as a case needs. No Python runs here; the helper itself has its own tests.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WorkFolderHelperTest {

    private static final Path HELPER_FILE = Path.of(
            "src/main/resources/org/carddemo/verify/differential/work_folder_helper.py");
    private static final String FACTS = "facts final=true work=empty";
    /** Variables a POSIX shell sets for itself when started with an empty environment. */
    private static final Set<String> SHELL_OWN = Set.of("PWD", "OLDPWD", "SHLVL", "_", "PATH");

    @TempDir
    Path tmp;

    private Path rec;
    private Path var;
    private long uid;

    @BeforeEach
    void setUp() throws IOException {
        tmp = tmp.toRealPath();
        rec = Files.createDirectory(tmp.resolve("rec"));
        var = Files.createDirectory(tmp.resolve(RefusalStubs.CANARY));
        uid = ProcessUid.current().orElseThrow();
    }

    private Path stub(String body) throws IOException {
        Path script = tmp.resolve("python-stub");
        Files.deleteIfExists(script);
        Files.writeString(script, "#!/bin/sh\n" + body + "\n", StandardCharsets.US_ASCII);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    private WorkFolderHelper helper(Path interpreter) {
        return new WorkFolderHelper(Map.of(WorkFolderHelper.INTERPRETER_VARIABLE, interpreter.toString())::get,
                () -> OptionalLong.of(uid));
    }

    private RefusalRun.Facts walk(Path interpreter) {
        return helper(interpreter).walk(var, RefusalStubs.instance(), RefusalStubs.CYCLE, RefusalStubs.ATTEMPT,
                RefusalStubs.E);
    }

    private static void assertHelperFailed(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RefusalException.class,
                e -> assertThat(e.code()).isEqualTo("work-folder-helper-failed"));
    }

    private String recorded(String name) throws IOException {
        return Files.readString(rec.resolve(name), StandardCharsets.UTF_8);
    }

    // The process (PH-4)

    @Test
    void theCommandIsExactlyPh4sForm() {
        assertThat(WorkFolderHelper.command("/opt/python3", "SOURCE")).containsExactly("/opt/python3", "-I", "-B", "-S",
                "-c", "SOURCE");
    }

    @Test
    void theBuilderPassesNothingButPipes() {
        ProcessBuilder b = WorkFolderHelper.builder(List.of("/opt/python3", "-c", "x"));
        assertThat(b.environment()).isEmpty();
        assertThat(b.directory()).isEqualTo(new File("/"));
        assertThat(b.redirectInput()).isEqualTo(ProcessBuilder.Redirect.PIPE);
        assertThat(b.redirectOutput()).isEqualTo(ProcessBuilder.Redirect.PIPE);
        assertThat(b.redirectError()).isEqualTo(ProcessBuilder.Redirect.DISCARD);
        assertThat(b.redirectErrorStream()).isFalse();
        assertThat(b.command()).containsExactly("/opt/python3", "-c", "x");
    }

    @Test
    void theStubSeesPh4sArgvAnEmptyEnvironmentSlashAndItsInputBeforeAnswering() throws Exception {
        String r = rec.toString();
        Path python = stub(String.join("\n",
                "/bin/cat > '" + r + "/stdin'",
                "printf '%s\\n' \"$@\" > '" + r + "/args'",
                "/usr/bin/env > '" + r + "/env'",
                "/bin/pwd > '" + r + "/cwd'",
                "/bin/ps -o pgid= -p $$ > '" + r + "/pgid'",
                "/bin/ps -o pgid= -p $PPID > '" + r + "/parent-pgid'",
                "printf '" + FACTS + "\\n'"));

        assertThat(walk(python)).isEqualTo(new RefusalRun.Facts(true, "empty"));

        String source = new String(Files.readAllBytes(HELPER_FILE), StandardCharsets.UTF_8);
        assertThat(recorded("args")).isEqualTo("-I\n-B\n-S\n-c\n" + source + "\n")
                .doesNotContain(RefusalStubs.CANARY).doesNotContain(var.toString());
        assertThat(Files.readAllBytes(rec.resolve("stdin"))).isEqualTo(WorkFolderHelper.input(var,
                RefusalStubs.instance(), RefusalStubs.CYCLE, RefusalStubs.ATTEMPT, RefusalStubs.E));
        assertThat(recorded("cwd")).isEqualTo("/\n");
        // D-7: the helper stays in the JVM's process group; nothing calls setsid or setpgid.
        assertThat(recorded("pgid").strip()).isEqualTo(recorded("parent-pgid").strip()).isNotEmpty();
        for (String line : recorded("env").split("\n")) {
            if (!line.isEmpty()) {
                String key = line.substring(0, Math.max(0, line.indexOf('=')));
                assertThat(SHELL_OWN).as("the stub inherited %s", key).contains(key);
            }
        }
    }

    @Test
    void stderrIsDiscardedAndNeverBlocks() throws Exception {
        Path python = stub("/bin/cat > /dev/null\n/usr/bin/head -c 1048576 /dev/zero >&2\nprintf '" + FACTS + "\\n'");
        assertThat(walk(python)).isEqualTo(new RefusalRun.Facts(true, "empty"));
    }

    @Test
    void theHelperIsStartedOnceWithNoRetry() throws Exception {
        Path python = stub("/bin/cat > /dev/null\necho start >> '" + rec + "/starts'\nexit 1");
        assertHelperFailed(() -> walk(python));
        assertThat(recorded("starts")).isEqualTo("start\n");
    }

    // The outcomes (PH-9, AR-3)

    @Test
    void theEightFactLinesWithExitZero() {
        for (boolean exists : new boolean[] {false, true}) {
            for (String work : List.of("absent", "empty", "nonempty", "not-a-directory")) {
                byte[] line = ("facts final=" + exists + " work=" + work + "\n").getBytes(StandardCharsets.US_ASCII);
                assertThat(WorkFolderHelper.parse(0, line)).isEqualTo(new RefusalRun.Facts(exists, work));
                assertHelperFailed(() -> WorkFolderHelper.parse(3, line));
            }
        }
    }

    @Test
    void theHelpersFourCodesWithExitThreeAndNoOther() {
        for (String code : RefusalCodes.ALL) {
            byte[] line = ("refuse " + code + "\n").getBytes(StandardCharsets.US_ASCII);
            if (RefusalCodes.HELPER.contains(code)) {
                assertThatThrownBy(() -> WorkFolderHelper.parse(3, line)).isInstanceOfSatisfying(RefusalException.class,
                        e -> assertThat(e.code()).isEqualTo(code));
            } else {
                assertHelperFailed(() -> WorkFolderHelper.parse(3, line));
            }
            assertHelperFailed(() -> WorkFolderHelper.parse(0, line));
        }
        assertHelperFailed(() -> WorkFolderHelper.parse(3, "refuse unknown-code\n".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void everyOtherOutcomeIsAHelperFailure() {
        String[] malformed = {"", "\n", FACTS, FACTS + "\n\n", FACTS + "\r\n", FACTS + " \n", " " + FACTS + "\n",
            FACTS + "\n" + FACTS + "\n", "facts final=TRUE work=empty\n", "facts final=true work=full\n",
            "facts work=empty final=true\n", "refuse work-folder-input", "refuse  work-folder-input\n",
            "refuse work-folder-input\nrefuse work-folder-input\n", "unknown\n"};
        for (String out : malformed) {
            for (int exit : new int[] {0, 3}) {
                assertHelperFailed(() -> WorkFolderHelper.parse(exit, out.getBytes(StandardCharsets.US_ASCII)));
            }
        }
        for (int exit : new int[] {1, 2, 4, 127, 137, -1}) {
            assertHelperFailed(() -> WorkFolderHelper.parse(exit, (FACTS + "\n").getBytes(StandardCharsets.US_ASCII)));
        }
    }

    @Test
    void malformedProcessOutcomes() throws Exception {
        String[] bodies = {
            "/bin/cat > /dev/null\nprintf '" + FACTS + "\\n'\nexit 1",
            "/bin/cat > /dev/null\nprintf '" + FACTS + "\\n'\nexit 2",
            "/bin/cat > /dev/null\nprintf '" + FACTS + "\\n" + FACTS + "\\n'",
            "/bin/cat > /dev/null\nprintf 'unknown\\n'",
            "/bin/cat > /dev/null\nprintf '" + FACTS + "\\n'\nexit 3",
            "/bin/cat > /dev/null\nprintf '" + FACTS + "'",
            "/bin/cat > /dev/null\nprintf '" + FACTS + "\\n' >&2",
            "/bin/cat > /dev/null\nprintf 'refuse execution-changed\\n'\nexit 3",
        };
        for (String body : bodies) {
            Path python = stub(body);
            assertHelperFailed(() -> walk(python));
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aStdoutFloodStopsAtTheCap() throws Exception {
        Path python = stub("exec /usr/bin/yes");
        assertHelperFailed(() -> walk(python));
    }

    @Test
    void readingStopsAtTheCap() throws IOException {
        assertThat(WorkFolderHelper.readCapped(new ByteArrayInputStream(new byte[255]))).hasSize(255);
        assertHelperFailed(() -> {
            try {
                WorkFolderHelper.readCapped(new ByteArrayInputStream(new byte[256]));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return 'x';
            }
        };
        assertHelperFailed(() -> {
            try {
                WorkFolderHelper.readCapped(endless);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void aStartFailureIsAHelperFailureWithoutTheProgramName() {
        String absent = tmp.resolve(RefusalStubs.CANARY + "-absent").toString();
        assertThatThrownBy(() -> helper(Path.of(absent)).run(List.of(absent, "-c", "x"), new byte[0]))
                .isInstanceOfSatisfying(RefusalException.class, e -> {
                    assertThat(e.code()).isEqualTo("work-folder-helper-failed");
                    assertThat(e.getMessage()).doesNotContain(RefusalStubs.CANARY);
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void anInterruptIsAHelperFailureAndKeepsTheFlag() throws Exception {
        Path python = stub("/bin/cat > /dev/null\nexec 1>&-\nexec /bin/sleep 20");
        Thread.currentThread().interrupt();
        try {
            assertHelperFailed(() -> walk(python));
        } finally {
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    // The interpreter value (PH-3)

    @Test
    void theInterpreterMustBeAPrivateOrRootOwnedExecutableFile() throws Exception {
        Path python = stub("exit 0");
        assertThat(helper(python).interpreter()).isEqualTo(python.toString());
        Path root = Stream.of("/bin/cat", "/usr/bin/env", "/bin/ls")
                .map(Path::of)
                .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> owner(p) == 0L)
                .findFirst().orElse(null);
        assertTrue(root != null, "this test needs a root-owned system program that is not a link");
        assertThat(helper(root).interpreter()).isEqualTo(root.toString());
    }

    private static long owner(Path p) {
        try {
            return Integer.toUnsignedLong((Integer) Files.getAttribute(p, "unix:uid", LinkOption.NOFOLLOW_LINKS));
        } catch (IOException e) {
            return -1;
        }
    }

    @Test
    void aBadInterpreterValueRefusesBeforeAnyProcess() throws Exception {
        Path python = stub("echo start >> '" + rec + "/starts'\nexit 0");
        List<String> values = new ArrayList<>();
        values.add("");
        values.add("python3");
        values.add("bin/" + RefusalStubs.CANARY);
        values.add(tmp.resolve("absent").toString());
        values.add(tmp.toString());
        values.add(Files.createSymbolicLink(tmp.resolve("linked"), python).toString());
        for (String value : values) {
            WorkFolderHelper h = new WorkFolderHelper(Map.of(WorkFolderHelper.INTERPRETER_VARIABLE, value)::get,
                    () -> OptionalLong.of(uid));
            assertHelperFailed(h::interpreter);
        }
        assertHelperFailed(new WorkFolderHelper(name -> null, () -> OptionalLong.of(uid))::interpreter);
        for (String mode : new String[] {"rwx-w----", "rwx----w-", "rwxrwxrwx", "rw-------"}) {
            Files.setPosixFilePermissions(python, PosixFilePermissions.fromString(mode));
            assertHelperFailed(helper(python)::interpreter);
        }
        Files.setPosixFilePermissions(python, PosixFilePermissions.fromString("rwx------"));
        // A foreign owner, stubbed in-process: chown needs root, so the process's user ID is changed instead.
        assertHelperFailed(new WorkFolderHelper(Map.of(WorkFolderHelper.INTERPRETER_VARIABLE, python.toString())::get,
                () -> OptionalLong.of(uid + 1))::interpreter);
        assertHelperFailed(new WorkFolderHelper(Map.of(WorkFolderHelper.INTERPRETER_VARIABLE, python.toString())::get,
                OptionalLong::empty)::interpreter);
        assertHelperFailed(() -> walk(tmp.resolve("absent")));
        assertThat(rec.resolve("starts")).doesNotExist();
    }

    @Test
    void failuresCarryNoPathOrValue() throws Exception {
        Path python = stub("exit 0");
        Files.setPosixFilePermissions(python, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThatThrownBy(helper(python)::interpreter).isInstanceOfSatisfying(RefusalException.class, e -> {
            assertThat(e.getMessage()).isEqualTo("work-folder-helper-failed");
            assertThat(e.getCause()).isNull();
            assertThat(e.getStackTrace()).isEmpty();
        });
    }

    // The inputs (PH-5)

    @Test
    void theInputIsSixKeysWithFixedNames() {
        byte[] input = WorkFolderHelper.input(Path.of("/var/carddemo"), RefusalStubs.instance(), 7, 2, 41);
        assertThat(new String(input, StandardCharsets.US_ASCII)).isEqualTo("{\"var_dir\":\"/var/carddemo\","
                + "\"database\":\"carddemo_\\u00e9\",\"instance_id\":\"0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0\","
                + "\"cycle_id\":7,\"attempt\":2,\"job_execution_id\":41}");
        OutputRoot.Instance upper = OutputRoot.Instance.of(RefusalStubs.DATABASE, RefusalStubs.INSTANCE_ID.toUpperCase());
        assertThat(WorkFolderHelper.input(Path.of("/v"), upper, 1, 1, 1)).asString(StandardCharsets.US_ASCII)
                .contains("\"instance_id\":\"" + RefusalStubs.INSTANCE_ID + "\"");
    }

    @Test
    void theJsonEscapesQuotesBackslashesControlsAndNonAscii() {
        assertThat(WorkFolderHelper.quoted("a\"b\\c\n\t\u0001\u001f\u007f\u00e9\uD83D\uDE00/"))
                .isEqualTo("\"a\\\"b\\\\c\\u000a\\u0009\\u0001\\u001f\\u007f\\u00e9\\ud83d\\ude00/\"");
        for (String lone : new String[] {"\uD800", "a\uDC00", "\uD800a", "\uDC00\uD800"}) {
            assertHelperFailed(() -> WorkFolderHelper.quoted(lone));
        }
    }

    // One file, one vector (PH-2, PH-12)

    @Test
    void theResourceIsTheTestedFile() throws Exception {
        byte[] resource;
        try (InputStream in = WorkFolderHelper.class.getResourceAsStream(WorkFolderHelper.RESOURCE)) {
            assertThat(in).isNotNull();
            resource = in.readAllBytes();
        }
        byte[] file = Files.readAllBytes(HELPER_FILE);
        assertThat(sha256(resource)).isEqualTo(sha256(file));
        assertThat(WorkFolderHelper.source()).isEqualTo(new String(file, StandardCharsets.UTF_8));
    }

    @Test
    void theModuleHasOneHelperFileAndNoBytecodeNextToIt() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src"))) {
            List<Path> python = files.filter(p -> p.toString().endsWith(".py") || p.toString().endsWith(".pyc")
                    || p.getFileName().toString().equals("__pycache__")).toList();
            assertThat(python).containsExactly(HELPER_FILE);
        }
        assertThat(Files.readString(Path.of("tests/test_work_folder_helper.py")))
                .contains("/ \"src\" / \"main\" / \"resources\" / \"org\" / \"carddemo\" / \"verify\" / \"differential\"")
                .contains("/ \"work_folder_helper.py\"");
    }

    @Test
    void theMarkerVectorSharedWithTheHelpersTest() {
        byte[] vector = ("carddemo-output-root 1\ndatabase=Y2FyZGRlbW9fw6k\n"
                + "instance=0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0\n").getBytes(StandardCharsets.US_ASCII);
        assertThat(OutputRoot.Instance.of("carddemo_\u00e9", "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0").marker())
                .isEqualTo(vector);
        assertThat(OutputRoot.Instance.of("carddemo_\u00e9", "0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0").marker())
                .isEqualTo(vector);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
