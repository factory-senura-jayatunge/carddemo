package org.carddemo.transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The read of transaction view (COTRN01C): one master row by its ID, no lock (SE-TRNV-001,
 * DEF-TRNV-001). The card number is read in clear through the column's UNMASK grant; the service
 * wraps it in {@code Pii} at once.
 */
@Repository
public class TransactionViewRepository {

    public record StoredTransaction(String tranId, String typeCd, String catCd, String source, String description,
                                    BigDecimal amount, String merchantId, String merchantName, String merchantCity,
                                    String merchantZip, String cardNum, LocalDateTime origTs, LocalDateTime procTs) {
    }

    private static final RowMapper<StoredTransaction> ROW = (rs, n) -> new StoredTransaction(rs.getString(1),
            rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6), rs.getString(7),
            rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
            rs.getObject(12, LocalDateTime.class), rs.getObject(13, LocalDateTime.class));

    private final JdbcClient jdbc;

    public TransactionViewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** At most one row ({@code UX_tran_master}); the key is at most 16 printable characters. */
    public Optional<StoredTransaction> byId(String tranId) {
        return jdbc.sql("""
                        SELECT tran_id, type_cd, cat_cd, source, description, amount, merchant_id, merchant_name,
                               merchant_city, merchant_zip, card_num, orig_ts, proc_ts
                        FROM core.[transaction]
                        WHERE in_master = 1 AND tran_id = CAST(:id AS CHAR(16))""")
                .param("id", tranId)
                .query(ROW)
                .optional();
    }
}
