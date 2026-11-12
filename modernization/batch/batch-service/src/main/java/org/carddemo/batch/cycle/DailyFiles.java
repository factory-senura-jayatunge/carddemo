package org.carddemo.batch.cycle;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Consumer;

import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.platform.outputroot.OutputRoot;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The daily file of a cycle attempt (contract section 4.9). The inbound file
 * {@code <CARDDEMO_VAR_DIR>/inbound/dailytran/<businessDate>.txt} is the operator's: no job moves,
 * renames, rewrites or deletes it. When {@code stage} first starts for an attempt, the execution
 * copies it into {@code dailytran/archive/.work/} under its bound output root, hashes the copy,
 * publishes it with no replace as {@code dailytran/archive/<businessDate>.<attempt>.<sha256>.txt}
 * and stores that name and hash on batch.cycle. From then on the attempt reads only that archive
 * copy and checks its hash at every start.
 */
final class DailyFiles {

    /** The archive copy that the stored identity names, with its content, which matched the stored hash. */
    record Located(Path path, byte[] content) { }

    /** The stored identity of an attempt: the archive copy's name and its SHA-256. */
    record Identity(String name, byte[] sha256) { }

    static final String DAILYTRAN = "dailytran";
    static final String ARCHIVE = "archive";
    static final String WORK = ".work";

    private final OutputRoots roots;
    private final JdbcClient db;
    private final OutputPublication publication;

    /**
     * Runs after the archive copy is published and before the identity commit, on the step's
     * thread; a test pauses an owner there to lose its lease at that point.
     */
    volatile Consumer<JobExecution> afterCopyPublished = execution -> { };

    DailyFiles(OutputRoots roots, JdbcClient db, OutputPublication publication) {
        this.roots = roots;
        this.db = db;
        this.publication = publication;
    }

    /** The inbound file's name. */
    String name(CycleRun run) {
        return run.businessDate() + ".txt";
    }

    Path inbound(CycleRun run) {
        return roots.inbound().resolve(DAILYTRAN).resolve(name(run));
    }

    /** The current instance's root for cycle-rerun, which runs outside any job execution. */
    Optional<Path> currentRoot() {
        return roots.currentRoot();
    }

    /** The archive copy's name: {@code <businessDate>.<attempt>.<sha256 hex>.txt}. */
    static String archiveName(CycleRun run, byte[] sha256) {
        return run.businessDate() + "." + run.attempt() + "." + HexFormat.of().formatHex(sha256) + ".txt";
    }

    /** The working copy of one execution: {@code <businessDate>.<attempt>.<jobExecutionId>.tmp}. */
    static String workName(CycleRun run, long jobExecutionId) {
        return run.businessDate() + "." + run.attempt() + "." + jobExecutionId + ".tmp";
    }

    /** The identity stored for the cycle's current attempt, or empty when it has none. */
    Optional<Identity> stored(CycleRun run) {
        return db.sql("SELECT attempt, daily_file_name, daily_file_sha256 FROM batch.cycle WHERE cycle_id = :id")
                .param("id", run.cycleId())
                .query((rs, n) -> {
                    if (rs.getInt(1) != run.attempt()) {
                        throw new CycleJobException("CYCLE_ATTEMPT_CHANGED", "cycle " + run.cycleId()
                                + " is at attempt " + rs.getInt(1) + ", not " + run.attempt());
                    }
                    return rs.getBytes(3) == null ? null : new Identity(rs.getString(2), rs.getBytes(3));
                })
                .optional();
    }

    /**
     * The archive copy to stage, in the step's fenced transaction. With no identity stored for the
     * attempt, this execution archives the inbound file and stores the identity in the caller's
     * transaction (compare-and-set); when another execution stored one first, that one is used.
     */
    Located locateForStage(CycleRun run, JobExecution execution) {
        Path root = roots.root(execution);
        Identity identity = stored(run).orElseGet(() -> archive(run, execution, root));
        return locate(run, identity, root);
    }

    private Identity archive(CycleRun run, JobExecution execution, Path root) {
        Path inbound = inbound(run);
        if (!Files.isRegularFile(inbound)) {
            throw new CycleJobException("DAILY_FILE_NOT_FOUND", "no daily file " + name(run) + " in the inbound folder");
        }
        Path archive = OutputRoot.ensureDirectory(root, DAILYTRAN, ARCHIVE);
        Path work = OutputRoot.ensureDirectory(root, DAILYTRAN, ARCHIVE, WORK);
        publication.cleanUp(execution, null, work, id -> workName(run, id));
        Path copy = work.resolve(workName(run, execution.getId()));
        try (OutputStream out = OutputPublication.createWorkFile(copy); InputStream in = Files.newInputStream(inbound)) {
            in.transferTo(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String hex = OutputPublication.sha256(copy);
        Identity copied = new Identity(archiveName(run, HexFormat.of().parseHex(hex)), HexFormat.of().parseHex(hex));
        OutputPublication.publishFile(copy, archive.resolve(copied.name()), hex);
        afterCopyPublished.accept(execution);
        int updated = db.sql("""
                        UPDATE batch.cycle SET daily_file_name = :name, daily_file_sha256 = :sha
                        WHERE cycle_id = :id AND attempt = :attempt AND daily_file_sha256 IS NULL""")
                .param("name", copied.name()).param("sha", copied.sha256()).param("id", run.cycleId())
                .param("attempt", run.attempt()).update();
        if (updated == 1) {
            return copied;
        }
        return stored(run).orElseThrow(() -> new CycleJobException("CYCLE_ATTEMPT_CHANGED", "cycle " + run.cycleId()
                + " is no longer at attempt " + run.attempt()));
    }

    private Located locate(CycleRun run, Identity identity, Path root) {
        if (!identity.name().equals(archiveName(run, identity.sha256()))) {
            throw new CycleJobException("DAILY_FILE_CHANGED", "the identity stored for cycle " + run.cycleId()
                    + " attempt " + run.attempt() + " names no archive copy of " + name(run));
        }
        Path file = OutputRoot.existingFile(root, DAILYTRAN, ARCHIVE, identity.name())
                .orElseThrow(() -> new CycleJobException("DAILY_FILE_NOT_FOUND", "the archive copy of " + name(run)
                        + " that cycle " + run.cycleId() + " attempt " + run.attempt() + " staged is missing"));
        byte[] content = read(file);
        if (!Arrays.equals(sha256(content), identity.sha256())) {
            throw new CycleJobException("DAILY_FILE_CHANGED", "the archive copy of " + name(run) + " that cycle "
                    + run.cycleId() + " attempt " + run.attempt() + " staged no longer has its stored checksum");
        }
        return new Located(file, content);
    }

    static byte[] read(Path file) {
        try (InputStream in = Files.newInputStream(file, NOFOLLOW_LINKS)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
