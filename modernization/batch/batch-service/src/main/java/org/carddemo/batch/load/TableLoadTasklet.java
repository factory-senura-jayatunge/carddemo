package org.carddemo.batch.load;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import org.carddemo.batch.BatchSwitches;
import org.carddemo.batch.data.ColumnCapacityException;
import org.carddemo.batch.data.CoreRows;
import org.carddemo.batch.data.MissingColumnException;
import org.carddemo.batch.data.RefRows;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitches;
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
    protected final DataLoadProperties properties;
    private final FidelitySwitches switches;
    protected CoreRows rows;
    protected RefRows refRows;

    TableLoadTasklet(JdbcClient db, DataLoadProperties properties, FidelitySwitches switches) {
        this.db = db;
        this.properties = properties;
        this.switches = switches;
    }

    /** The mode of DEF-ACUP-002 for this run, read when the step starts. */
    FidelityMode groupIdMode() {
        return switches.mode(BatchSwitches.DEF_ACUP_002);
    }

    abstract String stepName();

    abstract String fileName();

    abstract RecordLayout layout();

    /** Length of the record key at the start of the record (the KSDS {@code KEYS(n 0)}). */
    abstract int keyLength();

    abstract String table();

    /** Checks what the layout cannot (dates, references) and inserts one record. */
    abstract void insert(SourceFile file, SourceLine line);

    /** Reads the step's file; a step that must hash exactly the bytes it loads overrides it. */
    SourceFile readSource(StepContribution contribution, Path dir) {
        return SourceFile.read(dir, fileName(), layout());
    }

    /**
     * Called once after the file-wide checks and before anything is deleted, for checks and
     * classifications that need the whole file and may report to the step.
     */
    void prepare(StepContribution contribution, SourceFile file) {
    }

    /** Called once before the first insert, for checks that need the whole file or other tables. */
    void beforeInsert(SourceFile file) {
    }

    /** Called once after the last insert, to report what the step stored. */
    void afterInsert(StepContribution contribution, SourceFile file) {
    }

    /** Appended to the step's summary log line. */
    String loadDetail() {
        return "";
    }

    /**
     * The 1-based positions of the unnamed FILLERs whose content this step drops: the file may
     * hold anything there and the export writes the constant. Every other unnamed FILLER must
     * hold its constant, or the step fails.
     */
    Set<Integer> droppedFillers() {
        return Set.of();
    }

    /** Reports the lines whose dropped FILLER did not hold its constant; called before the first insert. */
    void reportDroppedFillers(StepContribution contribution, SourceFile file, List<Integer> lines) {
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        rows = new CoreRows(db);
        refRows = new RefRows(db);
        SourceFile file = readSource(contribution, sourceDir());
        file.requireAscendingKeys(keyLength());
        List<Integer> dropped = file.requireRepresentable(droppedFillers());
        if (!droppedFillers().isEmpty()) {
            reportDroppedFillers(contribution, file, dropped);
        }
        prepare(contribution, file);
        beforeInsert(file);
        db.sql("DELETE FROM " + table()).update();
        for (SourceLine line : file.lines()) {
            contribution.incrementReadCount();
            try {
                insert(file, line);
            } catch (DataLoadException e) {
                throw e;
            } catch (ColumnCapacityException | MissingColumnException e) {
                // The file, line and column identify the value; the key would be personal data (D-NFR-04).
                throw new DataLoadException(file.where(line) + ": " + e.getMessage());
            } catch (DataAccessException e) {
                // The driver's message can quote the row's values; keep only the error number.
                Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
                String code = cause instanceof SQLException sql ? "SQL Server error " + sql.getErrorCode()
                        : cause.getClass().getSimpleName();
                throw new DataLoadException(file.where(line) + ": " + table() + " refused the row (" + code + ")");
            } catch (RuntimeException e) {
                throw new DataLoadException(file.where(line) + ": " + table() + " refused the row ("
                        + e.getClass().getSimpleName() + ")");
            }
        }
        contribution.incrementWriteCount(file.lines().size());
        afterInsert(contribution, file);
        log.info("{}: {} rows from {} into {}{}", stepName(), file.lines().size(), fileName(), table(), loadDetail());
        return RepeatStatus.FINISHED;
    }

    // A property, not a job parameter: a launch names the run, not where its files are.
    private Path sourceDir() {
        String dir = properties.sourceDir();
        if (dir == null || dir.isBlank()) {
            throw new DataLoadException("no source directory: set carddemo.data-load.source-dir");
        }
        try {
            return Path.of(dir);
        } catch (InvalidPathException e) {
            // Its message quotes the setting's value, which may name a folder below CARDDEMO_VAR_DIR.
            throw new DataLoadException("carddemo.data-load.source-dir is not a valid path");
        }
    }

    static void requireDate(SourceFile file, SourceLine line, String field, String value) {
        if (value != null && !value.isBlank() && LegacyTimestamps.parseDate(value).isEmpty()) {
            throw new DataLoadException(file.where(line) + ": " + field + " is not a valid YYYY-MM-DD date");
        }
    }
}
