package org.carddemo.transaction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.AuditLog;
import org.carddemo.platform.web.CorrelationIdFilter;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.Pii;
import org.carddemo.platform.web.SafeErrors;
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.carddemo.platform.web.Unmask;
import org.carddemo.transaction.TransactionViewRepository.StoredTransaction;
import org.carddemo.transaction.api.TransactionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * COTRN01C: a master transaction by its typed ID. Read-only; only the ADMIN unmask writes, one
 * {@code ops.audit} row in the transaction of its read.
 */
@Service
public class TransactionViewService {

    static final List<String> UNMASKABLE = List.of("cardNum");
    static final String SUBJECT_TYPE = "TRANSACTION";

    private static final Logger log = LoggerFactory.getLogger(TransactionViewService.class);
    private static final String FIELD = TransactionKeys.VIEW_FIELD;

    private final TransactionViewRepository repository;
    private final AuditLog audit;
    private final SafeTransactionTemplate readTx;
    private final SafeTransactionTemplate tx;

    public TransactionViewService(TransactionViewRepository repository, JdbcClient jdbc,
                                  SafeTransactionTemplate transactionReadTx, SafeTransactionTemplate transactionWriteTx) {
        this.repository = repository;
        this.audit = new AuditLog(jdbc, "transaction-service");
        this.readTx = transactionReadTx;
        this.tx = transactionWriteTx;
    }

    public TransactionView view(String tranId) {
        String key = TransactionKeys.viewKey(tranId).orElseThrow(TransactionViewService::notFound);
        StoredTransaction t;
        try {
            t = readTx.execute(status -> read(key));
        } catch (TransactionException e) {
            throw lookupFailed(key, e);
        }
        return view(found(t));
    }

    /** The clear card number, after one audit row in the same transaction (DEF-TRNV-004, D-09). */
    public Map<String, String> unmask(String tranId, List<String> fields, String userId) {
        SortedSet<String> names = Unmask.requireFields(new Unmask.Request(fields), UNMASKABLE);
        String key = TransactionKeys.viewKey(tranId).orElseThrow(TransactionViewService::notFound);
        return tx.execute(status -> {
            StoredTransaction t = found(read(key));
            try {
                audit.record(userId, AuditLog.Action.UNMASK, SUBJECT_TYPE, t.tranId(), names);
            } catch (DataAccessException e) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.UNMASK_AUDIT_FAILED, null, e);
            }
            Map<String, String> clear = new HashMap<>();
            clear.put("cardNum", t.cardNum() == null ? null : TransactionFormats.trimTrailing(t.cardNum()));
            return clear;
        });
    }

    /**
     * Any database failure is the one read-error problem (ERR-TRNV-001). The log line has the key,
     * which is not PII and holds printable characters only, and the cause instead of the CICS
     * response codes.
     */
    private StoredTransaction read(String key) {
        try {
            return repository.byId(key).orElse(null);
        } catch (DataAccessException e) {
            throw lookupFailed(key, e);
        }
    }

    private static ApiException lookupFailed(String key, RuntimeException e) {
        log.error("transaction read failed: tran_id {}, correlation ID {}: {}", key,
                MDC.get(CorrelationIdFilter.MDC_KEY), SafeErrors.describe(e));
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.TRAN_LOOKUP_FAILED, FIELD, e);
    }

    private static StoredTransaction found(StoredTransaction t) {
        if (t == null) {
            throw notFound();
        }
        return t;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, MessageCatalog.TRAN_NOT_FOUND, FIELD);
    }

    static TransactionView view(StoredTransaction t) {
        return new TransactionView(TransactionFormats.trimTrailing(t.tranId()),
                Pii.cardNumber(t.cardNum() == null ? null : TransactionFormats.trimTrailing(t.cardNum())),
                TransactionFormats.trimTrailing(t.typeCd()), TransactionFormats.trimTrailing(t.catCd()),
                TransactionFormats.trimTrailing(t.source()), TransactionFormats.trimTrailing(t.description()),
                TransactionFormats.amount(t.amount()), TransactionFormats.amountDisplay(t.amount()),
                TransactionFormats.timestamp(t.origTs()), TransactionFormats.timestamp(t.procTs()),
                TransactionFormats.trimTrailing(t.merchantId()), TransactionFormats.trimTrailing(t.merchantName()),
                TransactionFormats.trimTrailing(t.merchantCity()), TransactionFormats.trimTrailing(t.merchantZip()));
    }
}
