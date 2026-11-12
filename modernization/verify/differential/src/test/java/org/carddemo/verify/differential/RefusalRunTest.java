package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.carddemo.platform.outputroot.OutputRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/** The bracket of steps 1 to 5 over stub reads and a stub helper: each step's codes and its order. */
class RefusalRunTest {

    private static final List<String> STEPS_1_AND_2 = List.of("execution", "cycle", "above", "runs", "instance",
            "execution");
    private static final List<String> STEPS_1_TO_4 = List.of("execution", "cycle", "above", "runs", "instance",
            "execution", "execution", "cycle", "above");

    @TempDir
    Path tmp;

    private Path var;
    private Path dest;
    private RefusalStubs.Reads reads;
    private RefusalStubs.Environment env;
    private RefusalStubs.Walk walk;

    @BeforeEach
    void setUp() throws IOException {
        var = Files.createDirectory(tmp.resolve(RefusalStubs.CANARY),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        dest = tmp.resolve("refusal");
        reset();
    }

    private void reset() {
        reads = new RefusalStubs.Reads();
        env = new RefusalStubs.Environment(var);
        walk = new RefusalStubs.Walk();
    }

    private RefusalRun run(boolean helperEnabled) {
        return new RefusalRun(reads, env, helperEnabled, walk);
    }

    private void refused(boolean helperEnabled, RefusalOptions options, String code) {
        assertThatThrownBy(() -> run(helperEnabled).run(options)).isInstanceOfSatisfying(RefusalException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    /** A refusal before step 5 leaves no destination behind and never reaches the helper when it is earlier. */
    private void refusedBeforeTheHelper(Consumer<RefusalStubs.Reads> setup, RefusalOptions options, String code) {
        reset();
        setup.accept(reads);
        refused(true, options, code);
        assertThat(walk.calls).as(code).isZero();
        assertThat(dest).doesNotExist();
    }

    @Test
    void aCompletedRefusalWritesRefusalJsonAndNothingElse() throws Exception {
        reads.runs = List.of("FAILED", "COMPLETED");
        RefusalJson.Refusal r = run(true).run(RefusalStubs.options(dest));

        assertThat(r).isEqualTo(new RefusalJson.Refusal(RefusalStubs.E, "FAILED", "STATEMENT_TABLE_LIMIT", true, true,
                "empty"));
        assertThat(Files.readAllBytes(dest.resolve("refusal.json"))).isEqualTo(RefusalJson.of(r));
        try (var entries = Files.list(dest)) {
            assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("refusal.json");
        }
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dest))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dest.resolve("refusal.json"))))
                .isEqualTo("rw-------");
        assertThat(walk.arguments).containsExactly(var.toRealPath(), RefusalStubs.instance(), RefusalStubs.CYCLE,
                RefusalStubs.ATTEMPT, RefusalStubs.E);
        assertThat(reads.calls).containsExactlyElementsOf(STEPS_1_TO_4);
    }

    @Test
    void aRunRowCountsOnlyWhenOneIsExactlyCompleted() {
        reads.runs = List.of("FAILED", "completed", "COMPLETED ", "STARTED");
        assertThat(run(true).run(RefusalStubs.options(dest)).completedRunRow()).isFalse();
    }

    @Test
    void executionRefusedAtStepOne() {
        RefusalOptions o = RefusalStubs.options(dest);
        RefusalReads.Execution other = new RefusalReads.Execution(900, false, "FAILED", RefusalStubs.END, "FAILED",
                "unrecognised");
        refusedBeforeTheHelper(r -> r.execution = n -> Optional.empty(), o, "execution-refused");
        refusedBeforeTheHelper(r -> r.execution = n -> Optional.of(other), o, "execution-refused");
        refusedBeforeTheHelper(r -> { }, new RefusalOptions(RefusalStubs.CYCLE, RefusalStubs.E, RefusalStubs.E, dest),
                "execution-refused");
        refusedBeforeTheHelper(r -> { }, new RefusalOptions(RefusalStubs.CYCLE, 99, RefusalStubs.E, dest),
                "execution-refused");
        refusedBeforeTheHelper(r -> r.cycle = n -> Optional.empty(), o, "execution-refused");
        refusedBeforeTheHelper(r -> r.above = n -> List.of(), o, "execution-refused");
        refusedBeforeTheHelper(r -> r.above = n -> List.of(new RefusalReads.StatementExecution(1, RefusalStubs.E)), o,
                "execution-refused");
        refusedBeforeTheHelper(r -> r.above = n -> List.of(new RefusalReads.StatementExecution(1, 42),
                new RefusalReads.StatementExecution(RefusalStubs.ATTEMPT, RefusalStubs.E)), o, "execution-refused");
        refusedBeforeTheHelper(r -> r.above = n -> List.of(new RefusalReads.StatementExecution(RefusalStubs.ATTEMPT,
                RefusalStubs.E), new RefusalReads.StatementExecution(RefusalStubs.ATTEMPT, 43)), o, "execution-refused");
    }

    @Test
    void executionNotFinalAtStepTwo() {
        RefusalOptions o = RefusalStubs.options(dest);
        for (String status : new String[] {"STARTED", "STARTING", "STOPPING", "STOPPED", "COMPLETED", "ABANDONED",
            "UNKNOWN", "failed"}) {
            RefusalReads.Execution e = new RefusalReads.Execution(900, true, status, RefusalStubs.END, "FAILED",
                    "unrecognised");
            refusedBeforeTheHelper(r -> r.execution = n -> Optional.of(e), o, "execution-not-final");
        }
        RefusalReads.Execution noEnd = new RefusalReads.Execution(900, true, "FAILED", null, "FAILED", "unrecognised");
        refusedBeforeTheHelper(r -> r.execution = n -> Optional.of(noEnd), o, "execution-not-final");
        RefusalReads.Execution later = new RefusalReads.Execution(900, true, "FAILED",
                RefusalStubs.END.plusSeconds(1), "FAILED", ExitTokens.TABLE_LIMIT);
        refusedBeforeTheHelper(r -> r.execution = n -> Optional.of(n == 2 ? later : RefusalStubs.FAILED), o,
                "execution-not-final");
        refusedBeforeTheHelper(r -> r.execution = n -> n == 2 ? Optional.empty() : Optional.of(RefusalStubs.FAILED), o,
                "execution-not-final");
    }

    @Test
    void executionChangedAtStepFourAfterTheHelper() {
        RefusalReads.Execution stopped = new RefusalReads.Execution(900, true, "ABANDONED", RefusalStubs.END, "FAILED",
                ExitTokens.TABLE_LIMIT);
        RefusalReads.Execution otherExit = new RefusalReads.Execution(900, true, "FAILED", RefusalStubs.END, "STOPPED",
                ExitTokens.TABLE_LIMIT);
        List<Runnable> changes = List.of(
                () -> reads.execution = n -> Optional.of(n >= 3 ? stopped : RefusalStubs.FAILED),
                () -> reads.execution = n -> Optional.of(n >= 3 ? otherExit : RefusalStubs.FAILED),
                () -> reads.execution = n -> n >= 3 ? Optional.empty() : Optional.of(RefusalStubs.FAILED),
                () -> reads.cycle = n -> Optional.of(n >= 2 ? new RefusalReads.Cycle(RefusalStubs.DATE, 3)
                        : RefusalStubs.CYCLE_ROW),
                () -> reads.above = n -> n >= 2 ? List.of(new RefusalReads.StatementExecution(RefusalStubs.ATTEMPT,
                        RefusalStubs.E), new RefusalReads.StatementExecution(RefusalStubs.ATTEMPT, 42))
                        : List.of(new RefusalReads.StatementExecution(RefusalStubs.ATTEMPT, RefusalStubs.E)));
        for (Runnable change : changes) {
            reset();
            walk.hook = change;
            refused(true, RefusalStubs.options(dest), "execution-changed");
            assertThat(walk.calls).isEqualTo(1);
            assertThat(dest).doesNotExist();
        }
    }

    @Test
    void anyDataAccessFailureIsDatabaseReadFailed() {
        RefusalOptions o = RefusalStubs.options(dest);
        for (String read : new String[] {"execution", "cycle", "above", "runs", "instance"}) {
            refusedBeforeTheHelper(r -> r.failing.put(read, new DataAccessResourceFailureException(RefusalStubs.CANARY)),
                    o, "database-read-failed");
            refusedBeforeTheHelper(r -> r.failing.put(read, new CannotCreateTransactionException(RefusalStubs.CANARY)),
                    o, "database-read-failed");
        }
        refusedBeforeTheHelper(r -> r.instance = () -> {
            throw new SQLException(RefusalStubs.CANARY);
        }, o, "database-read-failed");
        refusedBeforeTheHelper(r -> r.cycle = n -> Optional.of(new RefusalReads.Cycle(RefusalStubs.DATE, 0)), o,
                "database-read-failed");
        refusedBeforeTheHelper(r -> r.cycle = n -> Optional.of(new RefusalReads.Cycle(null, RefusalStubs.ATTEMPT)), o,
                "database-read-failed");

        reset();
        walk.hook = () -> reads.failing.put("above", new DataAccessResourceFailureException(RefusalStubs.CANARY));
        refused(true, o, "database-read-failed");
        assertThat(dest).doesNotExist();
    }

    @Test
    void aRefusedInstanceRowIsInstanceReadRefused() {
        RefusalOptions o = RefusalStubs.options(dest);
        refusedBeforeTheHelper(r -> r.instance = () -> OutputRoot.Instance.of(RefusalStubs.DATABASE, "not-a-uuid"), o,
                "instance-read-refused");
        refusedBeforeTheHelper(r -> r.instance = () -> OutputRoot.Instance.of("", RefusalStubs.INSTANCE_ID), o,
                "instance-read-refused");
        refusedBeforeTheHelper(r -> r.instance = () -> {
            throw new UncheckedIOException(new IOException(RefusalStubs.CANARY));
        }, o, "instance-read-refused");
    }

    @Test
    void aRefusedVarDirIsVarDirRefused() throws IOException {
        Path file = Files.writeString(tmp.resolve("file"), "x");
        List<Consumer<RefusalStubs.Environment>> settings = List.of(
                e -> e.values.remove(OutputRoot.VAR_DIR_VARIABLE),
                e -> e.values.put(OutputRoot.VAR_DIR_VARIABLE, ""),
                e -> e.values.put(OutputRoot.VAR_DIR_VARIABLE, "relative/var"),
                e -> e.values.put(OutputRoot.VAR_DIR_VARIABLE, file.toString()),
                e -> e.values.put("CARDDEMO_OUTPUT_DIR", RefusalStubs.CANARY),
                e -> {
                    e.values.remove(OutputRoot.VAR_DIR_VARIABLE);
                    e.values.put(OutputRoot.VAR_DIR_PROPERTY, var.toString());
                });
        for (Consumer<RefusalStubs.Environment> setting : settings) {
            reset();
            setting.accept(env);
            refused(true, RefusalStubs.options(dest), "var-dir-refused");
            assertThat(walk.calls).isZero();
            assertThat(dest).doesNotExist();
        }
    }

    @Test
    void theVarDirComesFromTheEnvironmentOnly() throws Exception {
        // AR-1: carddemo.var-dir is never asked, as an environment entry or as a system property.
        Path canary = Files.createDirectory(tmp.resolve("canary-property"));
        env.values.put(OutputRoot.VAR_DIR_PROPERTY, canary.toString());
        String previous = System.setProperty(OutputRoot.VAR_DIR_PROPERTY, canary.toString());
        try {
            run(true).run(RefusalStubs.options(dest));
        } finally {
            if (previous == null) {
                System.clearProperty(OutputRoot.VAR_DIR_PROPERTY);
            } else {
                System.setProperty(OutputRoot.VAR_DIR_PROPERTY, previous);
            }
        }
        assertThat(walk.arguments.get(0)).isEqualTo(var.toRealPath());
        assertThat(env.asked).doesNotContain(OutputRoot.VAR_DIR_PROPERTY).contains(OutputRoot.VAR_DIR_VARIABLE)
                .containsAll(OutputRoot.LEGACY_SETTINGS.stream()
                        .filter(n -> !n.contains(".")).toList());
    }

    @Test
    void aLinkedVarDirIsPassedInCanonicalForm() throws Exception {
        Path link = Files.createSymbolicLink(tmp.resolve("var-link"), var);
        env.values.put(OutputRoot.VAR_DIR_VARIABLE, link.toString());
        run(true).run(RefusalStubs.options(dest));
        assertThat(walk.arguments.get(0)).isEqualTo(var.toRealPath());
    }

    @Test
    void aMissingVarDirIsPassedUnresolvedForTheHelperToRefuse() throws Exception {
        Path missing = tmp.resolve("missing-var");
        env.values.put(OutputRoot.VAR_DIR_VARIABLE, missing.toString());
        run(true).run(RefusalStubs.options(dest));
        assertThat(walk.arguments.get(0)).isEqualTo(tmp.toRealPath().resolve("missing-var"));
    }

    @Test
    void theHelpersRefusalsAndFailuresPassThroughWithNothingWritten() {
        for (String code : List.of("work-folder-unsupported", "work-folder-input", "instance-root-refused",
                "work-folder-refused", "work-folder-helper-failed")) {
            reset();
            walk.failure = new RefusalException(code);
            refused(true, RefusalStubs.options(dest), code);
            assertThat(reads.calls).as("step 4 is not run").containsExactlyElementsOf(STEPS_1_AND_2);
            assertThat(dest).doesNotExist();
        }
    }

    @Test
    void theDestinationIsCheckedOnlyAtStepFive() throws IOException {
        Path existing = Files.createDirectory(tmp.resolve("existing"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path open = Files.createDirectory(tmp.resolve("open"));
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxr-x---"));
        Path full = Files.createDirectory(tmp.resolve("full"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(full.resolve("old"), "x");
        List<Path> bad = List.of(tmp.resolve("missing").resolve("refusal"), open, full,
                Files.createSymbolicLink(tmp.resolve("link"), existing), Files.writeString(tmp.resolve("plain"), "x"));
        for (Path destination : bad) {
            reset();
            refused(true, RefusalStubs.options(destination), "destination-refused");
            assertThat(walk.calls).as("the helper ran before the destination check").isEqualTo(1);
            assertThat(reads.calls).containsExactlyElementsOf(STEPS_1_TO_4);
        }
        assertThat(tmp.resolve("missing")).doesNotExist();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(open))).isEqualTo("rwxr-x---");
    }

    @Test
    void aFailedWriteIsRefusalWriteFailed() throws IOException {
        // Root can write into a read-only folder, so a root run must fail here, not skip.
        assertTrue(ProcessUid.current().orElse(0) != 0,
                "this test must run as a user other than root, with a known user ID");
        Path readOnly = Files.createDirectory(dest,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-x------")));
        refused(true, RefusalStubs.options(readOnly), "refusal-write-failed");
        try (var entries = Files.list(readOnly)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void disabledTheModeRefusesUnsupportedAfterStepsOneAndTwo() {
        refused(false, RefusalStubs.options(dest), "work-folder-unsupported");
        assertThat(walk.calls).isZero();
        assertThat(reads.calls).containsExactlyElementsOf(STEPS_1_AND_2);
        assertThat(dest).doesNotExist();
    }

    @Test
    void disabledABadDestinationIsNeverReached() throws IOException {
        Path full = Files.createDirectory(dest,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(full.resolve("old"), "x");
        refused(false, RefusalStubs.options(full), "work-folder-unsupported");
        refused(false, RefusalStubs.options(tmp.resolve("missing").resolve("refusal")), "work-folder-unsupported");
        assertThat(tmp.resolve("missing")).doesNotExist();
        try (var entries = Files.list(full)) {
            assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("old");
        }
    }
}
