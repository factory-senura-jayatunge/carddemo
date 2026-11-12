package org.carddemo.batch.data;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.carddemo.platform.legacyformat.entity.DiscGroupRecord;
import org.carddemo.platform.legacyformat.entity.TranCategoryRecord;
import org.carddemo.platform.legacyformat.entity.TranTypeRecord;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The mapping between the legacy reference records and the {@code ref} tables, in
 * both directions, as {@link CoreRows} does for the core tables: the load inserts
 * with it and the re-export (verify-data) reads with it. The lookup lists have no
 * legacy file; their rows are the codes as the list holds them.
 */
public final class RefRows {

    private final JdbcClient db;
    private final Map<String, ColumnCapacity> capacities = new HashMap<>();

    public RefRows(JdbcClient db) {
        this.db = db;
    }

    // --- transaction type -----------------------------------------------------------------

    public void insert(TranTypeRecord t) {
        insert("ref.tran_type", transactionTypeRow(t));
    }

    static Map<String, Object> transactionTypeRow(TranTypeRecord t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type_cd", t.typeCd());
        row.put("description", t.description());
        return row;
    }

    public List<TranTypeRecord> transactionTypes() {
        return db.sql("SELECT type_cd, description FROM ref.tran_type ORDER BY type_cd")
                .query((rs, n) -> new TranTypeRecord(rs.getString("type_cd"), rs.getString("description")))
                .list();
    }

    // --- transaction category -------------------------------------------------------------

    public void insert(TranCategoryRecord c) {
        insert("ref.tran_category", categoryRow(c));
    }

    static Map<String, Object> categoryRow(TranCategoryRecord c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type_cd", c.typeCd());
        row.put("cat_cd", c.catCd());
        row.put("description", c.description());
        return row;
    }

    public List<TranCategoryRecord> categories() {
        return db.sql("SELECT type_cd, cat_cd, description FROM ref.tran_category ORDER BY type_cd, cat_cd")
                .query((rs, n) -> new TranCategoryRecord(rs.getString("type_cd"), rs.getString("cat_cd"),
                        rs.getString("description")))
                .list();
    }

    // --- disclosure group -----------------------------------------------------------------

    public void insert(DiscGroupRecord d) {
        insert("ref.disclosure_group", disclosureGroupRow(d));
    }

    static Map<String, Object> disclosureGroupRow(DiscGroupRecord d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("group_id", d.groupId());
        row.put("type_cd", d.typeCd());
        row.put("cat_cd", d.catCd());
        row.put("int_rate", d.intRate());
        return row;
    }

    public List<DiscGroupRecord> disclosureGroups() {
        return db.sql("""
                SELECT group_id, type_cd, cat_cd, int_rate FROM ref.disclosure_group
                ORDER BY group_id, type_cd, cat_cd
                """)
                .query((rs, n) -> new DiscGroupRecord(rs.getString("group_id"), rs.getString("type_cd"),
                        rs.getString("cat_cd"), rs.getBigDecimal("int_rate")))
                .list();
    }

    /** As {@link CoreRows}: a text value is stored unchanged or the insert fails. */
    private void insert(String table, Map<String, Object> row) {
        CoreRows.insertRow(db, capacities.computeIfAbsent(table, t -> ColumnCapacity.of(db, t)), table, row);
    }

    // --- lookup lists ---------------------------------------------------------------------

    public void insertState(String stateCd) {
        db.sql("INSERT INTO ref.us_state (state_cd) VALUES (:s)").param("s", stateCd).update();
    }

    /** @param combo the state code and the first two ZIP digits, as {@code usStateZipPrefixes} holds them */
    public void insertStateZipPrefix(String combo) {
        db.sql("INSERT INTO ref.us_state_zip_prefix (state_cd, zip_prefix) VALUES (:s, :z)")
                .param("s", combo.substring(0, 2))
                .param("z", combo.substring(2))
                .update();
    }

    public void insertAreaCode(String areaCode) {
        db.sql("INSERT INTO ref.nanp_area_code (area_code) VALUES (:a)").param("a", areaCode).update();
    }

    public List<String> states() {
        return db.sql("SELECT state_cd FROM ref.us_state ORDER BY state_cd").query(String.class).list();
    }

    /** In the list's form: state code followed by the ZIP prefix. */
    public List<String> stateZipPrefixes() {
        return db.sql("SELECT state_cd + zip_prefix FROM ref.us_state_zip_prefix ORDER BY state_cd, zip_prefix")
                .query(String.class).list();
    }

    public List<String> areaCodes() {
        return db.sql("SELECT area_code FROM ref.nanp_area_code ORDER BY area_code").query(String.class).list();
    }
}
