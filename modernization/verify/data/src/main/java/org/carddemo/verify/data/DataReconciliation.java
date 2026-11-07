package org.carddemo.verify.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.carddemo.batch.data.CoreRows;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.legacyformat.Masking;
import org.carddemo.platform.legacyformat.entity.UserSecurityRecord;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FieldSpec;
import org.carddemo.platform.legacyformat.record.FieldType;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reconciles the database with the legacy sample data after {@code make load}: per core
 * table the row count, the key set, the control totals and a byte-exact re-export of
 * every record (CRLF read as LF, the 36-byte xref records padded to 50); then the
 * relations between the tables, and the users of {@code DUSRSECJ} against
 * {@code iam.app_user} (no passwords). Only mismatching keys and field names are
 * reported, never values; card numbers show their last four digits.
 */
public final class DataReconciliation {

    /** At most this many keys per finding; the count is always given. */
    static final int MAX_KEYS = 10;

    private final JdbcClient db;
    private final Path sourceDir;
    private final Path usersJcl;
    private final GroupIdMode groupIdMode;

    public DataReconciliation(JdbcClient db, Path sourceDir, Path usersJcl, FidelityMode groupIdMode) {
        this(db, sourceDir, usersJcl, new GroupIdMode(groupIdMode, "given"));
    }

    DataReconciliation(JdbcClient db, Path sourceDir, Path usersJcl, GroupIdMode groupIdMode) {
        this.db = db;
        this.sourceDir = sourceDir;
        this.usersJcl = usersJcl;
        this.groupIdMode = groupIdMode;
    }

    public Report run() {
        Report report = new Report();
        // An account loaded in the other mode re-exports differently, so a wrong mode fails core.account.
        report.info("DEF-ACUP-002", groupIdMode.describe());
        CoreRows rows = new CoreRows(db, groupIdMode.mode());
        for (CoreTable t : CoreTable.ALL) {
            table(report, t, rows);
        }
        relations(report);
        users(report);
        long transactions = db.sql("SELECT COUNT_BIG(*) FROM core.[transaction]").query(Long.class).single();
        report.info("transaction", transactions + " rows (the load leaves the table empty; online and batch add rows)");
        return report;
    }

    // --- one core table --------------------------------------------------------------------

    private void table(Report report, CoreTable t, CoreRows rows) {
        List<SourceLine> source = LegacyFileReader.read(sourceDir.resolve(t.file()), t.layout().length());
        List<String> problems = new ArrayList<>();

        long count = db.sql("SELECT COUNT_BIG(*) FROM " + t.table()).query(Long.class).single();
        if (count != source.size()) {
            problems.add("count " + count + ", file " + source.size());
        }

        Set<String> fileKeys = source.stream()
                .map(l -> new String(l.record(), 0, t.keyLength(), StandardCharsets.ISO_8859_1))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> tableKeys = new TreeSet<>(
                db.sql("SELECT " + t.keySql() + " FROM " + t.table()).query(String.class).list());
        Set<String> missing = new TreeSet<>(fileKeys);
        missing.removeAll(tableKeys);
        Set<String> extra = new TreeSet<>(tableKeys);
        extra.removeAll(fileKeys);
        if (!missing.isEmpty()) {
            problems.add("missing keys " + keys(missing, t.keyDisplay()));
        }
        if (!extra.isEmpty()) {
            problems.add("keys not in the file " + keys(extra, t.keyDisplay()));
        }

        for (Map.Entry<String, String> sum : new TreeMap<>(t.sums()).entrySet()) {
            BigDecimal fromFile = source.stream().map(l -> l.view(t.layout()).decimal(sum.getKey()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal fromTable = db.sql("SELECT COALESCE(SUM(" + sum.getValue() + "), 0) FROM " + t.table())
                    .query(BigDecimal.class).single();
            if (fromFile.compareTo(fromTable) != 0) {
                problems.add("total " + sum.getKey() + " differs");
            }
        }

        Map<String, byte[]> exported = new HashMap<>();
        for (byte[] record : t.export().apply(rows)) {
            exported.put(new String(record, 0, t.keyLength(), StandardCharsets.ISO_8859_1), record);
        }
        Map<String, Set<String>> different = new TreeMap<>();
        for (SourceLine l : source) {
            String key = new String(l.record(), 0, t.keyLength(), StandardCharsets.ISO_8859_1);
            byte[] export = exported.get(key);
            if (export != null && !Arrays.equals(l.record(), export)) {
                different.put(key, differingFields(t, l.record(), export));
            }
        }
        if (!different.isEmpty()) {
            problems.add(different.size() + " records re-export differently: " + different.entrySet().stream()
                    .limit(MAX_KEYS)
                    .map(e -> t.keyDisplay().apply(e.getKey()) + " " + e.getValue())
                    .collect(Collectors.joining(", ")));
        }

        if (problems.isEmpty()) {
            int n = t.sums().size();
            String totals = n == 0 ? "" : ", " + n + (n == 1 ? " control total" : " control totals");
            report.ok(t.table(), count + " rows; keys" + totals + " and byte-exact re-export of " + t.file() + " match");
        } else {
            report.fail(t.table(), String.join("; ", problems));
        }
    }

    private static Set<String> differingFields(CoreTable t, byte[] source, byte[] export) {
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

    // --- relations -------------------------------------------------------------------------

    private void relations(Report report) {
        relation(report, "card has xref", Masking::lastFour, """
                SELECT c.card_num FROM core.card AS c
                LEFT JOIN core.card_xref AS x ON x.card_num = c.card_num WHERE x.card_num IS NULL""");
        relation(report, "card account = xref", Masking::lastFour, """
                SELECT c.card_num FROM core.card AS c
                JOIN core.card_xref AS x ON x.card_num = c.card_num WHERE x.acct_id <> c.acct_id""");
        relation(report, "xref account exists", Masking::lastFour, """
                SELECT x.card_num FROM core.card_xref AS x
                LEFT JOIN core.account AS a ON a.acct_id = x.acct_id WHERE a.acct_id IS NULL""");
        relation(report, "xref customer exists", Masking::lastFour, """
                SELECT x.card_num FROM core.card_xref AS x
                LEFT JOIN core.customer AS c ON c.cust_id = x.cust_id WHERE c.cust_id IS NULL""");
        relation(report, "tcat_bal account exists", UnaryOperator.identity(), """
                SELECT t.acct_id + t.type_cd + t.cat_cd FROM core.tcat_bal AS t
                LEFT JOIN core.account AS a ON a.acct_id = t.acct_id WHERE a.acct_id IS NULL""");
        if (exists("ref.disclosure_group")) {
            long defaults = db.sql("SELECT COUNT_BIG(*) FROM ref.disclosure_group WHERE group_id = 'DEFAULT'")
                    .query(Long.class).single();
            if (defaults > 0) {
                report.ok("DEFAULT disc group", defaults + " rows in ref.disclosure_group");
            } else {
                report.fail("DEFAULT disc group", "ref.disclosure_group has no DEFAULT group");
            }
        } else {
            report.skip("DEFAULT disc group", "ref.disclosure_group does not exist yet (reference tables not migrated)");
        }
    }

    private void relation(Report report, String name, UnaryOperator<String> display, String sql) {
        List<String> keys = db.sql(sql).query(String.class).list();
        if (keys.isEmpty()) {
            report.ok(name, "no violations");
        } else {
            report.fail(name, keys.size() + " violations: " + keys(new TreeSet<>(keys), display));
        }
    }

    // --- users -----------------------------------------------------------------------------

    private void users(Report report) {
        if (!exists("iam.app_user")) {
            report.skip("iam.app_user", "iam.app_user does not exist yet (identity-service migrations not in this database)");
            return;
        }
        Map<String, UserSecurityRecord> expected = new TreeMap<>();
        for (UserSecurityRecord u : seedUsers(usersJcl)) {
            expected.put(u.userId(), u);
        }
        Map<String, UserSecurityRecord> actual = new TreeMap<>();
        db.sql("SELECT user_id, first_name, last_name, user_type FROM iam.app_user")
                .query((rs, n) -> new UserSecurityRecord(rs.getString(1).stripTrailing(), rs.getString(2),
                        rs.getString(3), rs.getString(4)))
                .list()
                .forEach(u -> actual.put(u.userId(), u));
        List<String> problems = new ArrayList<>();
        Set<String> missing = new TreeSet<>(expected.keySet());
        missing.removeAll(actual.keySet());
        Set<String> extra = new TreeSet<>(actual.keySet());
        extra.removeAll(expected.keySet());
        if (!missing.isEmpty()) {
            problems.add("missing users " + keys(missing, UnaryOperator.identity()));
        }
        if (!extra.isEmpty()) {
            problems.add("users not in DUSRSECJ " + keys(extra, UnaryOperator.identity()));
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

    private static String keys(Set<String> keys, UnaryOperator<String> display) {
        String shown = keys.stream().limit(MAX_KEYS).map(display).collect(Collectors.joining(", "));
        return keys.size() > MAX_KEYS ? shown + " and " + (keys.size() - MAX_KEYS) + " more" : shown;
    }
}
