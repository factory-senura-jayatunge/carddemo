package org.carddemo.batch.execution;

import static org.carddemo.platform.outputroot.OutputRootException.Code.OUTPUT_ROOT_INVALID;
import static org.carddemo.platform.outputroot.OutputRootException.Code.OUTPUT_ROOT_MISMATCH;

import java.io.UncheckedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.outputroot.OutputRootException;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * The instance root of each job execution (batch-execution-contract.md section 4.10). The first
 * execution of a job instance is bound, in its launch transaction, to the database instance that
 * {@code ops.database_instance} names then; a restart keeps the binding its context carries. Every
 * fenced transaction reads the marker again on its own connection and compares it with the
 * binding, so an execution never writes into another instance's root, also after a database reset
 * or restore under a running service. Nothing here caches a root: it is derived from the binding
 * and {@code CARDDEMO_VAR_DIR} for each use.
 */
public final class OutputRoots {

    /** Job execution context: the bound {@code DB_NAME()}. */
    public static final String DATABASE_KEY = "outputRoot.database";
    /** Job execution context: the bound instance ID, lower case. */
    public static final String INSTANCE_KEY = "outputRoot.instanceId";
    /** The output roots below the instance root that batch-service writes (section 4.8). */
    static final List<List<String>> OUTPUT_FOLDERS = List.of(List.of("statements"), List.of("reports", "tranrept"),
            List.of("reports", "prtcatbl"), List.of("exports"), List.of("imports"), List.of("rejects"),
            List.of("dailytran", "archive"));
    /** Each output root's folder of per-execution working paths. */
    static final String WORK = ".work";
    /** The operator's inbound folder below CARDDEMO_VAR_DIR (section 4.9): shared, only read. */
    static final String INBOUND = "inbound";

    private final Path varDir;
    private final DataSource dataSource;

    /** @param varDir the result of {@link OutputRoot#varDir}, absolute and canonical */
    public OutputRoots(Path varDir, DataSource dataSource) {
        this.varDir = varDir;
        this.dataSource = dataSource;
    }

    public Path varDir() {
        return varDir;
    }

    /** {@code <CARDDEMO_VAR_DIR>/inbound}; never created or checked, the operator owns it. */
    public Path inbound() {
        return varDir.resolve(INBOUND);
    }

    /**
     * The start check of the server and of every one-shot JVM, after the configuration check:
     * the current instance's root, created when missing, and each output root and its
     * {@code .work/} folder, created when missing and checked. A failure fails the start with a
     * {@link Refusal}, never a path.
     */
    public Path ensureAtStart() {
        OutputRoot.Instance instance = readAtStart();
        Path root = refusing(Refusal.ENSURE, () -> OutputRoot.ensure(varDir, instance));
        for (List<String> folder : OUTPUT_FOLDERS) {
            refusing(Refusal.ENSURE_DIRECTORY, () -> OutputRoot.ensureDirectory(root, folder.toArray(String[]::new)));
            refusing(Refusal.ENSURE_DIRECTORY, () -> OutputRoot.ensureDirectory(root, append(folder, WORK)));
        }
        return root;
    }

    private OutputRoot.Instance readAtStart() {
        try (Connection connection = dataSource.getConnection()) {
            return OutputRoot.read(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("the database instance marker could not be read: "
                    + SafeFailures.describe(e), SafeFailures.sanitized(e));
        }
    }

    /**
     * The closed set of reasons that an {@link OutputRootException} from this class carries instead
     * of {@link OutputRoot}'s message, which names paths below CARDDEMO_VAR_DIR; with the code, the
     * reason is all that reaches a log, an exit message, a poll answer or command output
     * (batch-execution-contract.md section 4.10, Messages outside the root).
     */
    public enum Refusal {
        ENSURE("the instance root could not be made or failed its check"),
        ENSURE_DIRECTORY("an output folder could not be made or failed its check"),
        RESOLVE("the output root failed its check");

        private final String text;

        Refusal(String text) {
            this.text = text;
        }

        /** {@code <REASON>: <fixed text>}, the detail of the exception. */
        public String detail() {
            return name() + ": " + text;
        }
    }

    /**
     * Runs an {@link OutputRoot} call that can fail naming a path: an {@link OutputRootException}
     * leaves with its code and {@code refusal} and no cause, a file system failure with the type of
     * its cause only ({@link PublicationFailures#redacted}).
     */
    static <T> T refusing(Refusal refusal, Supplier<T> call) {
        try {
            return call.get();
        } catch (OutputRootException e) {
            throw new OutputRootException(e.code(), refusal.detail());
        } catch (UncheckedIOException | DirectoryIteratorException e) {
            throw PublicationFailures.redacted(e);
        }
    }

    /**
     * In the launch transaction, before the execution and its context are inserted: the first
     * execution of an instance gets the current instance, whose root is ensured; a restart's
     * context keeps the binding of the instance's first execution, compared later by every fenced
     * transaction. A restart whose context has no binding (an execution from before the binding
     * existed) is refused, never bound late: its earlier outputs may sit in another root.
     *
     * @param previous the instance's last execution, which a restart continues; null for a first execution
     * @return the instance the context is bound to
     */
    OutputRoot.Instance bind(ExecutionContext context, JobExecution previous) {
        Optional<OutputRoot.Instance> bound = bound(context);
        if (bound.isPresent()) {
            return bound.get();
        }
        if (previous != null) {
            throw new OutputRootException(OUTPUT_ROOT_INVALID, "job execution " + previous.getId()
                    + " is bound to no output root, so its restart is refused; rerun the cycle or start a new run");
        }
        OutputRoot.Instance instance = readInTransaction();
        refusing(Refusal.ENSURE, () -> OutputRoot.ensure(varDir, instance));
        context.putString(DATABASE_KEY, instance.database());
        context.putString(INSTANCE_KEY, instance.instanceText());
        return instance;
    }

    /** The binding in a job execution context, if it has one. */
    static Optional<OutputRoot.Instance> bound(ExecutionContext context) {
        boolean database = context.containsKey(DATABASE_KEY);
        boolean instance = context.containsKey(INSTANCE_KEY);
        if (!database && !instance) {
            return Optional.empty();
        }
        if (context.get(DATABASE_KEY) instanceof String name && context.get(INSTANCE_KEY) instanceof String id) {
            return Optional.of(OutputRoot.Instance.of(name, id));
        }
        throw new OutputRootException(OUTPUT_ROOT_INVALID, "the job execution context holds an incomplete output"
                + " root binding");
    }

    /** The execution's binding; an execution with none is refused, never bound late (see {@link #bind}). */
    static OutputRoot.Instance requireBound(JobExecution execution) {
        return bound(execution.getExecutionContext()).orElseThrow(() -> new OutputRootException(OUTPUT_ROOT_INVALID,
                "job execution " + execution.getId() + " is bound to no output root"));
    }

    /**
     * The bound root of {@code execution}, for a step that writes or publishes an output. It runs
     * in the step's fenced transaction: the marker is read again on that transaction's connection
     * and compared with the binding, and the root must exist with its marker. It only reads; the
     * caller creates its folders below the root with {@link OutputRoot#ensureDirectory}.
     *
     * @throws OutputRootException {@code OUTPUT_ROOT_MISMATCH} when the database is another
     *         instance now, or the code of the failed root check with {@link Refusal#RESOLVE}
     */
    public Path root(JobExecution execution) {
        OutputRoot.Instance bound = requireBound(execution);
        check(execution.getId(), bound);
        Optional<Path> root = refusing(Refusal.RESOLVE, () -> OutputRoot.resolve(varDir, bound));
        return root.orElseThrow(() -> new OutputRootException(OUTPUT_ROOT_INVALID,
                "the output root of job execution " + execution.getId() + " is missing"));
    }

    /**
     * The current instance's root for a caller with no execution (cycle-rerun), read-only; empty
     * when it does not exist. In the caller's transaction when there is one.
     */
    public Optional<Path> currentRoot() {
        OutputRoot.Instance instance = readInTransaction();
        return refusing(Refusal.RESOLVE, () -> OutputRoot.resolve(varDir, instance));
    }

    /** The fence check: in the current transaction, the database must still be the bound instance. */
    void check(long executionId, OutputRoot.Instance bound) {
        if (!readInTransaction().equals(bound)) {
            throw new OutputRootException(OUTPUT_ROOT_MISMATCH, "job execution " + executionId
                    + " is bound to another database instance; it writes nothing more");
        }
    }

    private OutputRoot.Instance readInTransaction() {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            return OutputRoot.read(connection);
        } catch (SQLException e) {
            throw new UncategorizedSQLException("reading ops.database_instance", null, e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private static String[] append(List<String> folder, String segment) {
        String[] segments = folder.toArray(new String[folder.size() + 1]);
        segments[folder.size()] = segment;
        return segments;
    }
}
