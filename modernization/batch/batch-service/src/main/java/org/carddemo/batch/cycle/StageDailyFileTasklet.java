package org.carddemo.batch.cycle;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.carddemo.batch.data.ColumnCapacity;
import org.carddemo.batch.data.ColumnCapacityException;
import org.carddemo.platform.legacyformat.LegacyFormatException;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ingestDailyFileJob's {@code stage}: the daily file's records into core.daily_tran, with seq =
 * record number, {@code chunkSize} records per transaction. The fields are kept as read, without
 * validation, so staging never refuses a record that posting must reject; only a field that its
 * column cannot hold unchanged fails the stage. It reads the attempt's archive copy, never the
 * inbound file after the copy ({@link DailyFiles}). A restart resumes after the records already
 * staged, which the table itself says.
 */
final class StageDailyFileTasklet implements Tasklet, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(StageDailyFileTasklet.class);

    private static final String INSERT = """
            INSERT INTO core.daily_tran (cycle_id, seq, origin, tran_id, type_cd, cat_cd, source, description,
                amount, merchant_id, merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts,
                raw_record)
            VALUES (:cycle_id, :seq, 'DAILY_FILE', :tran_id, :type_cd, :cat_cd, :source, :description, :amount,
                :merchant_id, :merchant_name, :merchant_city, :merchant_zip, :card_num, :orig_ts, :proc_ts,
                :raw_record)""";

    private final JdbcClient db;
    private final NamedParameterJdbcTemplate batch;
    private final DailyFiles files;
    private final int chunkSize;
    private final Map<Long, List<SourceLine>> records = new ConcurrentHashMap<>();
    private volatile ColumnCapacity capacity;

    StageDailyFileTasklet(JdbcClient db, NamedParameterJdbcTemplate batch, DailyFiles files, int chunkSize) {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("carddemo.batch.files.chunk-size must be at least 1");
        }
        this.db = db;
        this.batch = batch;
        this.files = files;
        this.chunkSize = chunkSize;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        StepExecution step = contribution.getStepExecution();
        CycleRun run = CycleRun.of(step);
        List<SourceLine> lines = records.get(step.getId());
        if (lines == null) {
            DailyFiles.Located file = files.locateForStage(run, step.getJobExecution());
            lines = parse(file.content(), files.name(run));
            records.put(step.getId(), lines);
            log.info("stage: cycle {} attempt {} reads {} records of its archive copy of {}", run.cycleId(),
                    run.attempt(), lines.size(), files.name(run));
        }
        int staged = db.sql("SELECT COALESCE(MAX(seq), 0) FROM core.daily_tran WHERE cycle_id = :id AND origin = 'DAILY_FILE'")
                .param("id", run.cycleId()).query(Integer.class).single();
        int end = Math.min(lines.size(), staged + chunkSize);
        ColumnCapacity capacity = capacity();
        SqlParameterSource[] rows = new SqlParameterSource[end - staged];
        for (int i = staged; i < end; i++) {
            rows[i - staged] = row(run.cycleId(), lines.get(i), capacity, files.name(run));
        }
        if (rows.length > 0) {
            batch.batchUpdate(INSERT, rows);
        }
        for (int i = 0; i < rows.length; i++) {
            contribution.incrementReadCount();
        }
        contribution.incrementWriteCount(rows.length);
        return end < lines.size() ? RepeatStatus.CONTINUABLE : RepeatStatus.FINISHED;
    }

    @Override
    public ExitStatus afterStep(StepExecution step) {
        records.remove(step.getId());
        return null;
    }

    private static List<SourceLine> parse(byte[] content, String name) {
        try {
            return LegacyFileReader.read(new ByteArrayInputStream(content), Layouts.DAILY_TRAN.length(), name);
        } catch (LegacyFormatException e) {
            throw new CycleJobException("DAILY_FILE_INVALID", e.getMessage());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ColumnCapacity capacity() {
        ColumnCapacity c = capacity;
        if (c == null) {
            c = ColumnCapacity.of(db, "core.daily_tran");
            capacity = c;
        }
        return c;
    }

    /**
     * A record whose text a column cannot hold unchanged fails the stage, naming the record
     * number and the column: storing it cut or with '?' would post something else than the file says.
     */
    private static SqlParameterSource row(long cycleId, SourceLine line, ColumnCapacity capacity, String fileName) {
        FixedRecord r = line.view(Layouts.DAILY_TRAN);
        BigDecimal amount;
        try {
            amount = r.decimal("DALYTRAN-AMT");
        } catch (LegacyFormatException e) {
            amount = null;
        }
        Map<String, String> text = new LinkedHashMap<>();
        text.put("tran_id", r.raw("DALYTRAN-ID"));
        text.put("type_cd", r.raw("DALYTRAN-TYPE-CD"));
        text.put("cat_cd", r.raw("DALYTRAN-CAT-CD"));
        text.put("source", r.text("DALYTRAN-SOURCE"));
        text.put("description", r.text("DALYTRAN-DESC"));
        text.put("merchant_id", r.raw("DALYTRAN-MERCHANT-ID"));
        text.put("merchant_name", r.text("DALYTRAN-MERCHANT-NAME"));
        text.put("merchant_city", r.text("DALYTRAN-MERCHANT-CITY"));
        text.put("merchant_zip", r.text("DALYTRAN-MERCHANT-ZIP"));
        text.put("card_num", r.raw("DALYTRAN-CARD-NUM"));
        text.put("orig_ts", r.raw("DALYTRAN-ORIG-TS"));
        text.put("proc_ts", r.raw("DALYTRAN-PROC-TS"));
        text.put("raw_record", new String(line.record(), StandardCharsets.ISO_8859_1));
        MapSqlParameterSource row = new MapSqlParameterSource()
                .addValue("cycle_id", cycleId).addValue("seq", line.lineNumber())
                .addValue("amount", amount, Types.DECIMAL);
        try {
            text.forEach((column, value) -> {
                capacity.require(column, value);
                row.addValue(column, value);
            });
        } catch (ColumnCapacityException e) {
            throw new CycleJobException("DAILY_FILE_INVALID", fileName + " record " + line.lineNumber() + ": "
                    + e.getMessage());
        }
        return row;
    }
}
