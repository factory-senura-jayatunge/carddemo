package org.carddemo.batch.transactionreport;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.execution.PublicationException;
import org.carddemo.batch.internal.ExecutionOutputs;
import org.carddemo.platform.outputroot.OutputRoot;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/** TRANREPT path layout; the shared file-publication API owns the publication protocol. */
final class ReportPublication {
    static final String PREFIX = "carddemo.tranrept";
    static final String WORK = PREFIX + OutputPublication.WORK_PATH;
    static final String HASH = PREFIX + OutputPublication.SHA256;
    static final String TARGET = "TRANREPT.txt";
    /** Written only in the transaction that completes the run row. */
    static final String OUTPUT = ExecutionOutputs.OUTPUT_PREFIX + "report";
    private static final Pattern WORK_NAME = Pattern.compile("TRANREPT\\.txt\\.[1-9][0-9]*\\.tmp");
    private final Path root;
    private final String runId;

    ReportPublication(Path root, long runId) {
        this.root = root;
        this.runId = Long.toString(runId);
    }

    /** The output's path below the instance root, as transaction-service derives it from the run row. */
    static String relativePath(long runId) {
        return "reports/tranrept/" + runId + "/" + TARGET;
    }

    /** Whether {@code context} carries an intent whose publication completed the run row. */
    static boolean finished(ExecutionContext context, long runId) {
        return context.get(WORK) instanceof String && context.get(HASH) instanceof String
                && relativePath(runId).equals(context.get(OUTPUT));
    }

    /**
     * Checks a published report against its intent, removing a working file with the same bytes.
     * The target must exist first, so this never publishes.
     */
    void verifyPublished(OutputPublication.Intent intent) {
        if (!targetExists()) {
            mismatch();
        }
        OutputPublication.publishFile(work(intent.workName()), target(), intent.sha256());
    }

    Path directory() {
        return OutputRoot.ensureDirectory(root, "reports", "tranrept", runId);
    }

    Path folder() {
        return root.resolve("reports").resolve("tranrept").resolve(runId);
    }

    Path target() {
        return folder().resolve(TARGET);
    }

    String workName(long executionId) {
        return OutputPublication.workFileName(TARGET, executionId);
    }

    OutputStream create(long executionId) {
        return OutputPublication.createWorkFile(directory().resolve(workName(executionId)));
    }

    boolean targetExists() {
        return OutputRoot.existingFile(root, "reports", "tranrept", runId, TARGET).isPresent();
    }

    Path work(String work) {
        if (work == null || !WORK_NAME.matcher(work).matches()) {
            mismatch();
        }
        return folder().resolve(work);
    }

    String hashWork(String work) {
        return OutputPublication.sha256(work(work));
    }

    static void mismatch() {
        throw new PublicationException("TRANREPT");
    }
}
