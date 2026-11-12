package org.carddemo.batch.statement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

/**
 * A writer in flight: one session of a login with an explicit transaction open until
 * {@link #commit()}, rolled back at {@link #close()} otherwise. Autocommit stays on, so the driver
 * adds no implicit transaction and the one {@code BEGIN TRANSACTION} is the only level.
 */
final class InFlightWrite implements AutoCloseable {

    private final Connection connection;

    InFlightWrite(DataSource dataSource) throws SQLException {
        connection = dataSource.getConnection();
        connection.setAutoCommit(true);
        try (Statement s = connection.createStatement()) {
            s.execute("BEGIN TRANSACTION");
        }
    }

    int spid() throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT CAST(@@SPID AS INT)")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** {@code sp_getapplock}'s return code for the window application lock, owned by this transaction. */
    int windowLock(String mode, int timeoutMillis) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("""
                SET NOCOUNT ON;
                DECLARE @r INT;
                EXEC @r = sp_getapplock @Resource = '%s', @LockMode = ?, @LockOwner = 'Transaction',
                    @LockTimeout = ?;
                SELECT @r;""".formatted(StatementCapture.WINDOW_LOCK))) {
            s.setString(1, mode);
            s.setInt(2, timeoutMillis);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Inserts a history row and returns its {@code tran_pk}, which only this session sees until the commit. */
    long insert(String tranId, String card, String amount, String procTs, String description, String origin)
            throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("""
                INSERT INTO core.[transaction] (tran_id, type_cd, cat_cd, source, description, amount, merchant_id,
                    merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, origin, in_master)
                VALUES (?, '01', '0001', 'POS TERM', ?, CAST(? AS DECIMAL(11,2)), '000000001', 'M', 'C', '1', ?, ?, ?,
                    ?, 0)""")) {
            s.setString(1, tranId);
            s.setString(2, description);
            s.setString(3, amount);
            s.setString(4, card);
            s.setString(5, procTs);
            s.setString(6, procTs);
            s.setString(7, origin);
            s.executeUpdate();
        }
        try (PreparedStatement s = connection.prepareStatement(
                "SELECT tran_pk FROM core.[transaction] WHERE tran_id = ?")) {
            s.setString(1, tranId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    void addToBalance(String acctId, String amount) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(
                "UPDATE core.account SET curr_bal = curr_bal + CAST(? AS DECIMAL(12,2)) WHERE acct_id = ?")) {
            s.setString(1, amount);
            s.setString(2, acctId);
            if (s.executeUpdate() != 1) {
                throw new IllegalStateException("no account row to change");
            }
        }
    }

    void commit() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("COMMIT TRANSACTION");
        }
    }

    @Override
    public void close() throws SQLException {
        try (Connection c = connection; Statement s = c.createStatement()) {
            s.execute("IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION");
        }
    }
}
