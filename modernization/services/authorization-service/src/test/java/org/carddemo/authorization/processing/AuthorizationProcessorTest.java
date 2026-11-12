package org.carddemo.authorization.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.carddemo.authorization.AuthorizationConfiguration;
import org.carddemo.authorization.decision.Reason;
import org.carddemo.authorization.processing.AuthorizationRepository.AccountRow;
import org.carddemo.authorization.processing.AuthorizationRepository.CardRow;
import org.carddemo.authorization.processing.AuthorizationRepository.SummaryChange;
import org.carddemo.authorization.processing.AuthorizationRepository.Xref;
import org.carddemo.authorization.request.AuthorizationRequest;
import org.carddemo.authorization.request.MessageKey;
import org.carddemo.authorization.request.RequestParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The order of work and the failure handling of one request (contract §6.2, §6.4) against a
 * stand-in repository; what the SQL itself does is covered by the database tests.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthorizationProcessorTest {

    static final String CARD = "4111111111111111";
    static final String ACCT = "00000000091";
    static final String CUST = "000000091";
    static final String CSV = "240405,143015," + CARD + ",SALE,0426,0100  ,POS   ,000000,%s,5411,840,01,"
            + "MERCH0000000001,GROCERY STORE,SPRINGFIELD,IL,62701,TRN000000000001";
    static final ReplyRoute ROUTE = new ReplyRoute("", "amq.gen-reply", "corr-1");
    static final Instant NOW = Instant.parse("2026-04-05T14:30:15.123Z");

    final AuthorizationRepository repo = mock(AuthorizationRepository.class);
    final RecordingTransactions transactions = new RecordingTransactions();
    final AuthorizationProcessor processor = new AuthorizationProcessor(repo,
            new AuthorizationConfiguration().authorizationTransaction(transactions),
            Clock.fixed(NOW, ZoneId.of("UTC")));

    @BeforeEach
    void knownCard() {
        when(repo.xref(CARD)).thenReturn(Optional.of(new Xref(ACCT, CUST)));
        when(repo.account(ACCT)).thenReturn(Optional.of(
                new AccountRow("Y", new BigDecimal("200.00"), new BigDecimal("1000.00"), new BigDecimal("300.00"))));
        when(repo.customerExists(CUST)).thenReturn(true);
        when(repo.card(CARD)).thenReturn(Optional.of(new CardRow("Y", "2030-12-31")));
        when(repo.lockSummary(ACCT)).thenReturn(Optional.empty());
        when(repo.insertDetail(eq(ACCT), any(), any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(OptionalLong.of(1_234_567L));
        when(repo.setAuthIdCode(1_234_567L)).thenReturn("234567");
        when(repo.insertOutbox(any(), anyString(), eq(5000), anyString())).thenReturn(77L);
    }

    static AuthorizationRequest csv(String amount) throws Exception {
        return RequestParser.parse(String.format(CSV, amount).getBytes(StandardCharsets.UTF_8), "text/csv");
    }

    static MessageKey key(AuthorizationRequest r) {
        return MessageKey.of("msg-1", r);
    }

    Outcome process(AuthorizationRequest r) {
        return processor.process(r, key(r), ROUTE);
    }

    @Test
    @DisplayName("M1: an approval creates the summary, writes the detail with its code, the outbox row and the key")
    void approvalWritesEverything() throws Exception {
        AuthorizationRequest r = csv("50.00");
        Outcome o = process(r);

        assertThat(o.reply().toCsv()).isEqualTo(CARD + ",TRN000000000001,234567,00,0000,         50.00,");
        assertThat(o.authId()).isEqualTo(1_234_567L);
        assertThat(o.outboxId()).isEqualTo(77L);
        assertThat(o.body()).isEqualTo(o.reply().toCsv());
        ArgumentCaptor<SummaryChange> change = ArgumentCaptor.forClass(SummaryChange.class);
        verify(repo).updateSummary(change.capture());
        verify(repo).insertSummary(change.getValue());
        assertThat(change.getValue()).isEqualTo(new SummaryChange(ACCT, CUST, new BigDecimal("1000.00"),
                new BigDecimal("300.00"), 1, new BigDecimal("50.00"), 0, new BigDecimal("0.00")));
        verify(repo).insertDetail(ACCT, LocalDateTime.of(2026, 4, 5, 14, 30, 15, 123_000_000), r, "00", "0000",
                new BigDecimal("50.00"), "P");
        verify(repo).insertOutbox(ROUTE, "text/csv", 5000, o.body());
        verify(repo).insertProcessedMessage(key(r), 1_234_567L, o.reply().toCsv());
        assertThat(transactions.commits).isEqualTo(1);
        assertThat(transactions.isolation).containsExactly(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Test
    @DisplayName("§6.2 step 6: an existing summary is updated in one statement, never inserted again")
    void existingSummaryIsUpdated() throws Exception {
        when(repo.lockSummary(ACCT)).thenReturn(Optional.of(new BigDecimal("50.00")));
        when(repo.updateSummary(any())).thenReturn(1);
        process(csv("50.00"));
        verify(repo, never()).insertSummary(any());
    }

    @Test
    @DisplayName("M2, M3: the pending amount from the locked summary counts against the available credit")
    void pendingFromTheLockedSummary() throws Exception {
        when(repo.lockSummary(ACCT)).thenReturn(Optional.of(new BigDecimal("50.00")));
        when(repo.updateSummary(any())).thenReturn(1);
        assertThat(process(csv("750.00")).decision().reason()).isEqualTo(Reason.APPROVED);
        Outcome declined = process(csv("750.01"));
        assertThat(declined.reply().toCsv()).isEqualTo(CARD + ",TRN000000000001,234567,05,4100,          0.00,");
        ArgumentCaptor<SummaryChange> change = ArgumentCaptor.forClass(SummaryChange.class);
        verify(repo, times(2)).updateSummary(change.capture());
        assertThat(change.getValue()).isEqualTo(new SummaryChange(ACCT, CUST, new BigDecimal("1000.00"),
                new BigDecimal("300.00"), 0, new BigDecimal("0.00"), 1, new BigDecimal("750.01")));
        verify(repo).insertDetail(eq(ACCT), any(), any(), eq("05"), eq("4100"), eq(new BigDecimal("0.00")), eq("D"));
    }

    @Test
    @DisplayName("M4, M28: an unknown card is declined 3100 with code 000000, no summary or detail, a masked log line")
    void unknownCard(CapturedOutput output) throws Exception {
        when(repo.xref(CARD)).thenReturn(Optional.empty());
        AuthorizationRequest r = csv("50.00");
        Outcome o = process(r);
        assertThat(o.reply().toCsv()).isEqualTo(CARD + ",TRN000000000001,000000,05,3100,          0.00,");
        assertThat(o.authId()).isNull();
        verify(repo, never()).lockSummary(anyString());
        verify(repo, never()).updateSummary(any());
        verify(repo, never()).insertSummary(any());
        verify(repo, never()).insertDetail(any(), any(), any(), any(), any(), any(), any());
        verify(repo).insertOutbox(ROUTE, "text/csv", 5000, o.body());
        verify(repo).insertProcessedMessage(eq(key(r)), isNull(), eq(o.reply().toCsv()));
        assertThat(output).contains("A001", "************1111").doesNotContain(CARD);
    }

    @Test
    @DisplayName("BR-AUTH-003: a card value that is not 16 digits is never looked up")
    void invalidCardIsNotRead() throws Exception {
        AuthorizationRequest r = RequestParser.parse(String.format(CSV, "50.00").replace(CARD, "41111111111111x1")
                .getBytes(StandardCharsets.UTF_8), "text/csv");
        Outcome o = process(r);
        assertThat(o.decision().reason()).isEqualTo(Reason.INVALID_CARD);
        verify(repo, never()).xref(anyString());
    }

    @Test
    @DisplayName("M6: a cross-reference whose account is missing is declined 3100 without any write")
    void accountMissing() throws Exception {
        when(repo.account(ACCT)).thenReturn(Optional.empty());
        Outcome o = process(csv("50.00"));
        assertThat(o.decision().reason()).isEqualTo(Reason.INVALID_CARD);
        assertThat(o.authId()).isNull();
        verify(repo, never()).lockSummary(anyString());
        verify(repo, never()).updateSummary(any());
        verify(repo, never()).insertDetail(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("M7: a missing customer is declined 3100, and the summary and detail are still written")
    void customerMissing(CapturedOutput output) throws Exception {
        when(repo.customerExists(CUST)).thenReturn(false);
        Outcome o = process(csv("50.00"));
        assertThat(o.decision().reason()).isEqualTo(Reason.INVALID_CARD);
        assertThat(o.reply().authIdCode()).isEqualTo("234567");
        verify(repo).insertSummary(new SummaryChange(ACCT, CUST, new BigDecimal("1000.00"), new BigDecimal("300.00"),
                0, new BigDecimal("0.00"), 1, new BigDecimal("50.00")));
        verify(repo).insertDetail(eq(ACCT), any(), any(), eq("05"), eq("3100"), any(), eq("D"));
        assertThat(output).contains("A003").doesNotContain(CARD);
    }

    @Test
    @DisplayName("M8, M10: card status and confirmed fraud come from their reads")
    void cardAndFraudReads() throws Exception {
        when(repo.card(CARD)).thenReturn(Optional.of(new CardRow("Y", "2026-04-04")));
        assertThat(process(csv("50.00")).decision().reason()).isEqualTo(Reason.CARD_NOT_ACTIVE);
        when(repo.card(CARD)).thenReturn(Optional.of(new CardRow("Y", "2026-04-05")));
        when(repo.confirmedFraud(CARD)).thenReturn(true);
        assertThat(process(csv("50.00")).decision().reason()).isEqualTo(Reason.CARD_FRAUD);
    }

    @Test
    @DisplayName("M12: an invalid amount is declined 9000, stored as 0.00 and adds 0.00 to the declined amount")
    void invalidAmount() throws Exception {
        Outcome o = process(csv("12.345"));
        assertThat(o.decision().reason()).isEqualTo(Reason.UNKNOWN);
        ArgumentCaptor<SummaryChange> change = ArgumentCaptor.forClass(SummaryChange.class);
        verify(repo).insertSummary(change.capture());
        assertThat(change.getValue().declinedAmt()).isEqualByComparingTo("0.00");
        assertThat(change.getValue().declined()).isEqualTo(1);
    }

    @Test
    @DisplayName("M17: a JSON request gets the JSON reply in the outbox and the CSV form as the stored reply")
    void jsonReply() throws Exception {
        String json = "{\"authDate\":\"240405\",\"authTime\":\"143015\",\"cardNum\":\"" + CARD + "\",\"authType\":\"SALE\","
                + "\"cardExpiryDate\":\"0426\",\"messageType\":\"0100\",\"messageSource\":\"POS\","
                + "\"processingCode\":\"0\",\"transactionAmt\":\"50.00\",\"merchantCategoryCode\":\"5411\","
                + "\"acqrCountryCode\":\"840\",\"posEntryMode\":\"1\",\"merchantId\":\"MERCH0000000001\","
                + "\"merchantName\":\"GROCERY STORE\",\"merchantCity\":\"SPRINGFIELD\",\"merchantState\":\"IL\","
                + "\"merchantZip\":\"62701\",\"transactionId\":\"TRN000000000001\"}";
        AuthorizationRequest r = RequestParser.parse(json.getBytes(StandardCharsets.UTF_8), "application/json");
        Outcome o = process(r);
        assertThat(o.body()).isEqualTo("{\"cardNum\":\"" + CARD + "\",\"transactionId\":\"TRN000000000001\","
                + "\"authIdCode\":\"234567\",\"respCode\":\"00\",\"respReason\":\"0000\",\"approvedAmt\":\"50.00\"}");
        verify(repo).insertOutbox(ROUTE, "application/json", 5000, o.body());
        verify(repo).insertProcessedMessage(key(r), 1_234_567L, o.reply().toCsv());
    }

    @Test
    @DisplayName("M27, ERR-AUTH-009: a detail key collision moves processed_at 1 ms later and tries again")
    void detailKeyCollision() throws Exception {
        when(repo.insertDetail(eq(ACCT), any(), any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.of(1_234_567L));
        process(csv("50.00"));
        ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo, times(3)).insertDetail(eq(ACCT), at.capture(), any(), anyString(), anyString(), any(),
                anyString());
        LocalDateTime first = LocalDateTime.of(2026, 4, 5, 14, 30, 15, 123_000_000);
        assertThat(at.getAllValues()).containsExactly(first, first.plusNanos(1_000_000), first.plusNanos(2_000_000));
        assertThat(transactions.commits).isEqualTo(1);
    }

    @Test
    @DisplayName("ERR-AUTH-009: after 5 retries of the detail key the transaction fails at I004")
    void detailKeyCollisionsUsedUp() throws Exception {
        when(repo.insertDetail(eq(ACCT), any(), any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(OptionalLong.empty());
        assertThatThrownBy(() -> process(csv("50.00")))
                .isInstanceOfSatisfying(AuthorizationStoreException.class, e -> {
                    assertThat(e.point()).isEqualTo(FailurePoint.DETAIL_WRITE);
                    assertThat(e.point().legacyLocation()).isEqualTo("I004");
                    assertThat(e.retryable()).isFalse();
                });
        verify(repo, times(6)).insertDetail(eq(ACCT), any(), any(), anyString(), anyString(), any(), anyString());
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.commits).isZero();
    }

    @Test
    @DisplayName("§6.4: a deadlock rolls the transaction back and runs it again")
    void deadlockIsRetried() throws Exception {
        when(repo.lockSummary(ACCT)).thenThrow(sqlError(1205)).thenThrow(sqlError(1222))
                .thenReturn(Optional.empty());
        Outcome o = process(csv("50.00"));
        assertThat(o.decision().approved()).isTrue();
        assertThat(transactions.rollbacks).isEqualTo(2);
        assertThat(transactions.commits).isEqualTo(1);
    }

    @Test
    @DisplayName("§6.4: after 3 retries the deadlock is the failure, at its step")
    void deadlockRetriesUsedUp() throws Exception {
        when(repo.lockSummary(ACCT)).thenThrow(sqlError(1205));
        assertThatThrownBy(() -> process(csv("50.00")))
                .isInstanceOfSatisfying(AuthorizationStoreException.class, e -> {
                    assertThat(e.point()).isEqualTo(FailurePoint.SUMMARY_READ);
                    assertThat(e.point().legacyLocation()).isEqualTo("I002");
                    assertThat(e.sqlError()).isEqualTo(1205);
                    assertThat(e.getMessage()).isEqualTo("SUMMARY_READ failed (SQL Server error 1205)");
                });
        verify(repo, times(4)).lockSummary(ACCT);
        assertThat(transactions.rollbacks).isEqualTo(4);
    }

    @Test
    @DisplayName("§6.4: any other SQL error fails at once with its legacy location, and the message holds no value")
    void otherSqlErrorIsNotRetried() throws Exception {
        when(repo.account(ACCT)).thenThrow(sqlError(229));
        assertThatThrownBy(() -> process(csv("50.00")))
                .isInstanceOfSatisfying(AuthorizationStoreException.class, e -> {
                    assertThat(e.point().legacyLocation()).isEqualTo("C002");
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).doesNotContain(CARD, ACCT);
                });
        verify(repo, times(1)).account(ACCT);
        verify(repo, never()).insertOutbox(any(), anyString(), eq(5000), anyString());
    }

    @Test
    @DisplayName("§6.3: a key stored by another delivery rolls everything back for the duplicate path")
    void keyStoredMeanwhile() throws Exception {
        AuthorizationRequest r = csv("50.00");
        org.mockito.Mockito.doThrow(new AlreadyProcessedException()).when(repo)
                .insertProcessedMessage(eq(key(r)), anyLong(), anyString());
        assertThatThrownBy(() -> process(r)).isInstanceOf(AlreadyProcessedException.class);
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.commits).isZero();
    }

    @Test
    @DisplayName("§6.2 step 2: the duplicate-key read runs outside the transaction")
    void duplicateCheckOutsideTheTransaction() throws Exception {
        AuthorizationRequest r = csv("50.00");
        when(repo.processedMessage(key(r))).thenReturn(Optional.empty());
        assertThat(processor.findProcessed(key(r))).isEmpty();
        assertThat(transactions.begun).isZero();
    }

    static DataAccessException sqlError(int code) {
        SQLException sql = new SQLException("simulated", "40001", code);
        return code == 1205 || code == 1222
                ? new PessimisticLockingFailureException("simulated", sql)
                : new UncategorizedSQLException("simulated", "SELECT", sql);
    }

    static final class RecordingTransactions implements PlatformTransactionManager {
        int begun;
        int commits;
        int rollbacks;
        final List<Integer> isolation = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            begun++;
            isolation.add(definition.getIsolationLevel());
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commits++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
        }
    }
}
