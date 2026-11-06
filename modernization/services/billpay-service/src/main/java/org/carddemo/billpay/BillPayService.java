package org.carddemo.billpay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.ledger.AccountNotFoundException;
import org.carddemo.platform.ledger.BalancePosting;
import org.carddemo.platform.ledger.BatchWindowException;
import org.carddemo.platform.ledger.BatchWindowGuard;
import org.carddemo.platform.ledger.DuplicateTranIdException;
import org.carddemo.platform.ledger.NewTransaction;
import org.carddemo.platform.ledger.Origin;
import org.carddemo.platform.ledger.TransactionIds;
import org.carddemo.platform.ledger.TransactionWriter;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.Severity;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * COBIL00C as two calls: the balance preview and the full-balance payment. The checks run in the
 * order of the legacy program, so the first failing one is the message the client sees; the
 * payment's writes are one database transaction (D-29).
 */
@Service
public class BillPayService {

    static final int ACCT_ID_LENGTH = 11;
    static final String RETRY_AFTER_SECONDS = "300";

    static final String TYPE_CD = "02";
    static final String CAT_CD = "0002";
    static final String SOURCE = "POS TERM";
    static final String DESCRIPTION = "BILL PAYMENT - ONLINE";
    static final String MERCHANT_ID = "999999999";
    static final String MERCHANT_NAME = "BILL PAYMENT";
    static final String MERCHANT_CITY = "N/A";
    static final String MERCHANT_ZIP = "N/A";

    private static final String ACCT_ID = "acctId";
    private static final String CONFIRM = "confirm";

    private final BillPayRepository repository;
    private final BatchWindowGuard batchWindow;
    private final TransactionIds transactionIds;
    private final TransactionWriter transactions;
    private final BalancePosting balances;
    private final FidelitySwitches switches;
    private final Clock clock;
    private final TransactionTemplate tx;

    public BillPayService(BillPayRepository repository, BatchWindowGuard batchWindow, TransactionIds transactionIds,
                          TransactionWriter transactions, BalancePosting balances, FidelitySwitches switches,
                          Clock clock, TransactionTemplate billPayTransaction) {
        this.repository = repository;
        this.batchWindow = batchWindow;
        this.transactionIds = transactionIds;
        this.transactions = transactions;
        this.balances = balances;
        this.switches = switches;
        this.clock = clock;
        this.tx = billPayTransaction;
    }

    /** The result of a payment call; {@code replayed} when it is the stored answer to a key seen before. */
    public record Outcome(PaymentResult result, boolean replayed) {
    }

    public Preview preview(String acctId) {
        requireAcctId(acctId);
        BigDecimal balance = lookup(MessageCatalog.ACCOUNT_LOOKUP_FAILED, () -> repository.balance(acctId))
                .orElseThrow(BillPayService::accountNotFound);
        boolean payable = balance.signum() > 0;
        Message message = payable ? MessageCatalog.CONFIRM_PAYMENT : MessageCatalog.NOTHING_TO_PAY;
        return new Preview(acctId, plain(balance), display(balance), payable, message.render(), message.severity(),
                payable ? CONFIRM : ACCT_ID);
    }

    public Outcome pay(String acctId, String confirm, String idempotencyKey, String userId) {
        requireAcctId(acctId);
        if (!"Y".equals(confirm) && !"y".equals(confirm)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_CONFIRM, CONFIRM);
        }
        UUID key = parseKey(idempotencyKey);
        Optional<Outcome> earlier = replay(key, acctId, userId);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        try {
            return tx.execute(status -> payInTransaction(acctId, key, userId));
        } catch (KeyAlreadyUsed e) {
            return replay(key, acctId, userId)
                    .orElseThrow(() -> new IllegalStateException("payment of key " + key + " vanished", e));
        }
    }

    private Outcome payInTransaction(String acctId, UUID key, String userId) {
        try {
            batchWindow.requireWritesAllowed();
        } catch (BatchWindowException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, MessageCatalog.BATCH_WINDOW, ACCT_ID)
                    .header("Retry-After", RETRY_AFTER_SECONDS);
        }
        BigDecimal before = lookup(MessageCatalog.ACCOUNT_LOOKUP_FAILED, () -> repository.lockBalance(acctId))
                .orElseThrow(BillPayService::accountNotFound);
        // A retry of a payment that committed while this one waited for the account lock: it
        // would see a zero balance, but it must get the first payment's answer.
        if (repository.findPayment(key).isPresent()) {
            throw new KeyAlreadyUsed(null);
        }
        if (before.signum() <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, MessageCatalog.NOTHING_TO_PAY, ACCT_ID)
                    .with("currentBalance", plain(before))
                    .with("currentBalanceDisplay", display(before));
        }
        String card = lookup(MessageCatalog.XREF_LOOKUP_FAILED, () -> repository.firstCard(acctId))
                .orElseThrow(BillPayService::accountNotFound);

        BigDecimal amount = LegacyNumbers.truncate(before, 9);
        String tranId;
        try {
            tranId = transactionIds.next();
        } catch (DataAccessException | IllegalStateException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.TRAN_LOOKUP_FAILED, ACCT_ID, e);
        }

        boolean fixed = switches.isFixed(BillPaySwitches.DEF_BILL_010);
        BigDecimal stored = fixed ? amount.negate() : amount;
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime ts = wholeSeconds(now);
        long tranPk;
        try {
            tranPk = transactions.insert(new NewTransaction(tranId, TYPE_CD, CAT_CD, SOURCE, DESCRIPTION, stored, card,
                    MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, ts, ts, Origin.BILL_PAY, userId));
        } catch (DuplicateTranIdException e) {
            throw new ApiException(HttpStatus.CONFLICT, MessageCatalog.TRAN_ID_EXISTS, ACCT_ID, e);
        } catch (DataAccessException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.TRAN_ADD_FAILED, ACCT_ID, e);
        }

        try {
            if (fixed) {
                balances.post(acctId, TYPE_CD, CAT_CD, amount.negate());
            } else {
                balances.balanceOnly(acctId, amount.negate());
            }
        } catch (AccountNotFoundException e) {
            throw accountNotFound();
        } catch (DataAccessException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.ACCOUNT_UPDATE_FAILED, ACCT_ID, e);
        }

        try {
            repository.insertPayment(tranPk, tranId, acctId, userId, key, stored, before, now);
        } catch (DuplicateKeyException e) {
            throw new KeyAlreadyUsed(e);
        } catch (DataAccessException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.TRAN_ADD_FAILED, ACCT_ID, e);
        }
        return new Outcome(result(tranId, amount, acctId), false);
    }

    private Optional<Outcome> replay(UUID key, String acctId, String userId) {
        return repository.findPayment(key).map(p -> {
            if (!p.acctId().equals(acctId) || !p.paidBy().equals(userId.trim())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, MessageCatalog.IDEMPOTENCY_KEY_REUSED, null);
            }
            return new Outcome(result(p.tranId(), p.amount().abs(), p.acctId()), true);
        });
    }

    private static PaymentResult result(String tranId, BigDecimal amount, String acctId) {
        Message message = MessageCatalog.PAYMENT_SUCCESSFUL;
        return new PaymentResult(tranId, plain(amount), acctId, message.render(tranId), Severity.SUCCESS, ACCT_ID);
    }

    /** The legacy record has no fraction of a second (NUM-BILL-003). */
    static LocalDateTime wholeSeconds(LocalDateTime now) {
        return now.truncatedTo(ChronoUnit.SECONDS);
    }

    /** NUM-BILL-001: the screen's {@code +9999999999.99} edit of the balance. */
    static String display(BigDecimal balance) {
        return LegacyNumbers.editSigned(balance, 10, 2);
    }

    static String plain(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.DOWN).toPlainString();
    }

    private static void requireAcctId(String acctId) {
        if (acctId == null || acctId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.ACCT_ID_REQUIRED, ACCT_ID);
        }
    }

    private static UUID parseKey(String value) {
        if (value != null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return UUID.fromString(value);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.IDEMPOTENCY_KEY_REQUIRED, null);
    }

    private static <T> Optional<T> lookup(Message failure, Supplier<Optional<T>> read) {
        try {
            return read.get();
        } catch (DataAccessException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, failure, ACCT_ID, e);
        }
    }

    private static ApiException accountNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, MessageCatalog.ACCOUNT_NOT_FOUND, ACCT_ID);
    }

    /** Rolls the payment back; the caller answers with the payment stored under the same key. */
    private static final class KeyAlreadyUsed extends RuntimeException {
        KeyAlreadyUsed(Throwable cause) {
            super("idempotency key already stored", cause);
        }
    }
}
