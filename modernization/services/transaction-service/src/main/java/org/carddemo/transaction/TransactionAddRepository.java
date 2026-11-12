package org.carddemo.transaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The reads and the write of transaction add (COTRN02C). The cross-reference and reference reads
 * take no lock (SE-TRNA-003). The waiting row is written, and an earlier one found by its
 * idempotency key, only through procedures, so the login has no table {@code INSERT} and never
 * reads {@code card_num} or {@code raw_record} of {@code core.daily_tran}.
 */
@Repository
public class TransactionAddRepository {

    static final String IDEMPOTENCY_INDEX = "UX_daily_idempotency";
    static final String WAITING_INDEX = "UX_daily_waiting";

    /**
     * The row data of an add, normalised as it is written: padded IDs, text without trailing
     * spaces, {@code origTs} the 10-character date and 16 spaces.
     */
    public record DailyTranBody(String typeCd, String catCd, String source, String description, BigDecimal amount,
                                String merchantId, String merchantName, String merchantCity, String merchantZip,
                                String cardNum, String origTs) {

        @Override
        public String toString() {
            return "DailyTranBody[card ****" + (cardNum == null || cardNum.length() < 4 ? ""
                    : cardNum.substring(cardNum.length() - 4)) + "]";
        }
    }

    /** What {@code core.daily_tran_key_lookup} answers: no row, or the row's ID and whether its body is this one. */
    public record KeyLookup(boolean found, String tranId, boolean sameBody) {
    }

    private final JdbcClient jdbc;

    public TransactionAddRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The account's cards in card-number order; none when the account has no cross-reference row. */
    public List<String> cardsOfAccount(String acctId) {
        return jdbc.sql("SELECT card_num FROM core.card_xref WHERE acct_id = CAST(:acct AS CHAR(11)) ORDER BY card_num")
                .param("acct", acctId)
                .query(String.class)
                .list();
    }

    public Optional<String> accountOfCard(String cardNum) {
        return jdbc.sql("SELECT acct_id FROM core.card_xref WHERE card_num = CAST(:card AS CHAR(16))")
                .param("card", cardNum)
                .query(String.class)
                .optional();
    }

    public boolean typeExists(String typeCd) {
        return jdbc.sql("SELECT 1 FROM ref.tran_type WHERE type_cd = CAST(:type AS CHAR(2))")
                .param("type", typeCd)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public boolean categoryExists(String typeCd, String catCd) {
        return jdbc.sql("""
                        SELECT 1 FROM ref.tran_category
                        WHERE type_cd = CAST(:type AS CHAR(2)) AND cat_cd = CAST(:cat AS CHAR(4))""")
                .param("type", typeCd)
                .param("cat", catCd)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * The add stored under the user's key, compared with {@code body} by the procedure, card
     * number included. The procedure reads {@code UX_daily_idempotency} without a condition on
     * {@code cycle_id}, so a row the cycle has claimed is still found.
     */
    public KeyLookup keyLookup(String createdBy, UUID idempotencyKey, DailyTranBody body) {
        return jdbc.sql("""
                        EXEC core.daily_tran_key_lookup @created_by = :createdBy, @idempotency_key = :key,
                            @type_cd = :typeCd, @cat_cd = :catCd, @source = :source, @description = :description,
                            @amount = :amount, @merchant_id = :merchantId, @merchant_name = :merchantName,
                            @merchant_city = :merchantCity, @merchant_zip = :merchantZip, @card_num = :cardNum,
                            @orig_ts = :origTs""")
                .param("createdBy", createdBy)
                .param("key", idempotencyKey.toString())
                .param("typeCd", body.typeCd())
                .param("catCd", body.catCd())
                .param("source", body.source())
                .param("description", body.description())
                .param("amount", body.amount())
                .param("merchantId", body.merchantId())
                .param("merchantName", body.merchantName())
                .param("merchantCity", body.merchantCity())
                .param("merchantZip", body.merchantZip())
                .param("cardNum", body.cardNum())
                .param("origTs", body.origTs())
                .query((rs, n) -> new KeyLookup(rs.getBoolean("found"), rs.getString("tran_id"),
                        rs.getBoolean("same_body")))
                .single();
    }

    /**
     * Inserts the waiting row ({@code origin ONLINE_ADD}, {@code cycle_id} and {@code seq} NULL,
     * set by the procedure) and returns its {@code daily_pk}.
     *
     * @throws DuplicateKeyException on {@code UX_daily_waiting} or {@code UX_daily_idempotency};
     *     {@link #violates} tells them apart
     */
    public long onlineAdd(String createdBy, String tranId, DailyTranBody body, String procTs, String rawRecord,
                          UUID idempotencyKey) {
        return jdbc.sql("""
                        EXEC core.daily_tran_online_add @created_by = :createdBy, @tran_id = :tranId,
                            @type_cd = :typeCd, @cat_cd = :catCd, @source = :source, @description = :description,
                            @amount = :amount, @merchant_id = :merchantId, @merchant_name = :merchantName,
                            @merchant_city = :merchantCity, @merchant_zip = :merchantZip, @card_num = :cardNum,
                            @orig_ts = :origTs, @proc_ts = :procTs, @raw_record = :rawRecord,
                            @idempotency_key = :key""")
                .param("createdBy", createdBy)
                .param("tranId", tranId)
                .param("typeCd", body.typeCd())
                .param("catCd", body.catCd())
                .param("source", body.source())
                .param("description", body.description())
                .param("amount", body.amount())
                .param("merchantId", body.merchantId())
                .param("merchantName", body.merchantName())
                .param("merchantCity", body.merchantCity())
                .param("merchantZip", body.merchantZip())
                .param("cardNum", body.cardNum())
                .param("origTs", body.origTs())
                .param("procTs", procTs)
                .param("rawRecord", rawRecord)
                .param("key", idempotencyKey.toString())
                .query(Long.class)
                .single();
    }

    /**
     * Whether the duplicate key is one of {@code index}. SQL Server names the index in the text of
     * errors 2601 and 2627; only the name is looked for, the text is never logged.
     */
    static boolean violates(DuplicateKeyException e, String index) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(index)) {
                return true;
            }
        }
        return false;
    }
}
