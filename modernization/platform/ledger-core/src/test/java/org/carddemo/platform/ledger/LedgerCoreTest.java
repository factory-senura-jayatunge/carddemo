package org.carddemo.platform.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.fidelity.FidelityAutoConfiguration;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs as the Bill Pay login, and "copy last" as the transaction login, so every statement also
 * proves the grants of the real callers suffice.
 */
class LedgerCoreTest {

    static final String ACCT = "00000000001";
    static final String CARD = "4111111111111111";

    private final TestDatabase db = TestDatabase.shared();
    private final JdbcClient admin = db.admin();

    private ApplicationContextRunner runner(ServiceLogin login, String tranIdMode) {
        return runner(db.dataSource(login), tranIdMode);
    }

    private ApplicationContextRunner runner(DataSource dataSource, String tranIdMode) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceTransactionManagerAutoConfiguration.class,
                        TransactionAutoConfiguration.class, FidelityAutoConfiguration.class, LedgerAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource)
                .withPropertyValues("carddemo.ledger.dir=" + System.getProperty("java.io.tmpdir") + "/no-ledger",
                        "carddemo.fidelity.BR-TRNA-003=" + tranIdMode);
    }

    private static <T> T inTransaction(AssertableApplicationContext ctx, java.util.function.Supplier<T> work) {
        return new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class)).execute(status -> work.get());
    }

    private static void inTransaction(AssertableApplicationContext ctx, Runnable work) {
        inTransaction(ctx, () -> {
            work.run();
            return null;
        });
    }

    private void run(String mode, Consumer<AssertableApplicationContext> test) {
        run(ServiceLogin.BILLPAY, mode, test);
    }

    private void run(ServiceLogin login, String mode, Consumer<AssertableApplicationContext> test) {
        runner(login, mode).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            test.accept(ctx);
        });
    }

    @BeforeEach
    void data() {
        db.fixtures().deleteAll()
                .account(CoreFixtures.Account.of(ACCT, "194.00").withCycleTotals("10.00", "-5.00"))
                .customer("000000001")
                .card(CARD, ACCT, "000000001");
    }

    private static NewTransaction payment(String tranId, String amount) {
        LocalDateTime now = LocalDateTime.of(2026, 11, 6, 9, 30, 15);
        return new NewTransaction(tranId, "02", "0002", "POS TERM", "BILL PAYMENT - ONLINE", new BigDecimal(amount),
                CARD, "999999999", "BILL PAYMENT", "N/A", "N/A", now, now, Origin.BILL_PAY, "USER0001");
    }

    @Test
    @SpecItem({"BR-BILL-004", "BR-TRNA-003"})
    void legacyIdIsHighestMasterIdPlusOne() {
        run("legacy", ctx -> {
            TransactionIds ids = ctx.getBean(TransactionIds.class);
            assertThat(inTransaction(ctx, ids::next)).isEqualTo("0000000000000001");
            db.fixtures().transaction("0000000000000041", CARD, "1.00").transaction("0000000000000007", CARD, "1.00");
            assertThat(inTransaction(ctx, ids::next)).isEqualTo("0000000000000042");
        });
    }

    @Test
    @SpecItem({"BR-TRNA-003", "NUM-BILL-005"})
    void fixedIdComesFromTheSequence() {
        run("fixed", ctx -> {
            db.fixtures().transaction("0000000000000041", CARD, "1.00").restartTranIdSequence(9_000_000_000_000_000L);
            TransactionIds ids = ctx.getBean(TransactionIds.class);
            assertThat(inTransaction(ctx, ids::next)).isEqualTo("9000000000000000");
            assertThat(inTransaction(ctx, ids::next)).isEqualTo("9000000000000001");
            assertThat(JdbcTransactionIds.format(9_999_999_999_999_999L)).isEqualTo("9999999999999999");
            assertThatThrownBy(() -> JdbcTransactionIds.format(10_000_000_000_000_000L)).isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    @SpecItem({"BR-TRNA-003", "BR-BILL-004"})
    void legacyIdCountsOnlineAddsWaitingForTheNextCycle() {
        run("legacy", ctx -> {
            TransactionIds ids = ctx.getBean(TransactionIds.class);
            db.fixtures().transaction("0000000000000041", CARD, "1.00").waitingOnlineAdd("0000000000000030", CARD, "1.00");
            assertThat(inTransaction(ctx, ids::next)).isEqualTo("0000000000000042");
            db.fixtures().waitingOnlineAdd("0000000000000050", CARD, "1.00");
            assertThat(inTransaction(ctx, ids::next)).isEqualTo("0000000000000051");
            admin.sql("DELETE FROM core.[transaction]").update();
            assertThat(inTransaction(ctx, ids::next)).isEqualTo("0000000000000051");
        });
    }

    @Test
    @SpecItem("BR-TRNA-003")
    void repeatedOnlineAddsThenABillPaymentTakeConsecutiveLegacyIds() {
        run("legacy", ctx -> {
            TransactionIds ids = ctx.getBean(TransactionIds.class);
            db.fixtures().transaction("0000000000000041", CARD, "1.00");
            for (String expected : new String[] {"0000000000000042", "0000000000000043", "0000000000000044"}) {
                String id = inTransaction(ctx, ids::next);
                assertThat(id).isEqualTo(expected);
                db.fixtures().waitingOnlineAdd(id, CARD, "1.00");
            }
            String paymentId = inTransaction(ctx, () -> {
                String id = ids.next();
                ctx.getBean(TransactionWriter.class).insert(payment(id, "1.00"));
                return id;
            });
            assertThat(paymentId).isEqualTo("0000000000000045");
            assertThat(inTransaction(ctx, ids::next)).isEqualTo("0000000000000046");
        });
    }

    @Test
    @SpecItem("BR-TRNA-003")
    void legacyIdIgnoresClaimedRowsAndCountsAPostedAddOnce() {
        run("legacy", ctx -> {
            TransactionIds ids = ctx.getBean(TransactionIds.class);
            db.fixtures().transaction("0000000000000041", CARD, "1.00")
                    .claimedDailyRow("2022-07-06", "DAILY_FILE", "0000000000000090", CARD)
                    .claimedDailyRow("2022-07-06", "ONLINE_ADD", "0000000000000095", CARD);
            assertThat(inTransaction(ctx, ids::next)).isEqualTo("0000000000000042");
            db.fixtures().transaction("0000000000000095", CARD, "1.00");
            assertThat(inTransaction(ctx, ids::next)).isEqualTo("0000000000000096");
        });
    }

    @Test
    @SpecItem({"BR-TRNA-003", "NUM-BILL-005"})
    void fixedIdIgnoresWaitingOnlineAdds() {
        run("fixed", ctx -> {
            db.fixtures().waitingOnlineAdd("0000000000000050", CARD, "1.00").restartTranIdSequence(9_000_000_000_000_007L);
            assertThat(inTransaction(ctx, ctx.getBean(TransactionIds.class)::next)).isEqualTo("9000000000000007");
        });
    }

    @Test
    @SpecItem({"NUM-BILL-005", "NUM-TRNA-004", "BR-TRNA-003"})
    void fixedSequenceIssuesTheLastIdOnceThenNeverWraps() {
        run("fixed", ctx -> {
            db.fixtures().restartTranIdSequence(9_999_999_999_999_999L);
            TransactionIds ids = ctx.getBean(TransactionIds.class);
            assertThat(inTransaction(ctx, ids::next)).isEqualTo("9999999999999999");
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() -> inTransaction(ctx, ids::next))
                        .isInstanceOf(TranIdExhaustedException.class)
                        .hasRootCauseInstanceOf(java.sql.SQLException.class);
            }
            assertThat(admin.sql("""
                            SELECT CAST(current_value AS BIGINT) FROM sys.sequences
                            WHERE object_id = OBJECT_ID('core.tran_id_seq')""")
                    .query(Long.class).single()).isEqualTo(9_999_999_999_999_999L);
        });
    }

    @Test
    @SpecItem({"NUM-BILL-005", "NUM-TRNA-004", "BR-TRNA-003"})
    void legacyHighestPlusOneStopsAtTheLastIdAndReleasesTheLock() {
        run("legacy", ctx -> {
            TransactionIds ids = ctx.getBean(TransactionIds.class);
            db.fixtures().transaction("9999999999999998", CARD, "1.00");
            String last = inTransaction(ctx, () -> {
                String id = ids.next();
                ctx.getBean(TransactionWriter.class).insert(payment(id, "1.00"));
                return id;
            });
            assertThat(last).isEqualTo("9999999999999999");
            assertThatThrownBy(() -> inTransaction(ctx, ids::next)).isInstanceOf(TranIdExhaustedException.class);

            // A waiting add holding the last ID counts as well.
            admin.sql("DELETE FROM core.[transaction] WHERE tran_id = '9999999999999999'").update();
            db.fixtures().waitingOnlineAdd("9999999999999999", CARD, "1.00");
            assertThatThrownBy(() -> inTransaction(ctx, ids::next)).isInstanceOf(TranIdExhaustedException.class);

            // The refused call rolled back, so the ID lock is free for the next writer.
            admin.sql("DELETE FROM core.daily_tran").update();
            assertThat(inTransaction(ctx, ids::next)).isEqualTo("9999999999999999");
        });
    }

    @Test
    @SpecItem({"BR-TRNA-006", "BR-TRNA-003"})
    void copyLatestReadsTheMasterAndTheWaitingAddsInBothModes() {
        for (String mode : new String[] {"legacy", "fixed"}) {
            db.fixtures().deleteAll()
                    .account(CoreFixtures.Account.of(ACCT, "194.00"))
                    .customer("000000001")
                    .card(CARD, ACCT, "000000001")
                    .restartTranIdSequence(9_000_000_000_000_000L);
            run(ServiceLogin.TRANSACTION, mode, ctx -> {
                LatestTransactions latest = ctx.getBean(LatestTransactions.class);
                assertThat(latest.latest()).isEmpty();

                db.fixtures().transaction("0000000000000041", CARD, "12.34");
                assertThat(latest.latest()).contains(new LatestTransaction("0000000000000041", "01", "0001",
                        "POS TERM", "FIXTURE PURCHASE", new BigDecimal("12.34"), "000000001", "FIXTURE", "TOWN",
                        "10001", "2022-07-05", "2022-07-05", false));

                db.fixtures().waitingOnlineAdd("0000000000000050", CARD, "56.78")
                        .waitingOnlineAdd("0000000000000045", CARD, "1.00");
                LatestTransaction add = new LatestTransaction("0000000000000050", "03", "0005", "OPERATOR",
                        "ONLINE ADD 0000000000000050", new BigDecimal("56.78"), "000000002", "ADDED MERCHANT",
                        "CITY", "20002", "2026-11-05", "2026-11-06", true);
                assertThat(latest.latest()).contains(add);

                // Rows of a cycle are not candidates until posting puts them in the master.
                db.fixtures().claimedDailyRow("2022-07-06", "DAILY_FILE", "0000000000000090", CARD)
                        .claimedDailyRow("2022-07-06", "ONLINE_ADD", "0000000000000095", CARD);
                assertThat(latest.latest()).contains(add);
                db.fixtures().transaction("0000000000000095", CARD, "9.50");
                assertThat(latest.latest()).get().extracting(LatestTransaction::tranId, LatestTransaction::waiting)
                        .containsExactly("0000000000000095", false);

                // The ID allocation runs under the same login over the same set.
                String next = inTransaction(ctx, ctx.getBean(TransactionIds.class)::next);
                assertThat(next).isEqualTo(mode.equals("legacy") ? "0000000000000096" : "9000000000000000");
            });
        }
    }

    @Test
    @SpecItem("BR-TRNA-006")
    void copyLatestPrefersTheMasterRowOnATie() {
        run(ServiceLogin.TRANSACTION, "legacy", ctx -> {
            db.fixtures().transaction("0000000000000050", CARD, "1.00").waitingOnlineAdd("0000000000000050", CARD, "2.00");
            for (int i = 0; i < 5; i++) {
                assertThat(ctx.getBean(LatestTransactions.class).latest()).get()
                        .extracting(LatestTransaction::waiting).isEqualTo(false);
            }
        });
    }

    @Test
    void everyOperationRequiresATransaction() {
        run("legacy", ctx -> {
            assertThatThrownBy(() -> ctx.getBean(TransactionIds.class).next()).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(TransactionWriter.class).insert(payment("0000000000000001", "1.00")))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(BalancePosting.class).balanceOnly(ACCT, BigDecimal.ONE))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(BatchWindowGuard.class).requireWritesAllowed())
                    .isInstanceOf(IllegalTransactionStateException.class);
        });
    }

    @Test
    @SpecItem({"DEF-BILL-003", "BR-BILL-004"})
    void legacyIdsOfConcurrentWritersAreDistinct() {
        run("legacy", ctx -> {
            TransactionIds ids = ctx.getBean(TransactionIds.class);
            TransactionWriter writer = ctx.getBean(TransactionWriter.class);
            CountDownLatch firstHasId = new CountDownLatch(1);
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> inTransaction(ctx, () -> {
                String id = ids.next();
                firstHasId.countDown();
                sleep(400);
                writer.insert(payment(id, "1.00"));
                return id;
            }));
            CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
                await(firstHasId);
                return inTransaction(ctx, () -> {
                    String id = ids.next();
                    writer.insert(payment(id, "2.00"));
                    return id;
                });
            });
            assertThat(first.join()).isEqualTo("0000000000000001");
            assertThat(second.join()).isEqualTo("0000000000000002");
        });
    }

    @Test
    void writerStoresEveryColumnAndRejectsADuplicateMasterId() {
        run("legacy", ctx -> {
            TransactionWriter writer = ctx.getBean(TransactionWriter.class);
            long pk = inTransaction(ctx, () -> writer.insert(payment("0000000000000005", "-194.00")));
            Map<String, Object> row = admin.sql("""
                    SELECT tran_id, type_cd, cat_cd, source, description, amount, merchant_id, merchant_name,
                        merchant_city, merchant_zip, card_num, CONVERT(VARCHAR(26), orig_ts, 121) AS orig_text,
                        CONVERT(VARCHAR(26), proc_ts, 121) AS proc_text, proc_date, legacy_filler, origin, cycle_id,
                        in_master, created_by
                    FROM core.[transaction] WHERE tran_pk = :pk""").param("pk", pk).query().singleRow();
            assertThat(row).containsEntry("tran_id", "0000000000000005").containsEntry("type_cd", "02")
                    .containsEntry("cat_cd", "0002").containsEntry("source", "POS TERM")
                    .containsEntry("description", "BILL PAYMENT - ONLINE").containsEntry("amount", new BigDecimal("-194.00"))
                    .containsEntry("merchant_id", "999999999").containsEntry("merchant_name", "BILL PAYMENT")
                    .containsEntry("merchant_city", "N/A").containsEntry("merchant_zip", "N/A")
                    .containsEntry("card_num", CARD).containsEntry("orig_text", "2026-11-06 09:30:15.000000")
                    .containsEntry("proc_text", "2026-11-06 09:30:15.000000").containsEntry("legacy_filler", null)
                    .containsEntry("origin", "BILL_PAY").containsEntry("cycle_id", null)
                    .containsEntry("in_master", true).containsEntry("created_by", "USER0001");
            assertThatThrownBy(() -> inTransaction(ctx, () -> writer.insert(payment("0000000000000005", "1.00"))))
                    .isInstanceOf(DuplicateTranIdException.class);
        });
    }

    @Test
    @SpecItem("BR-BILL-006")
    void balanceOnlyChangesNothingButTheBalance() {
        run("legacy", ctx -> {
            db.fixtures().categoryBalance(ACCT, "02", "0002", "3.00");
            inTransaction(ctx, () -> ctx.getBean(BalancePosting.class).balanceOnly(ACCT, new BigDecimal("-194.00")));
            assertThat(account()).containsEntry("curr_bal", new BigDecimal("0.00"))
                    .containsEntry("curr_cyc_credit", new BigDecimal("10.00"))
                    .containsEntry("curr_cyc_debit", new BigDecimal("-5.00"));
            assertThat(category("02", "0002")).isEqualTo(new BigDecimal("3.00"));
            assertThatThrownBy(() -> inTransaction(ctx, () ->
                    ctx.getBean(BalancePosting.class).balanceOnly("99999999999", BigDecimal.ONE)))
                    .isInstanceOf(AccountNotFoundException.class);
        });
    }

    @Test
    @SpecItem({"BR-POST-004", "BR-POST-005"})
    void postAppliesThePosttranRule() {
        run("legacy", ctx -> {
            BalancePosting posting = ctx.getBean(BalancePosting.class);
            inTransaction(ctx, () -> posting.post(ACCT, "02", "0002", new BigDecimal("-194.00")));
            assertThat(account()).containsEntry("curr_bal", new BigDecimal("0.00"))
                    .containsEntry("curr_cyc_credit", new BigDecimal("10.00"))
                    .containsEntry("curr_cyc_debit", new BigDecimal("-199.00"));
            assertThat(category("02", "0002")).isEqualTo(new BigDecimal("-194.00"));
            inTransaction(ctx, () -> posting.post(ACCT, "02", "0002", new BigDecimal("50.25")));
            inTransaction(ctx, () -> posting.post(ACCT, "01", "0001", new BigDecimal("0.00")));
            assertThat(account()).containsEntry("curr_bal", new BigDecimal("50.25"))
                    .containsEntry("curr_cyc_credit", new BigDecimal("60.25"))
                    .containsEntry("curr_cyc_debit", new BigDecimal("-199.00"));
            assertThat(category("02", "0002")).isEqualTo(new BigDecimal("-143.75"));
            assertThat(category("01", "0001")).isEqualTo(new BigDecimal("0.00"));
            assertThat(filler("02", "0002")).as("the online form inserts no filler").isNull();
            assertThatThrownBy(() -> inTransaction(ctx, () -> posting.post("99999999999", "02", "0002", BigDecimal.ONE)))
                    .isInstanceOf(AccountNotFoundException.class);
        });
    }

    @Test
    @SpecItem("NUM-POST-003")
    void sumsLoseTheirHighOrderDigitsAsInCobol() {
        run("legacy", ctx -> {
            admin.sql("UPDATE core.account SET curr_bal = 9999999999.99, curr_cyc_credit = 9999999999.99").update();
            db.fixtures().categoryBalance(ACCT, "01", "0001", "999999999.99");
            inTransaction(ctx, () -> ctx.getBean(BalancePosting.class).post(ACCT, "01", "0001", new BigDecimal("1.01")));
            assertThat(account()).containsEntry("curr_bal", new BigDecimal("1.00")).containsEntry("curr_cyc_credit", new BigDecimal("1.00"));
            assertThat(category("01", "0001")).isEqualTo(new BigDecimal("1.00"));
        });
        assertThat(Amounts.truncate(new BigDecimal("-12345678901.25"), 10)).isEqualTo(new BigDecimal("-2345678901.25"));
        assertThat(Amounts.truncate(new BigDecimal("1234567890.12"), 9)).isEqualTo(new BigDecimal("234567890.12"));
        assertThat(Amounts.truncate(new BigDecimal("5"), 9)).isEqualTo(new BigDecimal("5"));
    }

    @Test
    @SpecItem("BR-INTC-006")
    void interestAddsTheTotalAndResetsTheCycleTotals() {
        run("legacy", ctx -> {
            BalancePosting posting = ctx.getBean(BalancePosting.class);
            db.fixtures().categoryBalance(ACCT, "01", "0001", "3.00");
            inTransaction(ctx, () -> posting.interest(ACCT, new BigDecimal("2.42")));
            assertThat(account()).containsEntry("curr_bal", new BigDecimal("196.42"))
                    .containsEntry("curr_cyc_credit", new BigDecimal("0.00"))
                    .containsEntry("curr_cyc_debit", new BigDecimal("0.00"));
            assertThat(category("01", "0001")).isEqualTo(new BigDecimal("3.00"));

            admin.sql("UPDATE core.account SET curr_cyc_credit = 7.00, curr_cyc_debit = -3.00").update();
            inTransaction(ctx, () -> posting.interest(ACCT, new BigDecimal("0.00")));
            assertThat(account()).as("a zero total still resets the cycle totals")
                    .containsEntry("curr_bal", new BigDecimal("196.42"))
                    .containsEntry("curr_cyc_credit", new BigDecimal("0.00"))
                    .containsEntry("curr_cyc_debit", new BigDecimal("0.00"));

            inTransaction(ctx, () -> posting.interest(ACCT, new BigDecimal("-200.43")));
            assertThat(account()).containsEntry("curr_bal", new BigDecimal("-4.01"));

            assertThatThrownBy(() -> inTransaction(ctx, () -> posting.interest("99999999999", BigDecimal.ONE)))
                    .isInstanceOf(AccountNotFoundException.class);
            assertThatThrownBy(() -> inTransaction(ctx, () -> posting.interest(ACCT, new BigDecimal("0.125"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(account()).as("a refused total changes nothing").containsEntry("curr_bal", new BigDecimal("-4.01"));
        });
    }

    @Test
    @SpecItem("NUM-INTC-003")
    void interestOverflowingTheBalanceKeepsItsLowOrderDigitsAsInCobol() {
        run("legacy", ctx -> {
            BalancePosting posting = ctx.getBean(BalancePosting.class);
            admin.sql("UPDATE core.account SET curr_bal = 9999999999.99").update();
            inTransaction(ctx, () -> posting.interest(ACCT, new BigDecimal("0.02")));
            assertThat(account()).containsEntry("curr_bal", new BigDecimal("0.01"));
            admin.sql("UPDATE core.account SET curr_bal = -9999999999.99").update();
            inTransaction(ctx, () -> posting.interest(ACCT, new BigDecimal("-999999999.99")));
            assertThat(account()).containsEntry("curr_bal", new BigDecimal("-999999999.98"));
        });
    }

    @Test
    void batchWindowBlocksWrites() {
        run("legacy", ctx -> {
            BatchWindowGuard guard = ctx.getBean(BatchWindowGuard.class);
            inTransaction(ctx, guard::requireWritesAllowed);
            db.fixtures().openBatchWindow();
            assertThatThrownBy(() -> inTransaction(ctx, guard::requireWritesAllowed)).isInstanceOf(BatchWindowException.class);
            db.fixtures().closeBatchWindows();
            inTransaction(ctx, guard::requireWritesAllowed);
        });
    }

    @Test
    @SpecItem("DEF-POST-010")
    void postingKeepsTheCategoryFillerItIsGivenOrFinds() {
        run("legacy", ctx -> {
            BalancePosting posting = ctx.getBean(BalancePosting.class);
            String inherited = "\u0000".repeat(20) + "\u0080\u00FF";
            String other = " ".repeat(22);
            PostedCategory created = inTransaction(ctx, () -> posting.post(ACCT, "01", "0001", new BigDecimal("5.00"), inherited));
            assertThat(created).isEqualTo(new PostedCategory(true, inherited));
            assertThat(filler("01", "0001")).isEqualTo(inherited);

            PostedCategory found = inTransaction(ctx, () -> posting.post(ACCT, "01", "0001", new BigDecimal("1.00"), other));
            assertThat(found).isEqualTo(new PostedCategory(false, inherited));
            assertThat(filler("01", "0001")).isEqualTo(inherited);
            assertThat(category("01", "0001")).isEqualTo(new BigDecimal("6.00"));

            // A row an online writer created has no filler, and posting leaves it so.
            db.fixtures().categoryBalance(ACCT, "02", "0002", "3.00");
            PostedCategory online = inTransaction(ctx, () -> posting.post(ACCT, "02", "0002", new BigDecimal("-1.00"), other));
            assertThat(online).isEqualTo(new PostedCategory(false, null));
            assertThat(filler("02", "0002")).isNull();
            assertThat(category("02", "0002")).isEqualTo(new BigDecimal("2.00"));

            assertThat(account()).containsEntry("curr_bal", new BigDecimal("199.00"))
                    .containsEntry("curr_cyc_credit", new BigDecimal("16.00"))
                    .containsEntry("curr_cyc_debit", new BigDecimal("-6.00"));
            assertThatThrownBy(() -> inTransaction(ctx, () -> posting.post("99999999999", "01", "0001", BigDecimal.ONE, other)))
                    .isInstanceOf(AccountNotFoundException.class);
            assertThat(admin.sql("SELECT COUNT(*) FROM core.tcat_bal WHERE acct_id = '99999999999'").query(Integer.class).single())
                    .isZero();
        });
    }

    @Test
    void writerStampsTheCycleThatWroteTheRow() {
        int cycle = admin.sql("INSERT INTO batch.cycle (business_date) OUTPUT INSERTED.cycle_id VALUES ('2022-07-06')")
                .query(Integer.class).single();
        run("legacy", ctx -> {
            LocalDateTime ts = LocalDateTime.of(2022, 7, 6, 10, 0);
            NewTransaction posted = new NewTransaction("0000000000000009", "01", "0001", "POS TERM", "PURCHASE",
                    new BigDecimal("1.00"), CARD, "000000001", "SHOP", "TOWN", "10001", ts, ts, Origin.POSTED, null, cycle);
            long pk = inTransaction(ctx, () -> ctx.getBean(TransactionWriter.class).insert(posted));
            assertThat(admin.sql("SELECT origin, cycle_id, legacy_filler FROM core.[transaction] WHERE tran_pk = :pk")
                    .param("pk", pk).query().singleRow())
                    .containsEntry("origin", "POSTED").containsEntry("cycle_id", cycle).containsEntry("legacy_filler", null);
        });
    }

    @Test
    void batchHoldingTheWindowLockBlocksWrites() throws Exception {
        run("legacy", ctx -> {
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() ->
                    new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.adminDataSource()))
                            .executeWithoutResult(s -> {
                                boolean granted;
                                try {
                                    granted = AppLocks.acquire(admin, JdbcBatchWindowGuard.LOCK, "Exclusive", 1000);
                                } finally {
                                    held.countDown();
                                }
                                assertThat(granted).isTrue();
                                await(release);
                            }));
            await(held);
            if (batch.isCompletedExceptionally()) {
                batch.join();
            }
            try {
                assertThatThrownBy(() -> inTransaction(ctx, ctx.getBean(BatchWindowGuard.class)::requireWritesAllowed))
                        .isInstanceOf(BatchWindowException.class);
            } finally {
                release.countDown();
            }
            batch.join();
        });
    }

    @Test
    void theGuardTakenFirstReleasesItsLocksAndCommitsItsWorkAtCommit() {
        lockTakenFirstIsReleasedAndItsWorkCommittedAtCommit(true);
    }

    @Test
    void theLegacyIdLockTakenFirstReleasesItsLockAndCommitsItsWorkAtCommit() {
        lockTakenFirstIsReleasedAndItsWorkCommittedAtCommit(false);
    }

    /**
     * A non-autocommit mssql-jdbc connection runs with IMPLICIT_TRANSACTIONS ON, where a BEGIN
     * TRANSACTION at count 0 opens two levels and commit() ends only one. Spring calls afterCommit
     * after commit() and before it resets and returns the connection, so what another session sees
     * there is what the commit itself made durable and released.
     */
    private void lockTakenFirstIsReleasedAndItsWorkCommittedAtCommit(boolean guardFirst) {
        run("legacy", ctx -> {
            JdbcClient service = JdbcClient.create(ctx.getBean(DataSource.class));
            Map<String, Object> seen = new HashMap<>();
            String id = inTransaction(ctx, () -> {
                if (guardFirst) {
                    ctx.getBean(BatchWindowGuard.class).requireWritesAllowed();
                }
                String tranId = ctx.getBean(TransactionIds.class).next();
                ctx.getBean(TransactionWriter.class).insert(payment(tranId, "1.00"));
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        seen.put("trancount", service.sql("SELECT @@TRANCOUNT").query(Integer.class).single());
                        seen.put("windowLockFree", lockFree(JdbcBatchWindowGuard.LOCK));
                        seen.put("idLockFree", lockFree(JdbcTransactionIds.LOCK));
                        seen.put("rows", committedRows(tranId));
                    }
                });
                return tranId;
            });
            assertThat(id).isEqualTo("0000000000000001");
            assertThat(seen)
                    .containsEntry("trancount", 0)
                    .containsEntry("rows", 1)
                    .containsEntry("windowLockFree", true)
                    .containsEntry("idLockFree", true);
        });
    }

    /**
     * The session dies right after commit(), before any other callback of the transaction and
     * before Spring resets the connection, whose failure Spring only logs. The call must succeed,
     * and what it reported must already be durable: nothing that runs after commit may turn
     * committed work into a failed call.
     */
    @Test
    void workIsDurableOnceCommitReturnsThoughTheSessionDiesBeforeTheReset() {
        run("legacy", ctx -> {
            JdbcClient service = JdbcClient.create(ctx.getBean(DataSource.class));
            String id = inTransaction(ctx, () -> {
                int spid = service.sql("SELECT @@SPID").query(Integer.class).single();
                // Registered before the first lock, so it runs before every afterCommit the locks add.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        admin.sql("KILL " + spid).update();
                    }
                });
                ctx.getBean(BatchWindowGuard.class).requireWritesAllowed();
                String tranId = ctx.getBean(TransactionIds.class).next();
                ctx.getBean(TransactionWriter.class).insert(payment(tranId, "1.00"));
                return tranId;
            });
            assertThat(committedRows(id)).as("rows of a commit that returned normally").isOne();
        });
    }

    /**
     * A level opened behind the lock's back would outlive commit(); the call must fail before
     * anything is durable, with every level rolled back and no callback told it committed.
     */
    @Test
    void anExtraLevelAtCommitRollsBackAndFailsTheCallBeforeAnythingIsCommitted() {
        run("legacy", ctx -> {
            JdbcClient service = JdbcClient.create(ctx.getBean(DataSource.class));
            Throwable thrown = failedAtCommit(ctx, () -> service.sql("BEGIN TRANSACTION").update());
            assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("2 levels at commit");
        });
    }

    /**
     * The level check's own query fails. Spring rolls back only for an exception that is not a
     * TransactionException, so the database error must reach the caller as it was raised.
     */
    @Test
    void aFailedLevelCheckRollsBackAndReachesTheCallerAsTheDatabaseError() {
        LevelCheckFailingDataSource dataSource = new LevelCheckFailingDataSource(db.dataSource(ServiceLogin.BILLPAY));
        runner(dataSource, "legacy").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            Throwable thrown = failedAtCommit(ctx, dataSource::failNextCheck);
            assertThat(dataSource.injections()).as("injected failures").isOne();
            assertThat(thrown).isInstanceOf(DataAccessException.class).isNotInstanceOf(TransactionException.class)
                    .hasRootCauseMessage(LevelCheckFailingDataSource.MESSAGE);
        });
    }

    /**
     * Runs a lock-first write and {@code beforeCommit} just before commit, then checks that the
     * call failed with nothing committed: no callback told of a commit, the completion rolled back,
     * no row, both locks free, and the session back in the pool with no level open.
     */
    private Throwable failedAtCommit(AssertableApplicationContext ctx, Runnable beforeCommit) {
        DataSource pool = ctx.getBean(DataSource.class);
        JdbcClient service = JdbcClient.create(pool);
        String[] id = new String[1];
        int[] spid = new int[1];
        Map<String, Object> seen = new HashMap<>();
        Throwable thrown = catchThrowable(() -> inTransaction(ctx, () -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    seen.put("afterCommit", true);
                }

                @Override
                public void afterCompletion(int status) {
                    seen.put("completion", status);
                }
            });
            ctx.getBean(BatchWindowGuard.class).requireWritesAllowed();
            id[0] = ctx.getBean(TransactionIds.class).next();
            ctx.getBean(TransactionWriter.class).insert(payment(id[0], "1.00"));
            spid[0] = service.sql("SELECT @@SPID").query(Integer.class).single();
            beforeCommit.run();
        }));
        assertThat(thrown).as("the call fails").isNotNull();
        assertThat(seen).as("callbacks after the failed call").doesNotContainKey("afterCommit")
                .containsEntry("completion", TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(committedRows(id[0])).isZero();
        assertThat(lockFree(JdbcBatchWindowGuard.LOCK)).isTrue();
        assertThat(lockFree(JdbcTransactionIds.LOCK)).isTrue();
        assertThat(levelsOfPooledSession(pool, spid[0])).as("levels of the session when the pool hands it out again")
                .isZero();
        return thrown;
    }

    /**
     * Borrows connections from the pool, holding each so the next is another, until it hands out
     * the given session; returns that session's @@TRANCOUNT.
     */
    private static int levelsOfPooledSession(DataSource pool, int spid) {
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                Connection c = pool.getConnection();
                held.add(c);
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT @@SPID, @@TRANCOUNT")) {
                    rs.next();
                    if (rs.getInt(1) == spid) {
                        return rs.getInt(2);
                    }
                }
            }
            throw new AssertionError("the pool did not hand out session " + spid + " again");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            for (Connection c : held) {
                try {
                    c.close();
                } catch (SQLException ignored) {
                    // the next borrower gets a fresh connection
                }
            }
        }
    }

    /**
     * Fails the next prepare of a level check, on a connection that stays usable. A level check is
     * a statement that reads @@TRANCOUNT prepared from within a synchronization that AppLocks
     * registered; the lock statement reads @@TRANCOUNT too but is prepared by AppLocks itself.
     * Matching the caller rather than one exact text lets the injection reach a level check however
     * it phrases its query, so the test cannot pass merely because none fired.
     */
    private static final class LevelCheckFailingDataSource extends DelegatingDataSource {

        static final String MESSAGE = "injected failure of the level check";

        private static final String LOCK_SYNCHRONIZATION = AppLocks.class.getName() + "$";

        private final AtomicBoolean failNext = new AtomicBoolean();

        private final AtomicInteger injections = new AtomicInteger();

        LevelCheckFailingDataSource(DataSource target) {
            super(target);
        }

        void failNextCheck() {
            failNext.set(true);
        }

        int injections() {
            return injections.get();
        }

        private static boolean isLevelCheck(String sql) {
            return sql.contains("@@TRANCOUNT") && StackWalker.getInstance()
                    .walk(frames -> frames.anyMatch(f -> f.getClassName().startsWith(LOCK_SYNCHRONIZATION)));
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection target = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args != null
                                && args[0] instanceof String sql && isLevelCheck(sql)
                                && failNext.getAndSet(false)) {
                            injections.incrementAndGet();
                            throw new SQLException(MESSAGE);
                        }
                        try {
                            return method.invoke(target, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    /**
     * Rows with the ID that another session sees as committed. The lock timeout keeps a held row
     * lock from stalling the test on a database that reads without row versions.
     */
    private Integer committedRows(String tranId) {
        return admin.sql("""
                        SET NOCOUNT ON;
                        SET LOCK_TIMEOUT 2000;
                        DECLARE @n INT = (SELECT COUNT(*) FROM core.[transaction] WHERE tran_id = :id);
                        SET LOCK_TIMEOUT -1;
                        SELECT @n;""")
                .param("id", tranId).query(Integer.class).single();
    }

    /** Whether another session gets the lock exclusively within a second. */
    private boolean lockFree(String resource) {
        return new TransactionTemplate(new DataSourceTransactionManager(db.adminDataSource()))
                .execute(s -> AppLocks.acquire(admin, resource, "Exclusive", 1000));
    }

    private Map<String, Object> account() {
        return admin.sql("SELECT curr_bal, curr_cyc_credit, curr_cyc_debit FROM core.account WHERE acct_id = :id")
                .param("id", ACCT).query().singleRow();
    }

    private BigDecimal category(String type, String cat) {
        return admin.sql("SELECT balance FROM core.tcat_bal WHERE acct_id = :id AND type_cd = :t AND cat_cd = :c")
                .param("id", ACCT).param("t", type).param("c", cat).query(BigDecimal.class).single();
    }

    private String filler(String type, String cat) {
        return (String) admin.sql("SELECT legacy_filler FROM core.tcat_bal WHERE acct_id = :id AND type_cd = :t AND cat_cd = :c")
                .param("id", ACCT).param("t", type).param("c", cat).query().singleRow().get("legacy_filler");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
