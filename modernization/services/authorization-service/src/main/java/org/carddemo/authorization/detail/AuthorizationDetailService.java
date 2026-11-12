package org.carddemo.authorization.detail;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.carddemo.authorization.ScreenFormat;
import org.carddemo.authorization.api.AuthorizationDetail;
import org.carddemo.authorization.api.AuthorizationFraud;
import org.carddemo.authorization.api.AuthorizationMerchant;
import org.carddemo.authorization.detail.AuthorizationDetailRepository.StoredDetail;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.CorrelationIdFilter;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.Pii;
import org.carddemo.platform.web.RowVersions;
import org.carddemo.platform.web.SafeErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * COPAUS1C and COPAUS2C: one authorization with the next older one of its account (contract §9.1),
 * and the fraud toggle (§9.2), which writes the detail's fraud columns, the fraud row and one
 * {@code ops.audit} row in one transaction. A conflict is never retried here: the user confirms
 * again on the fresh view. The log names the authorization by its ID, never by its card.
 */
@Service
public class AuthorizationDetailService {

    static final String FRAUD = "F";
    static final String REMOVED = "R";

    private static final Logger log = LoggerFactory.getLogger(AuthorizationDetailService.class);
    private static final DateTimeFormatter REPORT_DATE = DateTimeFormatter.ofPattern("MM/dd/yy");

    /** BR-AUDT-003: the screen's table of decline reasons. */
    static final Map<String, String> REASONS = Map.of(
            "0000", "APPROVED",
            "3100", "INVALID CARD",
            "4100", "INSUFFICNT FUND",
            "4200", "CARD NOT ACTIVE",
            "4300", "ACCOUNT CLOSED",
            "4400", "EXCED DAILY LMT",
            "5100", "CARD FRAUD",
            "5200", "MERCHANT FRAUD",
            "5300", "LOST CARD",
            "9000", "UNKNOWN");

    private final AuthorizationDetailRepository repository;
    private final Clock clock;
    private final TransactionTemplate readTx;
    private final TransactionTemplate tx;

    public AuthorizationDetailService(AuthorizationDetailRepository repository, Clock clock,
            TransactionTemplate authorizationReadTransaction, TransactionTemplate authorizationTransaction) {
        this.repository = repository;
        this.clock = clock;
        this.readTx = authorizationReadTransaction;
        this.tx = authorizationTransaction;
    }

    /** A detail and the {@code ETag} that goes with it. */
    public record Versioned(AuthorizationDetail detail, String etag) {
    }

    /** A committed fraud change: the message of BR-AUDT-008 and the detail read back. */
    public record Marked(Message message, Versioned saved) {
    }

    /** §9.1: one read-only transaction for the detail and its next older row. */
    public Versioned detail(String authId) {
        long id = authId(authId);
        try {
            return readTx.execute(status -> versioned(found(read(MessageCatalog.AUDT_DETAIL_READ_FAILED, id,
                    () -> repository.find(id))), id));
        } catch (TransactionException e) {
            throw failure(MessageCatalog.AUDT_DETAIL_READ_FAILED, id, e);
        }
    }

    /** §9.2: the checks of step 1 in the contract's order, then steps 2 to 7 in one transaction. */
    public Marked changeFraud(String authId, String ifMatch, String action, String userId) {
        long id = authId(authId);
        String flag = action(action);
        String expected = detailVersion(RowVersions.requireIfMatch(ifMatch));
        LocalDate today = LocalDate.now(clock);
        Versioned saved;
        try {
            saved = tx.execute(status -> mark(id, expected, flag, today, userId));
        } catch (CannotCreateTransactionException e) {
            // Nothing has been read yet: the text of the first read, the locking read of step 2.
            throw failure(MessageCatalog.AUDT_DETAIL_READ_FAILED, id, e);
        } catch (TransactionException e) {
            // A commit that fails has changed nothing.
            throw failure(MessageCatalog.FRAUD_UPDATE_FAILED, id, e);
        }
        return new Marked(FRAUD.equals(flag) ? MessageCatalog.AUTH_MARKED_FRAUD : MessageCatalog.AUTH_FRAUD_REMOVED,
                saved);
    }

    private Versioned mark(long id, String expected, String action, LocalDate today, String userId) {
        StoredDetail locked = found(read(MessageCatalog.AUDT_DETAIL_READ_FAILED, id, () -> repository.lock(id)));
        if (!RowVersions.matches(expected, locked.rowVersion()) || !action.equals(toggle(locked.fraudFlag()))) {
            throw conflict(locked, id);
        }
        try {
            if (repository.markFraud(id, locked.rowVersion(), action, today, userId) != 1) {
                throw conflict(found(repository.find(id)), id);
            }
            if (repository.updateFraudRow(id, action, today, userId) == 0
                    && repository.insertFraudRow(id, action, today, userId) != 1) {
                throw failure(MessageCatalog.FRAUD_UPDATE_FAILED, id, new IllegalStateException("no fraud row written"));
            }
            repository.audit(userId, id);
            StoredDetail stored = found(repository.find(id));
            return versioned(stored, repository.nextOlder(stored.acctId(), stored.position()).orElse(null));
        } catch (DataAccessException e) {
            throw failure(MessageCatalog.FRAUD_UPDATE_FAILED, id, e);
        }
    }

    /** BR-AUDT-007: {@code F} becomes {@code R}; blank or {@code R} becomes {@code F}. */
    static String toggle(String storedFlag) {
        return FRAUD.equals(storedFlag) ? REMOVED : FRAUD;
    }

    /** VAL-AUDT-001: a positive BIGINT, as typed; nothing is read otherwise. */
    static long authId(String authId) {
        if (authId != null && authId.matches("[0-9]{1,19}")) {
            try {
                long id = Long.parseLong(authId);
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException beyondBigint) {
                // falls through to the 400
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_AUTH_ID, null);
    }

    static String action(String action) {
        if (!FRAUD.equals(action) && !REMOVED.equals(action)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_FRAUD_ACTION, null);
        }
        return action;
    }

    /** A detail has exactly one row version. */
    private static String detailVersion(String tag) {
        if (tag.indexOf('.') >= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_IF_MATCH, null);
        }
        return tag;
    }

    /** §9.2 step 3: the stored detail now, with its next older row, as {@code current}. */
    private ApiException conflict(StoredDetail current, long id) {
        Versioned fresh = versioned(current, id);
        return RowVersions.conflict(null, fresh.detail(), current.rowVersion());
    }

    private Versioned versioned(StoredDetail stored, long id) {
        Optional<Long> next = read(MessageCatalog.AUTH_NEXT_READ_FAILED, id,
                () -> repository.nextOlder(stored.acctId(), stored.position()));
        return versioned(stored, next.orElse(null));
    }

    private static Versioned versioned(StoredDetail stored, Long nextOlder) {
        return new Versioned(view(stored, nextOlder), RowVersions.etag(stored.rowVersion()));
    }

    private static StoredDetail found(Optional<StoredDetail> detail) {
        return detail.orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, MessageCatalog.AUTHORIZATION_NOT_FOUND, null));
    }

    private static <T> T read(Message failure, long id, Supplier<T> read) {
        try {
            return read.get();
        } catch (DataAccessException e) {
            throw failure(failure, id, e);
        }
    }

    private static ApiException failure(Message failure, long id, RuntimeException e) {
        log.error("{}: auth_id {}, correlation ID {}: {}", failure.code(), id, MDC.get(CorrelationIdFilter.MDC_KEY),
                SafeErrors.describe(e));
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, failure, null, e);
    }

    /** BR-AUDT-002 to BR-AUDT-004, with the authorization ID code and the match (DEF-AUDT-002 fixed, D-33). */
    static AuthorizationDetail view(StoredDetail d, Long nextOlder) {
        return new AuthorizationDetail(d.authId(), d.acctId(), RowVersions.tag(d.rowVersion()),
                Pii.cardNumber(d.cardNum()), ScreenFormat.date(d.origDate()), ScreenFormat.time(d.origTime()),
                "00".equals(d.respCode()) ? "A" : "D", d.respCode(), d.respReason(), reasonText(d.respReason()),
                d.authIdCode(), d.processingCode(), ScreenFormat.amount(d.approvedAmount()),
                ScreenFormat.amount(d.transactionAmount()), d.posEntryMode(), d.messageSource(),
                d.merchantCategoryCode(), ScreenFormat.expiry(d.cardExpiryDate()), d.authType(), d.transactionId(),
                d.matchStatus(), d.matchedTranId(), fraud(d.fraudFlag(), d.fraudReportDate()),
                new AuthorizationMerchant(d.merchantName(), d.merchantId(), d.merchantCity(), d.merchantState(),
                        d.merchantZip()), nextOlder);
    }

    static String reasonText(String code) {
        String description = REASONS.get(code);
        return description == null ? "9999-ERROR" : code + "-" + description;
    }

    /** BR-AUDT-004: {@code F-MM/DD/YY} or {@code R-MM/DD/YY}; {@code -} when never marked. */
    static AuthorizationFraud fraud(String storedFlag, LocalDate reportDate) {
        if (!FRAUD.equals(storedFlag) && !REMOVED.equals(storedFlag)) {
            return new AuthorizationFraud(null, null, "-");
        }
        return new AuthorizationFraud(storedFlag, reportDate == null ? null : reportDate.toString(),
                storedFlag + "-" + (reportDate == null ? "" : REPORT_DATE.format(reportDate)));
    }
}
