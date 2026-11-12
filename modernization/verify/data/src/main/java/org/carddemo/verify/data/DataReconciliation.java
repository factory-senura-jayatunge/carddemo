package org.carddemo.verify.data;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.carddemo.batch.data.CoreRows;
import org.carddemo.batch.data.RefRows;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.legacyformat.entity.UserSecurityRecord;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FieldSpec;
import org.carddemo.platform.legacyformat.record.FieldType;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.carddemo.platform.validation.RuleTable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reconciles the database with the legacy sample data after {@code make load}: per core
 * and reference table the row count, the key set, the control totals and a byte-exact
 * re-export of every record (CRLF read as LF, the 36-byte xref records padded to 50,
 * customer positions 333-500 compared as spaces and the lines that held anything else counted);
 * the lookup tables against the validators' lists; positions 103-122 of every account against
 * the layout its manifest declares (DEF-ACUP-002); then the relations between the
 * tables, and the users of {@code DUSRSECJ} against {@code iam.app_user} (no
 * passwords). Accounts re-export per row, with no mode (the per-row rule of {@link CoreRows}).
 *
 * <p>Findings name fields, never values. Account, customer, card and category balance
 * keys are never shown, not even masked: a finding points at the line of the sample
 * file, or at the ordinal of a row in key order when the row has no line. Only the
 * public reference codes (transaction types, categories, disclosure groups, lookup
 * codes) and the user IDs of the seed job are named; a user that is not in the seed job
 * is only counted.
 */
public final class DataReconciliation {

    /** At most this many keys, lines or rows per finding; the count is always given. */
    static final int MAX_KEYS = 10;

    private final JdbcClient db;
    private final Path sourceDir;
    private final Path usersJcl;
    private final GroupIdMode groupIdMode;
    private final Path accountLayoutManifest;

    public DataReconciliation(JdbcClient db, Path sourceDir, Path usersJcl, FidelityMode groupIdMode) {
        this(db, sourceDir, usersJcl, groupIdMode, null);
    }

    /** @param accountLayoutManifest the manifest the load was given, or {@code null} for the built-in lookup */
    public DataReconciliation(JdbcClient db, Path sourceDir, Path usersJcl, FidelityMode groupIdMode,
            Path accountLayoutManifest) {
        this(db, sourceDir, usersJcl, new GroupIdMode(groupIdMode, "given"), accountLayoutManifest);
    }

    DataReconciliation(JdbcClient db, Path sourceDir, Path usersJcl, GroupIdMode groupIdMode,
            Path accountLayoutManifest) {
        this.db = db;
        this.sourceDir = sourceDir;
        this.usersJcl = usersJcl;
        this.groupIdMode = groupIdMode;
        this.accountLayoutManifest = accountLayoutManifest;
    }

    public Report run() {
        Report report = new Report();
        // The re-export has no mode; an account loaded in another mode or layout fails check 6.
        report.info("DEF-ACUP-002", groupIdMode.describe());
        LoadedTable.Rows rows = new LoadedTable.Rows(new CoreRows(db), new RefRows(db));
        Map<String, List<SourceLine>> sources = new HashMap<>();
        for (LoadedTable t : LoadedTable.ALL) {
            List<SourceLine> source = LegacyFileReader.read(sourceDir.resolve(t.file()), t.layout().length());
            sources.put(t.name(), source);
            table(report, t, source, rows);
        }
        zipGroups(report);
        lookups(report, rows.ref());
        relations(report, sources);
        users(report);
        long transactions = db.sql("SELECT COUNT_BIG(*) FROM core.[transaction]").query(Long.class).single();
        report.info("transaction", transactions + " rows (the load leaves the table empty; online and batch add rows)");
        return report;
    }

    // --- one table loaded from a file ------------------------------------------------------

    private void table(Report report, LoadedTable t, List<SourceLine> source, LoadedTable.Rows rows) {
        long count = db.sql("SELECT COUNT_BIG(*) FROM " + t.table()).query(Long.class).single();
        List<String> tableKeys = db.sql("SELECT " + t.keySql() + " FROM " + t.table()).query(String.class).list();
        Map<String, BigDecimal> totals = new HashMap<>();
        for (Map.Entry<String, String> sum : t.sums().entrySet()) {
            totals.put(sum.getKey(), db.sql("SELECT COALESCE(SUM(" + sum.getValue() + "), 0) FROM " + t.table())
                    .query(BigDecimal.class).single());
        }
        compare(report, t, source, count, tableKeys, totals, t.export().apply(rows));
    }

    /** One table against its file, from what was read of the table: the comparison and the report line. */
    static void compare(Report report, LoadedTable t, List<SourceLine> source, long count, Collection<String> tableKeys,
                        Map<String, BigDecimal> tableTotals, List<byte[]> exported) {
        List<String> problems = new ArrayList<>();
        if (count != source.size()) {
            problems.add("count " + count + ", file " + source.size());
        }

        Set<String> fileKeys = source.stream().map(l -> key(t, l.record())).collect(Collectors.toCollection(TreeSet::new));
        Set<String> rowKeys = new TreeSet<>(tableKeys);
        if (t.codeKey()) {
            Set<String> missing = new TreeSet<>(fileKeys);
            missing.removeAll(rowKeys);
            Set<String> extra = new TreeSet<>(rowKeys);
            extra.removeAll(fileKeys);
            if (!missing.isEmpty()) {
                problems.add("missing keys " + keys(missing));
            }
            if (!extra.isEmpty()) {
                problems.add("keys not in the file " + keys(extra));
            }
        } else {
            List<Integer> missing = source.stream().filter(l -> !rowKeys.contains(key(t, l.record())))
                    .map(SourceLine::lineNumber).toList();
            List<Integer> extra = new ArrayList<>();
            int ordinal = 0;
            for (String k : rowKeys) {
                ordinal++;
                if (!fileKeys.contains(k)) {
                    extra.add(ordinal);
                }
            }
            if (!missing.isEmpty()) {
                problems.add("missing keys at " + lines(t.file(), missing));
            }
            if (!extra.isEmpty()) {
                problems.add(extra.size() + " keys not in the file: " + t.table() + " " + rowOrdinals(extra));
            }
        }

        for (String field : new TreeSet<>(t.sums().keySet())) {
            BigDecimal fromFile = source.stream().map(l -> l.view(t.layout()).decimal(field))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal fromTable = Objects.requireNonNull(tableTotals.get(field), () -> "no table total for " + field);
            if (fromFile.compareTo(fromTable) != 0) {
                problems.add("total " + field + " differs");
            }
        }

        Map<String, byte[]> byKey = new HashMap<>();
        for (byte[] record : exported) {
            byKey.put(key(t, record), record);
        }
        List<String> different = new ArrayList<>();
        List<Integer> spaced = new ArrayList<>();
        for (SourceLine l : source) {
            byte[] expected = expectedExport(t, l, spaced);
            byte[] export = byKey.get(key(t, l.record()));
            if (export != null && !Arrays.equals(expected, export)) {
                String where = t.codeKey() ? key(t, l.record()) : "line " + l.lineNumber();
                different.add(where + " " + differingFields(t, expected, export));
            }
        }
        if (!different.isEmpty()) {
            problems.add(different.size() + " records re-export differently: " + (t.codeKey() ? "" : t.file() + " ")
                    + limited(different));
        }

        if (problems.isEmpty()) {
            int n = t.sums().size();
            String totals = n == 0 ? "" : ", " + n + (n == 1 ? " control total" : " control totals");
            report.ok(t.table(), count + " rows; keys" + totals + " and byte-exact re-export of " + t.file() + " match");
        } else {
            report.fail(t.table(), String.join("; ", problems));
        }
        if (t.spaces() != null) {
            LoadedTable.Spaces s = t.spaces();
            report.info(t.table() + " FILLER", t.name() + " FILLER normalised: " + spaced.size()
                    + (spaced.isEmpty() ? "" : " (positions " + s.from() + "-" + s.to() + " not all spaces, compared as"
                    + " spaces): " + t.file() + (spaced.size() == 1 ? " line " : " lines ") + ranges(spaced)));
        }
    }

    /**
     * The record the export must equal: the source line, with the table's dropped positions as
     * spaces. The lines whose dropped positions held anything else are added to {@code spaced}.
     */
    private static byte[] expectedExport(LoadedTable t, SourceLine l, List<Integer> spaced) {
        LoadedTable.Spaces s = t.spaces();
        if (s == null) {
            return l.record();
        }
        byte[] expected = l.record().clone();
        boolean allSpaces = true;
        for (int i = s.from() - 1; i < s.to(); i++) {
            allSpaces &= expected[i] == ' ';
            expected[i] = ' ';
        }
        if (!allSpaces) {
            spaced.add(l.lineNumber());
        }
        return expected;
    }

    /** At most this many line ranges in a FILLER count; the count is always exact. */
    static final int MAX_RANGES = 100;

    /** Ascending line numbers as ranges, {@code 3, 7-9}, at most {@link #MAX_RANGES} of them. */
    static String ranges(List<Integer> lines) {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        int ranges = 0;
        int i = 0;
        while (i < lines.size() && ranges < MAX_RANGES) {
            int first = lines.get(i);
            int last = first;
            while (i + 1 < lines.size() && lines.get(i + 1) == last + 1) {
                last = lines.get(++i);
            }
            i++;
            out.append(ranges++ == 0 ? "" : ", ").append(first == last ? String.valueOf(first) : first + "-" + last);
            shown += last - first + 1;
        }
        return shown < lines.size() ? out + " and " + (lines.size() - shown) + " more" : out.toString();
    }

    private static String key(LoadedTable t, byte[] record) {
        return new String(record, 0, t.keyLength(), StandardCharsets.ISO_8859_1);
    }

    private static Set<String> differingFields(LoadedTable t, byte[] source, byte[] export) {
        FixedRecord a = FixedRecord.of(t.layout(), source);
        FixedRecord b = FixedRecord.of(t.layout(), export);
        Set<String> fields = new LinkedHashSet<>();
        for (FieldSpec f : t.layout().fields()) {
            if (!a.raw(f).equals(b.raw(f))) {
                fields.add(f.type() == FieldType.FILLER && f.name().startsWith("FILLER-")
                        ? "FILLER at " + f.position() : f.name());
            }
        }
        return fields;
    }

    // --- DEF-ACUP-002: positions 103-122 of every account ----------------------------------

    static final String ZIP_GROUP = "core.account 103-122";

    /**
     * Check 6, independent of the loader: reads {@code acctdata.txt} once, picks the layout of
     * each record from those bytes with its own resolver ({@link AccountLayoutCheck}; legacy: as
     * read; fixed: the supplied manifest, else the built-in by hash), derives the four stored values
     * itself and compares them with the table. Mismatches are named by file line and column, never by account.
     */
    private void zipGroups(Report report) {
        byte[] acctdata;
        try {
            acctdata = Files.readAllBytes(sourceDir.resolve("acctdata.txt"));
        } catch (IOException e) {
            report.fail(ZIP_GROUP, "acctdata.txt cannot be read (" + e.getClass().getSimpleName() + ")");
            return;
        }
        byte[] manifest = null;
        if (groupIdMode.mode() == FidelityMode.FIXED && accountLayoutManifest != null) {
            if (!Files.isRegularFile(accountLayoutManifest)) {
                report.fail(ZIP_GROUP, "layout manifest not found: " + accountLayoutManifest);
                return;
            }
            try (InputStream in = Files.newInputStream(accountLayoutManifest)) {
                manifest = in.readNBytes(AccountLayoutCheck.MAX_BYTES + 1);
            } catch (IOException e) {
                report.fail(ZIP_GROUP, "layout manifest cannot be read (" + e.getClass().getSimpleName() + ")");
                return;
            }
        }
        List<SourceLine> source;
        try {
            source = LegacyFileReader.read(new ByteArrayInputStream(acctdata), Layouts.ACCOUNT.length(), "acctdata.txt");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> ids = source.stream().map(l -> key(LoadedTable.named("account"), l.record()))
                .collect(Collectors.toSet());
        AccountLayoutCheck.Layouts layouts;
        try {
            layouts = AccountLayoutCheck.resolve(groupIdMode.mode(), acctdata, ids, manifest);
        } catch (AccountLayoutCheck.Refused e) {
            report.fail(ZIP_GROUP, e.getMessage());
            return;
        }
        Map<String, Map<String, Object>> rows = new HashMap<>();
        db.sql("SELECT acct_id, addr_zip, group_id, legacy_zip_group, zip_group_origin FROM core.account")
                .query().listOfRows().forEach(r -> rows.put((String) r.get("acct_id"), r));
        zipGroups(report, source, layouts, rows);
    }

    /** Check 6 from what was read: the file's records, their layouts and the table's rows by account. */
    static void zipGroups(Report report, List<SourceLine> source, AccountLayoutCheck.Layouts layouts,
            Map<String, Map<String, Object>> rows) {
        long asRead = 0;
        long migrated = 0;
        List<String> different = new ArrayList<>();
        for (SourceLine l : source) {
            FixedRecord r = l.view(Layouts.ACCOUNT);
            String zip = r.raw("ACCT-ADDR-ZIP");
            String group = r.raw("ACCT-GROUP-ID");
            String id = r.raw("ACCT-ID");
            boolean update = layouts.update(id);
            if (update && !blankField(group)) {
                different.add("line " + l.lineNumber() + " declared ACUP_UPDATE but positions 113-122 are not blank");
                continue;
            }
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put("addr_zip", update ? "" : withoutTrailingSpaces(zip));
            expected.put("group_id", update ? (blankField(zip) ? "" : withoutTrailingSpaces(zip))
                    : withoutTrailingSpaces(group));
            expected.put("zip_group_origin", update ? "M" : "R");
            expected.put("legacy_zip_group", zip + group);
            if (update) {
                migrated++;
            } else {
                asRead++;
            }
            Map<String, Object> row = rows.get(id);
            if (row == null) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            for (Map.Entry<String, String> e : expected.entrySet()) {
                Object stored = row.get(e.getKey());
                String actual = stored == null ? null : e.getKey().equals("legacy_zip_group") ? (String) stored
                        : withoutTrailingSpaces((String) stored);
                if (!e.getValue().equals(actual)) {
                    columns.add(e.getKey());
                }
            }
            if (!columns.isEmpty()) {
                different.add("line " + l.lineNumber() + " " + columns);
            }
        }
        String counts = "as read " + asRead + ", migrated " + migrated + ", layout manifest " + layouts.source();
        if (different.isEmpty()) {
            report.ok(ZIP_GROUP, source.size() + " records of acctdata.txt match addr_zip, group_id, zip_group_origin"
                    + " and legacy_zip_group (" + counts + ")");
        } else {
            report.fail(ZIP_GROUP, different.size() + " records differ: acctdata.txt " + limited(different) + " ("
                    + counts + ")");
        }
    }

    /** All U+0020 or all U+0000; a mix is data. */
    private static boolean blankField(String field) {
        return field.chars().allMatch(c -> c == ' ') || field.chars().allMatch(c -> c == 0);
    }

    /** Removes trailing U+0020 only; a NUL is data. */
    private static String withoutTrailingSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }
    // --- lookup lists ----------------------------------------------------------------------

    private static void lookups(Report report, RefRows rows) {
        RuleTable rules = RuleTable.standard();
        lookup(report, "ref.us_state", rules.list("usStateCodes"), rows.states());
        lookup(report, "ref.us_state_zip_prefix", rules.list("usStateZipPrefixes"), rows.stateZipPrefixes());
        lookup(report, "ref.nanp_area_code", rules.list("nanpGeneralPurposeAreaCodes"), rows.areaCodes());
    }

    private static void lookup(Report report, String table, List<String> expected, List<String> actual) {
        Set<String> missing = new TreeSet<>(expected);
        actual.forEach(missing::remove);
        Set<String> extra = new TreeSet<>(actual);
        expected.forEach(extra::remove);
        List<String> problems = new ArrayList<>();
        if (!missing.isEmpty()) {
            problems.add("missing codes " + keys(missing));
        }
        if (!extra.isEmpty()) {
            problems.add("codes not in " + RuleTable.LOOKUPS + " " + keys(extra));
        }
        if (problems.isEmpty()) {
            report.ok(table, actual.size() + " codes match " + RuleTable.LOOKUPS);
        } else {
            report.fail(table, String.join("; ", problems));
        }
    }

    // --- relations -------------------------------------------------------------------------

    private void relations(Report report, Map<String, List<SourceLine>> sources) {
        relation(report, "card has xref", "card", sources, """
                SELECT c.card_num FROM core.card AS c
                LEFT JOIN core.card_xref AS x ON x.card_num = c.card_num WHERE x.card_num IS NULL""");
        relation(report, "card account = xref", "card", sources, """
                SELECT c.card_num FROM core.card AS c
                JOIN core.card_xref AS x ON x.card_num = c.card_num WHERE x.acct_id <> c.acct_id""");
        relation(report, "xref account exists", "card_xref", sources, """
                SELECT x.card_num FROM core.card_xref AS x
                LEFT JOIN core.account AS a ON a.acct_id = x.acct_id WHERE a.acct_id IS NULL""");
        relation(report, "xref customer exists", "card_xref", sources, """
                SELECT x.card_num FROM core.card_xref AS x
                LEFT JOIN core.customer AS c ON c.cust_id = x.cust_id WHERE c.cust_id IS NULL""");
        relation(report, "tcat_bal account exists", "tcat_bal", sources, """
                SELECT t.acct_id + t.type_cd + t.cat_cd FROM core.tcat_bal AS t
                LEFT JOIN core.account AS a ON a.acct_id = t.acct_id WHERE a.acct_id IS NULL""");
        long defaults = db.sql("SELECT COUNT_BIG(*) FROM ref.disclosure_group WHERE group_id = 'DEFAULT'")
                .query(Long.class).single();
        if (defaults > 0) {
            report.ok("DEFAULT disc group", defaults + " rows in ref.disclosure_group");
        } else {
            report.fail("DEFAULT disc group", "ref.disclosure_group has no DEFAULT group");
        }
    }

    /** The query returns the key, in the file's key format, of each violating row of {@code table}. */
    private void relation(Report report, String name, String table, Map<String, List<SourceLine>> sources, String sql) {
        relation(report, name, LoadedTable.named(table), sources.get(table), db.sql(sql).query(String.class).list());
    }

    /** One relation, from the keys of the violating rows: each shown as its line in the table's file. */
    static void relation(Report report, String name, LoadedTable t, List<SourceLine> source, List<String> violating) {
        if (violating.isEmpty()) {
            report.ok(name, "no violations");
            return;
        }
        Map<String, Integer> lineOf = new HashMap<>();
        for (SourceLine l : source) {
            lineOf.putIfAbsent(key(t, l.record()), l.lineNumber());
        }
        Set<String> distinct = new TreeSet<>(violating);
        List<Integer> lines = distinct.stream().map(lineOf::get).filter(Objects::nonNull).sorted().toList();
        long elsewhere = distinct.stream().filter(k -> !lineOf.containsKey(k)).count();
        List<String> where = new ArrayList<>();
        if (!lines.isEmpty()) {
            where.add(lines(t.file(), lines));
        }
        if (elsewhere > 0) {
            where.add(elsewhere + (elsewhere == 1 ? " row" : " rows") + " of " + t.table() + " not in " + t.file());
        }
        report.fail(name, distinct.size() + " violations: " + String.join("; ", where));
    }

    // --- users -----------------------------------------------------------------------------

    private void users(Report report) {
        if (!exists("iam.app_user")) {
            report.skip("iam.app_user", "iam.app_user does not exist yet (identity-service migrations not in this database)");
            return;
        }
        List<UserSecurityRecord> actual = db.sql("SELECT user_id, first_name, last_name, user_type FROM iam.app_user")
                .query((rs, n) -> new UserSecurityRecord(rs.getString(1).stripTrailing(), rs.getString(2),
                        rs.getString(3), rs.getString(4)))
                .list();
        users(report, seedUsers(usersJcl), actual);
    }

    /** The users against the seed job's: the seed job's IDs are named, any other user is only counted. */
    static void users(Report report, List<UserSecurityRecord> seed, List<UserSecurityRecord> users) {
        Map<String, UserSecurityRecord> expected = new TreeMap<>();
        seed.forEach(u -> expected.put(u.userId(), u));
        Map<String, UserSecurityRecord> actual = new TreeMap<>();
        users.forEach(u -> actual.put(u.userId(), u));
        List<String> problems = new ArrayList<>();
        Set<String> missing = new TreeSet<>(expected.keySet());
        missing.removeAll(actual.keySet());
        Set<String> extra = new TreeSet<>(actual.keySet());
        extra.removeAll(expected.keySet());
        if (!missing.isEmpty()) {
            problems.add("missing users " + keys(missing));
        }
        if (!extra.isEmpty()) {
            problems.add(extra.size() + (extra.size() == 1 ? " user" : " users") + " not in DUSRSECJ");
        }
        for (Map.Entry<String, UserSecurityRecord> e : expected.entrySet()) {
            UserSecurityRecord a = actual.get(e.getKey());
            if (a == null) {
                continue;
            }
            UserSecurityRecord x = e.getValue();
            List<String> fields = new ArrayList<>();
            if (!x.firstName().strip().equals(a.firstName().strip())) {
                fields.add("first_name");
            }
            if (!x.lastName().strip().equals(a.lastName().strip())) {
                fields.add("last_name");
            }
            if (!x.userType().equals(a.userType())) {
                fields.add("user_type");
            }
            if (!fields.isEmpty()) {
                problems.add(e.getKey() + " " + fields);
            }
        }
        if (problems.isEmpty()) {
            report.ok("iam.app_user", actual.size() + " users match DUSRSECJ (ID, names, type; passwords not compared)");
        } else {
            report.fail("iam.app_user", String.join("; ", problems));
        }
    }

    /** The USRSEC records in the inline SYSUT1 data of DUSRSECJ, the user seed job. */
    static List<UserSecurityRecord> seedUsers(Path jcl) {
        List<String> lines;
        try {
            lines = Files.readAllLines(jcl, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<UserSecurityRecord> users = new ArrayList<>();
        boolean inData = false;
        for (String line : lines) {
            if (line.startsWith("//SYSUT1") && line.contains("DD *")) {
                inData = true;
            } else if (inData && (line.startsWith("/*") || line.startsWith("//"))) {
                break;
            } else if (inData) {
                String padded = (line + " ".repeat(80)).substring(0, Layouts.USER_SECURITY.length());
                users.add(UserSecurityRecord.parse(padded.getBytes(StandardCharsets.ISO_8859_1)));
            }
        }
        if (users.isEmpty()) {
            throw new IllegalStateException("no SYSUT1 inline data in " + jcl);
        }
        return users;
    }

    // --- helpers ---------------------------------------------------------------------------

    private boolean exists(String table) {
        return db.sql("SELECT CASE WHEN OBJECT_ID(:t, 'U') IS NULL THEN 0 ELSE 1 END").param("t", table)
                .query(Integer.class).single() == 1;
    }

    /** Public codes only: reference keys, lookup codes, seed user IDs. */
    private static String keys(Set<String> keys) {
        return limited(List.copyOf(keys));
    }

    private static String lines(String file, List<Integer> lines) {
        return file + (lines.size() == 1 ? " line " : " lines ") + limited(lines);
    }

    private static String rowOrdinals(List<Integer> ordinals) {
        return (ordinals.size() == 1 ? "row " : "rows ") + limited(ordinals) + " in key order";
    }

    private static String limited(List<?> items) {
        String shown = items.stream().limit(MAX_KEYS).map(String::valueOf).collect(Collectors.joining(", "));
        return items.size() > MAX_KEYS ? shown + " and " + (items.size() - MAX_KEYS) + " more" : shown;
    }
}
