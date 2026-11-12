package org.carddemo.billpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.ledger.JdbcTransactionIds;
import org.carddemo.platform.ledger.TransactionIds;
import org.carddemo.platform.testsupport.BothModes;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestClock;
import org.carddemo.platform.web.MessageCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A transaction ID that the master already holds, in both modes: the allocator is wrapped so that
 * it hands out an existing ID, which the unique index on the master refuses. Nothing of the payment
 * may survive. Fixed mode also reaches this state without the wrapper (see BillPayApiTest).
 */
@BothModes({"DEF-BILL-010", "BR-TRNA-003"})
@Import(BillPayDuplicateTranIdTest.CollidingIds.class)
class BillPayDuplicateTranIdTest extends BillPayTestBase {

    static final String ACCT = "00000000001";
    static final String CUST = "000000001";
    static final String CARD = "4111111111111111";
    static final String EXISTING_ID = "0000000000000041";

    /** The ID the wrapped allocator returns instead of its own; null for the real one. */
    static final AtomicReference<String> FORCED = new AtomicReference<>();

    @TestConfiguration
    static class CollidingIds {
        @Bean
        TransactionIds collidingTransactionIds(DataSource dataSource, FidelitySwitches switches) {
            TransactionIds real = new JdbcTransactionIds(JdbcClient.create(dataSource), switches);
            return () -> {
                String allocated = real.next();
                String forced = FORCED.get();
                return forced == null ? allocated : forced;
            };
        }
    }

    private final JdbcClient admin = DB.admin();

    @BeforeEach
    void sampleAccount() {
        TestClock.shared().reset();
        DB.fixtures().deleteAll()
                .account(CoreFixtures.Account.of(ACCT, "194.00").withCycleTotals("10.00", "-5.00"))
                .customer(CUST)
                .card(CARD, ACCT, CUST)
                .categoryBalance(ACCT, "01", "0001", "194.00")
                .transaction(EXISTING_ID, CARD, "194.00")
                .restartTranIdSequence(9_000_000_000_000_000L);
    }

    @AfterEach
    void reset() {
        FORCED.set(null);
        TestClock.shared().reset();
    }

    @Test
    @DisplayName("BILL-S21: an allocated ID that the master already holds rolls the whole payment back")
    @SpecItem({"SE-BILL-004", "ERR-BILL-001", "INV-001", "BR-BILL-004"})
    void collidingIdRollsEverythingBack() throws Exception {
        Map<String, Object> accountBefore = account();
        Map<String, Object> existingBefore = existing();
        Map<String, BigDecimal> balancesBefore = categoryBalances();
        FORCED.set(EXISTING_ID);

        pay(ACCT, "Y", UUID.randomUUID().toString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRAN_ID_EXISTS"))
                .andExpect(jsonPath("$.message").value(MessageCatalog.get("TRAN_ID_EXISTS").text()))
                .andExpect(jsonPath("$.field").value("acctId"));

        assertThat(admin.sql("SELECT COUNT(*) FROM core.[transaction]").query(Integer.class).single()).isEqualTo(1);
        assertThat(existing()).isEqualTo(existingBefore);
        assertThat(admin.sql("SELECT COUNT(*) FROM billpay.payment").query(Integer.class).single()).isZero();
        assertThat(account()).isEqualTo(accountBefore);
        assertThat(categoryBalances()).isEqualTo(balancesBefore);

        FORCED.set(null);
        pay(ACCT, "Y", UUID.randomUUID().toString()).andExpect(status().isCreated());
    }

    private Map<String, Object> account() {
        return row(admin.sql("SELECT * FROM core.account WHERE acct_id = :id").param("id", ACCT).query().singleRow());
    }

    private Map<String, Object> existing() {
        return row(admin.sql("SELECT * FROM core.[transaction] WHERE tran_id = :id").param("id", EXISTING_ID)
                .query().singleRow());
    }

    /** The row with binary columns such as row_version as hex, so that rows compare by value. */
    private static Map<String, Object> row(Map<String, Object> row) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        row.forEach((k, v) -> copy.put(k, v instanceof byte[] b ? java.util.HexFormat.of().formatHex(b) : v));
        return copy;
    }

    private Map<String, BigDecimal> categoryBalances() {
        Map<String, BigDecimal> balances = new java.util.LinkedHashMap<>();
        admin.sql("SELECT type_cd, cat_cd, balance FROM core.tcat_bal WHERE acct_id = :id ORDER BY type_cd, cat_cd")
                .param("id", ACCT)
                .query(rs -> {
                    balances.put(rs.getString(1) + "/" + rs.getString(2), rs.getBigDecimal(3));
                });
        return balances;
    }
}
