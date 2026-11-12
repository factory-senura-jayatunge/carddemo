package org.carddemo.batch.execution;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.regex.Pattern;

import org.carddemo.batch.execution.PublicationException.Reason;
import org.carddemo.platform.outputroot.OutputRoot;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Output publication (batch-execution-contract.md section 4.8), shared by every job that writes
 * under its instance root. Each execution writes only into a working path that carries its own
 * job execution ID, created new with mode {@code 0600}; the final path is created only by the
 * publish, as a hard link ({@code link(2)}, which fails with {@code EEXIST} on an existing name),
 * and nothing is ever replaced. An existing target is compared by SHA-256: equal bytes mean an
 * earlier execution published the same output, anything else is {@code PUBLICATION_MISMATCH}.
 * Single-file outputs are published here; a folder output is published file by file, under a
 * claim, by {@link FolderPublication}. A folder rename ({@code rename(2)}) replaces an empty folder
 * or a file at the target, so it is no no-replace publication and nothing uses it.
 *
 * <p>A job whose inputs can change between executions of one instance commits an {@link Intent}
 * into the job execution context first ({@link #commitIntent}) and publishes only what an intent
 * names. Its keys are {@code <prefix>.workPath} and {@code <prefix>.sha256}. The publish may run in
 * a fenced transaction whose first statement is {@link #beginPublish} (section 4.3).
 *
 * <p>No exception of this class names an entry, a file or a path (section 4.8, Logs): each
 * public method lets its failures leave through {@code PublicationFailures.redacting}.
 */
public final class OutputPublication {

    /** How a publish ended: this call created the target, or an earlier one had, with the same bytes. */
    public enum Outcome { PUBLISHED, ALREADY_PUBLISHED }

    /**
     * A committed intent: the working file's name in the folder of its target, and its SHA-256 in
     * lower-case hex.
     */
    public record Intent(String workName, String sha256) {

        public Intent {
            if (workName == null || !NAME.matcher(workName).matches() || workName.equals(".") || workName.equals("..")) {
                throw new PublicationException(Reason.INTENT_NO_WORK_PATH);
            }
            requireSha256(sha256);
        }
    }

    /** Suffix of the context key that holds an intent's working path name. */
    public static final String WORK_PATH = ".workPath";
    /** Suffix of the context key that holds an intent's SHA-256. */
    public static final String SHA256 = ".sha256";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern HEX_SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<BatchStatus> TERMINAL = EnumSet.of(BatchStatus.COMPLETED, BatchStatus.FAILED,
            BatchStatus.STOPPED, BatchStatus.ABANDONED);

    private final JobRepository repository;
    private final ExecutionLeases leases;
    private final LeaseKeeper keeper;

    public OutputPublication(JobRepository repository, ExecutionLeases leases, LeaseKeeper keeper) {
        this.repository = repository;
        this.leases = leases;
        this.keeper = keeper;
    }

    /** The working file of a single-file output: {@code <targetName>.<jobExecutionId>.tmp}, beside the target. */
    public static String workFileName(String targetName, long jobExecutionId) {
        return targetName + "." + jobExecutionId + ".tmp";
    }

    /**
     * Creates a working file, new and {@code 0600} from the start. An existing one is not this
     * execution's (job execution IDs are not reused within an instance) and is left in place.
     * Closing the stream forces the bytes to disk.
     *
     * @throws PublicationException when the file exists
     */
    public static OutputStream createWorkFile(Path file) {
        FileChannel channel = PublicationFailures.redacting(() -> {
            try {
                return FileChannel.open(file, Set.of(CREATE_NEW, WRITE),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (FileAlreadyExistsException e) {
                throw new PublicationException(Reason.WORK_EXISTS);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return redacting(Channels.newOutputStream(channel), () -> {
            if (channel.isOpen()) {
                channel.force(true);
            }
        });
    }

    /** Forces a working file's bytes to disk; runs before its stream closes. */
    @FunctionalInterface
    interface Force {
        void run() throws IOException;
    }

    /**
     * A working file's stream whose write, flush and close throw an {@link IOException} of the same
     * type with no message, cause or suppressed exception ({@link PublicationFailures#redacted(IOException)}).
     */
    static OutputStream redacting(OutputStream stream, Force force) {
        return new FilterOutputStream(stream) {
            @Override
            public void write(int b) throws IOException {
                try {
                    out.write(b);
                } catch (IOException e) {
                    throw PublicationFailures.redacted(e);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                try {
                    out.write(b, off, len);
                } catch (IOException e) {
                    throw PublicationFailures.redacted(e);
                }
            }

            @Override
            public void flush() throws IOException {
                try {
                    out.flush();
                } catch (IOException e) {
                    throw PublicationFailures.redacted(e);
                }
            }

            @Override
            public void close() throws IOException {
                try {
                    try {
                        force.run();
                    } finally {
                        super.close();
                    }
                } catch (IOException e) {
                    throw PublicationFailures.redacted(e);
                }
            }
        };
    }

    /**
     * The lower-case hex SHA-256 of a managed regular file: the file and its folder pass the path
     * checks of section 4.10 ({@link OutputRoot#existingFile}: no link, owned by the service user,
     * no group or other permission), and the file is read without following a link.
     *
     * @throws PublicationException when the file is missing
     * @throws org.carddemo.platform.outputroot.OutputRootException {@code OUTPUT_PERMISSIONS} when a check fails
     */
    public static String sha256(Path file) {
        return PublicationFailures.redacting(() -> hash(file));
    }

    private static String hash(Path file) {
        Path checked = OutputRoot.existingFile(file.getParent(), file.getFileName().toString())
                .orElseThrow(() -> new PublicationException(Reason.FILE_MISSING));
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(checked, NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65_536];
            for (int n; (n = in.read(buffer)) != -1; ) {
                digest.update(buffer, 0, n);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The lower-case hex SHA-256 of {@code bytes}. */
    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    /**
     * Publishes a working file onto {@code target} with {@link Files#createLink}, never replacing,
     * then removes the working file. The link's own result decides: created means published; an
     * existing name means an earlier publication, whose bytes must hash to {@code sha256}. No
     * earlier look at the target decides anything.
     *
     * <p>Two calls may publish one working file at once (a restart and an execution that lost its
     * lease, both holding the same committed intent), so the working file can vanish under either.
     * A call that finds it gone, before or at the link, looks at the target once more: when it is
     * there with the intent's bytes, the other call published it. A working file is never opened
     * for writing here; after the link it is a second name of the published bytes, and it is
     * only removed.
     *
     * @throws PublicationException {@code PUBLICATION_MISMATCH} when a working file or an existing
     *         target holds other bytes, or neither exists
     */
    public static Outcome publishFile(Path work, Path target, String sha256) {
        return publishFile(work, target, sha256, step -> { });
    }

    /** The points between which a concurrent publisher can act; tests run one there. */
    enum Step { STARTED, WORK_HASHED, TARGET_HASHED }

    static Outcome publishFile(Path work, Path target, String sha256, Consumer<Step> steps) {
        return PublicationFailures.redacting(() -> publish(work, target, sha256, steps));
    }

    private static Outcome publish(Path work, Path target, String sha256, Consumer<Step> steps) {
        requireSha256(sha256);
        try {
            steps.accept(Step.STARTED);
            Optional<String> workHash = sha256IfPresent(work);
            if (workHash.isEmpty()) {
                return workGone(work, target, sha256, steps);
            }
            if (!workHash.get().equals(sha256)) {
                throw new PublicationException(Reason.WORK_SHA256_MISMATCH);
            }
            steps.accept(Step.WORK_HASHED);
            try {
                Files.createLink(target, work);
            } catch (FileAlreadyExistsException e) {
                return alreadyPublished(work, target, sha256, steps);
            } catch (NoSuchFileException e) {
                return workGone(work, target, sha256, steps);
            }
            requireHash(target, sha256);
            Files.deleteIfExists(work);
            return Outcome.PUBLISHED;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Outcome workGone(Path work, Path target, String sha256, Consumer<Step> steps) throws IOException {
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            return alreadyPublished(work, target, sha256, steps);
        }
        throw new PublicationException(Reason.NOTHING_TO_PUBLISH);
    }
    private static Outcome alreadyPublished(Path work, Path target, String sha256, Consumer<Step> steps)
            throws IOException {
        requireHash(target, sha256);
        steps.accept(Step.TARGET_HASHED);
        Optional<String> workHash = sha256IfPresent(work);
        if (workHash.isPresent()) {
            if (!workHash.get().equals(sha256)) {
                throw new PublicationException(Reason.WORK_SHA256_MISMATCH);
            }
            Files.deleteIfExists(work);
        }
        return Outcome.ALREADY_PUBLISHED;
    }

    /** The hash of a managed file, or empty when it is missing or vanishes while it is read. */
    private static Optional<String> sha256IfPresent(Path file) {
        try {
            return Optional.of(sha256(file));
        } catch (PublicationException e) {
            if (Files.exists(file, NOFOLLOW_LINKS)) {
                throw e;
            }
            return Optional.empty();
        } catch (UncheckedIOException e) {
            if (e.getCause() instanceof NoSuchFileException) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static void requireHash(Path file, String sha256) {
        if (!sha256(file).equals(sha256)) {
            throw new PublicationException(Reason.TARGET_SHA256_MISMATCH);
        }
    }

    /**
     * The intent in a job execution context, if it holds one.
     *
     * @throws PublicationException when only one of its two keys is there, or it is malformed
     */
    public static Optional<Intent> intent(ExecutionContext context, String prefix) {
        Object work = context.get(prefix + WORK_PATH);
        Object sha256 = context.get(prefix + SHA256);
        if (work == null && sha256 == null) {
            return Optional.empty();
        }
        if (work instanceof String w && sha256 instanceof String s) {
            return Optional.of(new Intent(w, s));
        }
        throw new PublicationException(Reason.INTENT_INCOMPLETE);
    }

    /**
     * Commits {@code intent} into the job execution context with the job repository, in the
     * caller's fenced transaction, which the call joins. When that transaction does not commit
     * (a lost lease, a failure later in it), the keys are removed from the context again, so
     * Spring Batch's own save between steps cannot write an intent that never committed.
     *
     * @throws IllegalStateException outside a transaction, or when the context holds an intent already
     */
    public void commitIntent(JobExecution execution, String prefix, Intent intent) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("an intent is committed only in the step's fenced transaction");
        }
        ExecutionContext context = execution.getExecutionContext();
        if (intent(context, prefix).isPresent()) {
            throw new IllegalStateException("job execution " + execution.getId() + " holds a " + prefix
                    + " intent already");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    context.remove(prefix + WORK_PATH);
                    context.remove(prefix + SHA256);
                }
            }
        });
        context.putString(prefix + WORK_PATH, intent.workName());
        context.putString(prefix + SHA256, intent.sha256());
        repository.updateExecutionContext(execution);
    }

    /**
     * The first statement of a publish transaction (section 4.3): the lease renewal, whose
     * exclusive lock on the lease row is held until the commit, so no recovery revokes the lease
     * between this check and the publication. Call it before any other statement and any file
     * operation of that transaction.
     *
     * @throws LeaseLostException when the lease is gone; the execution is then lost in this process
     */
    public void beginPublish(JobExecution execution) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("a publish runs only in the step's fenced transaction");
        }
        long id = execution.getId();
        if (!leases.renew(id)) {
            keeper.lose(id);
            throw new LeaseLostException(id);
        }
    }

    /**
     * A start's clean-up (section 4.8): in {@code folder}, removes the working path that
     * {@code workName} gives for each other execution of the same job instance that is terminal
     * and whose path no committed intent under {@code prefix} names. Nothing else is removed. Only
     * regular files are removed, after the path checks of {@link OutputRoot#existingFile}; a link,
     * a folder or a file of another user at such a path fails the start with
     * {@code OUTPUT_PERMISSIONS} and is left in place.
     *
     * @param prefix the intent key prefix, or null for a job that commits no intent
     * @return the paths removed
     */
    public List<Path> cleanUp(JobExecution current, String prefix, Path folder, LongFunction<String> workName) {
        return PublicationFailures.redacting(() -> cleanUpFiles(current, prefix, folder, workName));
    }

    private List<Path> cleanUpFiles(JobExecution current, String prefix, Path folder, LongFunction<String> workName) {
        List<JobExecution> executions = repository.getJobExecutions(current.getJobInstance());
        Set<String> named = new HashSet<>();
        if (prefix != null) {
            for (ExecutionContext context : contexts(current, executions)) {
                if (context.get(prefix + WORK_PATH) instanceof String name) {
                    named.add(name);
                }
            }
        }
        List<Path> removed = new ArrayList<>();
        long currentId = current.getId();
        for (JobExecution execution : executions) {
            long id = execution.getId();
            if (id == currentId || !TERMINAL.contains(execution.getStatus())) {
                continue;
            }
            String name = workName.apply(id);
            if (!NAME.matcher(name).matches() || named.contains(name)) {
                continue;
            }
            Optional<Path> path = OutputRoot.existingFile(folder, name);
            if (path.isPresent()) {
                try {
                    Files.delete(path.get());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                removed.add(path.get());
            }
        }
        return List.copyOf(removed);
    }

    /**
     * A start's clean-up of a folder output (section 4.8): in {@code workRoot}
     * ({@code <kind>/.work}), removes the working folder that {@code workName} gives for each other
     * execution of the same job instance that is terminal and whose folder no committed intent
     * names, neither under {@code prefix} in a job execution context nor in {@code namedElsewhere}
     * (the working paths that run rows name, for a job whose intent is in its run row). Nothing
     * else is removed and no link is followed ({@link FolderPublication#removeWorkFolder}): a link,
     * a folder entry, a mode with a group or other bit or another owner, at the folder or at any of
     * its entries, fails the start with {@code OUTPUT_PERMISSIONS} and that folder keeps every
     * entry. A final folder, its {@code .claim} and its files keep their names and bytes.
     *
     * <p>The executions are taken in the repository's order and the first refused folder ends the
     * call with its exception: the folders removed before it stay removed, and the ones after it are
     * not looked at until a start passes it.
     *
     * <p>What the caller must hold, because this method cannot check it:
     * <ul>
     * <li>Call it only at a start, under the execution's lease, before the execution creates its own
     * working folder, and read {@code namedElsewhere} completely in the start's transaction after
     * the lease is taken and before this call.</li>
     * <li>A terminal execution can no longer commit an intent (its begin-publish is fenced), so the
     * names read then are every name an intent can hold; an intent a restart inherits from a
     * committed one names the same folder and must be among them.</li>
     * <li>{@code workName} must give different executions different names, as
     * {@link FolderPublication#workFolderName} does for one ID.</li>
     * <li>A producer writes its working files with {@link #createWorkFile} and the manifest with
     * {@link FolderPublication#writeManifest} before it commits the intent, and records the output
     * only after {@link FolderPublication#publishFolder} has verified the final folder.</li>
     * </ul>
     *
     * @param prefix the intent key prefix, or null for a job that commits no intent in its context
     * @param namedElsewhere working folder names that a committed intent outside the job execution
     *        context names; empty when there is none
     * @return the working folders removed
     */
    public List<Path> cleanUpFolders(JobExecution current, String prefix, Collection<String> namedElsewhere,
            Path workRoot, LongFunction<String> workName) {
        return PublicationFailures.redacting(
                () -> cleanUpWorkFolders(current, prefix, namedElsewhere, workRoot, workName));
    }

    private List<Path> cleanUpWorkFolders(JobExecution current, String prefix, Collection<String> namedElsewhere,
            Path workRoot, LongFunction<String> workName) {
        List<JobExecution> executions = repository.getJobExecutions(current.getJobInstance());
        Set<String> named = new HashSet<>(namedElsewhere);
        if (prefix != null) {
            for (ExecutionContext context : contexts(current, executions)) {
                if (context.get(prefix + WORK_PATH) instanceof String name) {
                    named.add(name);
                }
            }
        }
        List<Path> removed = new ArrayList<>();
        long currentId = current.getId();
        for (JobExecution execution : executions) {
            long id = execution.getId();
            if (id == currentId || !TERMINAL.contains(execution.getStatus())) {
                continue;
            }
            String name = workName.apply(id);
            if (!NAME.matcher(name).matches() || name.equals(".") || name.equals("..") || named.contains(name)) {
                continue;
            }
            if (FolderPublication.removeWorkFolder(workRoot, name)) {
                removed.add(workRoot.resolve(name));
            }
        }
        return List.copyOf(removed);
    }

    private static List<ExecutionContext> contexts(JobExecution current, List<JobExecution> executions) {
        List<ExecutionContext> contexts = new ArrayList<>();
        contexts.add(current.getExecutionContext());
        for (JobExecution execution : executions) {
            contexts.add(execution.getExecutionContext());
        }
        return contexts;
    }

    private static void requireSha256(String sha256) {
        if (sha256 == null || !HEX_SHA256.matcher(sha256).matches()) {
            throw new PublicationException(Reason.NO_SHA256);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }
}
