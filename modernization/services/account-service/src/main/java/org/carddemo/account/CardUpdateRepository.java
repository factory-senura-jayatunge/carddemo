package org.carddemo.account;

import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The reads and the one write of card update (COCRDUPC). No statement names {@code cvv_cd},
 * {@code acct_id} is never written and {@code legacy_filler} is neither read nor written
 * (D-12, D-13, D-DATA-04).
 */
@Repository
public class CardUpdateRepository {

    /** {@code expirationDate} is the stored text, {@code YYYY-MM-DD}. */
    public record StoredCard(long cardId, String cardNum, String acctId, String embossedName, String activeStatus,
                             String expirationDate, byte[] rowVersion) {
    }

    private static final String COLUMNS =
            "c.card_id, c.card_num, c.acct_id, c.embossed_name, c.active_status, c.expiration_date, c.row_version";

    private static final RowMapper<StoredCard> CARD = (rs, n) -> new StoredCard(rs.getLong(1), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getBytes(7));

    private final JdbcClient jdbc;

    public CardUpdateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The card whose cross-reference row names the account (D-13), as card view's lookup. */
    public Optional<StoredCard> byNumberAndAccount(String cardNum, String acctId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM core.card c JOIN core.card_xref x ON x.card_num = c.card_num"
                        + " WHERE c.card_num = :card AND x.acct_id = :acct")
                .param("card", cardNum).param("acct", acctId)
                .query(CARD)
                .optional();
    }

    public Optional<StoredCard> byId(long cardId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM core.card c WHERE c.card_id = :id")
                .param("id", cardId)
                .query(CARD)
                .optional();
    }

    /** SE-CRDU-002: the save's read, holding an update lock until the transaction ends. */
    public Optional<StoredCard> lock(long cardId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM core.card c WITH (UPDLOCK, ROWLOCK) WHERE c.card_id = :id")
                .param("id", cardId)
                .query(CARD)
                .optional();
    }

    /** The three columns the grant allows, only while the row still has the version the save read. */
    public int update(long cardId, byte[] rowVersion, String embossedName, String activeStatus, String expirationDate) {
        return jdbc.sql("""
                        UPDATE core.card SET embossed_name = :name, active_status = :status, expiration_date = :expiry
                        WHERE card_id = :id AND row_version = :version""")
                .param("name", embossedName).param("status", activeStatus).param("expiry", expirationDate)
                .param("id", cardId).param("version", rowVersion)
                .update();
    }
}
