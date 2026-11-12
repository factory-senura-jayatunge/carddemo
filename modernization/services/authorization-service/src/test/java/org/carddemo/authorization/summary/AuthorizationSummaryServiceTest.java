package org.carddemo.authorization.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.carddemo.authorization.api.AuthorizationPage;
import org.carddemo.authorization.api.AuthorizationRow;
import org.carddemo.authorization.api.AuthorizationSummary;
import org.carddemo.authorization.summary.AuthorizationSummaryRepository.AccountLimits;
import org.carddemo.authorization.summary.AuthorizationSummaryRepository.CustomerRow;
import org.carddemo.authorization.summary.AuthorizationSummaryRepository.ListedDetail;
import org.carddemo.authorization.summary.AuthorizationSummaryRepository.SummaryRow;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.Cursors;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The AUSM rules of contract §7 and §8 on an in-memory repository: the check order, the partial
 * views, the header format, the read-failure texts (A23) and the keyset paging (A7 to A9). The SQL
 * itself is covered by {@code AuthorizationSummaryApiTest} on the database.
 */
class AuthorizationSummaryServiceTest {

    static final String ACCT = "00000000091";
    static final String CUST = "000000091";
    static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 10, 9, 0);

    enum Read { ACCOUNT, XREF, CUSTOMER, SUMMARY, DETAIL }

    static final class FakeRepository extends AuthorizationSummaryRepository {
        final Map<String, AccountLimits> accounts = new HashMap<>();
        final Map<String, String> xref = new HashMap<>();
        final Map<String, CustomerRow> customers = new HashMap<>();
        final Map<String, SummaryRow> summaries = new HashMap<>();
        final List<ListedDetail> details = new ArrayList<>();
        final Set<Read> failing = EnumSet.noneOf(Read.class);
        final List<Read> reads = new ArrayList<>();
        DataAccessException failure;

        FakeRepository() {
            super(null);
        }

        private void read(Read read) {
            reads.add(read);
            if (failing.contains(read)) {
                throw failure != null ? failure : new DataAccessResourceFailureException("connection reset during " + read);
            }
        }

        @Override
        public Optional<AccountLimits> account(String acctId) {
            read(Read.ACCOUNT);
            return Optional.ofNullable(accounts.get(acctId));
        }

        @Override
        public Optional<String> firstCustomer(String acctId) {
            read(Read.XREF);
            return Optional.ofNullable(xref.get(acctId));
        }

        @Override
        public Optional<CustomerRow> customer(String custId) {
            read(Read.CUSTOMER);
            return Optional.ofNullable(customers.get(custId));
        }

        @Override
        public Optional<SummaryRow> summary(String acctId) {
            read(Read.SUMMARY);
            return Optional.ofNullable(summaries.get(acctId));
        }

        /** List order: processed_at descending, then auth_id descending. */
        static final Comparator<ListedDetail> NEWEST_FIRST = Comparator.comparing(ListedDetail::processedAt)
                .thenComparingLong(ListedDetail::authId).reversed();

        private static int compare(ListedDetail d, ListPosition p) {
            return NEWEST_FIRST.compare(d, new ListedDetail(p.authId(), p.processedAt(), null, null, null, null, null,
                    null, null));
        }

        /** Every fake detail belongs to ACCT, the only account the list reaches after the account check. */
        private List<ListedDetail> select(String acctId, Predicate<ListedDetail> where, Comparator<ListedDetail> order,
                int rows) {
            read(Read.DETAIL);
            return details.stream().filter(where).sorted(order).limit(rows).toList();
        }

        @Override
        public List<ListedDetail> older(String acctId, ListPosition from, int rows) {
            return select(acctId, d -> from == null || compare(d, from) > 0, NEWEST_FIRST, rows);
        }

        @Override
        public List<ListedDetail> newer(String acctId, ListPosition from, int rows) {
            return select(acctId, d -> compare(d, from) < 0, NEWEST_FIRST.reversed(), rows);
        }

        @Override
        public boolean anyNewer(String acctId, ListPosition from, boolean inclusive) {
            return !select(acctId, d -> inclusive ? compare(d, from) <= 0 : compare(d, from) < 0, NEWEST_FIRST, 1)
                    .isEmpty();
        }

        @Override
        public boolean anyOlder(String acctId, ListPosition from, boolean inclusive) {
            return !select(acctId, d -> inclusive ? compare(d, from) >= 0 : compare(d, from) > 0, NEWEST_FIRST, 1)
                    .isEmpty();
        }
    }

    static final class Transactions implements PlatformTransactionManager {
        boolean failToStart;
        final List<TransactionDefinition> begun = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            if (failToStart) {
                throw new CannotCreateTransactionException("no connection");
            }
            begun.add(definition);
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }

    FakeRepository repository;
    Transactions transactions;
    AuthorizationSummaryService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        transactions = new Transactions();
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        service = new AuthorizationSummaryService(repository, readOnly);
        repository.accounts.put(ACCT, new AccountLimits(new BigDecimal("20200.00"), new BigDecimal("10200.00")));
        repository.xref.put(ACCT, CUST);
        repository.customers.put(CUST, new CustomerRow(CUST, "Immanuel", "Madeline", "Kessler",
                "618 Deshaun Route   ", "Apt. 802", "Altenwerthshire", "NC", "12546-1234", "(908)119-8310"));
        repository.summaries.put(ACCT, new SummaryRow(1, 2, new BigDecimal("50.00"), new BigDecimal("1250.75"),
                new BigDecimal("50.00"), new BigDecimal("0.00")));
    }

    static void assertProblem(Runnable call, HttpStatus status, Message message, String text) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(message.code());
            assertThat(e.text()).isEqualTo(text);
        });
    }

    @Test
    @DisplayName("A1: header from the account and the lowest card's customer, the six totals from the summary")
    @SpecItem({"BR-AUSM-001", "BR-AUSM-002", "DEF-AUSM-003"})
    void summaryWithEverything() {
        AuthorizationSummary s = service.summary(ACCT);

        assertThat(s.acctId()).isEqualTo(ACCT);
        assertThat(s.customer()).isNotNull();
        assertThat(s.customer().custId()).isEqualTo(CUST);
        assertThat(s.customer().name()).isEqualTo("Immanuel M Kessler");
        assertThat(s.customer().addressLine1()).isEqualTo("618 Deshaun Route,Apt. 802");
        assertThat(s.customer().addressLine2()).isEqualTo("Altenwerthshire,NC,12546");
        assertThat(s.customer().phone()).isEqualTo("(908)119-8310");
        assertThat(s.creditLimit()).isEqualTo("20200.00");
        assertThat(s.cashLimit()).isEqualTo("10200.00");
        assertThat(s.summaryFound()).isTrue();
        assertThat(s.approvedCount()).isEqualTo(1);
        assertThat(s.declinedCount()).isEqualTo(2);
        assertThat(s.approvedAmount()).isEqualTo("50.00");
        assertThat(s.declinedAmount()).isEqualTo("1250.75");
        assertThat(s.creditBalance()).isEqualTo("50.00");
        assertThat(s.cashBalance()).isEqualTo("0.00");
        assertThat(s.message()).isNull();
        assertThat(s.severity()).isNull();
        assertThat(repository.reads).containsExactly(Read.ACCOUNT, Read.XREF, Read.CUSTOMER, Read.SUMMARY);
        assertThat(transactions.begun).singleElement().satisfies(d -> {
            assertThat(d.isReadOnly()).isTrue();
            assertThat(d.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        });
    }

    @Test
    @DisplayName("BR-AUSM-001: the legacy STRING of the name: first words, the middle initial or a space")
    @SpecItem("BR-AUSM-001")
    void nameAsTheLegacyStringBuildsIt() {
        repository.customers.put(CUST, new CustomerRow(CUST, "Mary Ann", "", "Van Dyke", "1 MAIN ST", "", "TOWN", "NY",
                "123", "(212)555-0100  "));

        AuthorizationSummary s = service.summary(ACCT);

        assertThat(s.customer().name()).isEqualTo("Mary   Van");
        assertThat(s.customer().addressLine1()).isEqualTo("1 MAIN ST,");
        assertThat(s.customer().addressLine2()).isEqualTo("TOWN,NY,123");
        assertThat(s.customer().phone()).isEqualTo("(212)555-0100  ");
    }

    @Test
    @DisplayName("BR-AUSM-001: a bare comma; only trailing spaces go; no cut at a double space or at 25 characters")
    @SpecItem({"BR-AUSM-001", "DEF-AUSM-004"})
    void addressKeepsWhatTheDisplayCuts() {
        repository.customers.put(CUST, new CustomerRow(CUST, "Bartholomew-Alexander", "Q", "Featherstonehaugh-Smythe",
                "  12  Long Street Name Avenue   ", "Building  7 ", "North Springfield Heights  ", "IL ",
                "62704 ", "(217)555-0199"));

        AuthorizationSummary s = service.summary(ACCT);

        assertThat(s.customer().name()).isEqualTo("Bartholomew-Alexander Q Featherstonehaugh-Smythe").hasSizeGreaterThan(25);
        assertThat(s.customer().addressLine1()).isEqualTo("  12  Long Street Name Avenue,Building  7");
        assertThat(s.customer().addressLine2()).isEqualTo("North Springfield Heights,IL,62704");
    }

    @Test
    @DisplayName("A2: no summary: summaryFound false and every total zero")
    @SpecItem("BR-AUSM-002")
    void summaryNotFound() {
        repository.summaries.clear();

        AuthorizationSummary s = service.summary(ACCT);

        assertThat(s.summaryFound()).isFalse();
        assertThat(List.of(s.approvedAmount(), s.declinedAmount(), s.creditBalance(), s.cashBalance()))
                .containsOnly("0.00");
        assertThat(s.approvedCount()).isZero();
        assertThat(s.declinedCount()).isZero();
        assertThat(s.customer()).isNotNull();
        assertThat(s.message()).isNull();
        assertThat(service.list(ACCT, null, null, null).items()).isEmpty();
    }

    @Test
    @DisplayName("A3: blank is ACCOUNT_ID_REQUIRED; anything but 11 digits is ACCOUNT_ID_NOT_NUMERIC; nothing is read")
    @SpecItem({"VAL-AUSM-001", "VAL-AUSM-002", "DEF-AUSM-005"})
    void accountIdChecks() {
        for (String blank : new String[] {"", " ", "           ", "\0\0"}) {
            assertProblem(() -> service.summary(blank), HttpStatus.BAD_REQUEST, MessageCatalog.ACCOUNT_ID_REQUIRED,
                    "Please enter Acct Id...");
            assertProblem(() -> service.list(blank, null, null, null), HttpStatus.BAD_REQUEST,
                    MessageCatalog.ACCOUNT_ID_REQUIRED, "Please enter Acct Id...");
        }
        for (String bad : new String[] {"123", "0000000000A", "91", " 0000000091", "000000000091", "٠٠٠٠٠٠٠٠٠٩١"}) {
            assertProblem(() -> service.summary(bad), HttpStatus.BAD_REQUEST, MessageCatalog.ACCOUNT_ID_NOT_NUMERIC,
                    "Acct Id must be Numeric ...");
            assertProblem(() -> service.list(bad, null, null, null), HttpStatus.BAD_REQUEST,
                    MessageCatalog.ACCOUNT_ID_NOT_NUMERIC, "Acct Id must be Numeric ...");
        }
        assertThat(repository.reads).isEmpty();
        assertThat(transactions.begun).isEmpty();
    }

    @Test
    @DisplayName("A4: a missing account is 404 ACCOUNT_NOT_FOUND with the ACCT text, and nothing else is read")
    @SpecItem({"ERR-AUSM-002", "DEF-AUSM-001"})
    void accountNotFound() {
        assertProblem(() -> service.summary("99999999999"), HttpStatus.NOT_FOUND, MessageCatalog.AUSM_ACCOUNT_NOT_FOUND,
                "Account:99999999999 not found in ACCT file.");
        assertThat(repository.reads).containsExactly(Read.ACCOUNT);
        assertProblem(() -> service.list("99999999999", null, null, null), HttpStatus.NOT_FOUND,
                MessageCatalog.AUSM_ACCOUNT_NOT_FOUND, "Account:99999999999 not found in ACCT file.");
        assertThat(MessageCatalog.AUSM_ACCOUNT_NOT_FOUND.code()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    @DisplayName("A6: no cross-reference row: customer null, the XREF text as an error, totals still returned")
    @SpecItem("ERR-AUSM-001")
    void noCrossReference() {
        repository.xref.clear();

        AuthorizationSummary s = service.summary(ACCT);

        assertThat(s.customer()).isNull();
        assertThat(s.message()).isEqualTo("Account:00000000091 not found in XREF file.");
        assertThat(s.severity()).isEqualTo(Severity.ERROR);
        assertThat(s.summaryFound()).isTrue();
        assertThat(s.approvedAmount()).isEqualTo("50.00");
        assertThat(s.creditLimit()).isEqualTo("20200.00");
        assertThat(repository.reads).containsExactly(Read.ACCOUNT, Read.XREF, Read.SUMMARY);
    }

    @Test
    @DisplayName("ERR-AUSM-003: a missing customer: customer null, the CUST text as an error, totals still returned")
    @SpecItem("ERR-AUSM-003")
    void customerMissing() {
        repository.customers.clear();

        AuthorizationSummary s = service.summary(ACCT);

        assertThat(s.customer()).isNull();
        assertThat(s.message()).isEqualTo("Customer:000000091 not found in CUST file.");
        assertThat(s.severity()).isEqualTo(Severity.ERROR);
        assertThat(s.declinedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("A23: each failing summary read is the 500 of that read, without codes; nothing partial is returned")
    @SpecItem({"ERR-AUSM-001", "ERR-AUSM-002", "ERR-AUSM-003", "ERR-AUSM-004"})
    void summaryReadFailures() {
        Map<Read, String[]> expected = Map.of(
                Read.ACCOUNT, new String[] {"AUTH_ACCOUNT_READ_FAILED", " System error while reading ACCT file."},
                Read.XREF, new String[] {"AUTH_XREF_READ_FAILED", " System error while reading XREF file."},
                Read.CUSTOMER, new String[] {"AUTH_CUSTOMER_READ_FAILED", " System error while reading CUST file."},
                Read.SUMMARY, new String[] {"AUTH_SUMMARY_READ_FAILED", " System error while reading AUTH Summary"});
        expected.forEach((read, codeAndText) -> {
            repository.failing.clear();
            repository.failing.add(read);
            assertThatThrownBy(() -> service.summary(ACCT)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                assertThat(e.code()).isEqualTo(codeAndText[0]);
                assertThat(e.text()).isEqualTo(codeAndText[1]).doesNotContain("Resp", "Code", "connection");
                assertThat(e.field()).isEqualTo("acctId");
            });
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("D-NFR-04: a failed read logs the failure's class names and SQL error number, never the driver's message")
    void aFailedReadLogsNoDriverMessage(CapturedOutput output) {
        repository.failure = new DataIntegrityViolationException("PreparedStatementCallback; bad SQL", new SQLException(
                "String or binary data would be truncated in table 'core.customer', column 'first_name'."
                        + " Truncated value: 'Immanuel'.", "22001", 2628));
        repository.failing.add(Read.CUSTOMER);

        assertProblem(() -> service.summary(ACCT), HttpStatus.INTERNAL_SERVER_ERROR,
                MessageCatalog.AUTH_CUSTOMER_READ_FAILED, " System error while reading CUST file.");
        assertThat(output.getOut() + output.getErr())
                .contains("AUTH_CUSTOMER_READ_FAILED: account " + ACCT,
                        "DataIntegrityViolationException < SQLException SQL error 2628 (state 22001)")
                .doesNotContain("Immanuel", "Truncated value", "PreparedStatementCallback");
    }

    @Test
    @DisplayName("ERR-AUSM-004: a summary transaction that cannot start is AUTH_SUMMARY_READ_FAILED; nothing is read")
    @SpecItem("ERR-AUSM-004")
    void summaryWithoutConnection() {
        transactions.failToStart = true;
        assertProblem(() -> service.summary(ACCT), HttpStatus.INTERNAL_SERVER_ERROR,
                MessageCatalog.AUTH_SUMMARY_READ_FAILED, " System error while reading AUTH Summary");
        assertThat(repository.reads).isEmpty();
    }

    @Test
    @DisplayName("ERR-AUSM-005: a list transaction that cannot start is AUTH_DETAIL_READ_FAILED; nothing is read")
    @SpecItem("ERR-AUSM-005")
    void listWithoutConnection() {
        transactions.failToStart = true;
        assertProblem(() -> service.list(ACCT, null, null, null), HttpStatus.INTERNAL_SERVER_ERROR,
                MessageCatalog.AUTH_DETAIL_READ_FAILED, " System error while reading AUTH Details");
        assertThat(repository.reads).isEmpty();
    }

    @Test
    @DisplayName("A23: a failing detail read is AUTH_DETAIL_READ_FAILED; a failing account read is the ACCT text")
    @SpecItem("ERR-AUSM-005")
    void listReadFailures() {
        details(12);
        repository.failing.add(Read.DETAIL);
        assertProblem(() -> service.list(ACCT, null, null, null), HttpStatus.INTERNAL_SERVER_ERROR,
                MessageCatalog.AUTH_DETAIL_READ_FAILED, " System error while reading AUTH Details");
        String after = cursor(T0.plusMinutes(5), 5);
        assertProblem(() -> service.list(ACCT, after, null, null), HttpStatus.INTERNAL_SERVER_ERROR,
                MessageCatalog.AUTH_DETAIL_READ_FAILED, " System error while reading AUTH Details");
        repository.failing.clear();
        repository.failing.add(Read.ACCOUNT);
        assertProblem(() -> service.list(ACCT, null, null, null), HttpStatus.INTERNAL_SERVER_ERROR,
                MessageCatalog.AUTH_ACCOUNT_READ_FAILED, " System error while reading ACCT file.");
    }

    /** Details 1 to n, one minute apart: detail n is the newest. */
    void details(int n) {
        for (int i = 1; i <= n; i++) {
            repository.details.add(detail(i, T0.plusMinutes(i), "00"));
        }
    }

    static ListedDetail detail(long authId, LocalDateTime at, String resp) {
        return new ListedDetail(authId, at, String.format("TRN%012d", authId), "240405", "143015", "SALE", resp,
                "P", "00".equals(resp) ? new BigDecimal("50.00") : new BigDecimal("0.00"));
    }

    static String cursor(LocalDateTime at, long authId) {
        return Cursors.encode(new ListPosition(at, authId).key());
    }

    static List<Long> ids(AuthorizationPage page) {
        return page.items().stream().map(AuthorizationRow::authId).toList();
    }

    @Test
    @DisplayName("A7: 12 details: 5, 5, 2 newest first; back one page at a time to the first; flags on each")
    @SpecItem({"BR-AUSM-003", "BR-AUSM-004", "BR-AUSM-005", "BR-AUSM-006", "DEF-AUSM-002", "DEF-AUSM-011"})
    void pagingForwardAndBack() {
        details(12);

        AuthorizationPage p1 = service.list(ACCT, null, null, null);
        assertThat(ids(p1)).containsExactly(12L, 11L, 10L, 9L, 8L);
        assertThat(p1.hasNext()).isTrue();
        assertThat(p1.hasPrevious()).isFalse();

        AuthorizationPage p2 = service.list(ACCT, p1.lastKey(), null, "5");
        assertThat(ids(p2)).containsExactly(7L, 6L, 5L, 4L, 3L);
        assertThat(p2.hasNext()).isTrue();
        assertThat(p2.hasPrevious()).isTrue();

        AuthorizationPage p3 = service.list(ACCT, p2.lastKey(), null, null);
        assertThat(ids(p3)).containsExactly(2L, 1L);
        assertThat(p3.hasNext()).isFalse();
        assertThat(p3.hasPrevious()).isTrue();

        AuthorizationPage back2 = service.list(ACCT, null, p3.firstKey(), null);
        assertThat(ids(back2)).containsExactly(7L, 6L, 5L, 4L, 3L);
        assertThat(back2.hasNext()).isTrue();
        assertThat(back2.hasPrevious()).isTrue();

        AuthorizationPage back1 = service.list(ACCT, null, back2.firstKey(), null);
        assertThat(ids(back1)).containsExactly(12L, 11L, 10L, 9L, 8L);
        assertThat(back1.hasNext()).isTrue();
        assertThat(back1.hasPrevious()).isFalse();
        assertThat(back1.firstKey()).isEqualTo(p1.firstKey());
        assertThat(back1.lastKey()).isEqualTo(p1.lastKey());
    }

    @Test
    @DisplayName("A7: rows with the same processed_at are ordered by auth_id, newest first, across a page boundary")
    @SpecItem("BR-AUSM-003")
    void tiesOnProcessedAt() {
        for (long id = 1; id <= 7; id++) {
            repository.details.add(detail(id, T0, "00"));
        }
        AuthorizationPage p1 = service.list(ACCT, null, null, null);
        assertThat(ids(p1)).containsExactly(7L, 6L, 5L, 4L, 3L);
        AuthorizationPage p2 = service.list(ACCT, p1.lastKey(), null, null);
        assertThat(ids(p2)).containsExactly(2L, 1L);
        assertThat(ids(service.list(ACCT, null, p2.firstKey(), null))).containsExactly(7L, 6L, 5L, 4L, 3L);
    }

    @Test
    @DisplayName("A8: the after cursor of a row purged since still gives the next five older rows")
    @SpecItem("BR-AUSM-005")
    void cursorOfAPurgedRow() {
        details(12);
        AuthorizationPage p1 = service.list(ACCT, null, null, null);
        repository.details.removeIf(d -> d.authId() == 8);

        AuthorizationPage p2 = service.list(ACCT, p1.lastKey(), null, null);

        assertThat(ids(p2)).containsExactly(7L, 6L, 5L, 4L, 3L);
        assertThat(p2.hasPrevious()).isTrue();
    }

    @Test
    @DisplayName("A9: exactly five details: one page and no next page (DEF-AUSM-011 fixed)")
    @SpecItem({"BR-AUSM-004", "DEF-AUSM-011"})
    void exactlyOnePage() {
        details(5);
        AuthorizationPage p = service.list(ACCT, null, null, null);
        assertThat(ids(p)).containsExactly(5L, 4L, 3L, 2L, 1L);
        assertThat(p.hasNext()).isFalse();
        assertThat(p.hasPrevious()).isFalse();
    }

    @Test
    @DisplayName("An empty list has no keys and no further pages")
    @SpecItem("BR-AUSM-003")
    void emptyList() {
        AuthorizationPage p = service.list(ACCT, null, null, null);
        assertThat(p.items()).isEmpty();
        assertThat(p.hasNext()).isFalse();
        assertThat(p.hasPrevious()).isFalse();
        assertThat(p.firstKey()).isNull();
        assertThat(p.lastKey()).isNull();
    }

    @Test
    @DisplayName("BR-AUSM-003, NUM-AUSM-003: the row columns: dates and times rearranged, A or D, amounts as stored")
    @SpecItem({"BR-AUSM-003", "NUM-AUSM-003"})
    void rowColumns() {
        repository.details.add(detail(1, T0, "05"));
        repository.details.add(new ListedDetail(2, T0.plusSeconds(1), "TRN000000000002", "99A1 3", "2561  ", "RFND",
                "00", "M", new BigDecimal("1234567890.10")));

        List<AuthorizationRow> rows = service.list(ACCT, null, null, null).items();

        assertThat(rows.get(0)).isEqualTo(new AuthorizationRow(2, "TRN000000000002", "A1/ 3/99", "25:61:  ", "RFND",
                "A", "M", "1234567890.10"));
        assertThat(rows.get(1)).isEqualTo(new AuthorizationRow(1, "TRN000000000001", "04/05/24", "14:30:15", "SALE",
                "D", "P", "0.00"));
    }

    @Test
    @DisplayName("size other than 5, both cursors, or a cursor this service did not make: 400, nothing read")
    @SpecItem("BR-AUSM-006")
    void pageRequestChecks() {
        for (String size : new String[] {"4", "6", "0", "05", "5.0", "five", ""}) {
            assertProblem(() -> service.list(ACCT, null, null, size), HttpStatus.BAD_REQUEST,
                    MessageCatalog.AUSM_INVALID_PAGE_SIZE, "Page size must be 5");
        }
        String valid = cursor(T0, 1);
        assertProblem(() -> service.list(ACCT, valid, valid, null), HttpStatus.BAD_REQUEST,
                MessageCatalog.AUSM_INVALID_PAGE_REQUEST, "Use only one of after and before");
        for (String bad : new String[] {"!!", Cursors.encode("1"), Cursors.encode("2026-11-10T09:00:00|1"),
                Cursors.encode("2026-11-10T09:00:00.000|0"), Cursors.encode("2026-02-30T09:00:00.000|1"),
                Cursors.encode("2026-11-10T09:00:00.000|01"), Cursors.encode("2026-11-10T09:00:00.000|99999999999999999999"),
                Cursors.encode("2026-11-10 09:00:00.000|1")}) {
            assertProblem(() -> service.list(ACCT, bad, null, null), HttpStatus.BAD_REQUEST,
                    MessageCatalog.INVALID_CURSOR, "The page position is not valid");
            assertProblem(() -> service.list(ACCT, null, bad, null), HttpStatus.BAD_REQUEST,
                    MessageCatalog.INVALID_CURSOR, "The page position is not valid");
        }
        assertThat(repository.reads).isEmpty();
        assertThat(ids(service.list(ACCT, " ", "", "5"))).isEmpty();
    }

    @Test
    @DisplayName("A position's key is canonical: milliseconds always, and it parses back to itself")
    void positionKeys() {
        ListPosition p = new ListPosition(LocalDateTime.of(2026, 11, 10, 14, 30, 15), 1234);
        assertThat(p.key()).isEqualTo("2026-11-10T14:30:15.000|1234");
        assertThat(ListPosition.parse(p.key())).contains(p);
        ListPosition ms = new ListPosition(LocalDateTime.of(2026, 11, 10, 14, 30, 15, 123_000_000), Long.MAX_VALUE);
        assertThat(ListPosition.parse(ms.key())).contains(ms);
        assertThat(ListPosition.parse("2026-11-10T14:30:15.000|1234|5")).isEmpty();
    }
}
