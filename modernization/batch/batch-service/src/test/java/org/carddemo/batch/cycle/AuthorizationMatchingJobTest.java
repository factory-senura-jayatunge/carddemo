package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.carddemo.batch.internal.CycleReruns;
import org.carddemo.batch.internal.WindowHolder;
import org.carddemo.platform.legacyformat.Zoned;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FieldSpec;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * D-33 matching in postDailyTransactionsJob (d33-matching-brief.md, the numbered cases), on
 * synthetic data: one account with one card, its authorization summary holding 500.00, and
 * approvals and cycle transactions written as the authorization service and posting write them.
 * expiryDays is the setting's default, 5. One pair per chunk, so a restart can resume between pairs.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.batch.files.chunk-size=1"})
class AuthorizationMatchingJobTest extends CycleJobTestBase {

    private static final String DATE = "2022-07-06";
    private static final String ACCT = "00000000001";
    private static final String CUST = "000000001";
    /** Synthetic, built at run time. */
    private static final String CARD = "9".repeat(12) + "0001";
    private static final String OTHER_CARD = "9".repeat(12) + "0002";
    private static final LocalDateTime DA = LocalDateTime.of(2022, 7, 5, 10, 0);

    @Autowired
    CycleReruns reruns;

    private long cycle;
    private int nextAuth;
    private int nextTran;

    @BeforeEach
    void anAccountWithAPendingBalance() {
        DB.admin().sql("DELETE FROM batch.auth_match_pair").update();
        DB.admin().sql("DELETE FROM batch.snapshot_tcat_bal").update();
        DB.admin().sql("DELETE FROM batch.snapshot_account").update();
        DB.fixtures().account(CoreFixtures.Account.of(ACCT, "0.00")).customer(CUST).card(CARD, ACCT, CUST)
                .card(OTHER_CARD, ACCT, CUST);
        DB.admin().sql("""
                INSERT INTO pauth.auth_summary (acct_id, cust_id, credit_limit, cash_limit, credit_balance, cash_balance,
                    approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt)
                VALUES (:a, :c, 2020.00, 1020.00, 500.00, 0, 3, 0, 500.00, 0)""").param("a", ACCT).param("c", CUST)
                .update();
        cycle = openCycle(DATE);
    }

    @AfterEach
    void dropTriggers() {
        DB.admin().sql("DROP TRIGGER IF EXISTS batch.tr_test_auth_match_pair").update();
        DB.admin().sql("DROP TRIGGER IF EXISTS pauth.tr_test_auth_summary").update();
        DB.admin().sql("DROP TRIGGER IF EXISTS pauth.tr_test_auth_detail_gate").update();
    }

    private long approval(String card, String amount, String merchantId, LocalDateTime processedAt, String resp,
            String fraud) {
        int n = ++nextAuth;
        return DB.admin().sql("""
                INSERT INTO pauth.auth_detail (acct_id, processed_at, auth_date_9c, auth_time_9c, orig_date, orig_time,
                    card_num, auth_type, card_expiry_date, message_type, message_source, auth_id_code, auth_resp_code,
                    auth_resp_reason, processing_code, transaction_amt, approved_amt, merchant_category_code,
                    acqr_country_code, pos_entry_mode, merchant_id, merchant_name, merchant_city, merchant_state,
                    merchant_zip, transaction_id, match_status, auth_fraud)
                OUTPUT inserted.auth_id
                VALUES (:acct, :at, 77814, :n, '220705', '100000', :card, 'AUTH', '0130', 'REQAUT', 'POS', '000001',
                    :resp, '0000', '000000', :amt, :amt, '5411', 'USA', '01', :merchant, 'SHOP', 'TOWN', 'NY', '10001',
                    '000000000000001', 'P', :fraud)""")
                .param("acct", ACCT).param("at", processedAt).param("n", 900000000 - n).param("card", card)
                .param("resp", resp).param("amt", new BigDecimal(amount)).param("merchant", merchantId)
                .param("fraud", fraud).query(Long.class).single();
    }

    private long approval(String amount, String merchantId, LocalDateTime processedAt) {
        return approval(CARD, amount, merchantId, processedAt, "00", " ");
    }

    private long transaction(String card, String amount, String merchantId, LocalDateTime origTs, String origin,
            Long cycleId) {
        int n = ++nextTran;
        return DB.admin().sql("""
                INSERT INTO core.[transaction] (tran_id, type_cd, cat_cd, source, description, amount, merchant_id,
                    merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, origin, cycle_id, in_master)
                OUTPUT inserted.tran_pk
                VALUES (:id, '01', '0001', 'POS TERM', 'PURCHASE', :amt, :merchant, 'SHOP', 'TOWN', '10001', :card,
                    :orig, :orig, :origin, :cycle, 1)""")
                .param("id", String.format("%016d", 7_000_000 + n)).param("amt", new BigDecimal(amount))
                .param("merchant", merchantId).param("card", card).param("orig", origTs).param("origin", origin)
                .param("cycle", cycleId).query(Long.class).single();
    }

    private long posted(String amount, String merchantId, LocalDateTime origTs) {
        return transaction(CARD, amount, merchantId, origTs, "POSTED", cycle);
    }

    private JobExecution post() {
        JobExecution run = launch("postDailyTransactionsJob", cycle);
        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        return run;
    }

    private String status(long authId) {
        return DB.admin().sql("SELECT match_status FROM pauth.auth_detail WHERE auth_id = :a").param("a", authId)
                .query(String.class).single();
    }

    private Long matchedTran(long authId) {
        return DB.admin().sql("SELECT matched_tran_pk FROM pauth.auth_detail WHERE auth_id = :a").param("a", authId)
                .query(Long.class).optional().orElse(null);
    }

    private BigDecimal creditBalance() {
        return DB.admin().sql("SELECT credit_balance FROM pauth.auth_summary WHERE acct_id = :a").param("a", ACCT)
                .query(BigDecimal.class).single();
    }

    private static long stage(JobExecution run, String key) {
        StepExecution s = IngestDailyFileJobTest.step(run, "stageAuthorizationMatches");
        return s.getExecutionContext().getLong(key);
    }

    private static long apply(JobExecution run, String key) {
        StepExecution s = IngestDailyFileJobTest.step(run, "applyAuthorizationMatches");
        return s.getExecutionContext().getLong(key);
    }

    private static String splice(String line, String field, String value) {
        FieldSpec f = Layouts.DAILY_TRAN.field(field);
        assertThat(value).hasSize(f.length());
        return line.substring(0, f.offset()) + value + line.substring(f.end());
    }

    /** A sample daily record for the test card with this amount and merchant ID, dated the business date. */
    private static String dailyRecord(String amount, String merchantId) {
        String line = sampleDaily().getFirst();
        line = splice(line, "DALYTRAN-CARD-NUM", CARD);
        line = splice(line, "DALYTRAN-AMT", Zoned.encode(new BigDecimal(amount),
                Layouts.DAILY_TRAN.field("DALYTRAN-AMT").pic()));
        line = splice(line, "DALYTRAN-MERCHANT-ID", merchantId);
        return splice(line, "DALYTRAN-ORIG-TS", "2022-07-06 09:30:00.000000");
    }

    @Test
    void case01_aPostedDailyRecordSettlesItsApprovalAndReleasesTheAmount() {
        long auth = approval("50.00", "000000000123456", DA);
        String line = dailyRecord("50.00", "000123456");
        dailyFile(DATE, List.of(line));
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        JobExecution run = post();

        long tranPk = DB.admin().sql("SELECT tran_pk FROM core.[transaction] WHERE cycle_id = :c AND origin = 'POSTED'")
                .param("c", cycle).query(Long.class).single();
        assertThat(status(auth)).isEqualTo("M");
        assertThat(matchedTran(auth)).isEqualTo(tranPk);
        assertThat(DB.admin().sql("""
                        SELECT CONCAT(RTRIM(matched_tran_id), '/', matched_cycle_id, '/',
                            CASE WHEN matched_at IS NULL THEN 'no time' ELSE 'time' END)
                        FROM pauth.auth_detail WHERE auth_id = :a""").param("a", auth).query(String.class).single())
                .isEqualTo(line.substring(0, 16).strip() + "/" + cycle + "/time");
        assertThat(creditBalance()).isEqualByComparingTo("450.00");
        assertThat(DB.admin().sql("SELECT CONCAT(approved_auth_cnt, '/', approved_auth_amt) FROM pauth.auth_summary")
                .query(String.class).single()).as("the purge removes them").isEqualTo("3/500.00");
        assertThat(stage(run, StageAuthorizationMatchesTasklet.CANDIDATE_PAIRS)).isEqualTo(1);
        assertThat(stage(run, StageAuthorizationMatchesTasklet.MATCHED)).isEqualTo(1);
        assertThat(apply(run, ApplyAuthorizationMatchesTasklet.APPLIED)).isEqualTo(1);
    }

    @Test
    void case02And03_theWindowIsTheApprovalDateToExpiryDaysLaterInclusive() {
        long same = approval("11.00", "1", DA);
        posted("11.00", "000000001", DA.withHour(0));
        long fifth = approval("12.00", "1", DA);
        posted("12.00", "000000001", DA.plusDays(5).withHour(23).withMinute(59));
        long sixth = approval("13.00", "1", DA);
        posted("13.00", "000000001", DA.plusDays(6).withHour(0));
        long before = approval("14.00", "1", DA);
        posted("14.00", "000000001", DA.minusDays(1).withHour(23));

        post();

        assertThat(List.of(status(same), status(fifth), status(sixth), status(before)))
                .containsExactly("M", "M", "P", "P");
        assertThat(creditBalance()).isEqualByComparingTo("477.00");
    }

    @Test
    void case04To06_anAmbiguousGroupStaysPending() {
        long twoA = approval("20.00", "5", DA);
        long twoB = approval("20.00", "5", DA);
        posted("20.00", "000000005", DA);
        long one = approval("21.00", "5", DA);
        posted("21.00", "000000005", DA);
        posted("21.00", "000000005", DA.plusHours(1));
        long gridA = approval("22.00", "5", DA);
        long gridB = approval("22.00", "5", DA);
        posted("22.00", "000000005", DA);
        posted("22.00", "000000005", DA);

        JobExecution run = post();

        assertThat(List.of(status(twoA), status(twoB), status(one), status(gridA), status(gridB)))
                .containsOnly("P");
        assertThat(stage(run, StageAuthorizationMatchesTasklet.CANDIDATE_PAIRS)).isEqualTo(2 + 2 + 4);
        assertThat(stage(run, StageAuthorizationMatchesTasklet.AMBIGUOUS)).isEqualTo(8);
        assertThat(stage(run, StageAuthorizationMatchesTasklet.MATCHED)).isZero();
        assertThat(creditBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void case07And08_aTipOrASplitShipmentDoesNotMatch() {
        long tip = approval("50.00", "7", DA);
        posted("57.50", "000000007", DA);
        long split = approval("60.00", "8", DA);
        posted("30.00", "000000008", DA);
        posted("30.00", "000000008", DA);

        JobExecution run = post();

        assertThat(List.of(status(tip), status(split))).containsOnly("P");
        assertThat(stage(run, StageAuthorizationMatchesTasklet.CANDIDATE_PAIRS)).isZero();
    }

    @Test
    void case09_anInvalidMerchantIdNeverMatchesNotEvenAnEqualOne() {
        String[] invalidOnBoth = {"ABC123", "12 34", "-123", "12.3", "", "000000000"};
        for (int i = 0; i < invalidOnBoth.length; i++) {
            String amount = (30 + i) + ".00";
            approval(amount, invalidOnBoth[i], DA);
            posted(amount, invalidOnBoth[i], DA);
        }
        approval("40.00", "1234567890", DA);
        posted("40.00", "234567890", DA);
        approval("41.00", "ABC123", DA);
        posted("41.00", "000000123", DA);
        approval("42.00", "123", DA);
        posted("42.00", "12 3", DA);

        JobExecution run = post();

        assertThat(DB.admin().sql("SELECT COUNT(*) FROM pauth.auth_detail WHERE match_status <> 'P'")
                .query(Integer.class).single()).isZero();
        assertThat(stage(run, StageAuthorizationMatchesTasklet.MERCHANT_INVALID)).isEqualTo(invalidOnBoth.length + 3);
        assertThat(stage(run, StageAuthorizationMatchesTasklet.CANDIDATE_PAIRS)).isZero();
    }

    @Test
    void case10_declinedAndFraudApprovalsNeverMatchButAFraudRemovedOneDoes() {
        long declined = approval(CARD, "50.00", "10", DA, "05", " ");
        posted("50.00", "000000010", DA);
        long fraud = approval(CARD, "51.00", "10", DA, "00", "F");
        posted("51.00", "000000010", DA);
        long removed = approval(CARD, "52.00", "10", DA, "00", "R");
        posted("52.00", "000000010", DA);

        post();

        assertThat(List.of(status(declined), status(fraud), status(removed))).containsExactly("P", "P", "M");
        assertThat(creditBalance()).isEqualByComparingTo("448.00");
    }

    @Test
    void case11And12_onlyPositiveTransactionsTheCyclePostedOrAddedMatch() {
        long billPay = approval("60.00", "11", DA);
        transaction(CARD, "60.00", "000000011", DA, "BILL_PAY", null);
        long interest = approval("61.00", "11", DA);
        transaction(CARD, "61.00", "000000011", DA, "INTEREST", cycle);
        long refund = approval("62.00", "11", DA);
        posted("-62.00", "000000011", DA);
        long otherCycle = approval("63.00", "11", DA);
        long earlier = DB.admin().sql("""
                INSERT INTO batch.cycle (business_date, status, closed_at) OUTPUT inserted.cycle_id
                VALUES ('2022-07-05', 'COMPLETED', SYSUTCDATETIME())""")
                .query(Long.class).single();
        transaction(CARD, "63.00", "000000011", DA, "POSTED", earlier);
        long otherCard = approval("64.00", "11", DA);
        transaction(OTHER_CARD, "64.00", "000000011", DA, "POSTED", cycle);
        long onlineAdd = approval("65.00", "12", DA);
        transaction(CARD, "65.00", "000000012", DA, "ONLINE_ADD", cycle);

        post();

        assertThat(List.of(status(billPay), status(interest), status(refund), status(otherCycle), status(otherCard),
                status(onlineAdd))).containsExactly("P", "P", "P", "P", "P", "M");
        assertThat(creditBalance()).isEqualByComparingTo("435.00");
    }

    @Test
    void case12_anOnlineAddTheCyclePostsMatchesLikeAFileRecord() {
        long fileAuth = approval("70.00", "12", DA);
        long addAuth = approval("71.00", "12", DA);
        dailyFile(DATE, List.of(withId(dailyRecord("70.00", "000000012"), "0000000000000070")));
        waitingAdd(withId(dailyRecord("71.00", "000000012"), "0000000000000071"));
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        JobExecution run = post();

        assertThat(DB.admin().sql("SELECT COUNT(*) FROM core.[transaction] WHERE cycle_id = :c AND created_by = 'USER0001'")
                .param("c", cycle).query(Integer.class).single()).as("the online add, posted").isEqualTo(1);
        assertThat(List.of(status(fileAuth), status(addAuth))).containsExactly("M", "M");
        assertThat(stage(run, StageAuthorizationMatchesTasklet.MATCHED)).isEqualTo(2);
        assertThat(creditBalance()).isEqualByComparingTo("359.00");
    }

    @Test
    void case13_anApprovalMarkedAsFraudAfterTheStagingIsNotReleased() {
        long auth = approval("50.00", "13", DA);
        posted("50.00", "000000013", DA);
        // Runs in the staging transaction, after the pair was chosen: as a fraud mark committed between the steps.
        DB.admin().sql("""
                CREATE TRIGGER batch.tr_test_auth_match_pair ON batch.auth_match_pair AFTER INSERT AS
                UPDATE d SET auth_fraud = 'F' FROM pauth.auth_detail AS d JOIN inserted AS i ON i.auth_id = d.auth_id""")
                .update();

        JobExecution run = post();

        assertThat(stage(run, StageAuthorizationMatchesTasklet.MATCHED)).isEqualTo(1);
        assertThat(apply(run, ApplyAuthorizationMatchesTasklet.CHANGED)).isEqualTo(1);
        assertThat(apply(run, ApplyAuthorizationMatchesTasklet.APPLIED)).isZero();
        assertThat(status(auth)).isEqualTo("P");
        assertThat(creditBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void case14_anOnlineApprovalCommittedWhileTheReleaseRunsIsKept() throws Exception {
        long auth = approval("50.00", "14", DA);
        posted("50.00", "000000014", DA);
        // The release waits here, after marking its approval and before it touches the summary.
        DB.admin().sql("""
                CREATE TRIGGER pauth.tr_test_auth_detail_gate ON pauth.auth_detail AFTER UPDATE AS
                BEGIN
                    DECLARE @r INT;
                    EXEC @r = sp_getapplock @Resource = 'test_d33_case14', @LockMode = 'Shared',
                        @LockOwner = 'Transaction', @LockTimeout = 60000;
                    IF @r < 0 THROW 50000, 'test: the gate timed out', 1;
                END""").update();
        try (Connection gate = DB.adminDataSource().getConnection();
                Connection online = DB.adminDataSource().getConnection()) {
            execute(gate, """
                    DECLARE @r INT;
                    EXEC @r = sp_getapplock @Resource = 'test_d33_case14', @LockMode = 'Exclusive',
                        @LockOwner = 'Session', @LockTimeout = 0;
                    IF @r < 0 THROW 50000, 'test: the gate is taken', 1;""");
            CompletableFuture<Boolean> approvalDuringRelease = CompletableFuture.supplyAsync(() -> {
                try {
                    return approveWhileReleaseIsOpen(online, auth);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    try {
                        execute(gate, "EXEC sp_releaseapplock @Resource = 'test_d33_case14', @LockOwner = 'Session'");
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }
            });

            JobExecution run = post();

            assertThat(approvalDuringRelease.get(60, TimeUnit.SECONDS))
                    .as("the approval committed while the release held its approval").isTrue();
            assertThat(apply(run, ApplyAuthorizationMatchesTasklet.APPLIED)).isEqualTo(1);
        }
        assertThat(status(auth)).isEqualTo("M");
        assertThat(creditBalance()).as("500.00 + 25.00 - 50.00").isEqualByComparingTo("475.00");
        assertThat(DB.admin().sql("SELECT CONCAT(approved_auth_cnt, '/', approved_auth_amt) FROM pauth.auth_summary")
                .query(String.class).single()).isEqualTo("4/525.00");
    }

    /**
     * Once the release has marked {@code auth} (seen uncommitted), commits an approval of 25.00 on the
     * same account as the authorization service does, by atomic increments, and tells whether the
     * release's transaction was still open afterwards (a locking read of the marked row times out).
     */
    private static boolean approveWhileReleaseIsOpen(Connection online, long auth) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!"M".equals(matchStatus(online, auth, "NOLOCK"))) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the release never marked the approval");
            }
            Thread.sleep(20);
        }
        online.setAutoCommit(false);
        try (PreparedStatement insert = online.prepareStatement("""
                INSERT INTO pauth.auth_detail (acct_id, processed_at, auth_date_9c, auth_time_9c, orig_date, orig_time,
                    card_num, auth_type, card_expiry_date, message_type, message_source, auth_id_code, auth_resp_code,
                    auth_resp_reason, processing_code, transaction_amt, approved_amt, merchant_category_code,
                    acqr_country_code, pos_entry_mode, merchant_id, merchant_name, merchant_city, merchant_state,
                    merchant_zip, transaction_id, match_status, auth_fraud)
                SELECT acct_id, processed_at, auth_date_9c, auth_time_9c - 1000, orig_date, orig_time, ?, auth_type,
                    card_expiry_date, message_type, message_source, auth_id_code, auth_resp_code, auth_resp_reason,
                    processing_code, 25.00, 25.00, merchant_category_code, acqr_country_code, pos_entry_mode,
                    merchant_id, merchant_name, merchant_city, merchant_state, merchant_zip, transaction_id, 'P', ' '
                FROM pauth.auth_detail WHERE auth_id = ?""");
                PreparedStatement hold = online.prepareStatement("""
                        UPDATE pauth.auth_summary SET credit_balance = credit_balance + 25.00,
                            approved_auth_cnt = approved_auth_cnt + 1, approved_auth_amt = approved_auth_amt + 25.00
                        WHERE acct_id = ?""")) {
            insert.setString(1, OTHER_CARD);
            insert.setLong(2, auth);
            assertThat(insert.executeUpdate()).isEqualTo(1);
            hold.setString(1, ACCT);
            assertThat(hold.executeUpdate()).isEqualTo(1);
            online.commit();
        } finally {
            online.setAutoCommit(true);
        }
        execute(online, "SET LOCK_TIMEOUT 0");
        try {
            matchStatus(online, auth, "READCOMMITTEDLOCK");
            return false;
        } catch (SQLException e) {
            if (e.getErrorCode() != 1222) {
                throw e;
            }
            return true;
        } finally {
            execute(online, "SET LOCK_TIMEOUT -1");
        }
    }

    private static String matchStatus(Connection c, long auth, String hint) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT match_status FROM pauth.auth_detail WITH (" + hint + ") WHERE auth_id = ?")) {
            s.setLong(1, auth);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
    @Test
    void case15_aRestartInTheMiddleReleasesEachPairExactlyOnce() {
        long first = approval("10.00", "15", DA);
        posted("10.00", "000000015", DA);
        long second = approval("20.00", "15", DA);
        posted("20.00", "000000015", DA);
        long third = approval("30.00", "15", DA);
        posted("30.00", "000000015", DA);
        // The second release fails; one pair per chunk, so the first stays committed.
        DB.admin().sql("""
                CREATE TRIGGER pauth.tr_test_auth_summary ON pauth.auth_summary AFTER UPDATE AS
                IF (SELECT COUNT(*) FROM pauth.auth_detail WHERE match_status = 'M') >= 2
                    THROW 50000, 'test: the release fails', 1""").update();

        JobExecution failed = launch("postDailyTransactionsJob", cycle);

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(List.of(status(first), status(second), status(third))).containsExactly("M", "P", "P");
        assertThat(creditBalance()).isEqualByComparingTo("490.00");
        DB.admin().sql("DROP TRIGGER pauth.tr_test_auth_summary").update();

        JobExecution restarted = post();

        assertThat(List.of(status(first), status(second), status(third))).containsOnly("M");
        assertThat(creditBalance()).isEqualByComparingTo("440.00");
        assertThat(apply(restarted, ApplyAuthorizationMatchesTasklet.APPLIED)).isEqualTo(3);
        assertThat(apply(restarted, ApplyAuthorizationMatchesTasklet.CHANGED)).isZero();
    }

    @Test
    void case16_aSummaryThatDoesNotHoldTheAmountRollsThePairBack() {
        DB.admin().sql("UPDATE pauth.auth_summary SET credit_balance = 10.00").update();
        long auth = approval("50.00", "16", DA);
        posted("50.00", "000000016", DA);

        JobExecution run = post();

        assertThat(apply(run, ApplyAuthorizationMatchesTasklet.INCONSISTENT)).isEqualTo(1);
        assertThat(status(auth)).isEqualTo("P");
        assertThat(matchedTran(auth)).isNull();
        assertThat(creditBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void case17_aMatchedApprovalIsPurgedWithoutASecondReleaseAndAnUnmatchedOneReleasesItsAmount() {
        long matched = approval("50.00", "17", DA);
        posted("50.00", "000000017", DA);
        long unmatched = approval("30.00", "17", DA);
        long fresh = approval("20.00", "17", LocalDateTime.of(2022, 7, 10, 10, 0));
        post();
        assertThat(List.of(status(matched), status(unmatched), status(fresh))).containsExactly("M", "P", "P");
        assertThat(creditBalance()).isEqualByComparingTo("450.00");
        windows.close(WindowHolder.cycle(cycle), true);
        // Expiry 5: on 2022-07-11 the details of 2022-07-05 are expired, the one of 2022-07-10 is not.
        long later = cycles.register(Map.of("businessDate", "2022-07-11", "interest", "never")).cycle().cycleId();

        assertThat(launch("purgePendingAuthorizationsJob", later).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(DB.admin().sql("SELECT auth_id FROM pauth.auth_detail").query(Long.class).list())
                .containsExactly(fresh);
        assertThat(creditBalance()).as("450.00 - 30.00, the unmatched approval only").isEqualByComparingTo("420.00");
        assertThat(DB.admin().sql("SELECT CONCAT(approved_auth_cnt, '/', approved_auth_amt) FROM pauth.auth_summary")
                .query(String.class).single()).as("both deleted approvals leave the totals").isEqualTo("1/420.00");
    }
    @Test
    void case18_aRerunRevertsTheCyclesMatchesAndTheNextRunMatchesThemOnce() {
        long auth = approval("50.00", "000000000123456", DA);
        dailyFile(DATE, List.of(dailyRecord("50.00", "000123456")));
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        post();
        assertThat(status(auth)).isEqualTo("M");
        windows.close(WindowHolder.cycle(cycle), true);

        reruns.rerun(cycle);

        assertThat(status(auth)).isEqualTo("P");
        assertThat(matchedTran(auth)).isNull();
        assertThat(creditBalance()).isEqualByComparingTo("500.00");
        assertThat(DB.admin().sql("SELECT COUNT(*) FROM batch.auth_match_pair WHERE cycle_id = :c").param("c", cycle)
                .query(Integer.class).single()).isZero();

        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        JobExecution again = post();

        assertThat(status(auth)).isEqualTo("M");
        assertThat(creditBalance()).isEqualByComparingTo("450.00");
        assertThat(apply(again, ApplyAuthorizationMatchesTasklet.APPLIED)).isEqualTo(1);
    }

    @Test
    void case19_anApprovalCommittedAfterTheStagingIsNotConsidered() {
        long auth = approval("50.00", "19", DA);
        posted("50.00", "000000019", DA);
        DB.admin().sql("""
                CREATE TRIGGER batch.tr_test_auth_match_pair ON batch.auth_match_pair AFTER INSERT AS
                INSERT INTO pauth.auth_detail (acct_id, processed_at, auth_date_9c, auth_time_9c, orig_date, orig_time,
                    card_num, auth_type, card_expiry_date, message_type, message_source, auth_id_code, auth_resp_code,
                    auth_resp_reason, processing_code, transaction_amt, approved_amt, merchant_category_code,
                    acqr_country_code, pos_entry_mode, merchant_id, merchant_name, merchant_city, merchant_state,
                    merchant_zip, transaction_id, match_status, auth_fraud)
                SELECT acct_id, processed_at, auth_date_9c, auth_time_9c - 1000, orig_date, orig_time, card_num,
                    auth_type, card_expiry_date, message_type, message_source, auth_id_code, auth_resp_code,
                    auth_resp_reason, processing_code, transaction_amt, approved_amt, merchant_category_code,
                    acqr_country_code, pos_entry_mode, merchant_id, merchant_name, merchant_city, merchant_state,
                    merchant_zip, transaction_id, 'P', auth_fraud
                FROM pauth.auth_detail WHERE auth_id IN (SELECT auth_id FROM inserted)""").update();

        post();

        assertThat(status(auth)).isEqualTo("M");
        assertThat(DB.admin().sql("SELECT COUNT(*) FROM pauth.auth_detail WHERE match_status = 'P'")
                .query(Integer.class).single()).as("the late approval").isEqualTo(1);
        assertThat(creditBalance()).isEqualByComparingTo("450.00");
    }

    @Test
    void uniquenessIsJudgedOnBothSides() {
        var a = new StageAuthorizationMatchesTasklet.Pair(1, 10, true);
        var b = new StageAuthorizationMatchesTasklet.Pair(2, 10, true);
        var c = new StageAuthorizationMatchesTasklet.Pair(3, 11, true);
        var d = new StageAuthorizationMatchesTasklet.Pair(3, 12, true);
        var e = new StageAuthorizationMatchesTasklet.Pair(4, 13, true);

        assertThat(StageAuthorizationMatchesTasklet.unique(List.of(a, b, c, d, e))).containsExactly(e);
    }
}
