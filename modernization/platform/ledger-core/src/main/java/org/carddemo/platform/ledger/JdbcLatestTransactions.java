package org.carddemo.platform.ledger;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link LatestTransactions} on core.transaction and core.daily_tran. */
public class JdbcLatestTransactions implements LatestTransactions {

    // Each branch takes its own highest row through its unique filtered index (UX_tran_master,
    // UX_daily_waiting); the master row wins a tie, so the choice never depends on plan order.
    static final String LATEST = """
            SELECT TOP (1) tran_id, type_cd, cat_cd, source, description, amount, merchant_id, merchant_name,
                merchant_city, merchant_zip, orig_date, proc_date, waiting
            FROM (
                SELECT * FROM (
                    SELECT TOP (1) tran_id, type_cd, cat_cd, source, description, amount, merchant_id,
                        merchant_name, merchant_city, merchant_zip, CONVERT(CHAR(10), orig_ts, 23) AS orig_date,
                        CONVERT(CHAR(10), proc_ts, 23) AS proc_date, CAST(0 AS BIT) AS waiting
                    FROM core.[transaction] WHERE in_master = 1 ORDER BY tran_id DESC
                ) AS master_row
                UNION ALL
                SELECT * FROM (
                    SELECT TOP (1) tran_id, type_cd, cat_cd, source, description, amount, merchant_id,
                        merchant_name, merchant_city, merchant_zip, CAST(LEFT(orig_ts, 10) AS CHAR(10)) AS orig_date,
                        CAST(LEFT(proc_ts, 10) AS CHAR(10)) AS proc_date, CAST(1 AS BIT) AS waiting
                    FROM core.daily_tran WHERE cycle_id IS NULL AND origin = 'ONLINE_ADD' ORDER BY tran_id DESC
                ) AS waiting_row
            ) AS candidates
            ORDER BY tran_id DESC, waiting""";

    private final JdbcClient jdbc;

    public JdbcLatestTransactions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LatestTransaction> latest() {
        return jdbc.sql(LATEST).query((rs, n) -> new LatestTransaction(rs.getString("tran_id"),
                rs.getString("type_cd"), rs.getString("cat_cd"), rs.getString("source"), rs.getString("description"),
                rs.getBigDecimal("amount"), rs.getString("merchant_id"), rs.getString("merchant_name"),
                rs.getString("merchant_city"), rs.getString("merchant_zip"), rs.getString("orig_date"),
                rs.getString("proc_date"), rs.getBoolean("waiting"))).optional();
    }
}
