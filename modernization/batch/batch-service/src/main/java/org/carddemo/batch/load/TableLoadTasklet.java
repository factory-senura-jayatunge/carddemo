package org.carddemo.batch.load;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.function.UnaryOperator;

import org.carddemo.batch.data.CoreRows;
import org.carddemo.platform.legacyformat.LegacyTimestamps;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.carddemo.platform.legacyformat.record.RecordLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One load step: reads a sample file, checks it, empties the table and inserts
 * every record. The step's tasklet runs in one transaction, so the table holds
 * either the whole file or what it held before (all or nothing).
 */
abstract class TableLoadTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(TableLoadTasklet.class);

    protected final JdbcClient db;
    protected final CoreRows rows;
    private final DataLoadProperties properties;

    TableLoadTasklet(JdbcClient db, DataLoadProperties properties) {
        this.db = db;
        this.rows = new CoreRows(db);
        this.properties = properties;
    }

    abstract String stepName();

    abstract String fileName();

    abstract RecordLayout layout();

    /** Length of the record key at the start of the record (the KSDS {@code KEYS(n 0)}). */
    abstract int keyLength();

    abstract String table();

    /** How a key appears in messages; card numbers override this to show the last four only. */
    UnaryOperator<String> keyDisplay() {
        return UnaryOperator.identity();
    }

    /** Checks what the layout cannot (dates, references) and inserts one record. */
    abstract void insert(SourceFile file, SourceLine line);

    /** Called once before the first insert, for checks that need the whole file or other tables. */
    void beforeInsert(SourceFile file) {
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        SourceFile file = SourceFile.read(sourceDir(), fileName(), layout());
        file.requireAscendingKeys(keyLength(), keyDisplay());
        file.requireRepresentable();
        beforeInsert(file);
        db.sql("DELETE FROM " + table()).update();
        for (SourceLine line : file.lines()) {
            contribution.incrementReadCount();
            try {
                insert(file, line);
            } catch (DataAccessException e) {
                // The driver's message can quote key values (card numbers); keep only the error number.
                Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
                String code = cause instanceof SQLException sql ? "SQL Server error " + sql.getErrorCode()
                        : cause.getClass().getSimpleName();
                throw new DataLoadException(file.where(line) + ": " + table() + " refused the row (" + code + ")");
            }
        }
        contribution.incrementWriteCount(file.lines().size());
        log.info("{}: {} rows from {} into {}", stepName(), file.lines().size(), fileName(), table());
        return RepeatStatus.FINISHED;
    }

    // A property, not a job parameter: the job has an incrementer, and Spring Batch
    // ignores the parameters given to a job that has one.
    private Path sourceDir() {
        Path dir = properties.sourceDir();
        if (dir == null) {
            throw new DataLoadException("no source directory: set carddemo.data-load.source-dir");
        }
        return dir;
    }

    static void requireDate(SourceFile file, SourceLine line, String field, String value) {
        if (value != null && !value.isBlank() && LegacyTimestamps.parseDate(value).isEmpty()) {
            throw new DataLoadException(file.where(line) + ": " + field + " is not a valid YYYY-MM-DD date");
        }
    }
}
