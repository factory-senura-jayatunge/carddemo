package org.carddemo.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.carddemo.account.AccountUpdateRepository.Link;
import org.carddemo.account.AccountUpdateRepository.StoredAccount;
import org.carddemo.account.AccountUpdateRepository.StoredCustomer;
import org.carddemo.account.api.AccountUpdateForm;
import org.carddemo.platform.ledger.BatchWindowException;
import org.carddemo.platform.ledger.BatchWindowGuard;
import org.carddemo.platform.validation.FormRules;
import org.carddemo.platform.validation.LegacyAmount;
import org.carddemo.platform.validation.RuleTable;
import org.carddemo.platform.validation.ValidationResult;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.AuditLog;
import org.carddemo.platform.web.CorrelationIdFilter;
import org.carddemo.platform.web.Message;
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
 * COACTUPC: the account and its customer, changed in two steps, validate and save. Only the
 * changed fields are checked (D-49) and written (D-11); account and customer are saved in one
 * transaction with one {@code ops.audit} row (D-16, D-56). The keys come from the path and the
 * cross-reference only (DEF-ACUP-013).
 */
@Service
public class AccountUpdateService {

    static final String FORM = "accountUpdate";
    static final String RETRY_AFTER_SECONDS = "300";

    private static final Logger log = LoggerFactory.getLogger(AccountUpdateService.class);
    private static final FormRules RULES = RuleTable.standard().form(FORM);
    /** BR-ACUP-105: only spaces, or {@code *} followed by spaces, is empty; the rules use the same test. */
    private static final Pattern EMPTY = Pattern.compile("^\\*? *$");
    private static final String ACCT_ID = "acctId";
    private static final String ACTIVE_STATUS = "activeStatus";
    private static final String CHANGES = "changes";
    private static final String SUBJECT_TYPE = "ACCOUNT";
    private static final int AMOUNT_DIGITS = 10;
    private static final int AMOUNT_SCALE = 2;

    private final AccountUpdateRepository repository;
    private final BatchWindowGuard batchWindow;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final Clock clock;
    private final SafeTransactionTemplate readTx;
    private final SafeTransactionTemplate tx;

    public AccountUpdateService(AccountUpdateRepository repository, BatchWindowGuard batchWindow,
                                JdbcTemplate jdbcTemplate, JdbcClient jdbc, Clock clock,
                                SafeTransactionTemplate accountReadTransaction, SafeTransactionTemplate accountTransaction) {
        this.repository = repository;
        this.batchWindow = batchWindow;
        this.jdbc = jdbcTemplate;
        this.audit = new AuditLog(jdbc, "account-service");
        this.clock = clock;
        this.readTx = accountReadTransaction;
        this.tx = accountTransaction;
    }

    /** A form and the {@code ETag} that goes with it. */
    public record Versioned(AccountUpdateForm form, String etag) {
    }

    /** The rows one form shows; no link and no customer for an account with no card (D-61). */
    record Snapshot(StoredAccount account, Link link, StoredCustomer customer) {

        byte[][] versions() {
            return customer == null ? new byte[][] {account.rowVersion()}
                    : new byte[][] {account.rowVersion(), customer.rowVersion()};
        }

        String version() {
            return RowVersions.tag(versions());
        }
    }

    enum Kind { FLAG, TEXT, AMOUNT, DATE, SSN, FICO, ZIP, PHONE }

    /**
     * What the screen edits, in screen order (FLOW-ACUP-102): a field, or a group whose parts change
     * together. {@code name} is the field name, or for a group the name the audit row records.
     */
    enum Item {
        ACTIVE_STATUS_ITEM("activeStatus", Kind.FLAG, false, "active_status", "activeStatus"),
        OPEN_DATE("openDate", Kind.DATE, false, "open_date", "openYear", "openMonth", "openDay"),
        CREDIT_LIMIT("creditLimit", Kind.AMOUNT, false, "credit_limit", "creditLimit"),
        EXPIRATION_DATE("expirationDate", Kind.DATE, false, "expiration_date", "expiryYear", "expiryMonth",
                "expiryDay"),
        CASH_CREDIT_LIMIT("cashCreditLimit", Kind.AMOUNT, false, "cash_credit_limit", "cashCreditLimit"),
        REISSUE_DATE("reissueDate", Kind.DATE, false, "reissue_date", "reissueYear", "reissueMonth", "reissueDay"),
        GROUP_ID("groupId", Kind.TEXT, false, "group_id", "groupId"),
        SSN("ssn", Kind.SSN, true, "ssn", "ssn1", "ssn2", "ssn3"),
        DOB("dob", Kind.DATE, true, "dob", "dobYear", "dobMonth", "dobDay"),
        FICO_SCORE("ficoScore", Kind.FICO, true, "fico_score", "ficoScore"),
        FIRST_NAME("firstName", Kind.TEXT, true, "first_name", "firstName"),
        MIDDLE_NAME("middleName", Kind.TEXT, true, "middle_name", "middleName"),
        LAST_NAME("lastName", Kind.TEXT, true, "last_name", "lastName"),
        ADDR_LINE_1("addrLine1", Kind.TEXT, true, "addr_line_1", "addrLine1"),
        STATE_CD("stateCd", Kind.FLAG, true, "state_cd", "stateCd"),
        ADDR_LINE_2("addrLine2", Kind.TEXT, true, "addr_line_2", "addrLine2"),
        ZIP("zip", Kind.ZIP, true, "zip", "zip5", "zipPlus4"),
        CITY("city", Kind.TEXT, true, "addr_line_3", "city"),
        PHONE_1("phone1", Kind.PHONE, true, "phone_1", "phone1Area", "phone1Prefix", "phone1Line"),
        GOVT_ISSUED_ID("govtIssuedId", Kind.TEXT, true, "govt_issued_id", "govtIssuedId"),
        PHONE_2("phone2", Kind.PHONE, true, "phone_2", "phone2Area", "phone2Prefix", "phone2Line"),
        EFT_ACCOUNT_ID("eftAccountId", Kind.TEXT, true, "eft_account_id", "eftAccountId"),
        PRI_CARD_HOLDER_IND("priCardHolderInd", Kind.FLAG, true, "pri_card_holder_ind", "priCardHolderInd");

        final String name;
        final Kind kind;
        final boolean customer;
        final String column;
        final List<String> parts;

        Item(String name, Kind kind, boolean customer, String column, String... parts) {
            this.name = name;
            this.kind = kind;
            this.customer = customer;
            this.column = column;
            this.parts = List.of(parts);
        }

        /** The item a part belongs to, or null when the screen does not edit the name. */
        static Item of(String part) {
            for (Item item : values()) {
                if (item.parts.contains(part)) {
                    return item;
                }
            }
            return null;
        }
    }

    /** The changed items, in screen order, with every part of each as normalised. */
    record Plan(List<Item> changed, Map<String, String> typed) {

        List<String> changedFields() {
            return changed.stream().map(item -> item.parts.get(0)).toList();
        }

        TreeSet<String> auditNames() {
            TreeSet<String> names = new TreeSet<>();
            changed.forEach(item -> names.add(item.name));
            return names;
        }

        Map<String, Object> columns(boolean customer) {
            Map<String, Object> columns = new LinkedHashMap<>();
            for (Item item : changed) {
                if (item.customer == customer) {
                    columns.put(item.column, written(item, typed));
                }
            }
            return columns;
        }
    }

    public Versioned fetch(String acctId) {
        requireAcctId(acctId);
        return versioned(read(acctId));
    }

    /** The Enter step: the changed fields in screen order, one per group; nothing is written. */
    public List<String> validate(String acctId, String version, Map<String, String> changes) {
        requireAcctId(acctId);
        Snapshot current = read(acctId);
        String expected = bodyVersion(version);
        if (!current.version().equals(expected)) {
            throw RowVersions.conflict(ACTIVE_STATUS, form(current), current.versions());
        }
        return check(sent(changes), current).changedFields();
    }

    public Versioned update(String acctId, String ifMatch, Map<String, String> changes, String userId) {
        requireAcctId(acctId);
        String expected = accountVersion(RowVersions.requireIfMatch(ifMatch));
        Map<String, String> sent = sent(changes);
        check(sent, read(acctId));
        return tx.execute(status -> {
            try {
                return LockTimeout.within(jdbc, () -> {
                    requireWritesAllowed(acctId);
                    return save(acctId, expected, sent, userId);
                });
            } catch (DataAccessException e) {
                throw lockFailed(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.ACCOUNT_LOCK_FAILED,
                        "account " + acctId, e);
            }
        });
    }

    private Versioned save(String acctId, String expected, Map<String, String> sent, String userId) {
        String accountSubject = "account " + acctId;
        StoredAccount account = locked(MessageCatalog.ACCOUNT_LOCK_FAILED, accountSubject,
                () -> repository.lockAccount(acctId))
                .orElseThrow(() -> lockFailed(HttpStatus.CONFLICT, MessageCatalog.ACCOUNT_LOCK_FAILED,
                        accountSubject, null));
        // The customer is derived again under the lock: the form's link may have moved since (S43).
        Optional<Link> link = locked(MessageCatalog.CUSTOMER_LOCK_FAILED, accountSubject,
                () -> repository.firstCard(acctId));
        StoredCustomer customer = null;
        if (link.isPresent()) {
            String customerSubject = "customer " + link.get().custId();
            customer = locked(MessageCatalog.CUSTOMER_LOCK_FAILED, customerSubject,
                    () -> repository.lockCustomer(link.get().custId()))
                    .orElseThrow(() -> lockFailed(HttpStatus.CONFLICT, MessageCatalog.CUSTOMER_LOCK_FAILED,
                            customerSubject, null));
        }
        Snapshot current = new Snapshot(account, link.orElse(null), customer);
        if (!current.version().equals(expected)) {
            throw RowVersions.conflict(ACTIVE_STATUS, form(current), current.versions());
        }
        // The same rows as the unlocked check, so this finds the same changes; it is repeated so the
        // writes follow from the locked rows alone.
        Plan plan = check(sent, current);
        try {
            Map<String, Object> accountColumns = plan.columns(false);
            if (!accountColumns.isEmpty()
                    && repository.updateAccount(acctId, account.rowVersion(), accountColumns) != 1) {
                throw saveFailed(acctId, null);
            }
            Map<String, Object> customerColumns = plan.columns(true);
            if (!customerColumns.isEmpty()
                    && repository.updateCustomer(customer.custId(), customer.rowVersion(), customerColumns) != 1) {
                throw saveFailed(acctId, null);
            }
            audit.record(userId, AuditLog.Action.UPDATE, SUBJECT_TYPE, acctId, plan.auditNames());
            StoredAccount savedAccount = repository.account(acctId).orElseThrow(() -> saveFailed(acctId, null));
            StoredCustomer savedCustomer = customer == null ? null : repository.customer(customer.custId())
                    .orElseThrow(() -> saveFailed(acctId, null));
            return versioned(new Snapshot(savedAccount, current.link(), savedCustomer));
        } catch (DataAccessException e) {
            throw saveFailed(acctId, e);
        }
    }

    /** Validate steps 3 to 5: names the screen edits, change detection, then the changed fields' rules. */
    private Plan check(Map<String, String> sent, Snapshot snapshot) {
        requireEditable(sent, snapshot.customer() != null);
        Map<String, String> stored = stored(snapshot);
        Map<String, String> typed = new HashMap<>();
        List<Item> changed = new ArrayList<>();
        for (Item item : Item.values()) {
            if (item.parts.stream().noneMatch(sent::containsKey)) {
                continue;
            }
            // A group is sent whole; a part that is missing counts as empty.
            item.parts.forEach(part -> typed.put(part, normalise(sent.get(part))));
            if (changed(item, typed, stored, snapshot.customer())) {
                changed.add(item);
            }
        }
        if (changed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.NO_CHANGE, ACTIVE_STATUS);
        }
        List<String> changedParts = changed.stream().flatMap(item -> item.parts.stream()).toList();
        ValidationResult result = RULES.validate(typed, LocalDate.now(clock), changedParts, stored);
        if (!result.valid()) {
            throw RuleProblems.rejected(result);
        }
        return new Plan(List.copyOf(changed), Map.copyOf(typed));
    }

    /**
     * A name the screen does not edit is refused with its label from the fixed table; any other
     * name with the generic text, so no part of it is returned or logged (architecture §11).
     */
    private static void requireEditable(Map<String, String> sent, boolean hasCustomer) {
        for (String name : sent.keySet()) {
            Item item = Item.of(name);
            if (item != null && (hasCustomer || !item.customer)) {
                continue;
            }
            Optional<String> label = AccountUpdateLabels.of(name);
            if (label.isPresent()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.FIELD_NOT_EDITABLE, name, label.get());
            }
            log.info("account update refused a change: code {}, correlation ID {}",
                    MessageCatalog.FIELD_NOT_EDITABLE_UNKNOWN.code(), MDC.get(CorrelationIdFilter.MDC_KEY));
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.FIELD_NOT_EDITABLE_UNKNOWN, CHANGES);
        }
    }

    /** BR-ACUP-102 as fixed by D-50 and D-75; a value that does not parse counts as changed. */
    static boolean changed(Item item, Map<String, String> typed, Map<String, String> stored,
            StoredCustomer customer) {
        List<String> t = item.parts.stream().map(typed::get).toList();
        List<String> s = item.parts.stream().map(part -> strip(stored.get(part))).toList();
        return switch (item.kind) {
            case FLAG -> !t.get(0).toUpperCase(Locale.ROOT).equals(s.get(0));
            case TEXT -> !t.get(0).equals(s.get(0));
            case AMOUNT -> {
                Optional<BigDecimal> value = LegacyAmount.parse(t.get(0), AMOUNT_DIGITS, AMOUNT_SCALE);
                yield value.isEmpty() || value.get().compareTo(new BigDecimal(s.get(0))) != 0;
            }
            case FICO -> {
                Integer value = number(t.get(0));
                yield value == null || value != Integer.parseInt(s.get(0));
            }
            case DATE -> {
                if (t.stream().allMatch(String::isEmpty)) {
                    yield !s.stream().allMatch(String::isEmpty);
                }
                boolean same = !s.get(0).isEmpty();
                for (int i = 0; same && i < 3; i++) {
                    Integer value = number(t.get(i));
                    same = value != null && value == Integer.parseInt(s.get(i));
                }
                yield !same;
            }
            case SSN -> !String.join("", t).equals(strip(customer.ssn()));
            case ZIP -> !zip(t.get(0), t.get(1)).equals(strip(customer.zip()));
            case PHONE -> !t.equals(s);
        };
    }

    /** Written values: stored case, trimmed text, DATE from the parts, phone and ZIP in their layouts. */
    static Object written(Item item, Map<String, String> typed) {
        List<String> t = item.parts.stream().map(typed::get).toList();
        return switch (item.kind) {
            case FLAG -> t.get(0).toUpperCase(Locale.ROOT);
            case TEXT -> t.get(0);
            case AMOUNT -> LegacyAmount.parse(t.get(0), AMOUNT_DIGITS, AMOUNT_SCALE).orElseThrow()
                    .setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
            case FICO -> Integer.parseInt(t.get(0));
            case DATE -> LocalDate.of(Integer.parseInt(t.get(0)), Integer.parseInt(t.get(1)),
                    Integer.parseInt(t.get(2)));
            case SSN -> String.join("", t);
            case ZIP -> zip(t.get(0), t.get(1));
            case PHONE -> t.stream().allMatch(String::isEmpty) ? ""
                    : "(" + t.get(0) + ")" + t.get(1) + "-" + t.get(2);
        };
    }

    private static String zip(String zip5, String zipPlus4) {
        return zipPlus4.isEmpty() ? zip5 : zip5 + "-" + zipPlus4;
    }

    /**
     * The value the rules check and the save writes: empty when the rules count it as empty,
     * otherwise without surrounding spaces, so " 05" passes the rules and is also written as 05.
     */
    static String normalise(String value) {
        String stripped = value == null ? "" : value.strip();
        return EMPTY.matcher(stripped).matches() ? "" : stripped;
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    /** One to nine digits, else null: such a part or score is compared as a number. */
    private static Integer number(String value) {
        if (value.isEmpty() || value.length() > 9 || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        return Integer.parseInt(value);
    }

    /** VAL-ACUP-001 and VAL-ACUP-002, on the value exactly as received. */
    static void requireAcctId(String acctId) {
        if (acctId == null || EMPTY.matcher(acctId).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.ACUP_ACCT_ID_REQUIRED, ACCT_ID);
        }
        if (!acctId.matches("[0-9]{11}") || acctId.equals("00000000000")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.ACUP_INVALID_ACCT_ID, ACCT_ID);
        }
    }

    private static Map<String, String> sent(Map<String, String> changes) {
        return changes == null ? Map.of() : changes;
    }

    /** Fetch steps 2 to 4: the account, its lowest card's cross-reference row, that row's customer. */
    private Snapshot read(String acctId) {
        return readTx.execute(status -> {
            String subject = "account " + acctId;
            StoredAccount account = read(MessageCatalog.ACCOUNT_DATA_READ_FAILED, subject,
                    () -> repository.account(acctId))
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, MessageCatalog.ACCOUNT_ID_NOT_FOUND,
                            ACCT_ID, acctId));
            Optional<Link> link = read(MessageCatalog.XREF_DATA_READ_FAILED, subject,
                    () -> repository.firstCard(acctId));
            if (link.isEmpty()) {
                return new Snapshot(account, null, null);
            }
            String custId = link.get().custId();
            StoredCustomer customer = read(MessageCatalog.CUSTOMER_LOOKUP_FAILED, "customer " + custId,
                    () -> repository.customer(custId))
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, MessageCatalog.CUSTOMER_NOT_FOUND,
                            ACCT_ID, custId));
            return new Snapshot(account, link.get(), customer);
        });
    }

    /** The log names the account or customer ID and the SQL error number, never a value (D-NFR-04). */
    private static <T> Optional<T> read(Message failure, String subject, Supplier<Optional<T>> read) {
        try {
            return read.get();
        } catch (DataAccessException e) {
            log.error("account update read failed: {}, {}, correlation ID {}: {}", subject, failure.code(),
                    MDC.get(CorrelationIdFilter.MDC_KEY), SafeErrors.describe(e));
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, failure, ACCT_ID, e);
        }
    }

    /** A lock that is not granted within the lock timeout is a conflict; any other failure is not. */
    private static <T> Optional<T> locked(Message failure, String subject, Supplier<Optional<T>> read) {
        try {
            return read.get();
        } catch (DataAccessException e) {
            throw lockFailed(LockTimeout.timedOut(e) ? HttpStatus.CONFLICT : HttpStatus.INTERNAL_SERVER_ERROR,
                    failure, subject, e);
        }
    }

    private void requireWritesAllowed(String acctId) {
        try {
            batchWindow.requireWritesAllowed();
        } catch (BatchWindowException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, MessageCatalog.ACCOUNT_UPDATE_BATCH_WINDOW,
                    ACTIVE_STATUS).header("Retry-After", RETRY_AFTER_SECONDS);
        } catch (DataAccessException e) {
            throw lockFailed(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.ACCOUNT_LOCK_FAILED,
                    "account " + acctId, e);
        }
    }

    /** {@code version} of the validate body, checked as an {@code If-Match} value. */
    private static String bodyVersion(String version) {
        if (version == null || version.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_IF_MATCH, null);
        }
        return accountVersion(RowVersions.requireIfMatch('"' + version + '"'));
    }

    /** An account update version names the account, and the customer when there is one. */
    private static String accountVersion(String tag) {
        if (tag.split("\\.").length > 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_IF_MATCH, null);
        }
        return tag;
    }

    private static Versioned versioned(Snapshot snapshot) {
        return new Versioned(form(snapshot), RowVersions.etag(snapshot.versions()));
    }

    static AccountUpdateForm form(Snapshot s) {
        StoredAccount a = s.account();
        StoredCustomer c = s.customer();
        Long cardId = s.link() == null ? null : s.link().cardId();
        boolean none = c == null;
        return new AccountUpdateForm(a.acctId(), cardId == null ? null : String.valueOf(cardId), a.activeStatus(),
                year(a.openDate()), month(a.openDate()), day(a.openDate()),
                year(a.expirationDate()), month(a.expirationDate()), day(a.expirationDate()),
                year(a.reissueDate()), month(a.reissueDate()), day(a.reissueDate()),
                AccountFormats.amount(a.creditLimit()), AccountFormats.amountDisplay(a.creditLimit()),
                AccountFormats.amount(a.cashCreditLimit()), AccountFormats.amountDisplay(a.cashCreditLimit()),
                AccountFormats.amount(a.currBal()), AccountFormats.amountDisplay(a.currBal()),
                AccountFormats.amount(a.currCycCredit()), AccountFormats.amountDisplay(a.currCycCredit()),
                AccountFormats.amount(a.currCycDebit()), AccountFormats.amountDisplay(a.currCycDebit()),
                AccountFormats.groupId(a.groupId()),
                none ? null : c.custId(),
                none ? null : Pii.ssn(c.ssn()),
                none ? null : c.dob() == null ? Pii.text("") : Pii.date(c.dob()),
                none ? null : AccountFormats.fico(c.ficoScore()),
                none ? null : c.firstName(), none ? null : c.middleName(), none ? null : c.lastName(),
                none ? null : c.addrLine1(), none ? null : c.addrLine2(), none ? null : c.addrLine3(),
                none ? null : c.stateCd(),
                none ? null : c.zip(), none ? null : zip5(c.zip()), none ? null : zipPlus4(c.zip()),
                none ? null : c.countryCd(),
                none ? null : c.phone1(), none ? null : phonePart(c.phone1(), 0), none ? null : phonePart(c.phone1(), 1),
                none ? null : phonePart(c.phone1(), 2),
                none ? null : c.phone2(), none ? null : phonePart(c.phone2(), 0), none ? null : phonePart(c.phone2(), 1),
                none ? null : phonePart(c.phone2(), 2),
                none ? null : Pii.text(c.govtIssuedId()), none ? null : Pii.text(c.eftAccountId()),
                none ? null : c.priCardHolderInd(),
                s.version(), null, null);
    }

    /**
     * The stored values by form field, in clear: the rules take an unchanged field's value from here
     * (D-49), and change detection compares with it.
     */
    static Map<String, String> stored(Snapshot s) {
        StoredAccount a = s.account();
        Map<String, String> m = new HashMap<>();
        m.put("activeStatus", a.activeStatus());
        dateParts(m, "open", a.openDate());
        m.put("creditLimit", AccountFormats.amount(a.creditLimit()));
        dateParts(m, "expiry", a.expirationDate());
        m.put("cashCreditLimit", AccountFormats.amount(a.cashCreditLimit()));
        dateParts(m, "reissue", a.reissueDate());
        m.put("groupId", AccountFormats.groupId(a.groupId()));
        StoredCustomer c = s.customer();
        if (c == null) {
            return m;
        }
        String ssn = strip(c.ssn());
        m.put("ssn1", ssn.length() == 9 ? ssn.substring(0, 3) : ssn);
        m.put("ssn2", ssn.length() == 9 ? ssn.substring(3, 5) : "");
        m.put("ssn3", ssn.length() == 9 ? ssn.substring(5) : "");
        dateParts(m, "dob", c.dob());
        m.put("ficoScore", String.valueOf(c.ficoScore()));
        m.put("firstName", c.firstName());
        m.put("middleName", c.middleName());
        m.put("lastName", c.lastName());
        m.put("addrLine1", c.addrLine1());
        m.put("stateCd", c.stateCd());
        m.put("addrLine2", c.addrLine2());
        m.put("zip5", zip5(c.zip()));
        m.put("zipPlus4", zipPlus4(c.zip()));
        m.put("city", c.addrLine3());
        for (int i = 0; i < 3; i++) {
            m.put("phone1" + PHONE_PARTS.get(i), phonePart(c.phone1(), i));
            m.put("phone2" + PHONE_PARTS.get(i), phonePart(c.phone2(), i));
        }
        m.put("govtIssuedId", c.govtIssuedId());
        m.put("eftAccountId", c.eftAccountId());
        m.put("priCardHolderInd", c.priCardHolderInd());
        return m;
    }

    private static final List<String> PHONE_PARTS = List.of("Area", "Prefix", "Line");

    private static void dateParts(Map<String, String> m, String prefix, LocalDate date) {
        m.put(prefix + "Year", year(date));
        m.put(prefix + "Month", month(date));
        m.put(prefix + "Day", day(date));
    }

    /** NUM-ACUP-002: the parts of a {@code DATE}, digits as stored; a NULL date gives three "". */
    private static String year(LocalDate date) {
        return date == null ? "" : String.format(Locale.ROOT, "%04d", date.getYear());
    }

    private static String month(LocalDate date) {
        return date == null ? "" : String.format(Locale.ROOT, "%02d", date.getMonthValue());
    }

    private static String day(LocalDate date) {
        return date == null ? "" : String.format(Locale.ROOT, "%02d", date.getDayOfMonth());
    }

    /** Characters 1-5 of the stored ZIP (D-14). */
    static String zip5(String zip) {
        return zip.length() > 5 ? zip.substring(0, 5) : zip;
    }

    /** Characters 7-10 of the stored ZIP when character 6 is {@code -}, else "". */
    static String zipPlus4(String zip) {
        return zip.length() > 6 && zip.charAt(5) == '-' ? zip.substring(6, Math.min(zip.length(), 10)) : "";
    }

    /** Characters 2-4, 6-8 and 10-13 of the stored phone, as far as it reaches. */
    static String phonePart(String phone, int part) {
        int[][] bounds = {{1, 4}, {5, 8}, {9, 13}};
        int from = Math.min(bounds[part][0], phone.length());
        int to = Math.min(bounds[part][1], phone.length());
        return phone.substring(from, to);
    }

    private static ApiException lockFailed(HttpStatus status, Message failure, String subject,
            DataAccessException cause) {
        log.warn("account update lock failed: {}, {}, correlation ID {}: {}", subject, failure.code(),
                MDC.get(CorrelationIdFilter.MDC_KEY), cause == null ? "no row" : SafeErrors.describe(cause));
        return new ApiException(status, failure, ACCT_ID, cause);
    }

    private static ApiException saveFailed(String acctId, DataAccessException cause) {
        log.error("account update failed: account {}, {}, correlation ID {}: {}", acctId,
                MessageCatalog.ACCOUNT_SAVE_FAILED.code(), MDC.get(CorrelationIdFilter.MDC_KEY),
                cause == null ? "no row updated" : SafeErrors.describe(cause));
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.ACCOUNT_SAVE_FAILED, ACCT_ID, cause);
    }
}
