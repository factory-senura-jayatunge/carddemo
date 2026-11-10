package org.carddemo.batch.load;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * First step of dataLoadJob: the core and reference tables must be empty, or
 * emptied when a reload is allowed (D-41: seed load only, except in local and
 * demo environments). Children go first because of the foreign keys. The
 * transaction table is emptied too: the load starts it empty (TRANFILE's dummy
 * row is not carried over).
 */
final class ClearCoreTablesTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ClearCoreTablesTasklet.class);

    static final List<String> CHILDREN_FIRST = List.of(
            "core.[transaction]", "core.tcat_bal", "core.card_xref", "core.card", "core.customer", "core.account",
            "ref.disclosure_group", "ref.tran_category", "ref.tran_type",
            "ref.us_state_zip_prefix", "ref.us_state", "ref.nanp_area_code");

    private final JdbcClient db;
    private final DataLoadProperties properties;

    ClearCoreTablesTasklet(JdbcClient db, DataLoadProperties properties) {
        this.db = db;
        this.properties = properties;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : CHILDREN_FIRST) {
            counts.put(table, db.sql("SELECT COUNT_BIG(*) FROM " + table).query(Long.class).single());
        }
        Map<String, Long> nonEmpty = counts.entrySet().stream().filter(e -> e.getValue() > 0)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        if (nonEmpty.isEmpty()) {
            log.info("clearCoreTables: the core and reference tables are empty");
            return RepeatStatus.FINISHED;
        }
        if (!properties.reloadAllowed()) {
            throw new DataLoadException("the core and reference tables already hold rows " + nonEmpty
                    + "; a reload is allowed only in a local or demo environment (D-41)");
        }
        for (String table : CHILDREN_FIRST) {
            try {
                db.sql("DELETE FROM " + table).update();
            } catch (DataIntegrityViolationException e) {
                throw new DataLoadException(table + " has rows that other tables refer to (for example Bill Pay"
                        + " payments); recreate the database with make db-reset, then load");
            }
        }
        log.info("clearCoreTables: emptied {}", nonEmpty);
        return RepeatStatus.FINISHED;
    }
}
