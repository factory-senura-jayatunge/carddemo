package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.carddemo.db.CarddemoMigrations;
import org.carddemo.db.DatabaseProvisioner;
import org.carddemo.db.LoginCheck;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.fidelity.SpecItem;
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

    /**
     * The card-to-account link changes only when batch reloads it (data-model.md: writer "batch (load)"): every
     * online login holds at most SELECT on core.card_xref, so each INSERT, UPDATE and DELETE it tries is refused
     * on that table and the row stays. batch_svc runs the same INSERT and DELETE, which shows the statements and
     * the table are valid.
     */
    @Test
    @SpecItem("SE-JXREF-007")
    void onlyBatchWritesTheCardCrossReference() throws SQLException {
        db.fixtures().account(CoreFixtures.Account.of("00000000001", "0.00"))
                .account(CoreFixtures.Account.of("00000000002", "0.00"))
                .customer("000000001")
                .card("4111111111111111", "00000000001", "000000001");
        assertThat(db.admin().sql("SELECT COUNT(*) FROM sys.tables WHERE object_id = OBJECT_ID(N'core.card_xref')")
                .query(Integer.class).single()).isEqualTo(1);
        List<String> writes = List.of(
                "INSERT INTO core.card_xref (card_num, cust_id, acct_id) VALUES ('4111111111111112', '000000001', '00000000001')",
                "UPDATE core.card_xref SET acct_id = '00000000002' WHERE card_num = '4111111111111111'",
                "DELETE FROM core.card_xref WHERE card_num = '4111111111111111'");
        List<ServiceLogin> online = Arrays.stream(ServiceLogin.values()).filter(l -> l != ServiceLogin.BATCH).toList();
        assertThat(online).hasSize(7);
        for (ServiceLogin login : online) {
            try (Connection c = db.dataSource(login).getConnection(); Statement s = c.createStatement()) {
                for (String write : writes) {
                    assertThatThrownBy(() -> s.executeUpdate(write)).as(login.userName() + ": " + write)
                            .isInstanceOfSatisfying(SQLException.class, e -> {
                                assertThat(e.getErrorCode()).isIn(229, 230);
                                assertThat(e.getMessage()).contains("'card_xref'", "schema 'core'");
                            });
                }
            }
        }
        assertThat(db.admin().sql("SELECT card_num, cust_id, acct_id FROM core.card_xref").query().listOfRows())
                .containsExactly(Map.of("card_num", "4111111111111111", "cust_id", "000000001", "acct_id", "00000000001"));
        try (Connection c = db.dataSource(ServiceLogin.BATCH).getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                assertThat(s.executeUpdate(writes.get(0))).isEqualTo(1);
                assertThat(s.executeUpdate(writes.get(2))).isEqualTo(1);
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
        }
    }

    @Test
    void billPayLoginWritesOnlyTheBalanceColumns() {
        db.fixtures().account(CoreFixtures.Account.of("00000000001", "194.00"));
        JdbcClient billpay = JdbcClient.create(db.dataSource(ServiceLogin.BILLPAY));
        assertThat(billpay.sql("UPDATE core.account SET curr_bal = 0, curr_cyc_debit = curr_cyc_debit - 1 WHERE acct_id = '00000000001'")
                .update()).isEqualTo(1);
        assertThatThrownBy(() -> billpay.sql("UPDATE core.account SET active_status = 'N' WHERE acct_id = '00000000001'").update())
                .hasMessageContaining("permission was denied");
        assertThat(billpay.sql("SELECT NEXT VALUE FOR core.tran_id_seq").query(Long.class).single())
                .isEqualTo(db.fixtures().tranIdSequenceMinimum());
    }

    @Test
    void authorizationLoginReadsOnlyTheSummaryColumnsOfTheCustomer() {
        db.fixtures().customer("000000001");
        JdbcClient authorization = JdbcClient.create(db.dataSource(ServiceLogin.AUTHORIZATION));
        assertThat(authorization.sql("""
                        SELECT cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3,
                            state_cd, zip, phone_1
                        FROM core.customer WHERE cust_id = '000000001'""").query().listOfRows()).hasSize(1);
        for (String column : new String[] {"ssn", "govt_issued_id", "dob", "eft_account_id", "fico_score",
                "country_cd", "phone_2", "pri_card_holder_ind", "row_version"}) {
            assertThatThrownBy(() -> authorization.sql("SELECT TOP 0 " + column + " FROM core.customer").query().listOfRows())
                    .as(column).hasMessageContaining("permission was denied");
        }
    }

    /**
     * A table-level SELECT would also cover any column added to core.account later, such as the load's ZIP and
     * group provenance (DEF-ACUP-002), so the online logins hold SELECT on exactly these columns.
     */
    @Test
    void theOnlineLoginsReadTheAccountByColumn() {
        List<String> columns = List.of("acct_id", "active_status", "curr_bal", "credit_limit", "cash_credit_limit",
                "open_date", "expiration_date", "reissue_date", "curr_cyc_credit", "curr_cyc_debit", "addr_zip",
                "group_id", "legacy_filler", "row_version");
        for (ServiceLogin login : new ServiceLogin[] {ServiceLogin.ACCOUNT, ServiceLogin.BILLPAY,
            ServiceLogin.AUTHORIZATION}) {
            List<String> granted = db.admin().sql("""
                            SELECT COALESCE(COL_NAME(p.major_id, p.minor_id), N'(table)')
                            FROM sys.database_permissions AS p
                            JOIN sys.database_principals AS u ON u.principal_id = p.grantee_principal_id
                            WHERE u.name = :user AND p.class = 1 AND p.major_id = OBJECT_ID(N'core.account')
                              AND p.permission_name = N'SELECT' AND p.state IN ('G', 'W')""")
                    .param("user", login.userName()).query(String.class).list();
            assertThat(granted).as(login.userName()).containsExactlyInAnyOrderElementsOf(columns);
            JdbcClient service = JdbcClient.create(db.dataSource(login));
            assertThat(service.sql("SELECT TOP 0 " + String.join(", ", columns) + " FROM core.account")
                    .query().listOfRows()).as(login.userName()).isEmpty();
        }
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
        db.fixtures().restartTranIdSequence(9_000_000_000_000_042L);
        assertThat(db.admin().sql("SELECT NEXT VALUE FOR core.tran_id_seq").query(Long.class).single())
                .isEqualTo(9_000_000_000_000_042L);
    }

    @Test
    void theSequenceRestartIsNeverClampedIntoRange() {
        assertThatThrownBy(() -> db.fixtures().restartTranIdSequence(0)).hasMessageContaining("RESTART WITH");
        assertThatThrownBy(() -> db.fixtures().restartTranIdSequence(10_000_000_000_000_000L))
                .hasMessageContaining("RESTART WITH");
        db.fixtures().restartTranIdSequence(9_999_999_999_999_999L);
        assertThat(db.admin().sql("SELECT NEXT VALUE FOR core.tran_id_seq").query(Long.class).single())
                .isEqualTo(9_999_999_999_999_999L);
        assertThatThrownBy(() -> db.admin().sql("SELECT NEXT VALUE FOR core.tran_id_seq").query(Long.class).single())
                .hasMessageContaining("tran_id_seq");
    }

    @Test
    void atMostOneBatchWindowIsOpen() {
        db.fixtures().openBatchWindow();
        assertThatThrownBy(() -> db.fixtures().openBatchWindow()).hasMessageContaining("UX_batch_window_open");
        db.fixtures().closeBatchWindows().openBatchWindow();
    }

    @Test
    void auditRowsNameFieldsAndAcceptOnlyKnownActions() {
        db.admin().sql("""
                INSERT INTO ops.audit (service, actor_user_id, action, subject_type, subject_key, field_names)
                VALUES ('account-service', 'ADMIN001', 'UNMASK', 'ACCOUNT', '00000000001', 'dob,ssn')""").update();
        Map<String, Object> row = db.admin().sql("SELECT occurred_at, field_names FROM ops.audit").query().singleRow();
        assertThat(row.get("occurred_at")).isNotNull();
        assertThat(row.get("field_names")).isEqualTo("dob,ssn");
        assertThatThrownBy(() -> db.admin().sql("""
                INSERT INTO ops.audit (service, actor_user_id, action, subject_type, subject_key)
                VALUES ('account-service', 'ADMIN001', 'VIEW', 'ACCOUNT', '00000000001')""").update())
                .hasMessageContaining("CK_audit_action");
    }

    @Test
    void aMigrationNumberedBelowTheAppliedVersionStillRuns() {
        db.admin().sql("DROP TABLE ops.audit").update();
        assertThat(db.admin().sql("DELETE FROM dbo.flyway_schema_history WHERE script = 'V004__ops_audit.sql'")
                .update()).isEqualTo(1);
        assertThat(CarddemoMigrations.migrate(db.adminDataSource()).migrationsExecuted).isEqualTo(1);
        assertThat(db.admin().sql("SELECT COUNT(*) FROM ops.audit").query(Integer.class).single()).isZero();
    }

    @Test
    void databaseNameIsPerModule() {
        assertThat(TestDatabase.databaseName("carddemo_test", "billpay-service")).isEqualTo("carddemo_test_billpay_service");
        assertThat(db.databaseName()).endsWith("_test_support");
    }
}
