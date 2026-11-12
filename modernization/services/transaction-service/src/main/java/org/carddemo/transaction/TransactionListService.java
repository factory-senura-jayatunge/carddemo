package org.carddemo.transaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.CorrelationIdFilter;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.SafeErrors;
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.carddemo.transaction.TransactionListRepository.ListedTransaction;
import org.carddemo.transaction.api.TransactionPage;
import org.carddemo.transaction.api.TransactionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * COTRN00C: one page of master transactions in one read-only transaction. The checks run in the
 * contract's order (cursor, size, search) and none of them reads.
 */
@Service
public class TransactionListService {

    /** The screen's row count. */
    static final int SCREEN_ROWS = 10;

    enum Kind { FIRST, START, AFTER, BEFORE }

    /** A checked request: {@code key} is 16 characters, null for {@link Kind#FIRST}. */
    record Request(Kind kind, String key, int size) {
    }

    /** The rows read and the list notice of step 6. */
    record Result(List<ListedTransaction> rows, boolean hasNext, boolean hasPrevious, Message notice) {
    }

    private static final Logger log = LoggerFactory.getLogger(TransactionListService.class);
    private static final String FIELD = TransactionKeys.SEARCH_FIELD;

    private final TransactionListRepository repository;
    private final SafeTransactionTemplate readTx;

    public TransactionListService(TransactionListRepository repository, SafeTransactionTemplate transactionReadTx) {
        this.repository = repository;
        this.readTx = transactionReadTx;
    }

    public TransactionPage list(String start, String after, String before, String size) {
        Request request = request(start, after, before, size);
        Result result;
        try {
            result = readTx.execute(status -> read(request));
        } catch (DataAccessException | TransactionException e) {
            log.error("transaction list failed: {} {}, correlation ID {}: {}", request.kind(),
                    request.key() == null ? "-" : request.key(), MDC.get(CorrelationIdFilter.MDC_KEY),
                    SafeErrors.describe(e));
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.TRAN_LIST_FAILED, FIELD, e);
        }
        List<TransactionRow> items = result.rows().stream().map(TransactionListService::row).toList();
        Message notice = result.notice();
        return new TransactionPage(items, result.hasNext(), result.hasPrevious(),
                result.rows().isEmpty() ? null : result.rows().getFirst().tranId(),
                result.rows().isEmpty() ? null : result.rows().getLast().tranId(),
                notice == null ? null : notice.render(), notice == null ? null : notice.severity());
    }

    /** Steps 1 to 3. An empty {@code after} or {@code before} counts as not given, as an empty search does. */
    static Request request(String start, String after, String before, String size) {
        boolean hasStart = TransactionKeys.isSearchGiven(start);
        boolean hasAfter = after != null && !after.isEmpty();
        boolean hasBefore = before != null && !before.isEmpty();
        if (Stream.of(hasStart, hasAfter, hasBefore).filter(b -> b).count() > 1
                || hasAfter && !TransactionKeys.isCursorKey(after)
                || hasBefore && !TransactionKeys.isCursorKey(before)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.CARD_LIST_INVALID_CURSOR, FIELD);
        }
        int rows = size(size);
        if (hasAfter) {
            return new Request(Kind.AFTER, after, rows);
        }
        if (hasBefore) {
            return new Request(Kind.BEFORE, before, rows);
        }
        if (hasStart) {
            return new Request(Kind.START, TransactionKeys.searchStart(start), rows);
        }
        return new Request(Kind.FIRST, null, rows);
    }

    /** The size as the query gave it: digits only, 1 to the screen's rows; none is the screen's rows. */
    static int size(String size) {
        if (size == null) {
            return SCREEN_ROWS;
        }
        if (size.matches("[0-9]{1,9}")) {
            int n = Integer.parseInt(size);
            if (n >= 1 && n <= SCREEN_ROWS) {
                return n;
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_PAGE_SIZE, FIELD,
                Integer.toString(SCREEN_ROWS));
    }

    private Result read(Request request) {
        return switch (request.kind()) {
            case FIRST -> forward(null, true, request.size());
            case START -> forward(request.key(), true, request.size());
            case AFTER -> forward(request.key(), false, request.size());
            case BEFORE -> backward(request.key(), request.size());
        };
    }

    /**
     * Read one row more than the page for {@code hasNext} (BR-TRNL-005). No master row lies between
     * the key and the first row read, so the rows before the first one decide {@code hasPrevious}.
     * An {@code after} read that finds nothing keeps the page the user is on: the bottom notice,
     * never the start of the file.
     */
    private Result forward(String key, boolean inclusive, int size) {
        List<ListedTransaction> fetched = repository.forward(key, inclusive, size + 1);
        boolean hasNext = fetched.size() > size;
        List<ListedTransaction> rows = hasNext ? fetched.subList(0, size) : fetched;
        boolean hasPrevious = key != null && (rows.isEmpty()
                ? repository.anyBefore(key, !inclusive)
                : repository.anyBefore(rows.getFirst().tranId(), false));
        Message notice;
        if (rows.isEmpty()) {
            notice = inclusive ? MessageCatalog.TRAN_LIST_NO_ROWS : MessageCatalog.TRAN_LIST_BOTTOM;
        } else {
            notice = hasNext ? null : MessageCatalog.TRAN_LIST_BOTTOM;
        }
        return new Result(rows, hasNext, hasPrevious, notice);
    }

    /**
     * The rows before the key, shown ascending. Fewer than a page is the start of the file: the
     * first page read forwards, with no blank rows at the top (DEF-TRNL-008).
     */
    private Result backward(String key, int size) {
        List<ListedTransaction> fetched = repository.backward(key, size + 1);
        if (fetched.size() < size) {
            Result first = forward(null, true, size);
            Message notice = first.rows().isEmpty() ? MessageCatalog.TRAN_LIST_NO_ROWS : MessageCatalog.TRAN_LIST_TOP;
            return new Result(first.rows(), first.hasNext(), false, notice);
        }
        boolean hasPrevious = fetched.size() > size;
        List<ListedTransaction> rows = new ArrayList<>(fetched.subList(0, size));
        Collections.reverse(rows);
        boolean hasNext = repository.anyAfter(rows.getLast().tranId());
        return new Result(rows, hasNext, hasPrevious, hasPrevious ? null : MessageCatalog.TRAN_LIST_TOP);
    }

    private static TransactionRow row(ListedTransaction t) {
        return new TransactionRow(t.tranId(), TransactionFormats.listDate(t.origTs()),
                TransactionFormats.listDescription(t.description()), TransactionFormats.amount(t.amount()),
                TransactionFormats.amountDisplay(t.amount()));
    }
}
