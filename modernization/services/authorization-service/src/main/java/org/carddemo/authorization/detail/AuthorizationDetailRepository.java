package org.carddemo.authorization.detail;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.carddemo.authorization.summary.ListPosition;
import org.carddemo.platform.web.AuditLog;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The reads of the detail screen (COPAUS1C) and the writes of a fraud change (COPAUS2C absorbed,
 * contract §9.2). Only the three fraud columns of {@code pauth.auth_detail} are ever written, so a
 * concurrent D-33 release is never lost.
 */
@Repository
public class AuthorizationDetailRepository {

    static final String SERVICE = "authorization-service";
    static final String SUBJECT_TYPE = "AUTHORIZATION";
    /** The API names of the two fraud members; never their values (D-56). */
    static final List<String> AUDITED_FIELDS = List.of("fraud.flag", "fraud.reportDate");

    /** One {@code pauth.auth_detail} row; {@code fraudFlag} is the stored CHAR(1), a space when never marked. */
    public record StoredDetail(long authId, String acctId, LocalDateTime processedAt, String origDate, String origTime,
            String cardNum, String authType, String cardExpiryDate, String messageSource, String authIdCode,
            String respCode, String respReason, String processingCode, BigDecimal transactionAmount,
            BigDecimal approvedAmount, String merchantCategoryCode, String posEntryMode, String merchantId,
            String merchantName, String merchantCity, String merchantState, String merchantZip, String transactionId,
            String matchStatus, String matchedTranId, String fraudFlag, LocalDate fraudReportDate, byte[] rowVersion) {

        public ListPosition position() {
            return new ListPosition(processedAt, authId);
        }
    }

    private static final String COLUMNS = """
            d.auth_id, d.acct_id, d.processed_at, d.orig_date, d.orig_time, d.card_num, d.auth_type,
            d.card_expiry_date, d.message_source, d.auth_id_code, d.auth_resp_code, d.auth_resp_reason,
            d.processing_code, d.transaction_amt, d.approved_amt, d.merchant_category_code, d.pos_entry_mode,
            d.merchant_id, d.merchant_name, d.merchant_city, d.merchant_state, d.merchant_zip, d.transaction_id,
            d.match_status, d.matched_tran_id, d.auth_fraud, d.fraud_rpt_date, d.row_version""";

    private static final RowMapper<StoredDetail> DETAIL = (rs, n) -> new StoredDetail(rs.getLong(1), rs.getString(2),
            rs.getObject(3, LocalDateTime.class), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
            rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13),
            rs.getBigDecimal(14), rs.getBigDecimal(15), rs.getString(16), rs.getString(17), rs.getString(18),
            rs.getString(19), rs.getString(20), rs.getString(21), rs.getString(22), rs.getString(23), rs.getString(24),
            rs.getString(25), rs.getString(26), rs.getObject(27, LocalDate.class), rs.getBytes(28));

    private final JdbcClient jdbc;

    public AuthorizationDetailRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredDetail> find(long authId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pauth.auth_detail d WHERE d.auth_id = :id")
                .param("id", authId)
                .query(DETAIL)
                .optional();
    }

    /** §9.2 step 2: the fraud change's read, holding an update lock until the transaction ends. */
    public Optional<StoredDetail> lock(long authId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pauth.auth_detail d WITH (UPDLOCK, ROWLOCK) WHERE d.auth_id = :id")
                .param("id", authId)
                .query(DETAIL)
                .optional();
    }

    /**
     * BR-AUDT-006: the next row of the account in list order ({@code processed_at}, then
     * {@code auth_id}, newest first), a seek of {@code IX_auth_detail_acct_newest}.
     */
    public Optional<Long> nextOlder(String acctId, ListPosition from) {
        return jdbc.sql("""
                        SELECT TOP 1 auth_id FROM pauth.auth_detail
                        WHERE acct_id = CAST(:acct AS CHAR(11))
                          AND (processed_at < CAST(:at AS DATETIME2(3))
                               OR (processed_at = CAST(:at AS DATETIME2(3)) AND auth_id < :id))
                        ORDER BY processed_at DESC, auth_id DESC""")
                .param("acct", acctId).param("at", from.at()).param("id", from.authId())
                .query(Long.class)
                .optional();
    }

    /** §9.2 step 4: the fraud columns, only while the row still has the version the change read. */
    public int markFraud(long authId, byte[] rowVersion, String action, LocalDate today, String userId) {
        return jdbc.sql("""
                        UPDATE pauth.auth_detail SET auth_fraud = :action, fraud_rpt_date = :today,
                            fraud_changed_by = :user
                        WHERE auth_id = :id AND row_version = :version""")
                .param("action", action).param("today", today).param("user", userId)
                .param("id", authId).param("version", rowVersion)
                .update();
    }

    /** §9.2 step 5, BR-AUFR-003: an existing fraud row keeps the first report's copy of the detail. */
    public int updateFraudRow(long authId, String action, LocalDate today, String userId) {
        return jdbc.sql("""
                        UPDATE pauth.auth_fraud SET auth_fraud = :action, fraud_rpt_date = :today, reported_by = :user,
                            reported_at = SYSUTCDATETIME()
                        WHERE auth_id = :id""")
                .param("action", action).param("today", today).param("user", userId).param("id", authId)
                .update();
    }

    /**
     * §9.2 step 5, BR-AUFR-002: the first report copies the detail. {@code cust_id} is the customer
     * the screen shows, the account's lowest card's (D-61), or NULL without a cross-reference row.
     */
    public int insertFraudRow(long authId, String action, LocalDate today, String userId) {
        return jdbc.sql("""
                        INSERT INTO pauth.auth_fraud (auth_id, card_num, auth_ts, auth_type, card_expiry_date,
                            message_type, message_source, auth_id_code, auth_resp_code, auth_resp_reason,
                            processing_code, transaction_amt, approved_amt, merchant_category_code, acqr_country_code,
                            pos_entry_mode, merchant_id, merchant_name, merchant_city, merchant_state, merchant_zip,
                            transaction_id, match_status, auth_fraud, fraud_rpt_date, acct_id, cust_id, reported_by)
                        SELECT d.auth_id, d.card_num, d.processed_at, d.auth_type, d.card_expiry_date,
                            d.message_type, d.message_source, d.auth_id_code, d.auth_resp_code, d.auth_resp_reason,
                            d.processing_code, d.transaction_amt, d.approved_amt, d.merchant_category_code,
                            d.acqr_country_code, TRY_CAST(d.pos_entry_mode AS SMALLINT), d.merchant_id, d.merchant_name,
                            d.merchant_city, d.merchant_state, d.merchant_zip, d.transaction_id, d.match_status,
                            :action, :today, d.acct_id,
                            (SELECT TOP 1 x.cust_id FROM core.card_xref x WHERE x.acct_id = d.acct_id
                             ORDER BY x.card_num),
                            :user
                        FROM pauth.auth_detail d WHERE d.auth_id = :id""")
                .param("action", action).param("today", today).param("user", userId).param("id", authId)
                .update();
    }

    /** §9.2 step 6: who changed the fraud status of which authorization, in the change's transaction. */
    public void audit(String userId, long authId) {
        new AuditLog(jdbc, SERVICE).record(userId, AuditLog.Action.UPDATE, SUBJECT_TYPE, String.valueOf(authId),
                AUDITED_FIELDS);
    }
}
