package org.carddemo.platform.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs as the Bill Pay login, so every statement also proves the grants suffice. */
class LedgerCoreTest {

    static final String ACCT = "00000000001";
    static final String CARD = "4111111111111111";

    private final TestDatabase db = TestDatabase.shared();
    private final JdbcClient admin = db.admin();

    private ApplicationContextRunner runner(String tranIdMode) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceTransactionManagerAutoConfiguration.class,
                        TransactionAutoConfiguration.class, FidelityAutoConfiguration.class, LedgerAutoConfiguration.class))
                .withBean(javax.sql.DataSource.class, () -> db.dataSource(ServiceLogin.BILLPAY))
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
        runner(mode).run(ctx -> {
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
            db.fixtures().transaction("0000000000000041", CARD, "1.00").restartTranIdSequence(7);
            TransactionIds ids = ctx.getBean(TransactionIds.class);
            assertThat(inTransaction(ctx, ids::next)).isEqualTo("0000000000000007");
            assertThat(inTransaction(ctx, ids::next)).isEqualTo("0000000000000008");
            assertThat(JdbcTransactionIds.format(9_999_999_999_999_999L)).isEqualTo("9999999999999999");
            assertThatThrownBy(() -> JdbcTransactionIds.format(10_000_000_000_000_000L)).isInstanceOf(IllegalStateException.class);
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

    private Map<String, Object> account() {
        return admin.sql("SELECT curr_bal, curr_cyc_credit, curr_cyc_debit FROM core.account WHERE acct_id = :id")
                .param("id", ACCT).query().singleRow();
    }

    private BigDecimal category(String type, String cat) {
        return admin.sql("SELECT balance FROM core.tcat_bal WHERE acct_id = :id AND type_cd = :t AND cat_cd = :c")
                .param("id", ACCT).param("t", type).param("c", cat).query(BigDecimal.class).single();
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
