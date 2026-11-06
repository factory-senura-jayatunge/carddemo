package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.carddemo.db.CarddemoMigrations;
import org.carddemo.db.DatabaseProvisioner;
import org.carddemo.db.LoginCheck;
import org.carddemo.db.ServiceLogin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class TestDatabaseTest {

    private final TestDatabase db = TestDatabase.shared();

    @BeforeEach
    void empty() {
        db.fixtures().deleteAll();
    }

    @Test
    void databaseIsMigratedAndValid() {
        var info = CarddemoMigrations.flyway(db.adminDataSource()).info();
        assertThat(info.pending()).isEmpty();
        assertThat(info.applied()).isNotEmpty();
        assertThat(db.admin().sql("SELECT collation_name FROM sys.databases WHERE name = DB_NAME()")
                .query(String.class).single()).isEqualTo(DatabaseProvisioner.COLLATION);
        assertThat(db.admin().sql("SELECT is_read_committed_snapshot_on FROM sys.databases WHERE name = DB_NAME()")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void everyServiceLoginIsRefusedItsProbes() {
        assertThat(new LoginCheck(new DatabaseProvisioner(db.settings())).run()).isEmpty();
    }

    @Test
    void billPayLoginWritesOnlyTheBalanceColumns() {
        db.fixtures().account(CoreFixtures.Account.of("00000000001", "194.00"));
        JdbcClient billpay = JdbcClient.create(db.dataSource(ServiceLogin.BILLPAY));
        assertThat(billpay.sql("UPDATE core.account SET curr_bal = 0, curr_cyc_debit = curr_cyc_debit - 1 WHERE acct_id = '00000000001'")
                .update()).isEqualTo(1);
        assertThatThrownBy(() -> billpay.sql("UPDATE core.account SET active_status = 'N' WHERE acct_id = '00000000001'").update())
                .hasMessageContaining("permission was denied");
        assertThat(billpay.sql("SELECT NEXT VALUE FOR core.tran_id_seq").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void transactionTimestampsAreDatetime2AndProcDateIsDerived() {
        db.fixtures().account(CoreFixtures.Account.of("00000000001", "0.00"))
                .customer("000000001")
                .card("4111111111111111", "00000000001", "000000001")
                .transaction("0000000000000001", "4111111111111111", "-12.34");
        Map<String, Object> row = db.admin().sql(
                        "SELECT proc_date, amount, CONVERT(VARCHAR(30), orig_ts, 121) AS orig FROM core.[transaction]")
                .query().singleRow();
        assertThat(row.get("proc_date")).isEqualTo(java.sql.Date.valueOf(LocalDate.of(2022, 7, 5)));
        assertThat(row.get("amount")).isEqualTo(new BigDecimal("-12.34"));
        assertThat(row.get("orig")).isEqualTo("2022-07-05 10:00:00.000000");
    }

    @Test
    void transactionIdSequenceHasSixteenDigitsAndNeverCycles() {
        Map<String, Object> seq = db.admin().sql("""
                SELECT CAST(maximum_value AS BIGINT) AS max_value, is_cycling
                FROM sys.sequences WHERE name = 'tran_id_seq' AND SCHEMA_NAME(schema_id) = 'core'""").query().singleRow();
        assertThat(seq.get("max_value")).isEqualTo(9_999_999_999_999_999L);
        assertThat(seq.get("is_cycling")).isEqualTo(false);
        db.fixtures().restartTranIdSequence(42);
        assertThat(db.admin().sql("SELECT NEXT VALUE FOR core.tran_id_seq").query(Long.class).single()).isEqualTo(42L);
    }

    @Test
    void atMostOneBatchWindowIsOpen() {
        db.fixtures().openBatchWindow();
        assertThatThrownBy(() -> db.fixtures().openBatchWindow()).hasMessageContaining("UX_batch_window_open");
        db.fixtures().closeBatchWindows().openBatchWindow();
    }

    @Test
    void databaseNameIsPerModule() {
        assertThat(TestDatabase.databaseName("carddemo_test", "billpay-service")).isEqualTo("carddemo_test_billpay_service");
        assertThat(db.databaseName()).endsWith("_test_support");
    }
}
