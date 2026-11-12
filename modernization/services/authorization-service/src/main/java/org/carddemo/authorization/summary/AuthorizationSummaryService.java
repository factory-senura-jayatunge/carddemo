package org.carddemo.authorization.summary;

import static org.carddemo.authorization.ScreenFormat.amount;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.carddemo.authorization.ScreenFormat;
import org.carddemo.authorization.api.AuthorizationPage;
import org.carddemo.authorization.api.AuthorizationRow;
import org.carddemo.authorization.api.AuthorizationSummary;
import org.carddemo.authorization.api.SummaryCustomer;
import org.carddemo.authorization.summary.AuthorizationSummaryRepository.AccountLimits;
import org.carddemo.authorization.summary.AuthorizationSummaryRepository.CustomerRow;
import org.carddemo.authorization.summary.AuthorizationSummaryRepository.ListedDetail;
import org.carddemo.authorization.summary.AuthorizationSummaryRepository.SummaryRow;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.CorrelationIdFilter;
import org.carddemo.platform.web.Cursors;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.Page;
import org.carddemo.platform.web.PageRequest.Kind;
import org.carddemo.platform.web.SafeErrors;
import org.carddemo.platform.web.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * COPAUS0C: the account, the customer of its lowest card, the summary (contract §7.2) and one page
 * of details (§8), each request in one read-only transaction with fresh state (D-31, D-75). A
 * missing cross-reference row or customer is a message on the summary, not an error; a failed read
 * is the {@code 500} of that read, without codes (D-58), and its cause goes to the log.
 */
@Service
public class AuthorizationSummaryService {

    /** The screen's five rows (BR-AUSM-003); the only page size. */
    static final int PAGE_ROWS = 5;

    private static final Logger log = LoggerFactory.getLogger(AuthorizationSummaryService.class);
    private static final String ACCT_ID = "acctId";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final AuthorizationSummaryRepository repository;
    private final TransactionTemplate readTx;

    public AuthorizationSummaryService(AuthorizationSummaryRepository repository,
            TransactionTemplate authorizationReadTransaction) {
        this.repository = repository;
        this.readTx = authorizationReadTransaction;
    }

    public AuthorizationSummary summary(String acctId) {
        requireAcctId(acctId);
        try {
            return readTx.execute(status -> readSummary(acctId));
        } catch (TransactionException e) {
            // A transaction that cannot start has no connection: the summary-read text (contract §7.2).
            throw failure(MessageCatalog.AUTH_SUMMARY_READ_FAILED, acctId, e);
        }
    }

    public AuthorizationPage list(String acctId, String after, String before, String size) {
        requireAcctId(acctId);
        requireSize(size);
        Kind kind = kind(after, before);
        ListPosition position = switch (kind) {
            case AFTER -> position(after);
            case BEFORE -> position(before);
            default -> null;
        };
        try {
            return readTx.execute(status -> readPage(acctId, kind, position));
        } catch (TransactionException e) {
            throw failure(MessageCatalog.AUTH_DETAIL_READ_FAILED, acctId, e);
        }
    }

    /** VAL-AUSM-001 then VAL-AUSM-002, on the value as typed: no padding (DEF-AUSM-005 kept). */
    static void requireAcctId(String acctId) {
        if (acctId == null || acctId.chars().allMatch(c -> c == ' ' || c == 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.ACCOUNT_ID_REQUIRED, ACCT_ID);
        }
        if (!acctId.matches("[0-9]{11}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.ACCOUNT_ID_NOT_NUMERIC, ACCT_ID);
        }
    }

    /** The query text as sent: absent or exactly {@code 5}. */
    static void requireSize(String size) {
        if (size != null && !size.equals(String.valueOf(PAGE_ROWS))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.AUSM_INVALID_PAGE_SIZE, null);
        }
    }

    /** At most one of {@code after} and {@code before}; a blank one counts as absent. */
    static Kind kind(String after, String before) {
        boolean hasAfter = after != null && !after.isBlank();
        boolean hasBefore = before != null && !before.isBlank();
        if (hasAfter && hasBefore) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.AUSM_INVALID_PAGE_REQUEST, null);
        }
        return hasAfter ? Kind.AFTER : hasBefore ? Kind.BEFORE : Kind.FIRST;
    }

    static ListPosition position(String cursor) {
        return ListPosition.parse(Cursors.decode(cursor))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_CURSOR, null));
    }

    private AuthorizationSummary readSummary(String acctId) {
        AccountLimits account = account(acctId);
        Optional<String> custId = read(MessageCatalog.AUTH_XREF_READ_FAILED, acctId,
                () -> repository.firstCustomer(acctId));
        SummaryCustomer customer = null;
        String notice = null;
        if (custId.isEmpty()) {
            notice = MessageCatalog.AUTH_XREF_NOT_FOUND.render(acctId);
        } else {
            Optional<CustomerRow> row = read(MessageCatalog.AUTH_CUSTOMER_READ_FAILED, acctId,
                    () -> repository.customer(custId.get()));
            if (row.isEmpty()) {
                notice = MessageCatalog.AUTH_CUSTOMER_NOT_FOUND.render(custId.get());
            } else {
                customer = customer(row.get());
            }
        }
        Optional<SummaryRow> summary = read(MessageCatalog.AUTH_SUMMARY_READ_FAILED, acctId,
                () -> repository.summary(acctId));
        SummaryRow s = summary.orElse(new SummaryRow(0, 0, ZERO, ZERO, ZERO, ZERO));
        return new AuthorizationSummary(acctId, customer, amount(account.creditLimit()), amount(account.cashLimit()),
                summary.isPresent(), s.approvedCount(), s.declinedCount(), amount(s.approvedAmount()),
                amount(s.declinedAmount()), amount(s.creditBalance()), amount(s.cashBalance()), notice,
                notice == null ? null : Severity.ERROR);
    }

    private AuthorizationPage readPage(String acctId, Kind kind, ListPosition position) {
        account(acctId);
        Page<ListedDetail> page = read(MessageCatalog.AUTH_DETAIL_READ_FAILED, acctId, () -> switch (kind) {
            case AFTER -> Page.forward(repository.older(acctId, position, PAGE_ROWS + 1), PAGE_ROWS,
                    AuthorizationSummaryService::key, repository.anyNewer(acctId, position, true));
            case BEFORE -> backward(acctId, position);
            default -> first(acctId);
        });
        Page<ListedDetail> shown = page.withKeys(Cursors::encode);
        return new AuthorizationPage(shown.items().stream().map(AuthorizationSummaryService::row).toList(),
                shown.hasNext(), shown.hasPrevious(), shown.firstKey(), shown.lastKey());
    }

    private Page<ListedDetail> first(String acctId) {
        return Page.forward(repository.older(acctId, null, PAGE_ROWS + 1), PAGE_ROWS, AuthorizationSummaryService::key,
                false);
    }

    /**
     * The page before the position, one page back each time (DEF-AUSM-002 fixed). Fewer newer rows
     * than a page is the top of the list: the first page.
     */
    private Page<ListedDetail> backward(String acctId, ListPosition position) {
        List<ListedDetail> fetched = repository.newer(acctId, position, PAGE_ROWS + 1);
        if (fetched.size() < PAGE_ROWS) {
            return first(acctId);
        }
        List<ListedDetail> rows = new ArrayList<>(fetched.subList(0, PAGE_ROWS));
        Collections.reverse(rows);
        return new Page<>(rows, repository.anyOlder(acctId, position, true), fetched.size() > PAGE_ROWS,
                key(rows.getFirst()), key(rows.getLast()));
    }

    private AccountLimits account(String acctId) {
        return read(MessageCatalog.AUTH_ACCOUNT_READ_FAILED, acctId, () -> repository.account(acctId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, MessageCatalog.AUSM_ACCOUNT_NOT_FOUND, ACCT_ID,
                        acctId));
    }

    private static <T> T read(Message failure, String acctId, Supplier<T> read) {
        try {
            return read.get();
        } catch (DataAccessException e) {
            throw failure(failure, acctId, e);
        }
    }

    private static ApiException failure(Message failure, String acctId, RuntimeException e) {
        log.error("{}: account {}, correlation ID {}: {}", failure.code(), acctId, MDC.get(CorrelationIdFilter.MDC_KEY),
                SafeErrors.describe(e));
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, failure, ACCT_ID, e);
    }

    private static String key(ListedDetail detail) {
        return detail.position().key();
    }

    /**
     * BR-AUSM-001: the legacy STRING of the name, no word cut; the address lines joined by a bare
     * comma. The cuts at 25 characters and at the first double space are display truncation
     * (DEF-AUSM-004), which belongs to the web.
     */
    static SummaryCustomer customer(CustomerRow c) {
        String name = firstWord(c.firstName()) + " " + initial(c.middleName()) + " " + firstWord(c.lastName());
        String line1 = trimEnd(c.addrLine1()) + "," + trimEnd(c.addrLine2());
        String zip = c.zip() == null ? "" : c.zip();
        String line2 = trimEnd(c.addrLine3()) + "," + trimEnd(c.stateCd()) + ","
                + trimEnd(zip.substring(0, Math.min(5, zip.length())));
        return new SummaryCustomer(c.custId(), name, line1, line2, c.phone1());
    }

    /** {@code DELIMITED BY SPACES}: the text up to its first space. */
    private static String firstWord(String value) {
        if (value == null) {
            return "";
        }
        int space = value.indexOf(' ');
        return space < 0 ? value : value.substring(0, space);
    }

    /** {@code CUST-MIDDLE-NAME(1:1)}: a space when there is no middle name. */
    private static String initial(String value) {
        return value == null || value.isEmpty() ? " " : value.substring(0, 1);
    }

    /** Only trailing spaces go: leading spaces are part of the stored value. */
    private static String trimEnd(String value) {
        return value == null ? "" : value.stripTrailing();
    }

    static AuthorizationRow row(ListedDetail d) {
        return new AuthorizationRow(d.authId(), d.transactionId(), ScreenFormat.date(d.origDate()),
                ScreenFormat.time(d.origTime()), d.authType(), "00".equals(d.respCode()) ? "A" : "D", d.matchStatus(),
                amount(d.approvedAmount()));
    }
}
