package org.carddemo.verify.differential;

import static org.carddemo.verify.differential.RefusalCodes.DATABASE_READ_FAILED;
import static org.carddemo.verify.differential.RefusalCodes.DESTINATION_REFUSED;
import static org.carddemo.verify.differential.RefusalCodes.EXECUTION_CHANGED;
import static org.carddemo.verify.differential.RefusalCodes.EXECUTION_NOT_FINAL;
import static org.carddemo.verify.differential.RefusalCodes.EXECUTION_REFUSED;
import static org.carddemo.verify.differential.RefusalCodes.INSTANCE_READ_REFUSED;
import static org.carddemo.verify.differential.RefusalCodes.REFUSAL_WRITE_FAILED;
import static org.carddemo.verify.differential.RefusalCodes.VAR_DIR_REFUSED;
import static org.carddemo.verify.differential.RefusalCodes.WORK_FOLDER_UNSUPPORTED;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.outputroot.OutputRootException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * The refusal mode's bracket, in one exporter process and in this order:
 * <ol>
 * <li>the execution checks, the run row, the instance row and {@code CARDDEMO_VAR_DIR};
 * <li>the execution is terminal {@code FAILED} with an end time, and unchanged since step 1;
 * <li>the work-folder helper, which does the whole folder walk in one run;
 * <li>after the helper has exited, the execution checks again, with nothing changed;
 * <li>then, and only then, the destination and {@code refusal.json}.
 * </ol>
 * Each refusal is one {@link RefusalException} code, and nothing is written. The destination is
 * prepared only at step 5, so a refusal at steps 1 to 4 leaves no folder behind. With the helper
 * disabled, step 3 refuses {@code work-folder-unsupported} without reading the interpreter
 * setting, the helper resource or any helper input, and without starting a process.
 */
final class RefusalRun {

    /** The folder facts of the instance root, from one helper run. */
    record Facts(boolean finalFolderExists, String workFolder) {
    }

    /** Step 3: reads the final folder and the work folder of {@code <cycleId>-<attempt>.<executionId>}. */
    interface WorkFolder {
        Facts walk(Path varDir, OutputRoot.Instance instance, long cycleId, int attempt, long executionId);
    }

    private record Snapshot(RefusalReads.Cycle cycle, RefusalReads.Execution execution) {
    }

    private final RefusalReads reads;
    private final Function<String, String> environment;
    private final boolean helperEnabled;
    private final WorkFolder workFolder;

    /**
     * @param environment the process environment; {@code carddemo.var-dir} is never asked of it
     * @param helperEnabled whether step 3 starts the helper; production passes
     *        {@link RefusalMode#HELPER_ENABLED}
     */
    RefusalRun(RefusalReads reads, Function<String, String> environment, boolean helperEnabled, WorkFolder workFolder) {
        this.reads = reads;
        this.environment = environment;
        this.helperEnabled = helperEnabled;
        this.workFolder = workFolder;
    }

    /** {@code carddemo.var-dir} is a system property, never consulted here; every other name is the environment's. */
    static Function<String, String> environmentOnly(Function<String, String> environment) {
        return name -> OutputRoot.VAR_DIR_PROPERTY.equals(name) ? null : environment.apply(name);
    }

    /** Runs steps 1 to 5; returns only after {@code refusal.json} is written. */
    RefusalJson.Refusal run(RefusalOptions options) {
        Snapshot first = executionChecks(options, EXECUTION_REFUSED);
        int attempt = first.cycle().attempt();
        List<String> runs = read(() -> reads.statementRunStatuses(options.cycleId(), attempt));
        boolean completedRunRow = runs.stream().anyMatch("COMPLETED"::equals);
        OutputRoot.Instance instance = instance();
        Path varDir = varDir();

        RefusalReads.Execution terminal = read(() -> reads.execution(options.jobExecutionId()))
                .orElseThrow(() -> new RefusalException(EXECUTION_NOT_FINAL));
        if (!first.execution().terminalFailed() || !terminal.equals(first.execution())) {
            throw new RefusalException(EXECUTION_NOT_FINAL);
        }

        if (!helperEnabled) {
            throw new RefusalException(WORK_FOLDER_UNSUPPORTED);
        }
        Facts facts = workFolder.walk(varDir, instance, options.cycleId(), attempt, options.jobExecutionId());

        Snapshot after = executionChecks(options, EXECUTION_CHANGED);
        if (!after.equals(first)) {
            throw new RefusalException(EXECUTION_CHANGED);
        }

        RefusalJson.Refusal refusal = new RefusalJson.Refusal(options.jobExecutionId(), first.execution().exitStatus(),
                first.execution().exitCode(), completedRunRow, facts.finalFolderExists(), facts.workFolder());
        write(options.outputDir(), RefusalJson.of(refusal));
        return refusal;
    }

    /**
     * E is a statementJob execution above L, and the only one of cycle N above L at any attempt, and
     * it belongs to the cycle's current attempt. A failed check is {@code failure}: the step's own
     * code ({@code execution-refused} at step 1, {@code execution-changed} at step 4).
     */
    private Snapshot executionChecks(RefusalOptions options, String failure) {
        long e = options.jobExecutionId();
        RefusalReads.Execution execution = read(() -> reads.execution(e))
                .orElseThrow(() -> new RefusalException(failure));
        if (!execution.statementJob() || e <= options.loadExecutionId()) {
            throw new RefusalException(failure);
        }
        RefusalReads.Cycle cycle = read(() -> reads.cycle(options.cycleId()))
                .orElseThrow(() -> new RefusalException(failure));
        if (cycle.attempt() < 1 || cycle.businessDate() == null) {
            throw new RefusalException(DATABASE_READ_FAILED);
        }
        List<RefusalReads.StatementExecution> above = read(
                () -> reads.statementExecutionsAbove(options.cycleId(), cycle, options.loadExecutionId()));
        if (!above.equals(List.of(new RefusalReads.StatementExecution(cycle.attempt(), e)))) {
            throw new RefusalException(failure);
        }
        return new Snapshot(cycle, execution);
    }

    private OutputRoot.Instance instance() {
        try {
            return reads.instance();
        } catch (SQLException | DataAccessException | TransactionException e) {
            throw new RefusalException(DATABASE_READ_FAILED);
        } catch (OutputRootException | UncheckedIOException e) {
            throw new RefusalException(INSTANCE_READ_REFUSED);
        }
    }

    private Path varDir() {
        try {
            return OutputRoot.varDir(environmentOnly(environment));
        } catch (OutputRootException | UncheckedIOException e) {
            throw new RefusalException(VAR_DIR_REFUSED);
        }
    }

    private static <T> T read(Supplier<T> read) {
        try {
            return read.get();
        } catch (DataAccessException | TransactionException e) {
            throw new RefusalException(DATABASE_READ_FAILED);
        }
    }

    private static void write(Path outputDir, byte[] json) {
        ExportFiles files;
        try {
            files = ExportFiles.open("--output-dir", outputDir);
        } catch (UsageException | ExportException e) {
            throw new RefusalException(DESTINATION_REFUSED);
        }
        try {
            files.bytes(RefusalJson.FILE, json);
        } catch (ExportException e) {
            throw new RefusalException(REFUSAL_WRITE_FAILED);
        }
    }
}
