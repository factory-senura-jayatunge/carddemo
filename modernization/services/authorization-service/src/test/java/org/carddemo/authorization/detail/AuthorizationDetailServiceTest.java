package org.carddemo.authorization.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.carddemo.authorization.api.AuthorizationDetail;
import org.carddemo.authorization.detail.AuthorizationDetailRepository.StoredDetail;
import org.carddemo.authorization.detail.AuthorizationDetailService.Marked;
import org.carddemo.authorization.detail.AuthorizationDetailService.Versioned;
import org.carddemo.authorization.summary.ListPosition;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.RowVersions;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The AUDT and AUFR rules of contract §9 on an in-memory repository whose writes a rollback undoes:
 * the detail view (A10 to A13), the check order and the conflict rules of the fraud toggle (A15,
 * A16, A18, A24), its writes (A14) and the failure texts (A17, A23). The SQL itself, the fraud
 * row's copy and its customer (A19, A22) are covered by {@code AuthorizationDetailApiTest}.
 */
class AuthorizationDetailServiceTest {

    static final String ACCT = "00000000091";
    static final String CARD = "4111111111111111";
    static final String USER = "USER0001";
    static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 10, 9, 0, 0, 123_000_000);
    /** 2026-11-11 02:30 UTC is still 2026-11-10 in Chicago: the report date is the zone's calendar date. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T02:30:00Z"), ZoneId.of("America/Chicago"));
    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    enum Step { FIND, LOCK, NEXT, MARK, UPDATE_FRAUD_ROW, INSERT_FRAUD_ROW, AUDIT }

    record FraudRow(String flag, LocalDate reportDate, String reportedBy) {
    }

    record Audit(String userId, long authId) {
    }

    /** The tables of one transaction; a rollback restores the state at its start. */
    static final class FakeRepository extends AuthorizationDetailRepository {
        Map<Long, StoredDetail> details = new LinkedHashMap<>();
        Map<Long, FraudRow> fraudRows = new HashMap<>();
        List<Audit> audits = new ArrayList<>();
        final Set<Step> failing = EnumSet.noneOf(Step.class);
        final List<Step> steps = new ArrayList<>();
        DataAccessException failure;
        int inserts;
        long nextVersion = 0x7D0;
        private Map<Long, StoredDetail> savedDetails;
        private Map<Long, FraudRow> savedFraudRows;
        private List<Audit> savedAudits;

        FakeRepository() {
            super(null);
        }

        void begin() {
            savedDetails = new LinkedHashMap<>(details);
            savedFraudRows = new HashMap<>(fraudRows);
            savedAudits = new ArrayList<>(audits);
        }

        void rollback() {
            details = savedDetails;
            fraudRows = savedFraudRows;
            audits = savedAudits;
        }

        byte[] newVersion() {
            return ByteBuffer.allocate(8).putLong(++nextVersion).array();
        }

        private void step(Step step) {
            steps.add(step);
            if (failing.contains(step)) {
                throw failure != null ? failure : new DataAccessResourceFailureException("connection reset during " + step);
            }
        }

        @Override
        public Optional<StoredDetail> find(long authId) {
            step(Step.FIND);
            return Optional.ofNullable(details.get(authId));
        }

        @Override
        public Optional<StoredDetail> lock(long authId) {
            step(Step.LOCK);
            return Optional.ofNullable(details.get(authId));
        }

        /** List order: processed_at descending, then auth_id descending. */
        static final Comparator<ListPosition> NEWEST_FIRST = Comparator.comparing(ListPosition::processedAt)
                .thenComparingLong(ListPosition::authId).reversed();

        @Override
        public Optional<Long> nextOlder(String acctId, ListPosition from) {
            step(Step.NEXT);
            return details.values().stream()
                    .filter(d -> d.acctId().equals(acctId))
                    .map(StoredDetail::position)
                    .filter(p -> NEWEST_FIRST.compare(p, from) > 0)
                    .min(NEWEST_FIRST)
                    .map(ListPosition::authId);
        }

        @Override
        public int markFraud(long authId, byte[] rowVersion, String action, LocalDate today, String userId) {
            step(Step.MARK);
            StoredDetail d = details.get(authId);
            if (d == null || !java.util.Arrays.equals(d.rowVersion(), rowVersion)) {
                return 0;
            }
            details.put(authId, fraud(d, action, today, newVersion()));
            return 1;
        }

        @Override
        public int updateFraudRow(long authId, String action, LocalDate today, String userId) {
            step(Step.UPDATE_FRAUD_ROW);
            if (!fraudRows.containsKey(authId)) {
                return 0;
            }
            fraudRows.put(authId, new FraudRow(action, today, userId));
            return 1;
        }

        @Override
        public int insertFraudRow(long authId, String action, LocalDate today, String userId) {
            step(Step.INSERT_FRAUD_ROW);
            inserts++;
            fraudRows.put(authId, new FraudRow(action, today, userId));
            return 1;
        }

        @Override
        public void audit(String userId, long authId) {
            step(Step.AUDIT);
            audits.add(new Audit(userId, authId));
        }
    }

    static final class Transactions implements PlatformTransactionManager {
        final FakeRepository repository;
        boolean failToStart;
        boolean failToCommit;
        final List<TransactionDefinition> begun = new ArrayList<>();
        int commits;
        int rollbacks;

        Transactions(FakeRepository repository) {
            this.repository = repository;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            if (failToStart) {
                throw new CannotCreateTransactionException("no connection");
            }
            begun.add(definition);
            repository.begin();
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            if (failToCommit) {
                repository.rollback();
                throw new TransactionSystemException("commit failed");
            }
            commits++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
            repository.rollback();
        }
    }

    FakeRepository repository;
    Transactions transactions;
    AuthorizationDetailService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        transactions = new Transactions(repository);
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        TransactionTemplate write = new TransactionTemplate(transactions);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        service = new AuthorizationDetailService(repository, CLOCK, readOnly, write);
    }

    StoredDetail add(long authId, LocalDateTime at, String resp, String reason) {
        StoredDetail d = new StoredDetail(authId, ACCT, at, "240405", "143015", CARD, "SALE", "0426", "POS   ",
                "001234", resp, reason, "000000", new BigDecimal("50.00"),
                "00".equals(resp) ? new BigDecimal("50.00") : new BigDecimal("0.00"), "5411", "01",
                "MERCH0000000001", "GROCERY STORE", "SPRINGFIELD", "IL", "62701", "TRN000000000001", "P", null, " ",
                null, repository.newVersion());
        repository.details.put(authId, d);
        return d;
    }

    static StoredDetail fraud(StoredDetail d, String flag, LocalDate date, byte[] version) {
        return new StoredDetail(d.authId(), d.acctId(), d.processedAt(), d.origDate(), d.origTime(), d.cardNum(),
                d.authType(), d.cardExpiryDate(), d.messageSource(), d.authIdCode(), d.respCode(), d.respReason(),
                d.processingCode(), d.transactionAmount(), d.approvedAmount(), d.merchantCategoryCode(),
                d.posEntryMode(), d.merchantId(), d.merchantName(), d.merchantCity(), d.merchantState(),
                d.merchantZip(), d.transactionId(), d.matchStatus(), d.matchedTranId(), flag, date, version);
    }

    /** A D-33 release committed on the row: match status M and the transaction ID, a new row version. */
    void match(long authId, String tranId) {
        StoredDetail d = repository.details.get(authId);
        repository.details.put(authId, new StoredDetail(d.authId(), d.acctId(), d.processedAt(), d.origDate(),
                d.origTime(), d.cardNum(), d.authType(), d.cardExpiryDate(), d.messageSource(), d.authIdCode(),
                d.respCode(), d.respReason(), d.processingCode(), d.transactionAmount(), d.approvedAmount(),
                d.merchantCategoryCode(), d.posEntryMode(), d.merchantId(), d.merchantName(), d.merchantCity(),
                d.merchantState(), d.merchantZip(), d.transactionId(), "M", tranId, d.fraudFlag(),
                d.fraudReportDate(), repository.newVersion()));
    }

    String etag(long authId) {
        return RowVersions.etag(repository.details.get(authId).rowVersion());
    }

    static void assertProblem(Runnable call, HttpStatus status, Message message, String text) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(message.code());
            assertThat(e.text()).isEqualTo(text);
            assertThat(e.field()).isNull();
        });
    }

    @Test
    @DisplayName("A10: every field; reasonText from the table; authIdCode, not the processing code; card masked")
    @SpecItem({"BR-AUDT-002", "BR-AUDT-003", "BR-AUDT-004", "NUM-AUDT-002", "NUM-AUDT-003", "DEF-AUDT-002"})
    void detailFields() {
        add(1, T0, "00", "0000");
        StoredDetail stored = add(2, T0.plusMinutes(1), "00", "0000");

        Versioned v = service.detail("2");
        AuthorizationDetail d = v.detail();

        assertThat(d.authId()).isEqualTo(2);
        assertThat(d.acctId()).isEqualTo(ACCT);
        assertThat(d.version()).isEqualTo(RowVersions.tag(stored.rowVersion()));
        assertThat(v.etag()).isEqualTo('"' + d.version() + '"');
        assertThat(d.cardNum().masked()).isEqualTo("************1111");
        assertThat(d.cardNum().toString()).doesNotContain("4111111111");
        assertThat(d.requestDate()).isEqualTo("04/05/24");
        assertThat(d.requestTime()).isEqualTo("14:30:15");
        assertThat(d.approval()).isEqualTo("A");
        assertThat(d.respCode()).isEqualTo("00");
        assertThat(d.reason()).isEqualTo("0000");
        assertThat(d.reasonText()).isEqualTo("0000-APPROVED");
        assertThat(d.authIdCode()).isEqualTo("001234");
        assertThat(d.processingCode()).isEqualTo("000000");
        assertThat(d.approvedAmount()).isEqualTo("50.00");
        assertThat(d.transactionAmount()).isEqualTo("50.00");
        assertThat(d.posEntryMode()).isEqualTo("01");
        assertThat(d.messageSource()).isEqualTo("POS   ");
        assertThat(d.merchantCategoryCode()).isEqualTo("5411");
        assertThat(d.cardExpiry()).isEqualTo("04/26");
        assertThat(d.authType()).isEqualTo("SALE");
        assertThat(d.transactionId()).isEqualTo("TRN000000000001");
        assertThat(d.matchStatus()).isEqualTo("P");
        assertThat(d.matchedTranId()).isNull();
        assertThat(d.fraud().flag()).isNull();
        assertThat(d.fraud().reportDate()).isNull();
        assertThat(d.fraud().display()).isEqualTo("-");
        assertThat(d.merchant().name()).isEqualTo("GROCERY STORE");
        assertThat(d.merchant().id()).isEqualTo("MERCH0000000001");
        assertThat(d.merchant().city()).isEqualTo("SPRINGFIELD");
        assertThat(d.merchant().state()).isEqualTo("IL");
        assertThat(d.merchant().zip()).isEqualTo("62701");
        assertThat(d.nextOlderAuthId()).isEqualTo(1L);
        assertThat(repository.steps).containsExactly(Step.FIND, Step.NEXT);
        assertThat(transactions.begun).singleElement().satisfies(t -> {
            assertThat(t.isReadOnly()).isTrue();
            assertThat(t.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        });
    }

    @Test
    @DisplayName("A11: each of the ten reasons with its description; an unknown code is 9999-ERROR; declines are D")
    @SpecItem({"BR-AUDT-002", "BR-AUDT-003"})
    void reasonTable() {
        Map<String, String> expected = Map.of("0000", "0000-APPROVED", "3100", "3100-INVALID CARD",
                "4100", "4100-INSUFFICNT FUND", "4200", "4200-CARD NOT ACTIVE", "4300", "4300-ACCOUNT CLOSED",
                "4400", "4400-EXCED DAILY LMT", "5100", "5100-CARD FRAUD", "5200", "5200-MERCHANT FRAUD",
                "5300", "5300-LOST CARD", "9000", "9000-UNKNOWN");
        expected.forEach((code, text) -> assertThat(AuthorizationDetailService.reasonText(code)).isEqualTo(text));
        for (String unknown : new String[] {"1234", "    ", "9999", "0001"}) {
            assertThat(AuthorizationDetailService.reasonText(unknown)).isEqualTo("9999-ERROR");
        }
        add(1, T0, "05", "1234");
        AuthorizationDetail d = service.detail("1").detail();
        assertThat(d.reasonText()).isEqualTo("9999-ERROR");
        assertThat(d.approval()).isEqualTo("D");
        assertThat(d.approvedAmount()).isEqualTo("0.00");
    }

    @Test
    @DisplayName("A12: next older in list order, ties by auth_id; null at the oldest; other accounts never")
    @SpecItem({"BR-AUDT-006", "VAL-AUDT-003"})
    void nextOlder() {
        add(1, T0, "00", "0000");
        add(2, T0.plusMinutes(1), "00", "0000");
        add(3, T0.plusMinutes(1), "00", "0000");
        StoredDetail other = add(4, T0.minusMinutes(1), "00", "0000");
        repository.details.put(4L, new StoredDetail(4, "00000000092", other.processedAt(), other.origDate(),
                other.origTime(), other.cardNum(), other.authType(), other.cardExpiryDate(), other.messageSource(),
                other.authIdCode(), other.respCode(), other.respReason(), other.processingCode(),
                other.transactionAmount(), other.approvedAmount(), other.merchantCategoryCode(), other.posEntryMode(),
                other.merchantId(), other.merchantName(), other.merchantCity(), other.merchantState(),
                other.merchantZip(), other.transactionId(), other.matchStatus(), other.matchedTranId(),
                other.fraudFlag(), other.fraudReportDate(), other.rowVersion()));

        assertThat(service.detail("3").detail().nextOlderAuthId()).isEqualTo(2L);
        assertThat(service.detail("2").detail().nextOlderAuthId()).isEqualTo(1L);
        assertThat(service.detail("1").detail().nextOlderAuthId()).isNull();
    }

    @Test
    @DisplayName("A13: a matched row shows M and the matched transaction ID")
    @SpecItem("BR-AUDT-002")
    void matchedRow() {
        add(1, T0, "00", "0000");
        match(1, "0000000000000301");
        AuthorizationDetail d = service.detail("1").detail();
        assertThat(d.matchStatus()).isEqualTo("M");
        assertThat(d.matchedTranId()).isEqualTo("0000000000000301");
    }

    @Test
    @DisplayName("BR-AUDT-004: F or R with the report date as MM/DD/YY; the ISO date travels too")
    @SpecItem("BR-AUDT-004")
    void fraudDisplay() {
        assertThat(AuthorizationDetailService.fraud("F", LocalDate.of(2023, 3, 24)))
                .satisfies(f -> {
                    assertThat(f.flag()).isEqualTo("F");
                    assertThat(f.reportDate()).isEqualTo("2023-03-24");
                    assertThat(f.display()).isEqualTo("F-03/24/23");
                });
        assertThat(AuthorizationDetailService.fraud("R", LocalDate.of(2026, 11, 10)).display()).isEqualTo("R-11/10/26");
        assertThat(AuthorizationDetailService.fraud(" ", null).display()).isEqualTo("-");
        assertThat(AuthorizationDetailService.fraud(" ", null).flag()).isNull();
    }

    @Test
    @DisplayName("VAL-AUDT-001: an authId that is not a positive BIGINT is 400 INVALID_AUTH_ID; nothing is read")
    @SpecItem("VAL-AUDT-001")
    void invalidAuthId() {
        for (String bad : new String[] {"0", "-1", "abc", "", " 1", "1.0", "99999999999999999999", "9223372036854775808",
            "١"}) {
            assertProblem(() -> service.detail(bad), HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_AUTH_ID,
                    "Authorization ID must be a positive number");
            assertProblem(() -> service.changeFraud(bad, "\"00000000000007d1\"", "F", USER), HttpStatus.BAD_REQUEST,
                    MessageCatalog.INVALID_AUTH_ID, "Authorization ID must be a positive number");
        }
        assertProblem(() -> service.detail(null), HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_AUTH_ID,
                "Authorization ID must be a positive number");
        assertThat(repository.steps).isEmpty();
        assertThat(transactions.begun).isEmpty();
        assertThat(AuthorizationDetailService.authId("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        assertThat(AuthorizationDetailService.authId("007")).isEqualTo(7L);
    }

    @Test
    @DisplayName("A18: no row (purged since the list) is 404 AUTHORIZATION_NOT_FOUND, on the view and the toggle")
    @SpecItem("DEF-AUDT-004")
    void notFound() {
        assertProblem(() -> service.detail("42"), HttpStatus.NOT_FOUND, MessageCatalog.AUTHORIZATION_NOT_FOUND,
                "Authorization not found...");
        assertProblem(() -> service.changeFraud("42", "\"00000000000007d1\"", "F", USER), HttpStatus.NOT_FOUND,
                MessageCatalog.AUTHORIZATION_NOT_FOUND, "Authorization not found...");
        assertThat(repository.steps).containsExactly(Step.FIND, Step.LOCK);
        assertThat(repository.fraudRows).isEmpty();
        assertThat(repository.audits).isEmpty();
    }

    @Test
    @DisplayName("A23: the detail read and the next read each fail with their own text, without codes; no detail")
    @SpecItem({"ERR-AUDT-001", "ERR-AUDT-002", "ERR-AUDT-003"})
    void readFailures() {
        add(1, T0, "00", "0000");
        repository.failing.add(Step.FIND);
        assertProblem(() -> service.detail("1"), HttpStatus.INTERNAL_SERVER_ERROR,
                MessageCatalog.AUDT_DETAIL_READ_FAILED, " System error while reading Auth Details");
        assertThat(MessageCatalog.AUDT_DETAIL_READ_FAILED.code()).isEqualTo("AUTH_DETAIL_READ_FAILED");
        repository.failing.clear();
        repository.failing.add(Step.NEXT);
        assertProblem(() -> service.detail("1"), HttpStatus.INTERNAL_SERVER_ERROR,
                MessageCatalog.AUTH_NEXT_READ_FAILED, " System error while reading next Auth");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("D-NFR-04: a failed read logs the failure's class names and SQL error number, never the driver's message")
    void aFailedReadLogsNoDriverMessage(CapturedOutput output) {
        add(1, T0, "00", "0000");
        repository.failure = new DataIntegrityViolationException("PreparedStatementCallback; bad SQL", new SQLException(
                "String or binary data would be truncated in table 'pauth.auth_fraud', column 'merchant_name'."
                        + " Truncated value: 'FIXTURE MERCHANT'.", "22001", 2628));
        repository.failing.add(Step.FIND);

        assertProblem(() -> service.detail("1"), HttpStatus.INTERNAL_SERVER_ERROR,
                MessageCatalog.AUDT_DETAIL_READ_FAILED, " System error while reading Auth Details");
        assertThat(output.getOut() + output.getErr())
                .contains("AUTH_DETAIL_READ_FAILED: auth_id 1",
                        "DataIntegrityViolationException < SQLException SQL error 2628 (state 22001)")
                .doesNotContain("FIXTURE MERCHANT", "Truncated value", "PreparedStatementCallback");
    }

    @Test
    @DisplayName("ERR-AUDT-002: a detail transaction that cannot start is AUTH_DETAIL_READ_FAILED; nothing is read")
    @SpecItem("ERR-AUDT-002")
    void detailWithoutConnection() {
        add(1, T0, "00", "0000");
        transactions.failToStart = true;
        assertProblem(() -> service.detail("1"), HttpStatus.INTERNAL_SERVER_ERROR,
                MessageCatalog.AUDT_DETAIL_READ_FAILED, " System error while reading Auth Details");
        assertThat(repository.steps).isEmpty();
    }

    @Test
    @DisplayName("A14: F on a blank row, then R: messages, F-<today>, one fraud row with the user, two audit rows")
    @SpecItem({"BR-AUDT-007", "BR-AUDT-008", "SE-AUDT-001", "SE-AUDT-002", "BR-AUFR-002", "BR-AUFR-003",
        "DEF-AUFR-001", "DEF-AUFR-004", "DEF-AUDT-006"})
    void markThenRemove() {
        add(2, T0, "00", "0000");
        add(5, T0.plusMinutes(1), "00", "0000");
        String before = etag(5);

        Marked marked = service.changeFraud("5", before, "F", USER);

        assertThat(marked.message()).isEqualTo(MessageCatalog.AUTH_MARKED_FRAUD);
        assertThat(marked.message().render()).isEqualTo("AUTH MARKED FRAUD...");
        assertThat(marked.message().severity()).isEqualTo(Severity.INFO);
        AuthorizationDetail d = marked.saved().detail();
        assertThat(d.fraud().flag()).isEqualTo("F");
        assertThat(d.fraud().reportDate()).isEqualTo("2026-11-10");
        assertThat(d.fraud().display()).isEqualTo("F-11/10/26");
        assertThat(d.nextOlderAuthId()).isEqualTo(2L);
        assertThat(marked.saved().etag()).isEqualTo(etag(5)).isNotEqualTo(before);
        assertThat(d.version()).isEqualTo(marked.saved().etag().replace("\"", ""));
        assertThat(repository.fraudRows).containsExactly(Map.entry(5L, new FraudRow("F", TODAY, USER)));
        assertThat(repository.inserts).isEqualTo(1);
        assertThat(repository.steps).containsExactly(Step.LOCK, Step.MARK, Step.UPDATE_FRAUD_ROW,
                Step.INSERT_FRAUD_ROW, Step.AUDIT, Step.FIND, Step.NEXT);
        assertThat(transactions.begun).singleElement().satisfies(t -> {
            assertThat(t.isReadOnly()).isFalse();
            assertThat(t.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        });
        assertThat(transactions.commits).isEqualTo(1);

        Marked removed = service.changeFraud("5", marked.saved().etag(), "R", "USER0002");

        assertThat(removed.message()).isEqualTo(MessageCatalog.AUTH_FRAUD_REMOVED);
        assertThat(removed.message().render()).isEqualTo("AUTH FRAUD REMOVED...");
        assertThat(removed.saved().detail().fraud().display()).isEqualTo("R-11/10/26");
        assertThat(repository.fraudRows).containsExactly(Map.entry(5L, new FraudRow("R", TODAY, "USER0002")));
        assertThat(repository.inserts).isEqualTo(1);
        assertThat(repository.audits).containsExactly(new Audit(USER, 5), new Audit("USER0002", 5));

        Marked again = service.changeFraud("5", removed.saved().etag(), "F", USER);
        assertThat(again.saved().detail().fraud().flag()).isEqualTo("F");
        assertThat(repository.inserts).isEqualTo(1);
    }

    @Test
    @DisplayName("A15: a stale If-Match is 409 RECORD_CHANGED with current and its ETag; nothing is written")
    @SpecItem({"BR-AUDT-007", "SE-AUDT-001"})
    void staleVersion() {
        add(1, T0, "00", "0000");
        String stale = etag(1);
        repository.details.put(1L, fraud(repository.details.get(1L), " ", null, repository.newVersion()));

        assertThatThrownBy(() -> service.changeFraud("1", stale, "F", USER))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("RECORD_CHANGED");
                    assertThat(e.text()).isEqualTo("Record changed by some one else. Please review");
                    assertThat(e.extras().get("current")).isInstanceOfSatisfying(AuthorizationDetail.class,
                            current -> assertThat(current.version()).isEqualTo(etag(1).replace("\"", "")));
                    assertThat(e.headers().getFirst(HttpHeaders.ETAG)).isEqualTo(etag(1));
                });
        assertThat(repository.steps).containsExactly(Step.LOCK, Step.NEXT);
        assertThat(repository.fraudRows).isEmpty();
        assertThat(repository.audits).isEmpty();
        assertThat(transactions.rollbacks).isEqualTo(1);
    }

    @Test
    @DisplayName("A16: R on a blank row, and F on an F row, are not the toggle: 409 RECORD_CHANGED")
    @SpecItem("BR-AUDT-007")
    void notTheToggle() {
        add(1, T0, "00", "0000");
        assertProblem409(() -> service.changeFraud("1", etag(1), "R", USER));
        repository.details.put(1L, fraud(repository.details.get(1L), "F", TODAY, repository.newVersion()));
        assertProblem409(() -> service.changeFraud("1", etag(1), "F", USER));
        repository.details.put(1L, fraud(repository.details.get(1L), "R", TODAY, repository.newVersion()));
        assertProblem409(() -> service.changeFraud("1", etag(1), "R", USER));
        assertThat(repository.steps).doesNotContain(Step.MARK);
        assertThat(AuthorizationDetailService.toggle(" ")).isEqualTo("F");
        assertThat(AuthorizationDetailService.toggle("R")).isEqualTo("F");
        assertThat(AuthorizationDetailService.toggle("F")).isEqualTo("R");
    }

    static void assertProblem409(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("RECORD_CHANGED");
            assertThat(e.extras()).containsKey("current");
        });
    }

    @Test
    @DisplayName("§9.2 step 1: the action first, then If-Match (428 missing, 400 malformed or two versions)")
    @SpecItem("BR-AUDT-007")
    void checkOrder() {
        add(1, T0, "00", "0000");
        for (String bad : new String[] {null, "", "f", "X", "FR", " F"}) {
            assertProblem(() -> service.changeFraud("1", null, bad, USER), HttpStatus.BAD_REQUEST,
                    MessageCatalog.INVALID_FRAUD_ACTION, "Fraud action must be F or R");
        }
        assertProblem(() -> service.changeFraud("1", null, "F", USER), HttpStatus.PRECONDITION_REQUIRED,
                MessageCatalog.PRECONDITION_REQUIRED, "If-Match header is required");
        String tag = etag(1).replace("\"", "");
        for (String ifMatch : new String[] {"xyz", tag, "W/\"" + tag + "\"", "*", "\"" + tag + "." + tag + "\""}) {
            assertProblem(() -> service.changeFraud("1", ifMatch, "F", USER), HttpStatus.BAD_REQUEST,
                    MessageCatalog.INVALID_IF_MATCH, "If-Match header is not a version of this record");
        }
        assertThat(repository.steps).isEmpty();
        assertThat(transactions.begun).isEmpty();
    }

    @Test
    @DisplayName("A17: any failure of steps 4 to 7 is FRAUD_UPDATE_FAILED and rolls everything back")
    @SpecItem({"ERR-AUDT-004", "ERR-AUDT-005", "ERR-AUDT-006", "SE-AUDT-002", "DEF-AUDT-003", "DEF-AUDT-005"})
    void writeFailures() {
        add(1, T0, "00", "0000");
        StoredDetail stored = repository.details.get(1L);
        for (Step step : List.of(Step.MARK, Step.UPDATE_FRAUD_ROW, Step.INSERT_FRAUD_ROW, Step.AUDIT)) {
            repository.failing.clear();
            repository.failing.add(step);
            assertProblem(() -> service.changeFraud("1", etag(1), "F", USER), HttpStatus.INTERNAL_SERVER_ERROR,
                    MessageCatalog.FRAUD_UPDATE_FAILED, " System error while FRAUD Tagging, ROLLBACK");
            assertThat(repository.details.get(1L)).as(step.name()).isSameAs(stored);
            assertThat(repository.fraudRows).as(step.name()).isEmpty();
            assertThat(repository.audits).as(step.name()).isEmpty();
        }
        repository.failing.clear();
        transactions.failToCommit = true;
        assertProblem(() -> service.changeFraud("1", etag(1), "F", USER), HttpStatus.INTERNAL_SERVER_ERROR,
                MessageCatalog.FRAUD_UPDATE_FAILED, " System error while FRAUD Tagging, ROLLBACK");
        assertThat(repository.details.get(1L)).isSameAs(stored);
        assertThat(repository.fraudRows).isEmpty();
    }

    @Test
    @DisplayName("§9.2 step 2: a transaction that cannot start, or a failing locking read, is AUTH_DETAIL_READ_FAILED")
    @SpecItem("ERR-AUDT-002")
    void fraudFailuresBeforeAnyWrite() {
        add(1, T0, "00", "0000");
        StoredDetail stored = repository.details.get(1L);
        transactions.failToStart = true;
        assertThatThrownBy(() -> service.changeFraud("1", etag(1), "F", USER))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(e.code()).isEqualTo("AUTH_DETAIL_READ_FAILED");
                    assertThat(e.text()).isEqualTo(" System error while reading Auth Details");
                    assertThat(e.field()).isNull();
                });
        assertThat(repository.steps).isEmpty();
        transactions.failToStart = false;
        repository.failing.add(Step.LOCK);
        assertProblem(() -> service.changeFraud("1", etag(1), "F", USER), HttpStatus.INTERNAL_SERVER_ERROR,
                MessageCatalog.AUDT_DETAIL_READ_FAILED, " System error while reading Auth Details");
        assertThat(repository.steps).containsExactly(Step.LOCK);
        assertThat(repository.details.get(1L)).isSameAs(stored);
        assertThat(repository.fraudRows).isEmpty();
        assertThat(repository.audits).isEmpty();
    }

    @Test
    @DisplayName("A24: a D-33 match committed since the view is 409; confirmed on the fresh view it stays M and is flagged")
    @SpecItem({"BR-AUDT-007", "SE-AUDT-001"})
    void matchCommittedSinceTheView() {
        add(1, T0, "00", "0000");
        String viewed = service.detail("1").etag();
        match(1, "0000000000000301");

        assertThatThrownBy(() -> service.changeFraud("1", viewed, "F", USER))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((AuthorizationDetail) e.extras().get("current")).matchStatus()).isEqualTo("M");
                });

        Marked marked = service.changeFraud("1", etag(1), "F", USER);
        assertThat(marked.saved().detail().matchStatus()).isEqualTo("M");
        assertThat(marked.saved().detail().matchedTranId()).isEqualTo("0000000000000301");
        assertThat(marked.saved().detail().fraud().flag()).isEqualTo("F");
    }
}
