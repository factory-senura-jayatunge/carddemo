package org.carddemo.batch.cycle;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.function.Supplier;

import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.batch.internal.ExecutionOutputs;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.outputroot.OutputRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * postDailyTransactionsJob's {@code exportRejects}: the cycle's rejects as CBTRN02C's DALYREJS file,
 * one 430-byte record and a line feed per reject in reject order (batch.md, files and tables), at
 * {@code rejects/<cycleId>-<attempt>/DALYREJS.txt} under the execution's bound output root
 * (contract section 4.10). Its inputs are fixed for the job instance (section 4.8, "no intent,
 * inputs fixed"): one statement reads the cycle's current attempt with its rejects, and a cycle
 * that a rerun has moved to another attempt fails the step with {@code CYCLE_ATTEMPT_CHANGED}
 * before anything is written. So every execution of the instance builds the same bytes. The
 * execution writes them into its own working file {@code DALYREJS.txt.<jobExecutionId>.tmp}
 * ({@code 0600}, created new) and links it onto the target with no replace; a target that exists
 * already must have the same SHA-256, otherwise the step fails with {@code PUBLICATION_MISMATCH}
 * and nothing is replaced. The records carry the full card numbers of the input.
 */
final class ExportRejectsTasklet implements Tasklet {

    static final String FILE_NAME = "DALYREJS.txt";
    static final String OUTPUT = ExecutionOutputs.OUTPUT_PREFIX + "rejectFile";
    private static final int RAW_LENGTH = Layouts.DAILY_TRAN.length();
    private static final int DESC_LENGTH = Layouts.DAILY_REJECT.length() - RAW_LENGTH - 4;
    private static final String READ = """
            SELECT c.attempt, r.seq, r.raw_record, r.reason_cd, r.reason_desc
            FROM batch.cycle AS c
            LEFT JOIN core.daily_reject AS r ON r.cycle_id = c.cycle_id
            WHERE c.cycle_id = :id
            ORDER BY r.seq""";

    private static final Logger log = LoggerFactory.getLogger(ExportRejectsTasklet.class);

    private final JdbcClient db;
    private final OutputRoots roots;
    private final OutputPublication publication;

    ExportRejectsTasklet(JdbcClient db, OutputRoots roots, OutputPublication publication) {
        this.db = db;
        this.roots = roots;
        this.publication = publication;
    }

    /** The reject file of a cycle attempt, relative to the instance root. */
    static Path relativePath(CycleRun run) {
        return Path.of("rejects", run.cycleId() + "-" + run.attempt(), FILE_NAME);
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        JobExecution job = contribution.getStepExecution().getJobExecution();
        CycleRun run = CycleRun.of(contribution.getStepExecution());
        Path root = roots.root(job);
        Export export = new Export(run, () -> {
            Path folder = OutputRoot.ensureDirectory(root, "rejects", run.cycleId() + "-" + run.attempt());
            publication.cleanUp(job, null, folder, id -> OutputPublication.workFileName(FILE_NAME, id));
            return folder.resolve(OutputPublication.workFileName(FILE_NAME, job.getId()));
        });
        try {
            db.sql(READ).param("id", run.cycleId()).query((RowCallbackHandler) export::row);
            export.finish();
        } catch (RuntimeException e) {
            export.abandon(e);
            throw e;
        }
        OutputPublication.publishFile(export.work, export.work.resolveSibling(FILE_NAME), export.sha256());
        ExecutionContext context = job.getExecutionContext();
        context.put(ExecutionOutputs.REJECT_COUNT, export.count);
        context.put(OUTPUT, relativePath(run).toString().replace('\\', '/'));
        contribution.incrementWriteCount(export.count);
        log.info("exportRejects: cycle {} attempt {} wrote {} rejects", run.cycleId(), run.attempt(), export.count);
        return RepeatStatus.FINISHED;
    }

    /**
     * The attempt's folder, the clean-up and the working file, all only after the first row showed
     * that the cycle is still at the instance's attempt, so a stale owner writes nothing.
     */
    private static final class Export {

        private final CycleRun run;
        private final Supplier<Path> prepare;
        private final MessageDigest digest = sha256Digest();
        private Path work;
        private OutputStream out;
        private long count;

        Export(CycleRun run, Supplier<Path> prepare) {
            this.run = run;
            this.prepare = prepare;
        }

        void row(ResultSet rs) throws SQLException {
            try {
                if (out == null) {
                    int attempt = rs.getInt(1);
                    if (attempt != run.attempt()) {
                        throw new CycleJobException("CYCLE_ATTEMPT_CHANGED", "cycle " + run.cycleId() + " is at attempt "
                                + attempt + ", not " + run.attempt() + "; no reject file is written");
                    }
                    work = prepare.get();
                    out = new DigestOutputStream(OutputPublication.createWorkFile(work), digest);
                }
                int seq = rs.getInt(2);
                if (rs.wasNull()) {
                    return;
                }
                out.write(record(seq, rs.getString(3), rs.getString(4), rs.getString(5)));
                out.write('\n');
                count++;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void finish() {
            if (out == null) {
                throw new IllegalStateException("cycle " + run.cycleId() + " does not exist");
            }
            try {
                out.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String sha256() {
            return HexFormat.of().formatHex(digest.digest());
        }

        /** A failure before the publish removes this execution's own working file, never another path. */
        void abandon(RuntimeException failure) {
            if (out == null) {
                return;
            }
            try {
                out.close();
            } catch (IOException e) {
                failure.addSuppressed(e);
            }
            try {
                Files.deleteIfExists(work);
            } catch (IOException e) {
                failure.addSuppressed(e);
            }
        }

        private static MessageDigest sha256Digest() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is part of every JDK", e);
            }
        }
    }

    /**
     * CBTRN02C's REJECT-RECORD: the input record as read, the 4-digit reason and the reason text
     * padded to 76 characters, in the one-byte encoding of the input file.
     */
    static byte[] record(int seq, String raw, String reasonCd, String reasonDesc) {
        if (raw == null || raw.length() != RAW_LENGTH) {
            throw new CycleJobException("REJECT_RECORD_INVALID", "reject " + seq + " does not hold a "
                    + RAW_LENGTH + "-byte input record");
        }
        if (reasonCd == null || reasonCd.length() != 4 || reasonDesc == null || reasonDesc.length() > DESC_LENGTH) {
            throw new CycleJobException("REJECT_RECORD_INVALID", "reject " + seq + " has no valid reason");
        }
        String text = raw + reasonCd + reasonDesc + " ".repeat(DESC_LENGTH - reasonDesc.length());
        CharsetEncoder latin1 = StandardCharsets.ISO_8859_1.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer bytes = latin1.encode(CharBuffer.wrap(text));
            byte[] record = new byte[bytes.remaining()];
            bytes.get(record);
            return record;
        } catch (CharacterCodingException e) {
            throw new CycleJobException("REJECT_RECORD_INVALID", "reject " + seq
                    + " holds a character that no input byte maps to");
        }
    }
}
