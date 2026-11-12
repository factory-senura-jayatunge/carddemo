package org.carddemo.account;

import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The reads of card view (COCRDSLC): keyed SELECTs, no lock, never the CVV (SE-CRDV-001, D-DATA-04). */
@Repository
public class CardViewRepository {

    /** {@code expirationDate} is the stored text, {@code YYYY-MM-DD}. */
    public record StoredCard(long cardId, String cardNum, String acctId, String embossedName, String activeStatus,
                             String expirationDate) {
    }

    private static final RowMapper<StoredCard> CARD = (rs, n) -> new StoredCard(rs.getLong(1), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6));

    private final JdbcClient jdbc;

    public CardViewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The card whose cross-reference row names the account (D-13): the cross-reference owns the
     * card-to-account link (D-DATA-02), so a card of another account is not found.
     */
    public Optional<StoredCard> byNumberAndAccount(String cardNum, String acctId) {
        return jdbc.sql("""
                        SELECT c.card_id, c.card_num, c.acct_id, c.embossed_name, c.active_status, c.expiration_date
                        FROM core.card c JOIN core.card_xref x ON x.card_num = c.card_num
                        WHERE c.card_num = :card AND x.acct_id = :acct""")
                .param("card", cardNum).param("acct", acctId)
                .query(CARD)
                .optional();
    }

    public Optional<StoredCard> byId(long cardId) {
        return jdbc.sql("""
                        SELECT card_id, card_num, acct_id, embossed_name, active_status, expiration_date
                        FROM core.card WHERE card_id = :id""")
                .param("id", cardId)
                .query(CARD)
                .optional();
    }
}
