package org.carddemo.verify.differential;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.carddemo.verify.differential.RefusalCodes.WORK_FOLDER_HELPER_FAILED;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import org.carddemo.platform.outputroot.OutputRoot;

/**
 * Step 3 of the refusal mode: the data-owned Python helper ({@value #RESOURCE}, a resource of
 * this package) does the folder walk, and this class starts it, feeds it and reads its one line.
 * Java reads no folder here.
 *
 * <ul>
 * <li>The interpreter is {@value #INTERPRETER_VARIABLE}: an absolute path to a regular file, not
 * a link, executable, owned by root or this process's user, and not writable by group or others.
 * <li>The command is exactly {@code [<interpreter>, -I, -B, -S, -c, <the resource text>]}, with an
 * empty environment, {@code /} as working folder, stdin and stdout as pipes and stderr discarded.
 * It is started once, with no retry, and stays in this JVM's process group.
 * <li>stdin gets one JSON object and is closed; then at most {@value #STDOUT_CAP} bytes of stdout
 * are read and the exit is awaited. There is no time limit here: the driver bounds the whole
 * step. Only after a decided failure, a still running helper is killed through its own
 * {@link Process} and awaited for {@value #CLEANUP_SECONDS} s.
 * <li>Exit 0 with one fact line, or exit 3 with one {@code refuse} line naming one of the helper's
 * four codes, is accepted; anything else is {@code work-folder-helper-failed}.
 * </ul>
 * No message, log or exception carries the interpreter path, the inputs, the helper's output,
 * its exit status or PID, or an exception text.
 */
final class WorkFolderHelper implements RefusalRun.WorkFolder {

    static final String RESOURCE = "work_folder_helper.py";
    static final String INTERPRETER_VARIABLE = "CARDDEMO_DIFF_PYTHON";
    static final int STDOUT_CAP = 256;
    static final long CLEANUP_SECONDS = 10;
    static final int FACTS_EXIT = 0;
    static final int REFUSE_EXIT = 3;

    private record FactLine(byte[] bytes, RefusalRun.Facts facts) {
    }

    private static final List<FactLine> FACT_LINES = factLines();

    private final Function<String, String> environment;
    private final Supplier<OptionalLong> uid;

    /**
     * @param environment the process environment, asked only for {@value #INTERPRETER_VARIABLE}
     * @param uid this process's user ID ({@link ProcessUid#current()})
     */
    WorkFolderHelper(Function<String, String> environment, Supplier<OptionalLong> uid) {
        this.environment = environment;
        this.uid = uid;
    }

    @Override
    public RefusalRun.Facts walk(Path varDir, OutputRoot.Instance instance, long cycleId, int attempt,
            long executionId) {
        String interpreter = interpreter();
        String source = source();
        byte[] input = input(varDir, instance, cycleId, attempt, executionId);
        return run(command(interpreter, source), input);
    }

    static List<String> command(String interpreter, String source) {
        return List.of(interpreter, "-I", "-B", "-S", "-c", source);
    }

    /** The process as it is started: nothing inherited but the JVM's process group. */
    static ProcessBuilder builder(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.directory(new File("/"));
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder;
    }

    RefusalRun.Facts run(List<String> command, byte[] input) {
        Process process;
        try {
            process = builder(command).start();
        } catch (IOException | RuntimeException e) {
            // The IOException names the program, so neither it nor its cause goes anywhere.
            throw failed();
        }
        try {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(input);
            }
            byte[] stdout;
            try (InputStream out = process.getInputStream()) {
                stdout = readCapped(out);
            }
            int exit = process.waitFor();
            return parse(exit, stdout);
        } catch (IOException e) {
            throw failed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(CLEANUP_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** Up to the cap; reaching it before the end of the stream is a failure. */
    static byte[] readCapped(InputStream in) throws IOException {
        byte[] buffer = new byte[STDOUT_CAP];
        int length = 0;
        while (length < STDOUT_CAP) {
            int n = in.read(buffer, length, STDOUT_CAP - length);
            if (n < 0) {
                return Arrays.copyOf(buffer, length);
            }
            length += n;
        }
        throw failed();
    }

    /** The two accepted outcomes; every other exit status or output is a helper failure. */
    static RefusalRun.Facts parse(int exit, byte[] stdout) {
        if (exit == FACTS_EXIT) {
            for (FactLine line : FACT_LINES) {
                if (Arrays.equals(line.bytes(), stdout)) {
                    return line.facts();
                }
            }
        } else if (exit == REFUSE_EXIT) {
            for (String code : RefusalCodes.HELPER) {
                if (Arrays.equals(("refuse " + code + "\n").getBytes(StandardCharsets.US_ASCII), stdout)) {
                    throw new RefusalException(code);
                }
            }
        }
        throw failed();
    }

    String interpreter() {
        String value = environment.apply(INTERPRETER_VARIABLE);
        if (value == null || value.isEmpty()) {
            throw failed();
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException e) {
            throw failed();
        }
        if (!path.isAbsolute()) {
            throw failed();
        }
        OptionalLong self = uid.get();
        if (self.isEmpty()) {
            throw failed();
        }
        try {
            PosixFileAttributes attributes = Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isRegularFile()
                    || attributes.permissions().contains(PosixFilePermission.GROUP_WRITE)
                    || attributes.permissions().contains(PosixFilePermission.OTHERS_WRITE)) {
                throw failed();
            }
            if (!(Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) instanceof Integer owner)) {
                throw failed();
            }
            long ownerUid = Integer.toUnsignedLong(owner);
            if (ownerUid != 0 && ownerUid != self.getAsLong()) {
                throw failed();
            }
            if (!Files.isExecutable(path)) {
                throw failed();
            }
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException | SecurityException e) {
            throw failed();
        }
        return path.toString();
    }

    /** The helper's source text, decoded strictly. */
    static String source() {
        try (InputStream in = WorkFolderHelper.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw failed();
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(in.readAllBytes()))
                    .toString();
        } catch (IOException e) {
            throw failed();
        }
    }

    /**
     * The helper's stdin: one object with exactly six keys, built here with fixed keys and RFC 8259
     * escaping. Every character outside printable ASCII is a {@code \\u} escape, so the bytes are
     * ASCII; a lone surrogate cannot be represented and is a failure.
     */
    static byte[] input(Path varDir, OutputRoot.Instance instance, long cycleId, int attempt, long executionId) {
        StringBuilder json = new StringBuilder("{");
        member(json, "var_dir").append(quoted(varDir.toString())).append(',');
        member(json, "database").append(quoted(instance.database())).append(',');
        member(json, "instance_id").append(quoted(instance.instanceText())).append(',');
        member(json, "cycle_id").append(cycleId).append(',');
        member(json, "attempt").append(attempt).append(',');
        member(json, "job_execution_id").append(executionId).append('}');
        return json.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static StringBuilder member(StringBuilder json, String key) {
        return json.append('"').append(key).append("\":");
    }

    static String quoted(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw failed();
                }
            } else if (Character.isLowSurrogate(c)
                    && (i == 0 || !Character.isHighSurrogate(value.charAt(i - 1)))) {
                throw failed();
            }
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7e) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static List<FactLine> factLines() {
        List<FactLine> lines = new ArrayList<>();
        for (boolean exists : new boolean[] {false, true}) {
            for (String work : RefusalJson.WORK_FOLDERS) {
                lines.add(new FactLine(("facts final=" + exists + " work=" + work + "\n")
                        .getBytes(StandardCharsets.US_ASCII), new RefusalRun.Facts(exists, work)));
            }
        }
        return List.copyOf(lines);
    }

    private static RefusalException failed() {
        return new RefusalException(WORK_FOLDER_HELPER_FAILED);
    }
}
