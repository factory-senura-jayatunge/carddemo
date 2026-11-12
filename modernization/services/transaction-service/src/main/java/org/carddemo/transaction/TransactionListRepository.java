package org.carddemo.transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

/**
 * The keyset reads of the transaction list (COTRN00C): master rows only, ascending {@code tran_id},
 * no lock (SE-TRNL-001, SE-TRNL-002). Waiting adds in {@code core.daily_tran} are not the master
 * and are never read (D-27).
 */
@Repository
public class TransactionListRepository {

    public record ListedTransaction(String tranId, LocalDateTime origTs, String description, BigDecimal amount) {
    }

    private static final RowMapper<ListedTransaction> ROW = (rs, n) -> new ListedTransaction(rs.getString(1),
            rs.getObject(2, LocalDateTime.class), rs.getString(3), rs.getBigDecimal(4));

    private final JdbcClient jdbc;

    public TransactionListRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Up to {@code rows} rows from the start ({@code key} null), from {@code key} or after it, ascending. */
    public List<ListedTransaction> forward(String key, boolean inclusive, int rows) {
        return select(key == null ? null : inclusive ? ">=" : ">", key, "ASC", rows);
    }

    /** Up to {@code rows} rows before {@code key}, nearest first. */
    public List<ListedTransaction> backward(String key, int rows) {
        return select("<", key, "DESC", rows);
    }

    public boolean anyBefore(String key, boolean inclusive) {
        return exists(inclusive ? "<=" : "<", key);
    }

    public boolean anyAfter(String key) {
        return exists(">", key);
    }

    private List<ListedTransaction> select(String op, String key, String order, int rows) {
        StatementSpec spec = jdbc.sql("SELECT TOP (:rows) tran_id, orig_ts, description, amount FROM core.[transaction]"
                + where(op) + " ORDER BY tran_id " + order);
        return bind(spec, key).param("rows", rows).query(ROW).list();
    }

    private boolean exists(String op, String key) {
        return bind(jdbc.sql("SELECT TOP 1 1 FROM core.[transaction]" + where(op)), key)
                .query(Integer.class).optional().isPresent();
    }

    /**
     * Only fixed fragments: the operator comes from this class and the key is a parameter.
     * {@code in_master = 1} is a literal so that the optimizer can match the filtered index
     * {@code UX_tran_master}, and the cast keeps the comparison CHAR to CHAR, as the driver sends
     * strings as NVARCHAR, which would convert the column side and turn the seek into a scan.
     */
    private static String where(String op) {
        return " WHERE in_master = 1" + (op == null ? "" : " AND tran_id " + op + " CAST(:key AS CHAR(16))");
    }

    private static StatementSpec bind(StatementSpec spec, String key) {
        return key == null ? spec : spec.param("key", key);
    }
}
