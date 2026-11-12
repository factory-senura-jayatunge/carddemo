package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.carddemo.db.ServiceLogin;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The capture against writers in flight (batch.md, "Which accounts and transactions"; "Tests
 * (statement job)", capture and watermark), with a lock wait of 3 s. A write through a login
 * outside the window guard holds an uncommitted insert into {@code core.transaction}, which the
 * capture's shared table lock waits for; a Bill Pay transaction holds the window application lock
 * shared, as the guard takes it, which the capture's exclusive request waits for. A write that
 * commits within the wait is at or below {@code W} and on this statement; one held past it, or the
 * window lock held elsewhere, fails the start with {@code STATEMENT_CAPTURE_BUSY} and leaves no run
 * row and no capture row.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.fidelity.DEF-STMT-005=fixed",
        "carddemo.batch.internal.launch-lock-timeout=3s"})
class StatementCaptureLockTest extends StatementJobTestBase {

    private static final String TABLE_LOCK = "%WITH (TABLOCK, HOLDLOCK)%";
    private static final String APP_LOCK = "%sp_getapplock%";
    private static final String NOT_GRANTED = "the batch window lock was not granted within 3000 ms";

    /** The first cross-reference row's card and account. */
    private static Map<String, Object> xref() {
        return DB.admin().sql("SELECT TOP (1) card_num, acct_id FROM core.card_xref ORDER BY card_num").query()
                .singleRow();
    }

    private CompletableFuture<JobExecution> launchInBackground(long cycle) {
        return CompletableFuture.supplyAsync(() -> launch("statementJob", cycle));
    }

    /**
     * Whether a request whose text matches {@code text} waits for session {@code blocker} within
     * 30 s, which is how the test knows the capture is waiting before it commits.
     */
    private static boolean awaitBlocked(int blocker, String text) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = DB.admin().sql("""
                    SELECT COUNT(*) FROM sys.dm_exec_requests AS r
                    CROSS APPLY sys.dm_exec_sql_text(r.sql_handle) AS t
                    WHERE r.blocking_session_id = :blocker AND t.text LIKE :text""")
                    .param("blocker", blocker).param("text", text).query(Integer.class).single();
            if (waiting > 0) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static JobExecution ended(CompletableFuture<JobExecution> job) throws Exception {
        return job.get(150, TimeUnit.SECONDS);
    }

    private String pdfText(long cycle, String acctId) {
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(finalFolder(cycle).resolve(acctId + ".pdf")))) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The committed row is at or below {@code W}, counted on its account's statement and printed on it. */
    private void assertOnTheStatement(JobExecution execution, long cycle, long tranPk, String acct,
            String description) {
        assertThat(execution.getStatus()).as(execution.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        Map<String, Object> run = runRow(cycle);
        assertThat(run).containsEntry("mode", "FIXED").containsEntry("status", "COMPLETED");
        long watermark = ((Number) run.get("watermark_tran_pk")).longValue();
        assertThat(tranPk).isLessThanOrEqualTo(watermark);
        Map<String, Object> mine = accountRows(runId(cycle)).get(acct);
        assertThat(((Number) mine.get("tran_count")).longValue()).isEqualTo(count("""
                SELECT COUNT_BIG(*) FROM core.[transaction] AS t JOIN core.card_xref AS x ON x.card_num = t.card_num
                WHERE x.acct_id = ? AND t.tran_pk <= ?""", acct, watermark));
        assertThat(pdfText(cycle, acct)).contains(description);
    }

    /** The start failed BUSY and the capture's transaction left nothing. */
    private static void assertBusy(JobExecution execution, long cycle, long capturesBefore, String detail) {
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitDescription()).startsWith(StatementCapture.BUSY + ": ")
                .contains(detail);
        assertThat(count("SELECT COUNT_BIG(*) FROM batch.statement_run WHERE cycle_id = ?", cycle)).isZero();
        assertThat(count("SELECT COUNT_BIG(*) FROM batch.statement_capture")).isEqualTo(capturesBefore);
    }

    @Test
    void anInsertOutsideTheGuardCommittedWithinTheLockWaitIsOnTheStatement() throws Exception {
        long cycle = postedSampleCycle();
        Map<String, Object> xref = xref();
        String acct = (String) xref.get("acct_id");
        long tranPk;
        JobExecution execution;
        try (InFlightWrite writer = new InFlightWrite(DB.adminDataSource())) {
            tranPk = writer.insert("STMTLCK000000001", (String) xref.get("card_num"), "6.00", DATE + " 10:00:00",
                    "committed in the wait", "POSTED");
            CompletableFuture<JobExecution> job = launchInBackground(cycle);
            assertThat(awaitBlocked(writer.spid(), TABLE_LOCK)).as("the capture waits for the insert").isTrue();
            writer.commit();
            execution = ended(job);
        }
        assertOnTheStatement(execution, cycle, tranPk, acct, "committed in the wait");
    }

    @Test
    void anInsertOutsideTheGuardHeldPastTheLockWaitFailsTheStartBusy() throws Exception {
        long cycle = postedSampleCycle();
        long captures = count("SELECT COUNT_BIG(*) FROM batch.statement_capture");
        try (InFlightWrite writer = new InFlightWrite(DB.adminDataSource())) {
            writer.insert("STMTLCK000000002", (String) xref().get("card_num"), "6.00", DATE + " 10:00:00",
                    "held past the wait", "POSTED");
            JobExecution execution = launch("statementJob", cycle);
            assertBusy(execution, cycle, captures, "SQL error 1222");
        }
    }

    /** The payment and its balance change commit together, so the capture holds the balance after it. */
    @Test
    void aBillPayTransactionInFlightDelaysTheCaptureUntilItCommits() throws Exception {
        long cycle = postedSampleCycle();
        Map<String, Object> xref = xref();
        String acct = (String) xref.get("acct_id");
        long tranPk;
        JobExecution execution;
        try (InFlightWrite billPay = new InFlightWrite(DB.dataSource(ServiceLogin.BILLPAY))) {
            assertThat(billPay.windowLock("Shared", 5000)).as("the guard's lock").isGreaterThanOrEqualTo(0);
            tranPk = billPay.insert("STMTLCK000000003", (String) xref.get("card_num"), "-7.50", DATE + " 11:00:00",
                    "bill payment in flight", "BILL_PAY");
            billPay.addToBalance(acct, "-7.50");
            CompletableFuture<JobExecution> job = launchInBackground(cycle);
            assertThat(awaitBlocked(billPay.spid(), APP_LOCK)).as("the capture waits for the guard").isTrue();
            billPay.commit();
            execution = ended(job);
        }
        assertOnTheStatement(execution, cycle, tranPk, acct, "bill payment in flight");
        BigDecimal captured = DB.admin().sql("""
                SELECT curr_bal FROM batch.statement_capture WHERE statement_run_id = :r AND acct_id = :a""")
                .param("r", runId(cycle)).param("a", acct).query(BigDecimal.class).single();
        assertThat(captured).isEqualByComparingTo(DB.admin().sql("SELECT curr_bal FROM core.account WHERE acct_id = :a")
                .param("a", acct).query(BigDecimal.class).single());
    }

    @Test
    void aBillPayTransactionHeldPastTheLockWaitFailsTheStartBusy() throws Exception {
        long cycle = postedSampleCycle();
        long captures = count("SELECT COUNT_BIG(*) FROM batch.statement_capture");
        Map<String, Object> xref = xref();
        try (InFlightWrite billPay = new InFlightWrite(DB.dataSource(ServiceLogin.BILLPAY))) {
            assertThat(billPay.windowLock("Shared", 5000)).as("the guard's lock").isGreaterThanOrEqualTo(0);
            billPay.insert("STMTLCK000000004", (String) xref.get("card_num"), "-7.50", DATE + " 11:00:00",
                    "bill payment held", "BILL_PAY");
            billPay.addToBalance((String) xref.get("acct_id"), "-7.50");
            JobExecution execution = launch("statementJob", cycle);
            assertBusy(execution, cycle, captures, NOT_GRANTED);
        }
    }

    /**
     * Another session holds the window lock exclusively past the wait. A request for it from the
     * batch login gets {@code -1}, the timeout, which is the answer the capture's request gets.
     */
    @Test
    void theWindowLockHeldPastTheLockWaitFailsTheStartBusy() throws Exception {
        long cycle = postedSampleCycle();
        long captures = count("SELECT COUNT_BIG(*) FROM batch.statement_capture");
        try (InFlightWrite holder = new InFlightWrite(DB.adminDataSource())) {
            assertThat(holder.windowLock("Exclusive", 5000)).isGreaterThanOrEqualTo(0);
            try (InFlightWrite probe = new InFlightWrite(DB.dataSource(ServiceLogin.BATCH))) {
                assertThat(probe.windowLock("Exclusive", 200)).isEqualTo(-1);
            }
            JobExecution execution = launch("statementJob", cycle);
            assertBusy(execution, cycle, captures, NOT_GRANTED);
        }
    }
}
