package org.carddemo.authorization.processing;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;
import org.carddemo.authorization.decision.Decision;
import org.carddemo.authorization.decision.DecisionFacts;
import org.carddemo.authorization.processing.AuthorizationRepository.AccountRow;
import org.carddemo.authorization.processing.AuthorizationRepository.CardRow;
import org.carddemo.authorization.processing.AuthorizationRepository.StoredReply;
import org.carddemo.authorization.processing.AuthorizationRepository.SummaryChange;
import org.carddemo.authorization.processing.AuthorizationRepository.Xref;
import org.carddemo.authorization.reply.AuthorizationReply;
import org.carddemo.authorization.request.Amount;
import org.carddemo.authorization.request.AuthorizationRequest;
import org.carddemo.authorization.request.MessageKey;
import org.carddemo.authorization.request.RequestField;
import org.carddemo.platform.web.Pii;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One request's database work (contract §6.2 steps 2 to 9): the duplicate-key check outside the
 * transaction, then the reads, the summary lock, the decision and every write in one READ
 * COMMITTED transaction. Publishing and acknowledging are the listener's.
 */
@Service
public class AuthorizationProcessor {

    /** Retries of the whole transaction after a deadlock or a lock timeout (§6.4). */
    static final int TRANSACTION_RETRIES = 3;
    /** Retries of the detail insert, each 1 ms later, after a 9-complement key collision (ERR-AUTH-009). */
    static final int DETAIL_KEY_RETRIES = 5;
    /** The reply's lifetime, stored with the outbox row. */
    public static final int REPLY_EXPIRATION_MS = 5_000;
    /** The code when no detail is written (§5.4). */
    static final String NO_CODE = "000000";

    private static final Logger log = LoggerFactory.getLogger(AuthorizationProcessor.class);

    private final AuthorizationRepository repository;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AuthorizationProcessor(AuthorizationRepository repository, TransactionTemplate authorizationTransaction,
            Clock clock) {
        this.repository = repository;
        this.tx = authorizationTransaction;
        this.clock = clock;
    }

    /** Step 2: the stored reply of a request handled before, outside any transaction. */
    public Optional<StoredReply> findProcessed(MessageKey key) {
        return at(FailurePoint.PROCESSED_MESSAGE_READ, () -> repository.processedMessage(key));
    }

    /**
     * Steps 3 to 9. Throws {@link AlreadyProcessedException} when another delivery stored the same
     * key first (everything is rolled back), and {@link AuthorizationStoreException} for any other
     * SQL failure, after the retries when it is a deadlock or a lock timeout.
     */
    public Outcome process(AuthorizationRequest request, MessageKey key, ReplyRoute route) {
        for (int retry = 0; ; retry++) {
            try {
                return tx.execute(status -> processInTransaction(request, key, route));
            } catch (AuthorizationStoreException e) {
                if (!e.retryable() || retry == TRANSACTION_RETRIES) {
                    throw e;
                }
                log.warn("authorization transaction rolled back by SQL Server error {} at {}; retry {} of {}",
                        e.sqlError(), e.point(), retry + 1, TRANSACTION_RETRIES);
            }
        }
    }

    private Outcome processInTransaction(AuthorizationRequest request, MessageKey key, ReplyRoute route) {
        String card = request.stored(RequestField.CARD_NUM);
        boolean cardValid = request.cardNumberValid();
        Optional<Xref> xref = cardValid
                ? at(FailurePoint.XREF_READ, () -> repository.xref(card))
                : Optional.empty();
        Optional<AccountRow> account = Optional.empty();
        boolean customer = false;
        Optional<CardRow> cardRow = Optional.empty();
        boolean fraud = false;
        Optional<BigDecimal> pending = Optional.empty();
        if (xref.isEmpty()) {
            log.warn("A001 card not found in the cross-reference: card {}", Pii.cardNumber(card));
        } else {
            Xref x = xref.get();
            account = at(FailurePoint.ACCOUNT_READ, () -> repository.account(x.acctId()));
            if (account.isEmpty()) {
                log.warn("A002 account of the card not found: card {}", Pii.cardNumber(card));
            }
            customer = at(FailurePoint.CUSTOMER_READ, () -> repository.customerExists(x.custId()));
            if (!customer) {
                log.warn("A003 customer of the card not found: card {}", Pii.cardNumber(card));
            }
            cardRow = at(FailurePoint.CARD_READ, () -> repository.card(card));
            fraud = at(FailurePoint.FRAUD_READ, () -> repository.confirmedFraud(card));
            if (account.isPresent()) {
                pending = at(FailurePoint.SUMMARY_READ, () -> repository.lockSummary(x.acctId()));
            }
        }

        Decision decision = Decision.decide(new DecisionFacts(cardValid, xref.isPresent(), account.isPresent(),
                customer, cardRow.isPresent(), request.amount(),
                account.map(AccountRow::creditLimit).orElse(null), account.map(AccountRow::currBal).orElse(null),
                pending.orElse(BigDecimal.ZERO), cardRow.map(CardRow::activeStatus).orElse(null),
                cardRow.map(CardRow::expirationDate).orElse(null), account.map(AccountRow::activeStatus).orElse(null),
                fraud, LocalDate.now(clock)));

        Long authId = null;
        String code = NO_CODE;
        if (xref.isPresent() && account.isPresent()) {
            authId = write(request, decision, xref.get(), account.get());
            long id = authId;
            code = at(FailurePoint.DETAIL_WRITE, () -> repository.setAuthIdCode(id));
        }

        AuthorizationReply reply = new AuthorizationReply(card, request.stored(RequestField.TRANSACTION_ID), code,
                decision.respCode(), decision.reason().code(), decision.approvedAmt());
        String body = reply.body(request.form());
        long outboxId = at(FailurePoint.OUTBOX_WRITE, () -> repository.insertOutbox(route,
                AuthorizationReply.contentType(request.form()), REPLY_EXPIRATION_MS, body));
        Long storedAuthId = authId;
        at(FailurePoint.PROCESSED_MESSAGE_WRITE, () -> {
            repository.insertProcessedMessage(key, storedAuthId, reply.toCsv());
            return null;
        });
        return new Outcome(decision, reply, authId, outboxId, body);
    }

    /** Step 6: the summary and the detail; returns the detail's {@code auth_id}. */
    private long write(AuthorizationRequest request, Decision decision, Xref xref, AccountRow account) {
        boolean approved = decision.approved();
        SummaryChange change = new SummaryChange(xref.acctId(), xref.custId(), account.creditLimit(),
                account.cashCreditLimit(), approved ? 1 : 0, decision.approvedAmt(), approved ? 0 : 1,
                approved ? Amount.ZERO : request.amount().value());
        at(FailurePoint.SUMMARY_WRITE, () -> {
            if (repository.updateSummary(change) == 0) {
                repository.insertSummary(change);
            }
            return null;
        });

        LocalDateTime processedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        String matchStatus = approved ? "P" : "D";
        for (int retry = 0; ; retry++) {
            LocalDateTime at = processedAt;
            OptionalLong id = at(FailurePoint.DETAIL_WRITE, () -> repository.insertDetail(xref.acctId(), at, request,
                    decision.respCode(), decision.reason().code(), decision.approvedAmt(), matchStatus));
            if (id.isPresent()) {
                return id.getAsLong();
            }
            if (retry == DETAIL_KEY_RETRIES) {
                throw new AuthorizationStoreException(FailurePoint.DETAIL_WRITE,
                        new IllegalStateException("the detail key collided " + (DETAIL_KEY_RETRIES + 1) + " times"));
            }
            processedAt = processedAt.plus(1, ChronoUnit.MILLIS);
        }
    }

    private static <T> T at(FailurePoint point, Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException e) {
            throw new AuthorizationStoreException(point, e);
        }
    }
}
