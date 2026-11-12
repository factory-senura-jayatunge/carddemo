package org.carddemo.batch.pauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.carddemo.batch.pauth.PauthFixtures.acct;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * purgePendingAuthorizationsJob on small fixtures whose expected results are worked out by hand
 * from the rules (authorization-service.md section 3, data-model.md purge). Business date
 * 2024-01-02 with expiry 5: a detail dated 2023-12-28 or earlier is expired.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
@ExtendWith(OutputCaptureExtension.class)
class PurgePendingAuthorizationsJobTest extends PurgeTestBase {

    static final LocalDate BUSINESS = LocalDate.of(2024, 1, 2);
    static final LocalDateTime EXPIRED = LocalDateTime.of(2023, 12, 20, 10, 0);
    static final LocalDateTime FRESH = LocalDateTime.of(2024, 1, 1, 10, 0);

    private static void completed(JobExecution execution) {
        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
    }

    private static void assertTotals(ExecutionContext c, long summariesRead, long summariesDeleted, long detailsRead,
            long detailsDeleted, long nonZero, long fraudKept, String creditReleased) {
        assertThat(c.getLong(PurgeSummaryWriter.SUMMARIES_READ)).as("summaries read").isEqualTo(summariesRead);
        assertThat(c.getLong(PurgeSummaryWriter.SUMMARIES_DELETED)).as("summaries deleted").isEqualTo(summariesDeleted);
        assertThat(c.getLong(PurgeSummaryWriter.DETAILS_READ)).as("details read").isEqualTo(detailsRead);
        assertThat(c.getLong(PurgeSummaryWriter.DETAILS_DELETED)).as("details deleted").isEqualTo(detailsDeleted);
        assertThat(c.getLong(PurgeSummaryWriter.SUMMARIES_DELETED_WITH_NON_ZERO_TOTALS)).as("non-zero deletes")
                .isEqualTo(nonZero);
        assertThat(c.getLong(PurgeSummaryWriter.FRAUD_DETAILS_KEPT)).as("fraud kept").isEqualTo(fraudKept);
        assertThat(new BigDecimal(c.getString(PurgeSummaryWriter.CREDIT_RELEASED))).as("credit released")
                .isEqualByComparingTo(creditReleased);
    }

    private static void assertSummary(Map<String, Object> row, String credit, int approvedCnt, String approvedAmt,
            int declinedCnt, String declinedAmt) {
        assertThat((BigDecimal) row.get("credit_balance")).as("credit_balance").isEqualByComparingTo(credit);
        assertThat(row.get("approved_auth_cnt")).as("approved_auth_cnt").isEqualTo(approvedCnt);
        assertThat((BigDecimal) row.get("approved_auth_amt")).as("approved_auth_amt").isEqualByComparingTo(approvedAmt);
        assertThat(row.get("declined_auth_cnt")).as("declined_auth_cnt").isEqualTo(declinedCnt);
        assertThat((BigDecimal) row.get("declined_auth_amt")).as("declined_auth_amt").isEqualByComparingTo(declinedAmt);
    }

    private List<Long> detailIds(String acctId) {
        return DB.admin().sql("SELECT auth_id FROM pauth.auth_detail WHERE acct_id = :a ORDER BY auth_id")
                .param("a", acctId).query(Long.class).list();
    }

    @Test
    @SpecItem({"BR-AUPG-001", "BR-AUPG-002", "DEF-AUPG-004"})
    void aDetailIsExpiredWhenItsDayIsExpiryDaysOrMoreBeforeTheBusinessDateAcrossTheYearEnd() throws Exception {
        String a = acct(1);
        pauth.summary(a, "5.00", 5, "5.00", 0, "0.00");
        pauth.detail(a, LocalDateTime.of(2023, 12, 28, 23, 59, 59, 999_000_000), "00", "P", " ", "1.00", "1.00");
        long firstKept = pauth.detail(a, LocalDateTime.of(2023, 12, 29, 0, 0), "00", "P", " ", "1.00", "1.00");
        pauth.detail(a, LocalDateTime.of(2023, 1, 1, 0, 0), "00", "P", " ", "1.00", "1.00");
        long businessDay = pauth.detail(a, LocalDateTime.of(2024, 1, 2, 23, 0), "00", "P", " ", "1.00", "1.00");
        long future = pauth.detail(a, LocalDateTime.of(2024, 1, 3, 0, 0), "00", "P", " ", "1.00", "1.00");

        JobExecution first = purge(BUSINESS, 5, 10);

        completed(first);
        assertThat(detailIds(a)).containsExactly(firstKept, businessDay, future);
        assertSummary(pauth.summaryRow(a), "3.00", 3, "3.00", 0, "0.00");
        assertTotals(totals(first), 1, 0, 5, 2, 0, 0, "2.00");

        // Another attempt of the same cycle, on any later day, counts from the same business date.
        JobExecution rerun = purge(BUSINESS, 5, 10);
        completed(rerun);
        assertThat(detailIds(a)).containsExactly(firstKept, businessDay, future);
        assertTotals(totals(rerun), 1, 0, 3, 0, 0, 0, "0");
    }

    @Test
    @SpecItem({"BR-AUPG-003", "DEF-AUPG-003", "DEF-AUPG-009"})
    void theTotalsLoseExactlyTheDeletedRowsAndOnlyPendingApprovalsReleaseCredit() throws Exception {
        String a = acct(2);
        pauth.summary(a, "300.00", 4, "400.00", 2, "70.00");
        pauth.detail(a, EXPIRED, "00", "P", " ", "100.00", "100.00");
        pauth.detail(a, EXPIRED, "00", "M", " ", "100.00", "100.00");
        pauth.detail(a, EXPIRED, "00", "E", " ", "100.00", "100.00");
        pauth.detail(a, EXPIRED, "05", "D", " ", "0.00", "30.00");
        pauth.detail(a, EXPIRED, "05", "P", "R", "0.00", "40.00");
        long fraud = pauth.detail(a, EXPIRED, "00", "P", "F", "100.00", "100.00");
        long fresh = pauth.detail(a, FRESH, "00", "P", " ", "100.00", "100.00");

        JobExecution execution = purge(BUSINESS, 5, 10);

        completed(execution);
        assertThat(detailIds(a)).containsExactly(fraud, fresh);
        // Approved: three 00 rows (100 each); credit only for the P one. Declined: 30 + 40 requested.
        assertSummary(pauth.summaryRow(a), "200.00", 1, "100.00", 0, "0.00");
        assertTotals(totals(execution), 1, 0, 7, 5, 0, 1, "100.00");
    }

    @Test
    @SpecItem({"BR-AUPG-004", "BR-AUPG-006", "DEF-AUPG-002", "SE-AUPG-001", "SE-AUPG-002"})
    void aSummaryIsDeletedWhenNoDetailIsLeftWhateverItsTotalsAndOnlyExpiredDetailsAreDeleted() throws Exception {
        DB.admin().sql("""
                IF OBJECT_ID('dbo.test_detail_deletes') IS NULL
                    CREATE TABLE dbo.test_detail_deletes (auth_id BIGINT NOT NULL)""").update();
        DB.admin().sql("DELETE FROM dbo.test_detail_deletes").update();
        DB.admin().sql("""
                CREATE OR ALTER TRIGGER pauth.tr_test_detail_deletes ON pauth.auth_detail AFTER DELETE AS
                INSERT INTO dbo.test_detail_deletes (auth_id) SELECT auth_id FROM deleted""").update();
        try {
            Set<Long> expired = new HashSet<>();
            String allExpired = acct(11);
            pauth.summary(allExpired, "20.00", 2, "20.00", 0, "0.00");
            expired.add(pauth.detail(allExpired, EXPIRED, "00", "P", " ", "10.00", "10.00"));
            expired.add(pauth.detail(allExpired, EXPIRED, "00", "P", " ", "10.00", "10.00"));
            String oneFresh = acct(12);
            pauth.summary(oneFresh, "20.00", 2, "20.00", 0, "0.00");
            expired.add(pauth.detail(oneFresh, EXPIRED, "00", "P", " ", "10.00", "10.00"));
            pauth.detail(oneFresh, FRESH, "00", "P", " ", "10.00", "10.00");
            String fraudOnly = acct(13);
            pauth.summary(fraudOnly, "10.00", 1, "10.00", 0, "0.00");
            pauth.detail(fraudOnly, EXPIRED, "00", "P", "F", "10.00", "10.00");
            String emptyNonZero = acct(14);
            pauth.summary(emptyNonZero, "50.00", 1, "50.00", 0, "0.00");
            String emptyZero = acct(15);
            pauth.summary(emptyZero, "0.00", 0, "0.00", 0, "0.00");
            String legacyTotals = acct(16);
            pauth.summary(legacyTotals, "0.00", 0, "0.00", 0, "0.00");
            expired.add(pauth.detail(legacyTotals, EXPIRED, "00", "P", " ", "25.00", "25.00"));

            JobExecution execution = purge(BUSINESS, 5, 10);

            completed(execution);
            assertThat(pauth.summaryExists(allExpired)).isFalse();
            assertThat(pauth.summaryExists(emptyNonZero)).isFalse();
            assertThat(pauth.summaryExists(emptyZero)).isFalse();
            assertThat(pauth.summaryExists(legacyTotals)).isFalse();
            assertSummary(pauth.summaryRow(oneFresh), "10.00", 1, "10.00", 0, "0.00");
            assertSummary(pauth.summaryRow(fraudOnly), "10.00", 1, "10.00", 0, "0.00");
            assertThat(pauth.details(oneFresh)).isEqualTo(1);
            assertThat(pauth.details(fraudOnly)).isEqualTo(1);
            assertThat(DB.admin().sql("SELECT auth_id FROM dbo.test_detail_deletes").query(Long.class).set())
                    .as("every deleted detail is an expired one the purge's detail DELETE removed; nothing else removed one")
                    .isEqualTo(expired);
            assertTotals(totals(execution), 6, 4, 6, 4, 2, 1, "55.00");
        } finally {
            DB.admin().sql("DROP TRIGGER IF EXISTS pauth.tr_test_detail_deletes").update();
            DB.admin().sql("DROP TABLE IF EXISTS dbo.test_detail_deletes").update();
        }
    }

    @Test
    @SpecItem({"DEF-AUPG-006", "BR-AUPG-005"})
    void aFailingSummaryRollsBackItsChunkFailsTheJobAndTheRestartResumesAfterTheLastCommittedChunk()
            throws Exception {
        for (int n = 1; n <= 6; n++) {
            pauth.summary(acct(30 + n), "0.00", 0, "0.00", n == 5 ? 0 : 1, "10.00");
            pauth.detail(acct(30 + n), EXPIRED, "05", "D", " ", "0.00", "10.00");
        }
        // The fifth summary's declined count would go below zero, which this constraint refuses.
        DB.admin().sql("ALTER TABLE pauth.auth_summary WITH NOCHECK ADD CONSTRAINT CK_test_purge_failure "
                + "CHECK (declined_auth_cnt >= 0)").update();
        JobExecution failed;
        try {
            failed = purge(BUSINESS, 5, 2);
        } finally {
            DB.admin().sql("ALTER TABLE pauth.auth_summary DROP CONSTRAINT CK_test_purge_failure").update();
        }

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failures(failed)).contains("SQL Server error 547").doesNotContain(acct(35)).doesNotContain(acct(36));
        assertThat(failed.getStepExecutions()).extracting(s -> s.getStepName())
                .containsExactly(PurgePendingAuthorizationsJobConfiguration.PURGE_STEP);
        for (int n = 1; n <= 4; n++) {
            assertThat(pauth.summaryExists(acct(30 + n))).as("committed chunk, summary %d", n).isFalse();
        }
        for (int n = 5; n <= 6; n++) {
            assertThat(pauth.summaryExists(acct(30 + n))).as("rolled-back chunk, summary %d", n).isTrue();
            assertThat(pauth.details(acct(30 + n))).as("rolled-back chunk, details of %d", n).isEqualTo(1);
        }
        assertTotals(totals(failed), 4, 4, 4, 4, 0, 0, "0");

        JobExecution restart = jobOperator.restart(failed);

        completed(restart);
        assertThat(pauth.count("pauth.auth_summary")).isZero();
        assertThat(pauth.count("pauth.auth_detail")).isZero();
        assertTotals(totals(restart), 6, 6, 6, 6, 1, 0, "0");
    }

    @Test
    void messageRowsOlderThanTheExpiryAreDeletedInUnitsAndUnsentOutboxRowsNever() throws Exception {
        LocalDateTime limit = LocalDateTime.of(2022, 7, 2, 0, 0);
        message(1, LocalDateTime.of(2021, 1, 1, 0, 0));
        message(2, limit.minusNanos(1_000_000));
        message(3, limit);
        DB.admin().sql("INSERT INTO pauth.processed_message (msg_key, key_kind, reply_body) "
                + "VALUES (HASHBYTES('SHA2_256', 'M|4'), 'M', 'R')").update();
        long sentOld = outbox(LocalDateTime.of(2022, 6, 30, 0, 0), true);
        long sentAtLimit = outbox(limit, true);
        long unsentOld = outbox(LocalDateTime.of(2020, 1, 1, 0, 0), false);

        JobExecution execution = purge(LocalDate.of(2022, 7, 6), 5, 1);

        completed(execution);
        ExecutionContext c = step(execution, PurgePendingAuthorizationsJobConfiguration.MESSAGES_STEP)
                .getExecutionContext();
        assertThat(c.getString(PurgeMessagesTasklet.BEFORE)).isEqualTo("2022-07-02T00:00");
        assertThat(c.getLong(PurgeMessagesTasklet.PROCESSED_MESSAGES_DELETED)).isEqualTo(2);
        assertThat(c.getLong(PurgeMessagesTasklet.OUTBOX_ROWS_DELETED)).isEqualTo(1);
        assertThat(pauth.count("pauth.processed_message")).isEqualTo(2);
        assertThat(DB.admin().sql("SELECT outbox_id FROM pauth.outbox ORDER BY outbox_id").query(Long.class).list())
                .containsExactly(sentAtLimit, unsentOld).doesNotContain(sentOld);
        assertThat(step(execution, PurgePendingAuthorizationsJobConfiguration.MESSAGES_STEP).getCommitCount())
                .as("one row per transaction").isGreaterThanOrEqualTo(3);
    }

    @Test
    void aBusinessDateAheadOfTheClockNeverDeletesAFreshIdempotencyRow() throws Exception {
        LocalDate today = DB.admin().sql("SELECT CAST(SYSUTCDATETIME() AS DATE)").query(LocalDate.class).single();
        message(1, today.minusDays(3).atTime(12, 0));
        message(2, today.minusDays(6).atTime(12, 0));

        JobExecution execution = purge(today.plusDays(30), 5, 10);

        completed(execution);
        assertThat(step(execution, PurgePendingAuthorizationsJobConfiguration.MESSAGES_STEP).getExecutionContext()
                .getString(PurgeMessagesTasklet.BEFORE)).isEqualTo(today.minusDays(4).atStartOfDay().toString());
        assertThat(DB.admin().sql("SELECT key_kind FROM pauth.processed_message").query(String.class).list())
                .hasSize(1);
        assertThat(DB.admin().sql("SELECT received_at FROM pauth.processed_message").query(LocalDateTime.class).single())
                .isEqualTo(today.minusDays(3).atTime(12, 0));
    }

    @Test
    @SpecItem({"DEF-AUPG-001", "FLOW-AUPG-001"})
    void expiryZeroIsRefusedAtLaunch() {
        assertThatThrownBy(() -> purge(BUSINESS, 0, 10)).isInstanceOf(InvalidJobParametersException.class)
                .hasMessageContaining("expiryDays must be at least 1");
    }

    @Test
    @SpecItem({"DEF-AUPG-008", "BR-AUPG-006"})
    void theRunLogsItsTotalsAsNumbersWithNoAccountOrCardNumber(CapturedOutput output) throws Exception {
        String a = "73190000001";
        String b = "73190000002";
        pauth.summary(a, "10.00", 1, "10.00", 0, "0.00");
        pauth.detail(a, EXPIRED, "00", "P", " ", "10.00", "10.00");
        pauth.summary(b, "10.00", 1, "10.00", 0, "0.00");
        pauth.detail(b, FRESH, "00", "P", " ", "10.00", "10.00");

        completed(purge(BUSINESS, 5, 1));

        assertThat(output.getAll()).contains("purge COMPLETED: 2 summaries read, 1 deleted (0 with non-zero totals); "
                        + "2 details read, 1 deleted, 0 expired but flagged F kept; credit released 10.00")
                .contains("purgeMessages: 0 processed messages and 0 sent outbox rows deleted")
                .doesNotContain(a).doesNotContain(b).doesNotContain(PauthFixtures.CARD);
    }

    private static void message(int n, LocalDateTime receivedAt) {
        DB.admin().sql("INSERT INTO pauth.processed_message (msg_key, key_kind, reply_body, received_at) "
                + "VALUES (HASHBYTES('SHA2_256', CONCAT('M|', :n)), 'M', 'R', :at)")
                .param("n", n).param("at", receivedAt).update();
    }

    private static long outbox(LocalDateTime createdAt, boolean sent) {
        return DB.admin().sql("""
                INSERT INTO pauth.outbox (created_at, exchange_name, routing_key, content_type, expiration_ms, body, sent_at)
                OUTPUT inserted.outbox_id
                VALUES (:at, 'pauth.reply', 'reply', 'text/plain', 5000, 'R', :sent)""")
                .param("at", createdAt).param("sent", sent ? createdAt : null, java.sql.Types.TIMESTAMP)
                .query(Long.class).single();
    }
}
