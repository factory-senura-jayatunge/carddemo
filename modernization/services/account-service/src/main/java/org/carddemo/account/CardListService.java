package org.carddemo.account;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.carddemo.account.CardListRepository.Filter;
import org.carddemo.account.CardListRepository.ListedCard;
import org.carddemo.account.api.CardPage;
import org.carddemo.account.api.CardRow;
import org.carddemo.account.api.CardSearchRequest;
import org.carddemo.platform.validation.FieldProblem;
import org.carddemo.platform.validation.FormRules;
import org.carddemo.platform.validation.RuleTable;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.CorrelationIdFilter;
import org.carddemo.platform.web.Cursors;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.Page;
import org.carddemo.platform.web.PageRequest;
import org.carddemo.platform.web.Pii;
import org.carddemo.platform.web.SafeErrors;
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * COCRDLIC: one page of cards, filtered by account and card number, in one read-only transaction.
 * The checks run in the contract's order (cursor, size, account filter, card filter) and none of
 * them reads; only the page read does.
 */
@Service
public class CardListService {

    /** The screen's row count. */
    static final int SCREEN_ROWS = 7;

    private static final Logger log = LoggerFactory.getLogger(CardListService.class);
    private static final String ACCT_ID = "acctId";
    private static final String CARD_NUM = "cardNum";
    private static final String FORM = "cardSearch";
    private static final Map<String, Message> FILTER_MESSAGES = Map.of(
            "INVALID_ACCT_FILTER", MessageCatalog.INVALID_ACCT_FILTER,
            "INVALID_CARD_FILTER", MessageCatalog.INVALID_CARD_FILTER);

    private final CardListRepository repository;
    private final Clock clock;
    private final SafeTransactionTemplate readTx;

    public CardListService(CardListRepository repository, Clock clock, SafeTransactionTemplate accountReadTransaction) {
        this.repository = repository;
        this.clock = clock;
        this.readTx = accountReadTransaction;
    }

    public CardPage list(CardSearchRequest request) {
        CardSearchRequest r = request == null ? new CardSearchRequest(null, null, null, null, null, null) : request;
        Long cursor = cursor(r.start(), r.after(), r.before());
        PageRequest page = PageRequest.of(r.start(), r.after(), r.before(), size(r.size()), SCREEN_ROWS);
        Filter filter = filter(r.acctId(), r.cardNum(), LocalDate.now(clock));
        try {
            return readTx.execute(status -> read(page, cursor, filter));
        } catch (DataAccessException | TransactionException e) {
            log.error("card list failed: card filter {}, correlation ID {}: {}",
                    filter.cardNum() == null ? "blank" : Pii.cardNumber(filter.cardNum()),
                    MDC.get(CorrelationIdFilter.MDC_KEY), SafeErrors.describe(e));
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.CARD_LIST_FAILED, ACCT_ID, e);
        }
    }

    /** The {@code card_id} of the one cursor given, or null; it must be a token of a positive {@code BIGINT}. */
    static Long cursor(String start, String after, String before) {
        List<String> given = Stream.of(start, after, before).filter(t -> t != null && !t.isBlank()).toList();
        if (given.size() > 1) {
            throw invalidCursor();
        }
        if (given.isEmpty()) {
            return null;
        }
        String key;
        try {
            key = Cursors.decode(given.getFirst());
        } catch (ApiException notAToken) {
            throw invalidCursor();
        }
        if (!key.matches("[1-9][0-9]{0,18}")) {
            throw invalidCursor();
        }
        try {
            return Long.parseLong(key);
        } catch (NumberFormatException beyondBigint) {
            throw invalidCursor();
        }
    }

    /**
     * The size as JSON gave it: only an integer from 1 to the screen's rows. The request keeps it
     * untyped because Jackson would coerce {@code "7"} and {@code 7.5} to 7 and turn {@code "a"}
     * into an unreadable body instead of the page-size problem.
     */
    static Integer size(Object size) {
        if (size == null) {
            return null;
        }
        if (size instanceof Integer n && n >= 1 && n <= SCREEN_ROWS) {
            return n;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_PAGE_SIZE, ACCT_ID,
                Integer.toString(SCREEN_ROWS));
    }

    /**
     * The rule table's {@code cardSearch} form: the account message first, one message only
     * (VAL-CRDL-007). Only a JSON string is typed text: a number, boolean, array or object is an
     * invalid filter, in the same order.
     */
    static Filter filter(Object acctId, Object cardNum, LocalDate today) {
        FormRules form = RuleTable.standard().form(FORM);
        String acct = text(acctId, MessageCatalog.INVALID_ACCT_FILTER, ACCT_ID);
        Map<String, String> input = new HashMap<>();
        input.put(ACCT_ID, acct);
        input.put(CARD_NUM, cardNum instanceof String c ? c : null);
        form.validate(input, today).first().ifPresent(problem -> {
            throw new ApiException(HttpStatus.BAD_REQUEST, message(problem), problem.field());
        });
        String card = text(cardNum, MessageCatalog.INVALID_CARD_FILTER, CARD_NUM);
        return new Filter(form.isBlank(ACCT_ID, acct) ? null : acct, form.isBlank(CARD_NUM, card) ? null : card);
    }

    private static String text(Object value, Message invalid, String field) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, invalid, field);
    }

    private static Message message(FieldProblem problem) {
        Message message = FILTER_MESSAGES.get(problem.code());
        if (message == null) {
            throw new IllegalStateException("no catalogue message for rule code " + problem.code());
        }
        return message;
    }

    private CardPage read(PageRequest request, Long cursor, Filter filter) {
        String key = cursor == null ? null : repository.cardNumOf(cursor).orElseThrow(CardListService::invalidCursor);
        int size = request.size();
        Page<ListedCard> page = switch (request.kind()) {
            case FIRST -> first(filter, size);
            case START -> forward(filter, key, true, size);
            case AFTER -> forward(filter, key, false, size);
            case BEFORE -> backward(filter, key, size);
        };
        Message notice = notice(request.kind(), page);
        Page<CardRow> shown = page.map(CardListService::row).withKeys(Cursors::encode);
        return new CardPage(shown.items(), shown.hasNext(), shown.hasPrevious(), shown.firstKey(), shown.lastKey(),
                notice == null ? null : notice.render(), notice == null ? null : notice.severity());
    }

    private Page<ListedCard> first(Filter filter, int size) {
        return Page.forward(repository.forward(filter, null, false, size + 1), size, CardListService::key, false);
    }

    /** No matching card lies between the key and the first row, so the cards before the key decide hasPrevious. */
    private Page<ListedCard> forward(Filter filter, String key, boolean inclusive, int size) {
        List<ListedCard> fetched = repository.forward(filter, key, inclusive, size + 1);
        return Page.forward(fetched, size, CardListService::key, repository.anyBefore(filter, key, !inclusive));
    }

    /**
     * The cards before the key. Fewer than a page, but some, is the start of the list: the first
     * page read forwards (DEF-CRDL-009). None at all is an empty page with its notice.
     */
    private Page<ListedCard> backward(Filter filter, String key, int size) {
        List<ListedCard> fetched = repository.backward(filter, key, size + 1);
        if (!fetched.isEmpty() && fetched.size() < size) {
            return first(filter, size);
        }
        List<ListedCard> rows = new ArrayList<>(fetched.subList(0, Math.min(size, fetched.size())));
        Collections.reverse(rows);
        return new Page<>(rows, repository.anyAfter(filter, key, true), fetched.size() > size,
                rows.isEmpty() ? null : key(rows.getFirst()), rows.isEmpty() ? null : key(rows.getLast()));
    }

    /** The list notices of step 6; a full page with more to come has none. */
    static Message notice(PageRequest.Kind kind, Page<?> page) {
        if (page.items().isEmpty()) {
            return switch (kind) {
                case AFTER -> MessageCatalog.NO_MORE_RECORDS;
                case BEFORE -> MessageCatalog.NO_PREVIOUS_PAGES;
                case FIRST, START -> MessageCatalog.NO_RECORDS_FOUND;
            };
        }
        return page.hasNext() ? null : MessageCatalog.NO_MORE_RECORDS;
    }

    private static String key(ListedCard card) {
        return String.valueOf(card.cardId());
    }

    private static CardRow row(ListedCard card) {
        return new CardRow(String.valueOf(card.cardId()), card.acctId(), Pii.cardNumber(card.cardNum()),
                card.activeStatus());
    }

    private static ApiException invalidCursor() {
        return new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.CARD_LIST_INVALID_CURSOR, ACCT_ID);
    }
}
