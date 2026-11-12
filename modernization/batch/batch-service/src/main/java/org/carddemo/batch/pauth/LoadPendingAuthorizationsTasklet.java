package org.carddemo.batch.pauth;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.carddemo.batch.data.ColumnCapacity;
import org.carddemo.batch.data.ColumnCapacityException;
import org.carddemo.batch.data.MissingColumnException;
import org.carddemo.batch.load.DataLoadException;
import org.carddemo.batch.load.DataLoadProperties;
import org.carddemo.batch.pauth.PendingAuthorizationRows.Detail;
import org.carddemo.batch.pauth.PendingAuthorizationRows.Summary;
import org.carddemo.platform.legacyformat.LegacyFormatException;
import org.carddemo.platform.legacyformat.record.HdUnloadReader;
import org.carddemo.platform.legacyformat.record.HdUnloadReader.Root;
import org.carddemo.platform.legacyformat.record.HdUnloadReader.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code loadPendingAuthorizations}: the pending-authorization HD unload into
 * {@code pauth.auth_summary} and {@code pauth.auth_detail}, in the step's one transaction. The
 * root whose key is all {@code X'40'} is skipped with its children (PAUDBUNL BR-PDUL-002); every
 * other segment must decode and refer to a loaded account. Tables that already hold rows are
 * refused unless a reload is allowed (D-41), and are then emptied with {@code DELETE}: the
 * {@code IDENTITY} is never reseeded, because fraud reports keep their {@code auth_id}, and
 * {@code auth_fraud} is never touched. Every row is checked against the columns before the first
 * write: a text value a column cannot hold unchanged fails the step, naming the column, where
 * SQL Server would store {@code ?} for it. Messages name the file, record and field, never content.
 */
final class LoadPendingAuthorizationsTasklet implements Tasklet {

    static final String STEP_NAME = "loadPendingAuthorizations";

    private static final Logger log = LoggerFactory.getLogger(LoadPendingAuthorizationsTasklet.class);

    private final JdbcClient db;
    private final PendingAuthorizationProperties properties;
    private final DataLoadProperties dataLoad;

    LoadPendingAuthorizationsTasklet(JdbcClient db, PendingAuthorizationProperties properties,
            DataLoadProperties dataLoad) {
        this.db = db;
        this.properties = properties;
        this.dataLoad = dataLoad;
    }

    private record Numbered<T>(int recordNumber, T row) {
    }

    private record LoadedRoot(Numbered<Summary> summary, List<Numbered<Detail>> details) {
    }

    private record Row(int recordNumber, String table, Map<String, Object> columns) {
    }

    private static final String SUMMARY_TABLE = "pauth.auth_summary";
    private static final String DETAIL_TABLE = "pauth.auth_detail";

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        String source = properties.sourceFile();
        if (source == null || source.isBlank()) {
            throw new DataLoadException("no pending-authorization file: set carddemo.batch.pauth.source-file");
        }
        Path file;
        try {
            file = Path.of(source).toAbsolutePath();
        } catch (InvalidPathException e) {
            throw new DataLoadException("carddemo.batch.pauth.source-file is not a valid path");
        }
        String name = file.getFileName().toString();
        if (!Files.isRegularFile(file)) {
            throw new DataLoadException(name + " not found (carddemo.batch.pauth.source-file)");
        }
        List<Root> roots;
        try {
            List<Segment> segments = HdUnloadReader.read(file, PendingAuthorizationRows.SEGMENTS);
            segments.forEach(s -> contribution.incrementReadCount());
            roots = HdUnloadReader.roots(segments, PendingAuthorizationRows.ROOT);
        } catch (LegacyFormatException e) {
            throw new DataLoadException(name + ": " + e.getMessage());
        } catch (UncheckedIOException e) {
            // The cause's message is the file's whole path.
            throw new DataLoadException(name + " cannot be read (" + e.getCause().getClass().getSimpleName() + ")");
        }

        List<LoadedRoot> loaded = decode(name, roots);
        int skipped = roots.size() - loaded.size();
        List<Row> rows = rows(loaded);
        requireCapacity(name, rows);
        clearTables();
        for (Row row : rows) {
            insert(name, row);
        }
        long details = rows.size() - loaded.size();
        contribution.incrementWriteCount(rows.size());
        log.info("{}: {} summaries and {} details from {}; {} all-space root(s) skipped", STEP_NAME, loaded.size(),
                details, name, skipped);
        return RepeatStatus.FINISHED;
    }

    private List<LoadedRoot> decode(String name, List<Root> roots) {
        Set<String> accounts = new HashSet<>(db.sql("SELECT acct_id FROM core.account").query(String.class).list());
        List<LoadedRoot> out = new ArrayList<>();
        String previous = null;
        for (Root root : roots) {
            Segment s = root.segment();
            if (s.data().allBytes("PA-ACCT-ID", 0x40)) {
                continue;
            }
            Summary summary = convert(name, s, () -> PendingAuthorizationRows.summary(s.data()));
            if (previous != null && summary.acctId().compareTo(previous) <= 0) {
                throw new DataLoadException(where(name, s) + ": PA-ACCT-ID is not above the previous summary's");
            }
            previous = summary.acctId();
            if (!accounts.contains(summary.acctId())) {
                throw new DataLoadException(where(name, s) + ": PA-ACCT-ID names an account that is not loaded");
            }
            List<Numbered<Detail>> details = new ArrayList<>();
            Set<Long> keys = new HashSet<>();
            for (Segment c : root.children()) {
                Detail d = convert(name, c, () -> PendingAuthorizationRows.detail(c.data()));
                if (!keys.add(((long) d.date9c() << 32) | (d.time9c() & 0xFFFFFFFFL))) {
                    throw new DataLoadException(where(name, c)
                            + ": PA-AUTH-DATE-9C and PA-AUTH-TIME-9C repeat a key under the same summary");
                }
                details.add(new Numbered<>(c.recordNumber(), d));
            }
            out.add(new LoadedRoot(new Numbered<>(s.recordNumber(), summary), details));
        }
        return out;
    }

    private static List<Row> rows(List<LoadedRoot> loaded) {
        List<Row> rows = new ArrayList<>();
        for (LoadedRoot root : loaded) {
            Numbered<Summary> s = root.summary();
            rows.add(new Row(s.recordNumber(), SUMMARY_TABLE, PendingAuthorizationRows.summaryColumns(s.row())));
            for (Numbered<Detail> d : root.details()) {
                rows.add(new Row(d.recordNumber(), DETAIL_TABLE,
                        PendingAuthorizationRows.detailColumns(s.row().acctId(), d.row())));
            }
        }
        return rows;
    }

    /** Fails on the first row, in file order, that a table cannot hold unchanged. */
    private void requireCapacity(String name, List<Row> rows) {
        ColumnCapacity summaries = ColumnCapacity.of(db, SUMMARY_TABLE);
        ColumnCapacity details = ColumnCapacity.of(db, DETAIL_TABLE);
        for (Row row : rows) {
            ColumnCapacity capacity = row.table().equals(SUMMARY_TABLE) ? summaries : details;
            try {
                capacity.requireColumns(row.columns().keySet());
                row.columns().forEach(capacity::require);
            } catch (ColumnCapacityException | MissingColumnException e) {
                throw new DataLoadException(name + " record " + row.recordNumber() + ": " + e.getMessage());
            }
        }
    }

    private void clearTables() {
        long summaries = count("pauth.auth_summary");
        long details = count("pauth.auth_detail");
        if (summaries + details == 0) {
            return;
        }
        if (!dataLoad.reloadAllowed()) {
            throw new DataLoadException("pauth.auth_summary and pauth.auth_detail already hold " + summaries + " and "
                    + details + " rows; a reload is allowed only in a local or demo environment (D-41)");
        }
        // Details first: the foreign key has no cascade (V507), so a summary with details cannot be deleted.
        db.sql("DELETE FROM pauth.auth_detail").update();
        db.sql("DELETE FROM pauth.auth_summary").update();
        log.info("{}: emptied {} summaries and {} details before the reload", STEP_NAME, summaries, details);
    }

    private long count(String table) {
        return db.sql("SELECT COUNT_BIG(*) FROM " + table).query(Long.class).single();
    }

    private void insert(String name, Row row) {
        Map<String, Object> columns = row.columns();
        String sql = "INSERT INTO " + row.table() + " (" + String.join(", ", columns.keySet()) + ") VALUES ("
                + String.join(", ", columns.keySet().stream().map(c -> ":" + c).toList()) + ")";
        refused(name, row.recordNumber(), row.table(), () -> {
            var spec = db.sql(sql);
            for (Map.Entry<String, Object> e : columns.entrySet()) {
                spec = spec.param(e.getKey(), e.getValue());
            }
            spec.update();
        });
    }

    private static void refused(String name, int recordNumber, String table, Runnable insert) {
        try {
            insert.run();
        } catch (DataAccessException e) {
            // The driver's message can quote key values; keep only the error number.
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            String code = cause instanceof SQLException sql ? "SQL Server error " + sql.getErrorCode()
                    : cause.getClass().getSimpleName();
            throw new DataLoadException(name + " record " + recordNumber + ": " + table + " refused the row (" + code
                    + ")");
        }
    }

    private static <T> T convert(String name, Segment s, java.util.function.Supplier<T> decode) {
        try {
            return decode.get();
        } catch (LegacyFormatException | ArithmeticException e) {
            String what = e instanceof ArithmeticException ? "a count or key out of range" : e.getMessage();
            throw new DataLoadException(where(name, s) + ": " + what);
        }
    }

    private static String where(String name, Segment s) {
        return name + " record " + s.recordNumber();
    }
}
