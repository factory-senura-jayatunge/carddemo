package org.carddemo.batch.pauth;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code purgeMessages}: deletes {@code processed_message} rows by {@code received_at} and sent
 * {@code outbox} rows by {@code created_at} that are older than the expiry (D-34), at most
 * {@code commitInterval} rows per transaction (one tasklet call each). Unsent outbox rows are never
 * deleted. Rows are chosen by age only, so a rerun or a second purge deletes nothing twice. The
 * bound is computed once per execution and kept in the step's execution context.
 */
final class PurgeMessagesTasklet implements Tasklet {

    static final String PROCESSED_MESSAGES_DELETED = "processedMessagesDeleted";
    static final String OUTBOX_ROWS_DELETED = "outboxRowsDeleted";
    static final String BEFORE = "messagesBefore";
    private static final String PHASE = "phase";
    private static final String NOTHING = "none";

    private static final Logger log = LoggerFactory.getLogger(PurgeMessagesTasklet.class);

    private final JdbcClient db;

    PurgeMessagesTasklet(JdbcClient db) {
        this.db = db;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        StepExecution step = chunkContext.getStepContext().getStepExecution();
        ExecutionContext context = step.getExecutionContext();
        PurgeParameters parameters = PurgeParameters.from(step.getJobParameters());
        if (!context.containsKey(BEFORE)) {
            LocalDate today = db.sql("SELECT CAST(SYSUTCDATETIME() AS DATE)").query(LocalDate.class).single();
            context.putString(BEFORE, parameters.messagesBefore(today).map(LocalDateTime::toString).orElse(NOTHING));
        }
        String bound = context.getString(BEFORE);
        if (bound.equals(NOTHING)) {
            return finish(step, context);
        }
        LocalDateTime before = LocalDateTime.parse(bound);
        int limit = parameters.commitInterval();
        if (!context.getString(PHASE, "messages").equals("outbox")) {
            int deleted = db.sql("DELETE TOP (:n) FROM pauth.processed_message WHERE received_at < :before")
                    .param("n", limit).param("before", before).update();
            add(context, PROCESSED_MESSAGES_DELETED, deleted, contribution);
            if (deleted < limit) {
                context.putString(PHASE, "outbox");
            }
            return RepeatStatus.CONTINUABLE;
        }
        int deleted = db.sql("""
                DELETE TOP (:n) FROM pauth.outbox
                WHERE sent_at IS NOT NULL AND created_at < :before""")
                .param("n", limit).param("before", before).update();
        add(context, OUTBOX_ROWS_DELETED, deleted, contribution);
        return deleted < limit ? finish(step, context) : RepeatStatus.CONTINUABLE;
    }

    private static void add(ExecutionContext context, String key, int deleted, StepContribution contribution) {
        context.putLong(key, context.getLong(key, 0) + deleted);
        contribution.incrementWriteCount(deleted);
    }

    private static RepeatStatus finish(StepExecution step, ExecutionContext context) {
        context.putLong(PROCESSED_MESSAGES_DELETED, context.getLong(PROCESSED_MESSAGES_DELETED, 0));
        context.putLong(OUTBOX_ROWS_DELETED, context.getLong(OUTBOX_ROWS_DELETED, 0));
        log.info("{}: {} processed messages and {} sent outbox rows deleted (before {})", step.getStepName(),
                context.getLong(PROCESSED_MESSAGES_DELETED), context.getLong(OUTBOX_ROWS_DELETED),
                context.getString(BEFORE));
        return RepeatStatus.FINISHED;
    }
}
