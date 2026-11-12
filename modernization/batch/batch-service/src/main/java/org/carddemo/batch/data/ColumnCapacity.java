package org.carddemo.batch.data;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What the text columns of one table can hold, as the catalog says. A loaded value is stored
 * unchanged or not at all: without this check SQL Server would refuse a value wider than its
 * column with a message that quotes the value (error 2628), or store {@code ?} for a character
 * that a code page 1252 column lacks (U+0080 to U+009F). The failure names the table and the
 * column, never the value. A column the table does not have fails with the column's name too
 * ({@link MissingColumnException}): the database is older than the build, and SQL Server's own
 * error 207 would not say which column.
 */
public final class ColumnCapacity {

    private static final int UTF8 = 65001;
    private static final int WINDOWS_1252 = 1252;
    private static final String WIDER = "it is wider than the column";
    private static final Set<String> TEXT_TYPES = Set.of("char", "varchar", "nchar", "nvarchar");

    private record Column(String type, int maxBytes, int codePage) {
    }

    private final String table;
    private final Set<String> names;
    private final Map<String, Column> columns;

    private ColumnCapacity(String table, Set<String> names, Map<String, Column> columns) {
        this.table = table;
        this.names = names;
        this.columns = columns;
    }

    /** A table with these columns and no text column to check, for tests that need no database. */
    static ColumnCapacity ofNames(String table, Set<String> names) {
        Set<String> lower = new HashSet<>();
        names.forEach(n -> lower.add(n.toLowerCase(Locale.ROOT)));
        return new ColumnCapacity(table, lower, Map.of());
    }

    /** @param table a table name as SQL accepts it, for example {@code core.[transaction]} */
    public static ColumnCapacity of(JdbcClient db, String table) {
        Set<String> names = new HashSet<>();
        Map<String, Column> columns = new HashMap<>();
        db.sql("""
                SELECT c.name, t.name AS type, c.max_length,
                       COALESCE(CAST(COLLATIONPROPERTY(c.collation_name, 'CodePage') AS INT), 0) AS code_page
                FROM sys.columns AS c
                JOIN sys.types AS t ON t.user_type_id = c.user_type_id
                WHERE c.object_id = OBJECT_ID(:table)
                """)
                .param("table", table)
                .query((rs, n) -> {
                    String name = rs.getString("name").toLowerCase(Locale.ROOT);
                    String type = rs.getString("type");
                    names.add(name);
                    if (TEXT_TYPES.contains(type)) {
                        columns.put(name, new Column(type, rs.getInt("max_length"), rs.getInt("code_page")));
                    }
                    return name;
                })
                .list();
        if (columns.isEmpty()) {
            throw new IllegalStateException(table + ": the catalog shows no text columns");
        }
        return new ColumnCapacity(table, names, columns);
    }

    /** Fails, naming them, when the table lacks any of these columns. */
    public void requireColumns(Collection<String> wanted) {
        List<String> missing = wanted.stream().filter(c -> !names.contains(c.toLowerCase(Locale.ROOT))).toList();
        if (!missing.isEmpty()) {
            throw new MissingColumnException(table, missing);
        }
    }

    /** Fails when {@code column} cannot hold {@code value} exactly; values that are not text pass. */
    public void require(String column, Object value) {
        if (!(value instanceof String text)) {
            return;
        }
        Column c = columns.get(column.toLowerCase(Locale.ROOT));
        if (c == null) {
            return;
        }
        String problem = problem(c.type(), c.maxBytes(), c.codePage(), text);
        if (problem != null) {
            throw new ColumnCapacityException(table + "." + column, problem);
        }
    }

    /**
     * Why a column of this type cannot hold {@code value} exactly, or {@code null} when it can.
     * {@code maxBytes} is the catalog's {@code max_length} (-1 for MAX).
     */
    static String problem(String type, int maxBytes, int codePage, String value) {
        boolean unicode = type.equals("nchar") || type.equals("nvarchar");
        if (unicode) {
            return maxBytes >= 0 && value.length() * 2 > maxBytes ? WIDER : null;
        }
        int bytes;
        if (codePage == UTF8) {
            bytes = value.getBytes(StandardCharsets.UTF_8).length;
        } else {
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                // U+00A0 to U+00FF are the same bytes in code page 1252; U+0080 to U+009F are not in it.
                boolean held = ch <= 0x7F || (codePage == WINDOWS_1252 && ch >= 0xA0 && ch <= 0xFF);
                if (!held) {
                    return "it has a character that the column's code page lacks";
                }
            }
            bytes = value.length();
        }
        return maxBytes >= 0 && bytes > maxBytes ? WIDER : null;
    }
}
