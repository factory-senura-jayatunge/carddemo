package org.carddemo.account;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Supplier;
import org.carddemo.account.CardViewRepository.StoredCard;
import org.carddemo.account.api.CardView;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.AuditLog;
import org.carddemo.platform.web.CorrelationIdFilter;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.Pii;
import org.carddemo.platform.web.SafeErrors;
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.carddemo.platform.web.Unmask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * COCRDSLC: a card by its typed keys, or by its ID from a card list row. Read-only; only the
 * ADMIN unmask writes, one {@code ops.audit} row.
 */
@Service
public class CardViewService {

    static final List<String> UNMASKABLE = List.of("cardNum");

    private static final Logger log = LoggerFactory.getLogger(CardViewService.class);
    private static final String ACCT_ID = "acctId";
    private static final String SUBJECT_TYPE = "CARD";

    private final CardViewRepository repository;
    private final AuditLog audit;
    private final Clock clock;
    private final SafeTransactionTemplate readTx;
    private final SafeTransactionTemplate tx;

    public CardViewService(CardViewRepository repository, JdbcClient jdbc, Clock clock,
                           SafeTransactionTemplate accountReadTransaction, SafeTransactionTemplate accountTransaction) {
        this.repository = repository;
        this.audit = new AuditLog(jdbc, "account-service");
        this.clock = clock;
        this.readTx = accountReadTransaction;
        this.tx = accountTransaction;
    }

    /** The keys exactly as typed; a missing key is blank. */
    public CardView lookup(String acctId, String cardNum) {
        CardKeys.require(acctId, cardNum, LocalDate.now(clock));
        StoredCard card = readTx.execute(status -> found(read(
                () -> repository.byNumberAndAccount(cardNum, acctId),
                () -> "card " + Pii.cardNumber(cardNum))));
        return view(card);
    }

    /** No edits: the list row's card, by ID (BR-CRDV-003). */
    public CardView view(String cardId) {
        long id = cardId(cardId);
        StoredCard card = readTx.execute(status -> found(read(() -> repository.byId(id), () -> "card_id " + id)));
        return view(card);
    }

    /** The clear card number, after one audit row in the same transaction. */
    public Map<String, String> unmask(String cardId, List<String> fields, String userId) {
        SortedSet<String> names = Unmask.requireFields(new Unmask.Request(fields), UNMASKABLE);
        long id = cardId(cardId);
        return tx.execute(status -> {
            StoredCard card = found(read(() -> repository.byId(id), () -> "card_id " + id));
            try {
                audit.record(userId, AuditLog.Action.UNMASK, SUBJECT_TYPE, String.valueOf(card.cardId()), names);
            } catch (DataAccessException e) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.UNMASK_AUDIT_FAILED, null, e);
            }
            return Map.of("cardNum", card.cardNum());
        });
    }

    /** A positive {@code BIGINT}, or the card is not found: no client-held context can be wrong (ERR-CRDV-003). */
    static long cardId(String cardId) {
        if (cardId != null && cardId.matches("[0-9]{1,19}")) {
            try {
                long id = Long.parseLong(cardId);
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException beyondBigint) {
                // falls through to not found
            }
        }
        throw notFound();
    }

    /** NUM-CRDV-001: month and year by position in the stored text, no check. */
    static String expiryMonth(String expirationDate) {
        return expirationDate.substring(5, 7);
    }

    static String expiryYear(String expirationDate) {
        return expirationDate.substring(0, 4);
    }

    private static StoredCard found(Optional<StoredCard> card) {
        return card.orElseThrow(CardViewService::notFound);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, MessageCatalog.CARD_NOT_FOUND, ACCT_ID);
    }

    /**
     * Any database failure is the one read-error problem (ERR-CRDV-001). The log line names the card
     * masked to its last four, or its ID, and never the embossed name (D-NFR-04).
     */
    private static Optional<StoredCard> read(Supplier<Optional<StoredCard>> read, Supplier<String> subject) {
        try {
            return read.get();
        } catch (DataAccessException e) {
            log.error("card read failed: {}, correlation ID {}: {}", subject.get(),
                    MDC.get(CorrelationIdFilter.MDC_KEY), SafeErrors.describe(e));
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.CARD_LOOKUP_FAILED, ACCT_ID, e);
        }
    }

    private static CardView view(StoredCard c) {
        return new CardView(String.valueOf(c.cardId()), c.acctId(), Pii.cardNumber(c.cardNum()), c.embossedName(),
                c.activeStatus(), expiryMonth(c.expirationDate()), expiryYear(c.expirationDate()),
                MessageCatalog.CARD_DISPLAYED.render(), MessageCatalog.CARD_DISPLAYED.severity());
    }
}
