package org.carddemo.account;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.carddemo.account.CardUpdateRepository.StoredCard;
import org.carddemo.account.api.CardChanges;
import org.carddemo.account.api.CardUpdateForm;
import org.carddemo.platform.ledger.BatchWindowException;
import org.carddemo.platform.ledger.BatchWindowGuard;
import org.carddemo.platform.validation.RuleTable;
import org.carddemo.platform.validation.ValidationResult;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.AuditLog;
import org.carddemo.platform.web.CorrelationIdFilter;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.Pii;
import org.carddemo.platform.web.RowVersions;
import org.carddemo.platform.web.SafeErrors;
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * COCRDUPC: the card's name, status and expiry month and year, changed in two steps, validate and
 * save. The account, the card number, the CVV and the filler are never written (D-12, D-13); each
 * save is one transaction with one {@code ops.audit} row (D-56).
 */
@Service
public class CardUpdateService {

    static final String FORM = "cardUpdate";
    static final String RETRY_AFTER_SECONDS = "300";

    private static final Logger log = LoggerFactory.getLogger(CardUpdateService.class);
    private static final String ACCT_ID = "acctId";
    private static final String EMBOSSED_NAME = "embossedName";
    private static final String SUBJECT_TYPE = "CARD";

    private final CardUpdateRepository repository;
    private final BatchWindowGuard batchWindow;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final Clock clock;
    private final SafeTransactionTemplate readTx;
    private final SafeTransactionTemplate tx;

    public CardUpdateService(CardUpdateRepository repository, BatchWindowGuard batchWindow, JdbcTemplate jdbcTemplate,
                             JdbcClient jdbc, Clock clock, SafeTransactionTemplate accountReadTransaction,
                             SafeTransactionTemplate accountTransaction) {
        this.repository = repository;
        this.batchWindow = batchWindow;
        this.jdbc = jdbcTemplate;
        this.audit = new AuditLog(jdbc, "account-service");
        this.clock = clock;
        this.readTx = accountReadTransaction;
        this.tx = accountTransaction;
    }

    /** A form and the {@code ETag} that goes with it. */
    public record Versioned(CardUpdateForm form, String etag) {
    }

    /** The four fields as typed; a missing one is empty. */
    record Typed(String embossedName, String activeStatus, String expiryMonth, String expiryYear) {

        static Typed of(CardChanges changes) {
            if (changes == null) {
                return new Typed("", "", "", "");
            }
            return new Typed(text(changes.embossedName()), text(changes.activeStatus()), text(changes.expiryMonth()),
                    text(changes.expiryYear()));
        }

        private static String text(String value) {
            return value == null ? "" : value;
        }

        /** BR-CRDU-008 (D-51): stored as typed, without the trailing spaces of the input field. */
        String name() {
            return embossedName.stripTrailing();
        }

        // The rules see status, month and year without surrounding spaces, so the comparison and the
        // write must too: " 05" passes the rules and must neither count as a change nor reach parseInt.
        String status() {
            return activeStatus.strip().toUpperCase(Locale.ROOT);
        }

        String month() {
            return expiryMonth.strip();
        }

        String year() {
            return expiryYear.strip();
        }

        Map<String, String> input() {
            Map<String, String> input = new HashMap<>();
            input.put(EMBOSSED_NAME, embossedName);
            input.put("activeStatus", activeStatus);
            input.put("expiryMonth", expiryMonth);
            input.put("expiryYear", expiryYear);
            return input;
        }
    }

    /** The keys exactly as typed; a missing key is blank. */
    public Versioned lookup(String acctId, String cardNum) {
        CardKeys.require(acctId, cardNum, LocalDate.now(clock));
        StoredCard card = readTx.execute(status -> found(read(
                () -> repository.byNumberAndAccount(cardNum, acctId), () -> "card " + Pii.cardNumber(cardNum))));
        return versioned(card);
    }

    /** FLOW-CRDU-005 and FLOW-CRDU-011: entry from the list, and Cancel. */
    public Versioned fetch(String cardId) {
        long id = CardViewService.cardId(cardId);
        return versioned(readTx.execute(status -> found(read(() -> repository.byId(id), () -> "card_id " + id))));
    }

    /** The Enter step: nothing is written. */
    public void validate(String cardId, String version, CardChanges changes) {
        long id = CardViewService.cardId(cardId);
        StoredCard card = readTx.execute(status -> found(read(() -> repository.byId(id), () -> "card_id " + id)));
        String expected = bodyVersion(version);
        if (!RowVersions.matches(expected, card.rowVersion())) {
            throw RowVersions.conflict(EMBOSSED_NAME, form(card), card.rowVersion());
        }
        check(Typed.of(changes), card);
    }

    public Versioned update(String cardId, String ifMatch, CardChanges changes, String userId) {
        long id = CardViewService.cardId(cardId);
        String expected = cardVersion(RowVersions.requireIfMatch(ifMatch));
        Typed typed = Typed.of(changes);
        // A card that is gone before the save starts is the lock failure of save step 6.
        StoredCard unlocked = readTx.execute(status -> read(() -> repository.byId(id), () -> "card_id " + id))
                .orElseThrow(() -> lockFailed(HttpStatus.CONFLICT, id, null));
        check(typed, unlocked);
        return tx.execute(status -> {
            try {
                return LockTimeout.within(jdbc, () -> {
                    requireWritesAllowed(id);
                    return save(id, expected, typed, userId);
                });
            } catch (DataAccessException e) {
                throw lockFailed(HttpStatus.INTERNAL_SERVER_ERROR, id, e);
            }
        });
    }

    private Versioned save(long id, String expected, Typed typed, String userId) {
        StoredCard locked;
        try {
            locked = repository.lock(id).orElseThrow(() -> lockFailed(HttpStatus.CONFLICT, id, null));
        } catch (DataAccessException e) {
            throw lockFailed(LockTimeout.timedOut(e) ? HttpStatus.CONFLICT : HttpStatus.INTERNAL_SERVER_ERROR, id, e);
        }
        if (!RowVersions.matches(expected, locked.rowVersion())) {
            throw RowVersions.conflict(EMBOSSED_NAME, form(locked), locked.rowVersion());
        }
        String name = typed.name();
        String status = typed.status();
        String expiry = expirationDate(typed.year(), typed.month(), locked.expirationDate());
        TreeSet<String> changed = new TreeSet<>();
        if (!name.equals(locked.embossedName())) {
            changed.add(EMBOSSED_NAME);
        }
        if (!status.equals(locked.activeStatus())) {
            changed.add("activeStatus");
        }
        if (!expiry.equals(locked.expirationDate())) {
            changed.add("expirationDate");
        }
        try {
            if (repository.update(id, locked.rowVersion(), name, status, expiry) != 1) {
                throw updateFailed(id, null);
            }
            audit.record(userId, AuditLog.Action.UPDATE, SUBJECT_TYPE, String.valueOf(id), changed);
            StoredCard saved = repository.byId(id).orElseThrow(() -> updateFailed(id, null));
            return versioned(saved);
        } catch (DataAccessException e) {
            throw updateFailed(id, e);
        }
    }

    /** Validate steps 3 and 4: change detection (BR-CRDU-004 as fixed by D-51), then the rules. */
    private void check(Typed typed, StoredCard card) {
        if (typed.name().equals(card.embossedName().stripTrailing())
                && typed.status().equals(card.activeStatus())
                && typed.month().equals(CardViewService.expiryMonth(card.expirationDate()))
                && typed.year().equals(CardViewService.expiryYear(card.expirationDate()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.NO_CHANGE, EMBOSSED_NAME);
        }
        Map<String, String> stored = Map.of(
                "expiryMonth", CardViewService.expiryMonth(card.expirationDate()),
                "expiryYear", CardViewService.expiryYear(card.expirationDate()));
        ValidationResult result = RuleTable.standard().form(FORM).validate(typed.input(), LocalDate.now(clock), null,
                stored);
        if (!result.valid()) {
            throw RuleProblems.rejected(result);
        }
    }

    /**
     * DEF-CRDU-009 (D-51): the typed year and month with the stored day, which becomes the month's
     * last day when the new month is shorter. The rules have checked year and month already.
     */
    static String expirationDate(String year, String month, String stored) {
        YearMonth ym = YearMonth.of(Integer.parseInt(year), Integer.parseInt(month));
        int day = Math.min(Integer.parseInt(stored.substring(8, 10)), ym.lengthOfMonth());
        return String.format(Locale.ROOT, "%04d-%02d-%02d", ym.getYear(), ym.getMonthValue(), day);
    }

    private void requireWritesAllowed(long id) {
        try {
            batchWindow.requireWritesAllowed();
        } catch (BatchWindowException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, MessageCatalog.CARD_UPDATE_BATCH_WINDOW,
                    EMBOSSED_NAME).header("Retry-After", RETRY_AFTER_SECONDS);
        } catch (DataAccessException e) {
            throw lockFailed(HttpStatus.INTERNAL_SERVER_ERROR, id, e);
        }
    }

    /** {@code version} of the validate body: a card has exactly one row version. */
    private static String bodyVersion(String version) {
        if (version == null || version.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_IF_MATCH, null);
        }
        return cardVersion(RowVersions.requireIfMatch('"' + version + '"'));
    }

    private static String cardVersion(String tag) {
        if (tag.indexOf('.') >= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_IF_MATCH, null);
        }
        return tag;
    }

    private static Versioned versioned(StoredCard card) {
        return new Versioned(form(card), RowVersions.etag(card.rowVersion()));
    }

    static CardUpdateForm form(StoredCard c) {
        return new CardUpdateForm(String.valueOf(c.cardId()), c.acctId(), Pii.cardNumber(c.cardNum()),
                c.embossedName(), c.activeStatus(), CardViewService.expiryMonth(c.expirationDate()),
                CardViewService.expiryYear(c.expirationDate()), RowVersions.tag(c.rowVersion()), null, null);
    }

    private static StoredCard found(Optional<StoredCard> card) {
        return card.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, MessageCatalog.CARD_NOT_FOUND, ACCT_ID));
    }

    /**
     * Any database failure of a read is the one read-error problem. The log names the card masked
     * to its last four, or its ID, and never the embossed name (D-NFR-04).
     */
    private static Optional<StoredCard> read(Supplier<Optional<StoredCard>> read, Supplier<String> subject) {
        try {
            return read.get();
        } catch (DataAccessException e) {
            log.error("card update read failed: {}, correlation ID {}: {}", subject.get(),
                    MDC.get(CorrelationIdFilter.MDC_KEY), SafeErrors.describe(e));
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.CARD_LOOKUP_FAILED, ACCT_ID, e);
        }
    }

    private static ApiException lockFailed(HttpStatus status, long id, DataAccessException cause) {
        log.warn("card lock failed: card_id {}, correlation ID {}{}", id, MDC.get(CorrelationIdFilter.MDC_KEY),
                cause == null ? ": no row" : ": " + SafeErrors.describe(cause));
        return new ApiException(status, MessageCatalog.CARD_LOCK_FAILED, ACCT_ID, cause);
    }

    private static ApiException updateFailed(long id, DataAccessException cause) {
        log.error("card update failed: card_id {}, correlation ID {}{}", id, MDC.get(CorrelationIdFilter.MDC_KEY),
                cause == null ? ": no row updated" : ": " + SafeErrors.describe(cause));
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.CARD_UPDATE_FAILED, ACCT_ID, cause);
    }
}
