package org.carddemo.authorization.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.carddemo.authorization.AuthorizationTestBase;
import org.carddemo.authorization.decision.Reason;
import org.carddemo.authorization.request.AuthorizationRequest;
import org.carddemo.authorization.request.MessageKey;
import org.carddemo.authorization.request.RequestParser;
import org.carddemo.platform.testsupport.CoreFixtures.Account;
import org.carddemo.platform.testsupport.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The listener's transaction against the migrated database as the authorization login (contract
 * §6.2, §11 M1 to M11, M20, M21, M27, M30 to M38). Every test that writes a detail needs backend's
 * {@code V505} grant {@code UPDATE (auth_id_code)}; until it is merged those tests fail.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthorizationTransactionTest extends AuthorizationTestBase {

    static final String CARD = "4111111111111111";
    static final String ACCT = "00000000091";
    static final String CUST = "000000091";
    static final String TRAN = "TRN000000000001";
    static final ReplyRoute ROUTE = new ReplyRoute("", "amq.gen-reply", "corr-1");

    @Autowired
    AuthorizationProcessor processor;

    final JdbcClient admin = DB.admin();

    record Summary(String custId, BigDecimal creditLimit, BigDecimal cashLimit, BigDecimal creditBalance,
            BigDecimal cashBalance, int approvedCnt, BigDecimal approvedAmt, int declinedCnt, BigDecimal declinedAmt) {
    }

    record Detail(long authId, String authIdCode, String respCode, String reason, BigDecimal transactionAmt,
            BigDecimal approvedAmt, String matchStatus, LocalDateTime processedAt, int time9c) {
    }

    @BeforeEach
    void fixtures() {
        TestClock.shared().reset();
        DB.fixtures().deleteAll().account(account(ACCT, "1000.00", "200.00")).customer(CUST).card(CARD, ACCT, CUST);
    }

    static Account account(String acctId, String limit, String currBal) {
        return new Account(acctId, "Y", new BigDecimal(currBal), new BigDecimal(limit), new BigDecimal("300.00"),
                LocalDate.of(2014, 11, 20), LocalDate.of(2030, 5, 20), LocalDate.of(2025, 5, 20),
                new BigDecimal("0.00"), new BigDecimal("0.00"), "A000000000", "");
    }

    static AuthorizationRequest request(String card, String amount, String tranId) throws Exception {
        return request(card, amount, tranId, "GROCERY STORE", "SPRINGFIELD", "0426");
    }

    static AuthorizationRequest request(String card, String amount, String tranId, String merchantName,
            String merchantCity, String expiry) throws Exception {
        String body = String.join(",", "261106", "093015", card, "SALE", expiry, "0100", "POS", "000000", amount,
                "5411", "840", "01", "MERCH0000000001", merchantName, merchantCity, "IL", "62701", tranId);
        return RequestParser.parse(body.getBytes(StandardCharsets.UTF_8), "text/csv");
    }

    Outcome send(AuthorizationRequest r) {
        return processor.process(r, MessageKey.of(UUID.randomUUID().toString(), r), ROUTE);
    }

    Outcome send(String card, String amount) throws Exception {
        return send(request(card, amount, TRAN));
    }

    Optional<Summary> summary(String acctId) {
        return admin.sql("""
                        SELECT cust_id, credit_limit, cash_limit, credit_balance, cash_balance, approved_auth_cnt,
                            approved_auth_amt, declined_auth_cnt, declined_auth_amt
                        FROM pauth.auth_summary WHERE acct_id = :acct""")
                .param("acct", acctId)
                .query((rs, n) -> new Summary(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getInt(6), rs.getBigDecimal(7), rs.getInt(8),
                        rs.getBigDecimal(9)))
                .optional();
    }

    List<Detail> details(String acctId) {
        return admin.sql("""
                        SELECT auth_id, auth_id_code, auth_resp_code, auth_resp_reason, transaction_amt, approved_amt,
                            match_status, processed_at, auth_time_9c
                        FROM pauth.auth_detail WHERE acct_id = :acct ORDER BY auth_id""")
                .param("acct", acctId)
                .query((rs, n) -> new Detail(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7),
                        rs.getObject(8, LocalDateTime.class), rs.getInt(9)))
                .list();
    }

    static String code(long authId) {
        return String.format("%06d", authId % 1_000_000);
    }

    static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("M1: an approval creates the summary, the detail P with its code, the outbox row and the key")
    void approvalWithoutSummary() throws Exception {
        Outcome o = send(CARD, "50.00");

        List<Detail> details = details(ACCT);
        assertThat(details).hasSize(1);
        Detail d = details.get(0);
        assertThat(o.authId()).isEqualTo(d.authId());
        assertThat(o.reply().toCsv()).isEqualTo(CARD + "," + TRAN + "," + code(d.authId()) + ",00,0000,         50.00,");
        assertThat(d.authIdCode()).isEqualTo(code(d.authId()));
        assertThat(d.matchStatus()).isEqualTo("P");
        assertThat(d.processedAt()).isEqualTo(LocalDateTime.of(2026, 11, 6, 9, 30, 15, 654_000_000));
        assertThat(summary(ACCT)).contains(new Summary(CUST, amount("1000.00"), amount("300.00"), amount("50.00"),
                amount("0.00"), 1, amount("50.00"), 0, amount("0.00")));
        assertThat(admin.sql("""
                        SELECT exchange_name, routing_key, correlation_id, content_type, expiration_ms, body, attempts,
                            CASE WHEN sent_at IS NULL THEN 'unsent' ELSE 'sent' END
                        FROM pauth.outbox WHERE outbox_id = :id""")
                .param("id", o.outboxId())
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)))
                .single())
                .containsExactly("", "amq.gen-reply", "corr-1", "text/csv", "5000", o.body(), "0", "unsent");
        assertThat(admin.sql("SELECT auth_id, reply_body FROM pauth.processed_message")
                .query((rs, n) -> rs.getLong(1) + "|" + rs.getString(2)).list())
                .containsExactly(d.authId() + "|" + o.reply().toCsv());
    }

    @Test
    @DisplayName("M2, M3: the pending approval counts; equality approves, a cent more declines 4100")
    void availableCreditBoundary() throws Exception {
        send(CARD, "50.00");
        assertThat(send(CARD, "750.01").reply().toCsv()).endsWith(",05,4100,          0.00,");
        assertThat(send(CARD, "750.00").decision().reason()).isEqualTo(Reason.APPROVED);
        assertThat(summary(ACCT)).contains(new Summary(CUST, amount("1000.00"), amount("300.00"), amount("800.00"),
                amount("0.00"), 2, amount("800.00"), 1, amount("750.01")));
        assertThat(details(ACCT)).extracting(Detail::matchStatus).containsExactly("P", "D", "P");
        assertThat(details(ACCT).get(1).transactionAmt()).isEqualByComparingTo("750.01");
        assertThat(details(ACCT).get(1).approvedAmt()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("M4, M28: an unknown card is declined 3100 with code 000000, nothing but the reply and the key")
    void unknownCard(CapturedOutput output) throws Exception {
        Outcome o = send("4000000000000002", "50.00");
        assertThat(o.reply().toCsv()).isEqualTo("4000000000000002," + TRAN + ",000000,05,3100,          0.00,");
        assertThat(admin.sql("SELECT COUNT(*) FROM pauth.auth_summary").query(Integer.class).single()).isZero();
        assertThat(admin.sql("SELECT COUNT(*) FROM pauth.auth_detail").query(Integer.class).single()).isZero();
        assertThat(admin.sql("SELECT COUNT(*) FROM pauth.processed_message WHERE auth_id IS NULL")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(output).contains("A001", "************0002").doesNotContain("4000000000000002");
    }

    @Test
    @DisplayName("M5: an unknown card right after an approval on another account is still declined 3100")
    void unknownCardAfterAnApproval() throws Exception {
        assertThat(send(CARD, "50.00").decision().approved()).isTrue();
        assertThat(send("4000000000000002", "50.00").reply().toCsv()).endsWith(",000000,05,3100,          0.00,");
    }

    @Test
    @DisplayName("M6: a cross-reference whose account row is missing is declined 3100 without any write")
    void accountMissing() throws Exception {
        String card = "4000000000000010";
        withoutForeignKey("FK_card_xref_account", card, () -> {
            admin.sql("INSERT INTO core.card_xref (card_num, cust_id, acct_id) VALUES (:card, :cust, '00000000092')")
                    .param("card", card).param("cust", CUST).update();
            Outcome o = send(card, "50.00");
            assertThat(o.decision().reason()).isEqualTo(Reason.INVALID_CARD);
            assertThat(o.authId()).isNull();
            assertThat(admin.sql("SELECT COUNT(*) FROM pauth.auth_summary").query(Integer.class).single()).isZero();
            assertThat(admin.sql("SELECT COUNT(*) FROM pauth.auth_detail").query(Integer.class).single()).isZero();
        });
    }

    @Test
    @DisplayName("M7: a missing customer is declined 3100; the summary counts the decline and the detail is D")
    void customerMissing() throws Exception {
        String card = "4000000000000028";
        withoutForeignKey("FK_card_xref_customer", card, () -> {
            admin.sql("INSERT INTO core.card_xref (card_num, cust_id, acct_id) VALUES (:card, '000000092', :acct)")
                    .param("card", card).param("acct", ACCT).update();
            Outcome o = send(card, "50.00");
            assertThat(o.decision().reason()).isEqualTo(Reason.INVALID_CARD);
            assertThat(summary(ACCT)).contains(new Summary("000000092", amount("1000.00"), amount("300.00"),
                    amount("0.00"), amount("0.00"), 0, amount("0.00"), 1, amount("50.00")));
            assertThat(details(ACCT)).extracting(Detail::matchStatus, Detail::reason).containsExactly(
                    tuple("D", "3100"));
        });
    }

    /**
     * The foreign keys make M6 and M7 impossible with the constraint on; it is switched off for the
     * test and trusted again (WITH CHECK) once the orphan cross-reference row is gone.
     */
    void withoutForeignKey(String constraint, String orphanCard, ThrowingRunnable work) throws Exception {
        admin.sql("ALTER TABLE core.card_xref NOCHECK CONSTRAINT " + constraint).update();
        try {
            work.run();
        } finally {
            admin.sql("DELETE FROM core.card_xref WHERE card_num = :card").param("card", orphanCard).update();
            admin.sql("ALTER TABLE core.card_xref WITH CHECK CHECK CONSTRAINT " + constraint).update();
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    @DisplayName("M8: an inactive card, and a card that expired yesterday, are declined 4200")
    void cardNotActive() throws Exception {
        admin.sql("UPDATE core.card SET active_status = 'N' WHERE card_num = :card").param("card", CARD).update();
        assertThat(send(CARD, "50.00").decision().reason()).isEqualTo(Reason.CARD_NOT_ACTIVE);
        admin.sql("UPDATE core.card SET active_status = 'Y', expiration_date = '2026-11-05' WHERE card_num = :card")
                .param("card", CARD).update();
        assertThat(send(CARD, "50.00").decision().reason()).isEqualTo(Reason.CARD_NOT_ACTIVE);
        admin.sql("UPDATE core.card SET expiration_date = '2026-11-06' WHERE card_num = :card")
                .param("card", CARD).update();
        assertThat(send(CARD, "50.00").decision().reason()).isEqualTo(Reason.APPROVED);
    }

    @Test
    @DisplayName("M9: a closed account is declined 4300")
    void accountClosed() throws Exception {
        admin.sql("UPDATE core.account SET active_status = 'N' WHERE acct_id = :acct").param("acct", ACCT).update();
        assertThat(send(CARD, "50.00").decision().reason()).isEqualTo(Reason.ACCOUNT_CLOSED);
    }

    @Test
    @DisplayName("M10: a confirmed fraud row declines 5100; once removed (R) the card is approved")
    void confirmedFraud() throws Exception {
        admin.sql("""
                        INSERT INTO pauth.auth_fraud (auth_id, card_num, auth_ts, auth_fraud, fraud_rpt_date, acct_id,
                            reported_by)
                        VALUES (999, :card, '2026-11-01T10:00:00', 'F', '2026-11-01', :acct, 'USER0001')""")
                .param("card", CARD).param("acct", ACCT).update();
        assertThat(send(CARD, "50.00").decision().reason()).isEqualTo(Reason.CARD_FRAUD);
        admin.sql("UPDATE pauth.auth_fraud SET auth_fraud = 'R' WHERE auth_id = 999").update();
        assertThat(send(CARD, "50.00").decision().reason()).isEqualTo(Reason.APPROVED);
    }

    @Test
    @DisplayName("M11: an inactive card asking for more than is available is declined 4100 (priority)")
    void priority() throws Exception {
        admin.sql("UPDATE core.card SET active_status = 'N' WHERE card_num = :card").param("card", CARD).update();
        assertThat(send(CARD, "800.01").decision().reason()).isEqualTo(Reason.INSUFFICIENT_FUND);
    }

    @Test
    @DisplayName("M20: an approval and a matching release on one account both land, whichever waits")
    void approvalDuringRelease() throws Exception {
        send(CARD, "100.00");
        TransactionTemplate release = new TransactionTemplate(new DataSourceTransactionManager(DB.adminDataSource()));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Outcome> approval = release.execute(status -> {
                JdbcClient inTx = JdbcClient.create(DB.adminDataSource());
                assertThat(inTx.sql("""
                                UPDATE pauth.auth_summary SET credit_balance = credit_balance - 40.00
                                WHERE acct_id = :acct AND credit_balance >= 40.00""")
                        .param("acct", ACCT).update()).isEqualTo(1);
                Future<Outcome> f = pool.submit(() -> send(CARD, "25.00"));
                awaitLockWait();
                return f;
            });
            assertThat(approval.get(30, TimeUnit.SECONDS).decision().approved()).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(summary(ACCT).orElseThrow().creditBalance()).isEqualByComparingTo("85.00");
    }

    /** Until a request of this database waits for a lock: the listener is blocked on the summary. */
    void awaitLockWait() {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (admin.sql("""
                            SELECT COUNT(*) FROM sys.dm_tran_locks
                            WHERE resource_database_id = DB_ID() AND request_status = 'WAIT'""")
                    .query(Integer.class).single() > 0) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("the listener never waited for the summary lock");
    }

    @Test
    @DisplayName("M21: 20 concurrent requests when the available credit covers 10: exactly 10 approved")
    void concurrentRequests() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(21);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> results = new ArrayList<>();
        // The requests run one after the other behind the summary lock; a clock that moves keeps
        // their details from all landing in one millisecond, which only a frozen test clock causes.
        AtomicBoolean ticking = new AtomicBoolean(true);
        pool.submit(() -> {
            while (ticking.get()) {
                TestClock.shared().advance(Duration.ofMillis(1));
                Thread.sleep(1);
            }
            return null;
        });
        try {
            for (int i = 0; i < 20; i++) {
                String tranId = String.format("TRN%012d", i);
                results.add(pool.submit(() -> {
                    start.await();
                    return send(request(CARD, "80.00", tranId));
                }));
            }
            start.countDown();
            int approved = 0;
            for (Future<Outcome> f : results) {
                if (f.get(60, TimeUnit.SECONDS).decision().approved()) {
                    approved++;
                }
            }
            assertThat(approved).isEqualTo(10);
        } finally {
            ticking.set(false);
            pool.shutdownNow();
        }
        Summary s = summary(ACCT).orElseThrow();
        assertThat(s.creditBalance()).isEqualByComparingTo("800.00");
        assertThat(s.approvedCnt()).isEqualTo(10);
        assertThat(s.declinedCnt()).isEqualTo(10);
        assertThat(s.declinedAmt()).isEqualByComparingTo("800.00");
        assertThat(details(ACCT)).hasSize(20);
    }

    @Test
    @DisplayName("M27: two requests for one account in the same millisecond are both stored, 1 ms apart")
    void sameMillisecond() throws Exception {
        send(request(CARD, "10.00", "TRN000000000001"));
        send(request(CARD, "10.00", "TRN000000000002"));
        List<Detail> details = details(ACCT);
        assertThat(details).hasSize(2);
        assertThat(details.get(1).processedAt()).isEqualTo(details.get(0).processedAt().plusNanos(1_000_000));
        assertThat(details.get(0).time9c() - details.get(1).time9c()).isEqualTo(1);
    }

    @Test
    @DisplayName("M30 (V505): a merchant name of 22 × É is stored unchanged")
    void fullWidthAccentedName() throws Exception {
        String name = "É".repeat(22);
        send(request(CARD, "10.00", TRAN, name, "SPRINGFIELD", "0426"));
        assertThat(admin.sql("SELECT merchant_name FROM pauth.auth_detail").query(String.class).single())
                .isEqualTo(name);
    }

    @Test
    @DisplayName("M31, M32 (V505): characters above U+00FF are stored as one ? each")
    void charactersAboveLatin1() throws Exception {
        send(request(CARD, "10.00", TRAN, "SHOP\uD83D\uDE00", "中CITY", "0426"));
        assertThat(admin.sql("SELECT merchant_name + '|' + merchant_city FROM pauth.auth_detail")
                .query(String.class).single()).isEqualTo(String.format("%-22s|%-13s", "SHOP?", "?CITY"));
    }

    @Test
    @DisplayName("M33 (V505): a transaction ID holding U+0085 and NUL is stored and replied unchanged")
    void controlCharactersInTheTransactionId() throws Exception {
        String tranId = "TRN\u0085\u0000ABCDEFGHIJ";
        Outcome o = send(request(CARD, "10.00", tranId));
        assertThat(o.reply().toCsv()).hasSize(63).contains("," + tranId + ",");
        assertThat(admin.sql("SELECT transaction_id FROM pauth.auth_detail").query(String.class).single())
                .isEqualTo(tranId);
    }

    @Test
    @DisplayName("M34: the request's card expiry is not compared; the card's own date alone gives 4200")
    void requestExpiryIgnored() throws Exception {
        assertThat(send(request(CARD, "10.00", TRAN, "GROCERY STORE", "SPRINGFIELD", "0101")).decision().approved())
                .isTrue();
        admin.sql("UPDATE core.card SET expiration_date = '2026-11-05' WHERE card_num = :card")
                .param("card", CARD).update();
        assertThat(send(request(CARD, "10.00", TRAN, "GROCERY STORE", "SPRINGFIELD", "1299")).decision().reason())
                .isEqualTo(Reason.CARD_NOT_ACTIVE);
    }

    @Test
    @DisplayName("M35: an approval on an account whose summary the purge deleted starts a new summary")
    void summaryDeletedByThePurge() throws Exception {
        send(CARD, "100.00");
        // As the purge does: the details first, since the foreign key has no cascade (V507).
        admin.sql("DELETE FROM pauth.auth_detail WHERE acct_id = :acct").param("acct", ACCT).update();
        admin.sql("DELETE FROM pauth.auth_summary WHERE acct_id = :acct").param("acct", ACCT).update();
        send(CARD, "30.00");
        Summary s = summary(ACCT).orElseThrow();
        assertThat(s.creditBalance()).isEqualByComparingTo("30.00");
        assertThat(s.approvedCnt()).isEqualTo(1);
    }

    @Test
    @DisplayName("M36: a credit limit of 9,999,999,999.99 is copied into the summary")
    void widestLimit() throws Exception {
        admin.sql("UPDATE core.account SET credit_limit = 9999999999.99, curr_bal = 0 WHERE acct_id = :acct")
                .param("acct", ACCT).update();
        assertThat(send(CARD, "9999999999.99").decision().approved()).isTrue();
        Summary s = summary(ACCT).orElseThrow();
        assertThat(s.creditLimit()).isEqualByComparingTo("9999999999.99");
        assertThat(s.creditBalance()).isEqualByComparingTo("9999999999.99");
    }

    @Test
    @DisplayName("M37: auth_id_code is the six rightmost digits of auth_id, across a million boundary")
    void authIdCode() throws Exception {
        long last = admin.sql("SELECT CAST(ISNULL(IDENT_CURRENT('pauth.auth_detail'), 0) AS BIGINT)")
                .query(Long.class).single();
        // Upwards only, so no ID comes back. The first ID is the reseed value or the one after it,
        // depending on whether the table ever had a row; either way the three cross the boundary.
        long boundary = (last / 1_000_000 + 2) * 1_000_000;
        admin.sql("DBCC CHECKIDENT ('pauth.auth_detail', RESEED, " + (boundary - 2) + ") WITH NO_INFOMSGS").update();
        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TestClock.shared().advance(Duration.ofMillis(1));
            outcomes.add(send(CARD, "1.00"));
        }
        assertThat(outcomes).allSatisfy(o -> assertThat(o.reply().authIdCode()).isEqualTo(code(o.authId())));
        assertThat(outcomes).extracting(o -> o.reply().authIdCode()).contains("000000");
        assertThat(details(ACCT)).allSatisfy(d -> assertThat(d.authIdCode()).isEqualTo(code(d.authId())));
    }

    @Test
    @DisplayName("M38: the key reads seek the card_xref, card and auth_summary indexes")
    void keyReadsSeek() throws Exception {
        send(CARD, "10.00");
        for (String table : List.of("core.card_xref", "core.card", "pauth.auth_summary")) {
            List<String> plans = admin.sql("""
                            SELECT CAST(p.query_plan AS NVARCHAR(MAX))
                            FROM sys.dm_exec_query_stats s
                            CROSS APPLY sys.dm_exec_sql_text(s.sql_handle) t
                            CROSS APPLY sys.dm_exec_query_plan(s.plan_handle) p
                            CROSS APPLY sys.dm_exec_plan_attributes(s.plan_handle) a
                            WHERE a.attribute = 'dbid' AND CAST(a.value AS INT) = DB_ID()
                              AND t.text LIKE '%FROM ' + :table + ' %' AND t.text LIKE '%CAST(%@P0 AS CHAR(%'
                              AND t.text NOT LIKE '%dm_exec_query_stats%'""")
                    .param("table", table)
                    .query(String.class).list();
            assertThat(plans).as(table).isNotEmpty();
            assertThat(plans).as(table).allSatisfy(plan -> {
                assertThat(plan).containsPattern("PhysicalOp=\"(Clustered )?Index Seek\"");
                assertThat(plan).doesNotContain("PhysicalOp=\"Index Scan\"", "PhysicalOp=\"Clustered Index Scan\"",
                        "PhysicalOp=\"Table Scan\"");
            });
        }
    }
}
