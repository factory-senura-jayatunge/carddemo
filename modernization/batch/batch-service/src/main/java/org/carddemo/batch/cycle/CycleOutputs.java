package org.carddemo.batch.cycle;

import java.nio.file.Path;

/**
 * What the cycle jobs leave for a reader outside batch-service: the step names, the counts each
 * step keeps in its execution context, and the reject record encoder, so that the differential
 * exporter reads the same keys and writes DALYREJS with the same bytes as the job.
 */
public final class CycleOutputs {

    public static final String INGEST_JOB = "ingestDailyFileJob";
    public static final String POST_JOB = "postDailyTransactionsJob";
    public static final String INTEREST_JOB = "calculateInterestJob";

    public static final String POST_STEP = "post";
    public static final String POSTED = PostDailyTransactionsTasklet.POSTED;
    public static final String REJECTED = PostDailyTransactionsTasklet.REJECTED;

    public static final String INTEREST_STEP = "interest";
    public static final String INTEREST_ACCOUNTS = CalculateInterestTasklet.ACCOUNTS;
    public static final String INTEREST_TRANSACTIONS = CalculateInterestTasklet.TRANSACTIONS;

    /** The job execution context key that names the reject file, relative to the output folder. */
    public static final String REJECT_FILE = ExportRejectsTasklet.OUTPUT;

    private CycleOutputs() {
    }

    /** One DALYREJS record as {@code exportRejects} writes it, without the line feed. */
    public static byte[] rejectRecord(int seq, String raw, String reasonCd, String reasonDesc) {
        return ExportRejectsTasklet.record(seq, raw, reasonCd, reasonDesc);
    }

    /** The reject file of a cycle attempt, relative to the output folder. */
    public static Path rejectFile(long cycleId, int attempt) {
        return ExportRejectsTasklet.relativePath(new CycleRun(cycleId, null, attempt));
    }
}
