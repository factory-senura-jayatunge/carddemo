package org.carddemo.account;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

/**
 * The keyset reads of the card list (COCRDLIC): ascending card number, the filters in the
 * {@code WHERE} clause, no lock, and never the CVV, embossed name or expiry (BR-CRDL-001,
 * SE-CRDL-001, SE-CRDL-002, D-DATA-04).
 */
@Repository
public class CardListRepository {

    public record ListedCard(long cardId, String cardNum, String acctId, String activeStatus) {
    }

    /** Exact-match filters; a null member is a blank filter. */
    public record Filter(String acctId, String cardNum) {
    }

    private static final RowMapper<ListedCard> CARD = (rs, n) -> new ListedCard(rs.getLong(1), rs.getString(2),
            rs.getString(3), rs.getString(4));

    private final JdbcClient jdbc;

    public CardListRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The card number of a cursor's card, if the card still exists. */
    public Optional<String> cardNumOf(long cardId) {
        return jdbc.sql("SELECT card_num FROM core.card WHERE card_id = :id").param("id", cardId)
                .query(String.class).optional();
    }

    /** Up to {@code rows} cards from the start ({@code key} null), from {@code key} or after it, ascending. */
    public List<ListedCard> forward(Filter filter, String key, boolean inclusive, int rows) {
        return select(filter, key == null ? null : inclusive ? ">=" : ">", key, "ASC", rows);
    }

    /** Up to {@code rows} cards before {@code key}, nearest first. */
    public List<ListedCard> backward(Filter filter, String key, int rows) {
        return select(filter, "<", key, "DESC", rows);
    }

    public boolean anyBefore(Filter filter, String key, boolean inclusive) {
        return exists(filter, inclusive ? "<=" : "<", key);
    }

    public boolean anyAfter(Filter filter, String key, boolean inclusive) {
        return exists(filter, inclusive ? ">=" : ">", key);
    }

    private List<ListedCard> select(Filter filter, String op, String key, String order, int rows) {
        StatementSpec spec = jdbc.sql("SELECT TOP (:rows) card_id, card_num, acct_id, active_status FROM core.card"
                + where(filter, op) + " ORDER BY card_num " + order);
        return bind(spec, filter, key).param("rows", rows).query(CARD).list();
    }

    private boolean exists(Filter filter, String op, String key) {
        StatementSpec spec = jdbc.sql("SELECT TOP 1 1 FROM core.card" + where(filter, op));
        return bind(spec, filter, key).query(Integer.class).optional().isPresent();
    }

    /**
     * Only fixed fragments: the operator comes from this class and every value is a parameter. The
     * driver sends strings as NVARCHAR, which would convert the CHAR column side and can turn the
     * seeks on the key and on {@code IX_card_acct} into scans; the casts compare CHAR to CHAR. The
     * filters are validated digits, so a cast never truncates.
     */
    private static String where(Filter filter, String op) {
        List<String> conditions = new ArrayList<>();
        if (filter.acctId() != null) {
            conditions.add("acct_id = CAST(:acct AS CHAR(11))");
        }
        if (filter.cardNum() != null) {
            conditions.add("card_num = CAST(:card AS CHAR(16))");
        }
        if (op != null) {
            conditions.add("card_num " + op + " CAST(:key AS CHAR(16))");
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private static StatementSpec bind(StatementSpec spec, Filter filter, String key) {
        if (filter.acctId() != null) {
            spec = spec.param("acct", filter.acctId());
        }
        if (filter.cardNum() != null) {
            spec = spec.param("card", filter.cardNum());
        }
        if (key != null) {
            spec = spec.param("key", key);
        }
        return spec;
    }
}
