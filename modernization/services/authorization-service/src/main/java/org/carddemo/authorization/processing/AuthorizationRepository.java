package org.carddemo.authorization.processing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.OptionalLong;
import org.carddemo.authorization.decision.DetailKey;
import org.carddemo.authorization.request.AuthorizationRequest;
import org.carddemo.authorization.request.MessageKey;
import org.carddemo.authorization.request.RequestField;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The listener's SQL (contract §6.2). Every key is bound as {@code CAST(? AS CHAR(n))}: the driver
 * sends strings as NVARCHAR, which would turn the index seeks on the CHAR keys into scans.
 */
@Repository
public class AuthorizationRepository {

    private final JdbcClient jdbc;

    public AuthorizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Xref(String acctId, String custId) {
    }

    public record AccountRow(String activeStatus, BigDecimal currBal, BigDecimal creditLimit,
            BigDecimal cashCreditLimit) {
    }

    public record CardRow(String activeStatus, String expirationDate) {
    }

    /** A handled request: its detail ({@code null} when none was written) and its 63-character reply. */
    public record StoredReply(Long authId, String replyBody) {
    }

    /** The summary change of one request: 1 or 0 for each count, and the amounts to add. */
    public record SummaryChange(String acctId, String custId, BigDecimal creditLimit, BigDecimal cashLimit,
            int approved, BigDecimal approvedAmt, int declined, BigDecimal declinedAmt) {
    }

    public Optional<StoredReply> processedMessage(MessageKey key) {
        return jdbc.sql("SELECT auth_id, reply_body FROM pauth.processed_message WHERE msg_key = CAST(:key AS BINARY(32))")
                .param("key", key.hash())
                .query((rs, n) -> new StoredReply(rs.getObject(1, Long.class), rs.getString(2)))
                .optional();
    }

    public Optional<Xref> xref(String cardNum) {
        return jdbc.sql("SELECT acct_id, cust_id FROM core.card_xref WHERE card_num = CAST(:card AS CHAR(16))")
                .param("card", cardNum)
                .query((rs, n) -> new Xref(rs.getString(1), rs.getString(2)))
                .optional();
    }

    public Optional<AccountRow> account(String acctId) {
        return jdbc.sql("""
                        SELECT active_status, curr_bal, credit_limit, cash_credit_limit FROM core.account
                        WHERE acct_id = CAST(:acct AS CHAR(11))""")
                .param("acct", acctId)
                .query((rs, n) -> new AccountRow(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4)))
                .optional();
    }

    /** Only the key is read: the customer's protected columns are never selected. */
    public boolean customerExists(String custId) {
        return jdbc.sql("SELECT 1 FROM core.customer WHERE cust_id = CAST(:cust AS CHAR(9))")
                .param("cust", custId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public Optional<CardRow> card(String cardNum) {
        return jdbc.sql("SELECT active_status, expiration_date FROM core.card WHERE card_num = CAST(:card AS CHAR(16))")
                .param("card", cardNum)
                .query((rs, n) -> new CardRow(rs.getString(1), rs.getString(2)))
                .optional();
    }

    public boolean confirmedFraud(String cardNum) {
        return jdbc.sql("""
                        SELECT TOP (1) 1 FROM pauth.auth_fraud
                        WHERE card_num = CAST(:card AS CHAR(16)) AND auth_fraud = 'F'""")
                .param("card", cardNum)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * The summary lock of step 4. {@code HOLDLOCK} also locks the key when the account has no
     * summary yet, so two first requests for one account cannot both insert one.
     */
    public Optional<BigDecimal> lockSummary(String acctId) {
        return jdbc.sql("""
                        SELECT credit_balance FROM pauth.auth_summary WITH (UPDLOCK, HOLDLOCK, ROWLOCK)
                        WHERE acct_id = CAST(:acct AS CHAR(11))""")
                .param("acct", acctId)
                .query(BigDecimal.class)
                .optional();
    }

    /** One atomic statement, never read, compute and write back; 0 when the account has no summary. */
    public int updateSummary(SummaryChange c) {
        return jdbc.sql("""
                        UPDATE pauth.auth_summary SET
                          credit_limit = :creditLimit, cash_limit = :cashLimit,
                          approved_auth_cnt = approved_auth_cnt + :a, approved_auth_amt = approved_auth_amt + :approved,
                          credit_balance = credit_balance + :approved,
                          cash_balance = CASE WHEN :a = 1 THEN 0 ELSE cash_balance END,
                          declined_auth_cnt = declined_auth_cnt + :d, declined_auth_amt = declined_auth_amt + :declined
                        WHERE acct_id = CAST(:acct AS CHAR(11))""")
                .param("creditLimit", c.creditLimit()).param("cashLimit", c.cashLimit())
                .param("a", c.approved()).param("approved", c.approvedAmt())
                .param("d", c.declined()).param("declined", c.declinedAmt())
                .param("acct", c.acctId())
                .update();
    }

    public void insertSummary(SummaryChange c) {
        jdbc.sql("""
                        INSERT INTO pauth.auth_summary (acct_id, cust_id, credit_limit, cash_limit, credit_balance,
                            cash_balance, approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt)
                        VALUES (CAST(:acct AS CHAR(11)), CAST(:cust AS CHAR(9)), :creditLimit, :cashLimit, :approved,
                            0, :a, :d, :approved, :declined)""")
                .param("acct", c.acctId()).param("cust", c.custId())
                .param("creditLimit", c.creditLimit()).param("cashLimit", c.cashLimit())
                .param("a", c.approved()).param("approved", c.approvedAmt())
                .param("d", c.declined()).param("declined", c.declinedAmt())
                .update();
    }

    /**
     * Inserts the detail with a placeholder code, which {@link #setAuthIdCode} replaces in the same
     * transaction. Empty when another detail of the account has the same 9-complement key (same
     * millisecond, ERR-AUTH-009); the caller moves the time and tries again.
     */
    public OptionalLong insertDetail(String acctId, LocalDateTime processedAt, AuthorizationRequest r,
            String respCode, String reason, BigDecimal approvedAmt, String matchStatus) {
        DetailKey key = DetailKey.of(processedAt);
        try {
            return OptionalLong.of(jdbc.sql("""
                            INSERT INTO pauth.auth_detail (acct_id, processed_at, auth_date_9c, auth_time_9c, orig_date,
                                orig_time, card_num, auth_type, card_expiry_date, message_type, message_source,
                                auth_id_code, auth_resp_code, auth_resp_reason, processing_code, transaction_amt,
                                approved_amt, merchant_category_code, acqr_country_code, pos_entry_mode, merchant_id,
                                merchant_name, merchant_city, merchant_state, merchant_zip, transaction_id, match_status)
                            OUTPUT inserted.auth_id
                            VALUES (CAST(:acct AS CHAR(11)), :processedAt, :date9c, :time9c, :origDate, :origTime,
                                :card, :authType, :cardExpiry, :messageType, :messageSource, '000000', :resp, :reason,
                                :processingCode, :transactionAmt, :approvedAmt, :mcc, :country, :posEntryMode,
                                :merchantId, :merchantName, :merchantCity, :merchantState, :merchantZip,
                                :transactionId, :matchStatus)""")
                    .param("acct", acctId).param("processedAt", processedAt)
                    .param("date9c", key.date9c()).param("time9c", key.time9c())
                    .param("origDate", r.stored(RequestField.AUTH_DATE))
                    .param("origTime", r.stored(RequestField.AUTH_TIME))
                    .param("card", r.stored(RequestField.CARD_NUM))
                    .param("authType", r.stored(RequestField.AUTH_TYPE))
                    .param("cardExpiry", r.stored(RequestField.CARD_EXPIRY_DATE))
                    .param("messageType", r.stored(RequestField.MESSAGE_TYPE))
                    .param("messageSource", r.stored(RequestField.MESSAGE_SOURCE))
                    .param("resp", respCode).param("reason", reason)
                    .param("processingCode", r.stored(RequestField.PROCESSING_CODE))
                    .param("transactionAmt", r.amount().value()).param("approvedAmt", approvedAmt)
                    .param("mcc", r.stored(RequestField.MERCHANT_CATEGORY_CODE))
                    .param("country", r.stored(RequestField.ACQR_COUNTRY_CODE))
                    .param("posEntryMode", r.stored(RequestField.POS_ENTRY_MODE))
                    .param("merchantId", r.stored(RequestField.MERCHANT_ID))
                    .param("merchantName", r.stored(RequestField.MERCHANT_NAME))
                    .param("merchantCity", r.stored(RequestField.MERCHANT_CITY))
                    .param("merchantState", r.stored(RequestField.MERCHANT_STATE))
                    .param("merchantZip", r.stored(RequestField.MERCHANT_ZIP))
                    .param("transactionId", r.stored(RequestField.TRANSACTION_ID))
                    .param("matchStatus", matchStatus)
                    .query(Long.class)
                    .single());
        } catch (DuplicateKeyException e) {
            if (violates(e, "UQ_auth_detail_9c")) {
                return OptionalLong.empty();
            }
            throw e;
        }
    }

    /** The six rightmost digits of {@code auth_id} (§5.4), as stored. */
    public String setAuthIdCode(long authId) {
        return jdbc.sql("""
                        UPDATE pauth.auth_detail
                        SET auth_id_code = RIGHT('00000' + CAST(auth_id % 1000000 AS VARCHAR(6)), 6)
                        OUTPUT inserted.auth_id_code
                        WHERE auth_id = :id""")
                .param("id", authId)
                .query(String.class)
                .single();
    }

    public long insertOutbox(ReplyRoute route, String contentType, int expirationMs, String body) {
        return jdbc.sql("""
                        INSERT INTO pauth.outbox (exchange_name, routing_key, correlation_id, content_type,
                            expiration_ms, body)
                        OUTPUT inserted.outbox_id
                        VALUES (:exchange, :routingKey, :correlationId, :contentType, :expiration, :body)""")
                .param("exchange", route.exchange()).param("routingKey", route.routingKey())
                .param("correlationId", route.correlationId()).param("contentType", contentType)
                .param("expiration", expirationMs).param("body", body)
                .query(Long.class)
                .single();
    }

    /** Throws {@link AlreadyProcessedException} when another delivery stored the key first. */
    public void insertProcessedMessage(MessageKey key, Long authId, String replyBody) {
        try {
            jdbc.sql("""
                            INSERT INTO pauth.processed_message (msg_key, key_kind, auth_id, reply_body)
                            VALUES (CAST(:key AS BINARY(32)), :kind, :authId, :reply)""")
                    .param("key", key.hash()).param("kind", String.valueOf(key.kind()))
                    .param("authId", authId, java.sql.Types.BIGINT).param("reply", replyBody)
                    .update();
        } catch (DuplicateKeyException e) {
            if (violates(e, "PK_processed_message")) {
                throw new AlreadyProcessedException();
            }
            throw e;
        }
    }

    private static boolean violates(DuplicateKeyException e, String constraint) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains("'" + constraint + "'");
    }
}
