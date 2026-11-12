package org.carddemo.transaction;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.legacyformat.LegacyNumbers;
import org.carddemo.platform.legacyformat.entity.TransactionRecord;
import org.carddemo.platform.ledger.BatchWindowException;
import org.carddemo.platform.ledger.BatchWindowGuard;
import org.carddemo.platform.ledger.LatestTransaction;
import org.carddemo.platform.ledger.LatestTransactions;
import org.carddemo.platform.ledger.LedgerSwitches;
import org.carddemo.platform.ledger.TranIdExhaustedException;
import org.carddemo.platform.ledger.TransactionIds;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.AuditLog;
import org.carddemo.platform.web.CorrelationIdFilter;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.Pii;
import org.carddemo.platform.web.SafeErrors;
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.carddemo.platform.web.Severity;
import org.carddemo.transaction.TransactionAddRepository.DailyTranBody;
import org.carddemo.transaction.TransactionAddRepository.KeyLookup;
import org.carddemo.transaction.TransactionAddRules.Codes;
import org.carddemo.transaction.TransactionAddRules.Keys;
import org.carddemo.transaction.api.AddResult;
import org.carddemo.transaction.api.Hint;
import org.carddemo.transaction.api.LatestTransactionView;
import org.carddemo.transaction.api.TransactionCheck;
import org.carddemo.transaction.api.TransactionForm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * COTRN02C as three calls: validate, add and copy last. An add writes one waiting
 * {@code core.daily_tran} row and its {@code ops.audit} row in one transaction under the batch
 * window, and nothing else; the next cycle posts the row (D-27). Validate and copy last read only.
 */
@Service
public class TransactionAddService {

    static final String SUBJECT_TYPE = "DAILY_TRAN";
    /** The form members that become row data; acctId, confirm and the server-set date are not listed. */
    static final List<String> AUDIT_FIELDS = List.of("amount", "catCd", "cardNum", "description", "merchantCity",
            "merchantId", "merchantName", "merchantZip", "origDate", "source", "typeCd");
    static final String RETRY_AFTER_SECONDS = "300";
    /** {@code ORIG-TS} and {@code PROC-TS}: the 10-character date and 16 spaces (NUM-TRNA-002). */
    static final String TIMESTAMP_PAD = " ".repeat(16);
    /** BR-TRNA-004: the queued record's FILLER is blank, not inherited. */
    static final String FILLER = " ".repeat(20);

    private static final Logger log = LoggerFactory.getLogger(TransactionAddService.class);
    private static final String ACCT_ID = TransactionAddRules.ACCT_ID;
    private static final String CARD_NUM = TransactionAddRules.CARD_NUM;
    private static final Hint POSTING_HINT = new Hint(MessageCatalog.TRANSACTION_POSTING_HINT.render(),
            MessageCatalog.TRANSACTION_POSTING_HINT.severity());

    private final TransactionAddRepository repository;
    private final AuditLog audit;
    private final BatchWindowGuard batchWindow;
    private final TransactionIds transactionIds;
    private final LatestTransactions latestTransactions;
    private final FidelitySwitches switches;
    private final Clock clock;
    private final SafeTransactionTemplate readTx;
    private final SafeTransactionTemplate tx;

    public TransactionAddService(TransactionAddRepository repository, JdbcClient jdbc, BatchWindowGuard batchWindow,
                                 TransactionIds transactionIds, LatestTransactions latestTransactions,
                                 FidelitySwitches switches, Clock clock, SafeTransactionTemplate transactionReadTx,
                                 SafeTransactionTemplate transactionWriteTx) {
        this.repository = repository;
        this.audit = new AuditLog(jdbc, "transaction-service");
        this.batchWindow = batchWindow;
        this.transactionIds = transactionIds;
        this.latestTransactions = latestTransactions;
        this.switches = switches;
        this.clock = clock;
        this.readTx = transactionReadTx;
        this.tx = transactionWriteTx;
    }

    /** The result of an add call; {@code replayed} when it is the stored answer to a key and body seen before. */
    public record Outcome(AddResult result, boolean replayed) {
    }

    /** A form that passed every check, normalised; the card number in clear for the record only. */
    record Checked(String acctId, String cardNum, String typeCd, String catCd, String source, String description,
                   BigDecimal amount, String amountDisplay, String origDate, String merchantId, String merchantName,
                   String merchantCity, String merchantZip) {

        @Override
        public String toString() {
            return "Checked[" + acctId + ", card " + Pii.cardNumber(cardNum) + "]";
        }
    }

    public TransactionCheck validate(TransactionForm form) {
        Checked c = check(form == null ? empty() : form, new LinkedHashMap<>());
        Message prompt = MessageCatalog.CONFIRM_ADD_TRANSACTION;
        return new TransactionCheck(c.acctId(), Pii.cardNumber(c.cardNum()), c.typeCd(), c.catCd(), c.merchantId(),
                TransactionFormats.amount(c.amount()), c.amountDisplay(), today().toString(), prompt.render(),
                prompt.severity(), TransactionAddRules.CONFIRM);
    }

    public Outcome add(TransactionForm typed, String idempotencyKey, String userId) {
        TransactionForm form = typed == null ? empty() : typed;
        Map<String, Object> passed = new LinkedHashMap<>();
        Checked c = check(form, passed);
        decorated(passed, () -> {
            TransactionAddRules.confirm(form.confirm());
            return null;
        });
        UUID key = parseKey(idempotencyKey);
        DailyTranBody body = body(c);
        Optional<Outcome> earlier = replay(userId, key, body);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        try {
            return tx.execute(status -> addInTransaction(c, body, key, userId));
        } catch (KeyAlreadyUsed e) {
            return replay(userId, key, body)
                    .orElseThrow(() -> new IllegalStateException("the add of an idempotency key vanished", e));
        } catch (DataAccessException | TransactionException e) {
            throw failed(MessageCatalog.ADD_FAILED, "transaction add failed", e);
        }
    }

    public LatestTransactionView latest() {
        Optional<LatestTransaction> row;
        try {
            row = readTx.execute(status -> latestTransactions.latest());
        } catch (DataAccessException | TransactionException e) {
            throw failed(MessageCatalog.TRAN_LOOKUP_FAILED, "latest transaction read failed", e);
        }
        LatestTransaction t = row.orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, MessageCatalog.NO_TRANSACTION, ACCT_ID));
        BigDecimal amount = t.amount();
        return new LatestTransactionView(TransactionFormats.trimTrailing(t.typeCd()),
                TransactionFormats.trimTrailing(t.catCd()), TransactionFormats.trimTrailing(t.source()),
                cut(t.description(), 60),
                amount == null ? null : TransactionFormats.amount(amount),
                amount == null ? null : TransactionAddRules.amountDisplay(
                        LegacyNumbers.truncate(amount, TransactionFormats.AMOUNT_DIGITS)),
                TransactionFormats.trimTrailing(t.origDate()), today().toString(),
                TransactionFormats.trimTrailing(t.merchantId()), cut(t.merchantName(), 30), cut(t.merchantCity(), 25),
                TransactionFormats.trimTrailing(t.merchantZip()));
    }

    /**
     * Validate steps 2 and 3, first failure wins. Each problem after the keys carries the values the
     * checks before it changed (padded IDs, the looked-up partner key, the re-edited amount), which
     * the page shows with the message; {@code passed} collects them.
     */
    private Checked check(TransactionForm form, Map<String, Object> passed) {
        return decorated(passed, () -> {
            Keys keys = TransactionAddRules.keys(form.acctId(), form.cardNum());
            String[] resolved = resolve(keys);
            passed.put(ACCT_ID, resolved[0]);
            passed.put(CARD_NUM, Pii.cardNumber(resolved[1]).masked());

            Map<String, String> values = values(form);
            TransactionAddRules.dataFields(values);
            Codes codes = TransactionAddRules.codes(form.typeCd(), form.catCd());
            passed.put("typeCd", codes.typeCd());
            passed.put("catCd", codes.catCd());
            references(codes);

            TransactionAddRules.forms(form.amount(), form.origDate());
            BigDecimal amount = TransactionAddRules.amount(form.amount());
            String display = TransactionAddRules.amountDisplay(amount);
            passed.put("amount", TransactionFormats.amount(amount));
            passed.put("amountDisplay", display);
            String origDate = TransactionAddRules.origDate(form.origDate());
            String merchantId = TransactionAddRules.merchantId(form.merchantId());
            passed.put("merchantId", merchantId);

            return new Checked(resolved[0], resolved[1], codes.typeCd(), codes.catCd(),
                    TransactionFormats.trimTrailing(values.get("source")),
                    TransactionFormats.trimTrailing(values.get("description")), amount, display, origDate, merchantId,
                    TransactionFormats.trimTrailing(values.get("merchantName")),
                    TransactionFormats.trimTrailing(values.get("merchantCity")),
                    TransactionFormats.trimTrailing(values.get("merchantZip")));
        });
    }

    /**
     * Rows 3 and 5 (D-61): the account's one card; with several, the user must choose; a typed pair
     * must be a cross-reference row; a card alone gives its account. Returns account and card.
     */
    private String[] resolve(Keys keys) {
        if (keys.acctId() != null) {
            List<String> cards = read(MessageCatalog.TRNA_XREF_LOOKUP_FAILED, ACCT_ID,
                    () -> repository.cardsOfAccount(keys.acctId()));
            if (cards.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, MessageCatalog.ACCOUNT_NOT_FOUND, ACCT_ID);
            }
            if (keys.cardNum() != null) {
                if (!cards.contains(keys.cardNum())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.CARD_ACCOUNT_MISMATCH, CARD_NUM);
                }
                return new String[] {keys.acctId(), keys.cardNum()};
            }
            if (cards.size() > 1) {
                throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.CARD_CHOICE_REQUIRED, CARD_NUM)
                        .with("cards", cards.stream().map(card -> Pii.cardNumber(card).masked()).toList());
            }
            return new String[] {keys.acctId(), cards.get(0)};
        }
        String account = read(MessageCatalog.TRNA_CARD_LOOKUP_FAILED, CARD_NUM,
                () -> repository.accountOfCard(keys.cardNum()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, MessageCatalog.TRNA_CARD_NOT_FOUND, CARD_NUM));
        return new String[] {account, keys.cardNum()};
    }

    /** Rows 18 and 19 (DEF-TRNA-002). */
    private void references(Codes codes) {
        if (!read(MessageCatalog.REFERENCE_LOOKUP_FAILED, "typeCd", () -> repository.typeExists(codes.typeCd()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.TYPE_NOT_FOUND, "typeCd");
        }
        if (!read(MessageCatalog.REFERENCE_LOOKUP_FAILED, "typeCd",
                () -> repository.categoryExists(codes.typeCd(), codes.catCd()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.CATEGORY_NOT_FOUND, "catCd");
        }
    }

    /** Add steps 4 to 8, in one transaction. */
    private Outcome addInTransaction(Checked c, DailyTranBody body, UUID key, String userId) {
        try {
            batchWindow.requireWritesAllowed();
        } catch (BatchWindowException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, MessageCatalog.TRAN_ADD_BATCH_WINDOW, ACCT_ID)
                    .header("Retry-After", RETRY_AFTER_SECONDS);
        }
        String tranId;
        try {
            tranId = transactionIds.next();
        } catch (TranIdExhaustedException e) {
            // The cause is told apart in the log only; neither the line nor the response names an ID.
            log.error("TRAN_ID_EXHAUSTED: transaction add refused, mode {}, correlation ID {}",
                    switches.isFixed(LedgerSwitches.BR_TRNA_003) ? "fixed" : "legacy",
                    MDC.get(CorrelationIdFilter.MDC_KEY));
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.ADD_FAILED, ACCT_ID, e);
        } catch (DataAccessException | IllegalStateException e) {
            throw failed(MessageCatalog.TRAN_LOOKUP_FAILED, "transaction ID allocation failed", e);
        }

        String procTs = today() + TIMESTAMP_PAD;
        String rawRecord = new String(record(tranId, c, body.origTs(), procTs).toDailyBytes(),
                StandardCharsets.ISO_8859_1);
        try {
            repository.onlineAdd(userId, tranId, body, procTs, rawRecord, key);
        } catch (DuplicateKeyException e) {
            if (TransactionAddRepository.violates(e, TransactionAddRepository.IDEMPOTENCY_INDEX)) {
                throw new KeyAlreadyUsed(e);
            }
            if (TransactionAddRepository.violates(e, TransactionAddRepository.WAITING_INDEX)) {
                throw new ApiException(HttpStatus.CONFLICT, MessageCatalog.TRAN_ID_EXISTS, ACCT_ID, e);
            }
            throw failed(MessageCatalog.ADD_FAILED, "transaction add failed", e);
        } catch (DataAccessException e) {
            throw failed(MessageCatalog.ADD_FAILED, "transaction add failed", e);
        }

        try {
            audit.record(userId, AuditLog.Action.CREATE, SUBJECT_TYPE, tranId, AUDIT_FIELDS);
        } catch (DataAccessException e) {
            throw failed(MessageCatalog.ADD_FAILED, "transaction add audit failed", e);
        }
        return new Outcome(result(tranId), false);
    }

    /** Add step 3: the stored add of this key, if any. Another body under the same key is refused. */
    private Optional<Outcome> replay(String userId, UUID key, DailyTranBody body) {
        KeyLookup found;
        try {
            found = repository.keyLookup(userId, key, body);
        } catch (DataAccessException e) {
            throw failed(MessageCatalog.TRAN_LOOKUP_FAILED, "idempotency key lookup failed", e);
        }
        if (!found.found()) {
            return Optional.empty();
        }
        if (!found.sameBody()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, MessageCatalog.TRAN_IDEMPOTENCY_KEY_REUSED, null);
        }
        return Optional.of(new Outcome(result(TransactionFormats.trimTrailing(found.tranId())), true));
    }

    static DailyTranBody body(Checked c) {
        return new DailyTranBody(c.typeCd(), c.catCd(), c.source(), c.description(), c.amount(), c.merchantId(),
                c.merchantName(), c.merchantCity(), c.merchantZip(), c.cardNum(), c.origDate() + TIMESTAMP_PAD);
    }

    /**
     * The 350-byte ENT-DAILY-TRAN record (CVTRA06Y), field by field as BR-TRNA-004: text fields
     * right-padded, the amount zoned with its overpunch sign, the FILLER blank.
     */
    static TransactionRecord record(String tranId, Checked c, String origTs, String procTs) {
        return new TransactionRecord(tranId, c.typeCd(), c.catCd(), c.source(), c.description(), c.amount(),
                c.merchantId(), c.merchantName(), c.merchantCity(), c.merchantZip(), c.cardNum(), origTs, procTs,
                FILLER);
    }

    private static AddResult result(String tranId) {
        Message added = MessageCatalog.TRANSACTION_ADDED;
        return new AddResult(tranId, Severity.SUCCESS, ACCT_ID, added.render(tranId), POSTING_HINT);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static Map<String, String> values(TransactionForm form) {
        Map<String, String> values = new HashMap<>();
        values.put("typeCd", form.typeCd());
        values.put("catCd", form.catCd());
        values.put("source", form.source());
        values.put("description", form.description());
        values.put("amount", form.amount());
        values.put("origDate", form.origDate());
        values.put("merchantId", form.merchantId());
        values.put("merchantName", form.merchantName());
        values.put("merchantCity", form.merchantCity());
        values.put("merchantZip", form.merchantZip());
        values.replaceAll((name, value) -> value == null ? "" : value);
        return values;
    }

    private static TransactionForm empty() {
        return new TransactionForm(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** The first {@code length} characters, then trailing spaces removed (NUM-TRNA-005). */
    private static String cut(String value, int length) {
        String text = value == null ? "" : value;
        return TransactionFormats.trimTrailing(text.length() > length ? text.substring(0, length) : text);
    }

    private static UUID parseKey(String value) {
        if (value != null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return UUID.fromString(value);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.IDEMPOTENCY_KEY_REQUIRED, null);
    }

    private static <T> T decorated(Map<String, Object> passed, Supplier<T> checks) {
        try {
            return checks.get();
        } catch (ApiException e) {
            passed.forEach((name, value) -> {
                if (!e.extras().containsKey(name)) {
                    e.with(name, value);
                }
            });
            throw e;
        }
    }

    private static <T> T read(Message failure, String field, Supplier<T> read) {
        try {
            return read.get();
        } catch (DataAccessException e) {
            log.error("transaction add read failed ({}), correlation ID {}: {}", failure.code(),
                    MDC.get(CorrelationIdFilter.MDC_KEY), SafeErrors.describe(e));
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, failure, field, e);
        }
    }

    private static ApiException failed(Message message, String what, RuntimeException e) {
        log.error("{}, correlation ID {}: {}", what, MDC.get(CorrelationIdFilter.MDC_KEY), SafeErrors.describe(e));
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, message, ACCT_ID, e);
    }

    /** Rolls the add back; the caller answers with the add stored under the same key. */
    private static final class KeyAlreadyUsed extends RuntimeException implements SafeErrors.MessageFree {
        KeyAlreadyUsed(Throwable cause) {
            super("idempotency key already stored", cause == null ? null : SafeErrors.sanitized(cause), false, true);
        }
    }
}
