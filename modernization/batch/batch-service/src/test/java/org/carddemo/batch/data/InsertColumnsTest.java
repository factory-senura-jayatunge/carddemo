package org.carddemo.batch.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.CardRecord;
import org.carddemo.platform.legacyformat.entity.CardXrefRecord;
import org.carddemo.platform.legacyformat.entity.CustomerRecord;
import org.carddemo.platform.legacyformat.entity.DiscGroupRecord;
import org.carddemo.platform.legacyformat.entity.TcatBalRecord;
import org.carddemo.platform.legacyformat.entity.TranCategoryRecord;
import org.carddemo.platform.legacyformat.entity.TranTypeRecord;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.junit.jupiter.api.Test;

/**
 * Every row the load inserts names exactly the columns its table has after the migrations in
 * the repository, read from their DDL with no database: a column the rows name and no migration
 * creates would fail every insert with SQL Server error 207, and a column a migration adds that
 * the rows leave out would be lost by the load and its re-export.
 */
public class InsertColumnsTest {

    private static final Path REPO = Path.of(System.getProperty("carddemo.repo.root", "../../..")).toAbsolutePath()
            .normalize();
    private static final Path MIGRATIONS = REPO.resolve("modernization/db/migrations");
    private static final Path SAMPLE_DIR = REPO.resolve("app/data/ASCII");
    /** A column the server fills, which no row names. */
    private static final Pattern SERVER_FILLED = Pattern.compile("(?is)\\S+\\s+(ROWVERSION|TIMESTAMP)\\b.*|.*\\bIDENTITY\\b.*");

    private static final Pattern VERSIONED = Pattern.compile("V(\\d+)__.*\\.sql");
    private static final Pattern CREATE = Pattern.compile("CREATE\\s+TABLE\\s+([\\w.\\[\\]]+)\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD = Pattern.compile(
            "ALTER\\s+TABLE\\s+([\\w.\\[\\]]+)\\s+ADD\\s+(?!CONSTRAINT\\b)([^;]*?)(?:;|\\n\\s*GO\\s*\\n|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP = Pattern.compile(
            "ALTER\\s+TABLE\\s+([\\w.\\[\\]]+)\\s+DROP\\s+COLUMN\\s+([^;]*?)(?:;|\\n\\s*GO\\s*\\n|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RENAME = Pattern.compile("sp_rename", Pattern.CASE_INSENSITIVE);

    /** Each table's columns, with their definitions, after every versioned migration in version order. */
    static Map<String, Map<String, String>> schema() {
        Map<String, Map<String, String>> tables = new HashMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(MIGRATIONS)) {
            files = s.filter(p -> VERSIONED.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(InsertColumnsTest::version)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path file : files) {
            String sql = withoutComments(read(file));
            assertThat(RENAME.matcher(sql).find()).as(file + " renames a column; teach this test sp_rename").isFalse();
            Matcher create = CREATE.matcher(sql);
            while (create.find()) {
                Map<String, String> columns = new LinkedHashMap<>();
                for (String element : topLevel(sql, create.end())) {
                    if (!element.matches("(?is)(CONSTRAINT|PRIMARY|UNIQUE|INDEX|CHECK|FOREIGN)\\b.*")) {
                        columns.put(firstWord(element), element);
                    }
                }
                tables.put(table(create.group(1)), columns);
            }
            Matcher add = ADD.matcher(sql);
            while (add.find()) {
                for (String element : splitTopLevel(add.group(2))) {
                    if (!element.matches("(?is)CONSTRAINT\\b.*")) {
                        tables.computeIfAbsent(table(add.group(1)), t -> new LinkedHashMap<>())
                                .put(firstWord(element), element);
                    }
                }
            }
            Matcher drop = DROP.matcher(sql);
            while (drop.find()) {
                for (String element : splitTopLevel(drop.group(2))) {
                    tables.getOrDefault(table(drop.group(1)), new LinkedHashMap<>()).remove(firstWord(element));
                }
            }
        }
        return tables;
    }

    /** The columns of {@code table} after the migrations that an insert may name: all but the server-filled ones. */
    public static Set<String> insertableColumns(String table) {
        Map<String, String> columns = schema().get(table);
        assertThat(columns).as("a migration creates " + table).isNotNull();
        Set<String> names = new LinkedHashSet<>(columns.keySet());
        names.removeAll(serverFilled(columns));
        return names;
    }

    private static Set<String> serverFilled(Map<String, String> columns) {
        Set<String> filled = new LinkedHashSet<>();
        columns.forEach((name, definition) -> {
            if (SERVER_FILLED.matcher(definition).matches()) {
                filled.add(name);
            }
        });
        return filled;
    }

    private static int version(Path p) {
        Matcher m = VERSIONED.matcher(p.getFileName().toString());
        if (!m.matches()) {
            throw new IllegalArgumentException(p.toString());
        }
        return Integer.parseInt(m.group(1));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String withoutComments(String sql) {
        return sql.replaceAll("--[^\\n]*", "");
    }

    private static String table(String name) {
        return name.replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
    }

    private static String firstWord(String element) {
        return element.strip().split("\\s+", 2)[0].replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
    }

    /** The comma-separated elements of the parenthesised list that starts at {@code from}. */
    private static List<String> topLevel(String sql, int from) {
        int depth = 1;
        int i = from;
        while (depth > 0) {
            char c = sql.charAt(i++);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return splitTopLevel(sql.substring(from, i - 1));
    }

    private static List<String> splitTopLevel(String list) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(list.substring(start, i));
                start = i + 1;
            }
        }
        out.add(list.substring(start));
        return out.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static <T> T first(String file, int length, Function<byte[], T> parse) {
        return parse.apply(LegacyFileReader.read(SAMPLE_DIR.resolve(file), length).get(0).record());
    }

    /** One row of each kind the load inserts, from the first record of each sample file. */
    private static Map<String, Map<String, Object>> rows() {
        AccountRecord account = first("acctdata.txt", Layouts.ACCOUNT.length(), AccountRecord::parse);
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        rows.put("core.account", CoreRows.accountRow(account,
                ZipGroup.classify(ZipGroup.asRead(account), AccountLayout.CVACT01Y)));
        rows.put("core.customer", CoreRows.customerRow(
                first("custdata.txt", Layouts.CUSTOMER.length(), CustomerRecord::parse)));
        rows.put("core.card", CoreRows.cardRow(first("carddata.txt", Layouts.CARD.length(), CardRecord::parse)));
        rows.put("core.card_xref", CoreRows.cardXrefRow(
                first("cardxref.txt", Layouts.CARD_XREF.length(), CardXrefRecord::parse)));
        rows.put("core.tcat_bal", CoreRows.categoryBalanceRow(
                first("tcatbal.txt", Layouts.TCAT_BAL.length(), TcatBalRecord::parse)));
        rows.put("ref.tran_type", RefRows.transactionTypeRow(
                first("trantype.txt", Layouts.TRAN_TYPE.length(), TranTypeRecord::parse)));
        rows.put("ref.tran_category", RefRows.categoryRow(
                first("trancatg.txt", Layouts.TRAN_CATEGORY.length(), TranCategoryRecord::parse)));
        rows.put("ref.disclosure_group", RefRows.disclosureGroupRow(
                first("discgrp.txt", Layouts.DISC_GROUP.length(), DiscGroupRecord::parse)));
        return rows;
    }

    @Test
    void theMigrationsAreReadAsTheirDdlSays() {
        Map<String, Map<String, String>> schema = schema();

        assertThat(schema.get("core.account").keySet()).containsExactly("acct_id", "active_status", "curr_bal", "credit_limit",
                "cash_credit_limit", "open_date", "expiration_date", "reissue_date", "curr_cyc_credit", "curr_cyc_debit",
                "addr_zip", "group_id", "legacy_filler", "row_version", "legacy_zip_group", "zip_group_origin");
        assertThat(schema.get("core.daily_reject").keySet()).as("ADD after CREATE, V413").contains("reason_cds");
        assertThat(schema.get("ref.tran_type").keySet()).containsExactly("type_cd", "description", "row_version");
        assertThat(serverFilled(schema.get("core.card"))).containsExactlyInAnyOrder("card_id", "row_version");
    }

    @Test
    void everyInsertNamesExactlyTheColumnsOfItsTable() {
        Map<String, Map<String, String>> schema = schema();

        rows().forEach((table, row) -> {
            assertThat(schema).as("a migration creates " + table).containsKey(table);
            Set<String> expected = new LinkedHashSet<>(schema.get(table).keySet());
            expected.removeAll(serverFilled(schema.get(table)));
            assertThat(row.keySet()).as(table).containsExactlyInAnyOrderElementsOf(expected);
        });
    }

    @Test
    void aRowNamingAColumnTheTableLacksIsRefusedByName() {
        Map<String, Map<String, String>> schema = schema();
        Set<String> beforeV418 = new LinkedHashSet<>(schema.get("core.account").keySet());
        beforeV418.removeAll(List.of("legacy_zip_group", "zip_group_origin"));
        ColumnCapacity account = ColumnCapacity.ofNames("core.account", beforeV418);
        Map<String, Object> row = rows().get("core.account");

        assertThatThrownBy(() -> account.requireColumns(row.keySet()))
                .isInstanceOf(MissingColumnException.class)
                .hasMessage("core.account has no column legacy_zip_group, zip_group_origin: the database is not"
                        + " migrated to the schema this build expects");
        ColumnCapacity.ofNames("core.account", schema.get("core.account").keySet()).requireColumns(row.keySet());
    }
}
