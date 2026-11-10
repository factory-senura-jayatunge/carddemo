package org.carddemo.batch.load;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.carddemo.batch.data.RefRows;
import org.carddemo.platform.validation.RuleTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code loadLookups}: the CSLKPCDY lists of the account update edits into
 * {@code ref.us_state}, {@code ref.us_state_zip_prefix} and {@code ref.nanp_area_code}.
 * The source is the validation library's {@code lookups.json}, which the Java and
 * TypeScript validators read, so the tables hold exactly the codes the edits accept.
 * One transaction for the three tables.
 */
final class LoadLookupsTasklet implements Tasklet {

    static final String STATES = "usStateCodes";
    static final String STATE_ZIP_PREFIXES = "usStateZipPrefixes";
    static final String AREA_CODES = "nanpGeneralPurposeAreaCodes";

    private static final Logger log = LoggerFactory.getLogger(LoadLookupsTasklet.class);

    private final JdbcClient db;
    private final RuleTable rules;

    LoadLookupsTasklet(JdbcClient db, RuleTable rules) {
        this.db = db;
        this.rules = rules;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        List<String> states = checked(STATES, "[A-Z]{2}");
        List<String> combos = checked(STATE_ZIP_PREFIXES, "[A-Z]{2}[0-9]{2}");
        List<String> areaCodes = checked(AREA_CODES, "[0-9]{3}");
        RefRows rows = new RefRows(db);
        db.sql("DELETE FROM ref.us_state_zip_prefix").update();
        db.sql("DELETE FROM ref.us_state").update();
        db.sql("DELETE FROM ref.nanp_area_code").update();
        int written = load(states, rows::insertState) + load(combos, rows::insertStateZipPrefix)
                + load(areaCodes, rows::insertAreaCode);
        contribution.incrementWriteCount(written);
        log.info("loadLookups: {} states, {} state and ZIP-prefix pairs, {} area codes", states.size(), combos.size(),
                areaCodes.size());
        return RepeatStatus.FINISHED;
    }

    private List<String> checked(String name, String pattern) {
        List<String> values = rules.list(name);
        Set<String> seen = new HashSet<>();
        for (String v : values) {
            if (!v.matches(pattern)) {
                throw new DataLoadException(RuleTable.LOOKUPS + " " + name + ": " + v + " does not match " + pattern);
            }
            if (!seen.add(v)) {
                throw new DataLoadException(RuleTable.LOOKUPS + " " + name + ": " + v + " is listed twice");
            }
        }
        return values;
    }

    private static int load(List<String> values, Consumer<String> insert) {
        values.forEach(insert);
        return values.size();
    }
}
