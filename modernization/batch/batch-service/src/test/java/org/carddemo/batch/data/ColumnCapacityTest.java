package org.carddemo.batch.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ColumnCapacity against what SQL Server itself does with each of the 256 characters a legacy
 * byte maps to: the check passes a value exactly when the server stores it unchanged.
 */
class ColumnCapacityTest {

    private static final TestDatabase DB = TestDatabase.shared();
    private static final int UTF8 = 65001;
    private static final int WINDOWS_1252 = 1252;

    /** The characters 0 to 255 that a column of this type stores unchanged, by the server's own conversion. */
    private static List<Integer> heldByServer(String collation, String type) {
        return DB.admin().sql("""
                WITH n AS (SELECT TOP 256 ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) - 1 AS i FROM sys.all_columns)
                SELECT i, CAST(NCHAR(i) COLLATE %s AS %s) AS s FROM n ORDER BY i""".formatted(collation, type))
                .query((rs, n) -> rs.getString("s").equals(String.valueOf((char) rs.getInt("i"))) ? rs.getInt("i") : -1)
                .list().stream().filter(i -> i >= 0).toList();
    }

    private static List<Integer> heldByCheck(String type, int maxBytes, int codePage) {
        List<Integer> held = new ArrayList<>();
        for (int i = 0; i <= 0xFF; i++) {
            if (ColumnCapacity.problem(type, maxBytes, codePage, String.valueOf((char) i)) == null) {
                held.add(i);
            }
        }
        return held;
    }

    @Test
    void theCheckAgreesWithTheServerForEveryByteValue() {
        assertThat(heldByCheck("varchar", 1, WINDOWS_1252))
                .isEqualTo(heldByServer("Latin1_General_100_BIN2", "VARCHAR(1)"))
                .doesNotContain(0x80, 0x81, 0x9F).contains(0x00, 0x7F, 0xA0, 0xE9, 0xFF).hasSize(224);
        assertThat(heldByCheck("char", 1, WINDOWS_1252)).isEqualTo(heldByServer("Latin1_General_100_BIN2", "CHAR(1)"));
        assertThat(heldByCheck("varchar", 1, UTF8))
                .isEqualTo(heldByServer("Latin1_General_100_CI_AS_SC_UTF8", "VARCHAR(1)")).hasSize(128);
        assertThat(heldByCheck("varchar", 2, UTF8))
                .isEqualTo(heldByServer("Latin1_General_100_CI_AS_SC_UTF8", "VARCHAR(2)")).hasSize(256);
        assertThat(heldByCheck("nvarchar", 2, 0))
                .isEqualTo(heldByServer("Latin1_General_100_BIN2", "NVARCHAR(1)")).hasSize(256);
        assertThat(heldByCheck("nchar", 2, 0))
                .isEqualTo(heldByServer("Latin1_General_100_CI_AS_SC_UTF8", "NCHAR(1)")).hasSize(256);
    }

    @Test
    void aColumnThatCannotHoldTheValueIsNamedAndTheValueIsNot() {
        JdbcClient batch = JdbcClient.create(DB.dataSource(ServiceLogin.BATCH));
        ColumnCapacity customer = ColumnCapacity.of(batch, "core.customer");
        ColumnCapacity card = ColumnCapacity.of(batch, "core.card");

        customer.require("addr_line_1", "\u0080\u009f\u00e9".repeat(16) + "\u0000\u00ff");
        customer.require("first_name", "\u00e9".repeat(12) + "a");
        customer.require("fico_score", 850);
        customer.require("no_such_column", "x".repeat(100));
        card.require("card_num", "\u00e9".repeat(16));

        assertThatThrownBy(() -> customer.require("first_name", "\u00e9".repeat(13)))
                .isInstanceOf(ColumnCapacityException.class)
                .hasMessage("core.customer.first_name cannot hold the value: it is wider than the column");
        assertThatThrownBy(() -> customer.require("addr_line_2", "a".repeat(51)))
                .hasMessage("core.customer.addr_line_2 cannot hold the value: it is wider than the column");
        assertThatThrownBy(() -> card.require("card_num", "411111111111111\u0081"))
                .isInstanceOf(ColumnCapacityException.class)
                .hasMessage("core.card.card_num cannot hold the value: it has a character that the column's code"
                        + " page lacks")
                .extracting(e -> ((ColumnCapacityException) e).column()).isEqualTo("core.card.card_num");
        assertThatThrownBy(() -> ColumnCapacity.of(batch, "core.no_such_table"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aColumnTheTableDoesNotHaveIsNamed() {
        ColumnCapacity account = ColumnCapacity.of(JdbcClient.create(DB.dataSource(ServiceLogin.BATCH)), "core.account");

        account.requireColumns(List.of("acct_id", "curr_bal", "open_date", "legacy_zip_group", "zip_group_origin"));
        assertThatThrownBy(() -> account.requireColumns(List.of("acct_id", "no_such_column")))
                .isInstanceOf(MissingColumnException.class)
                .hasMessage("core.account has no column no_such_column: the database is not migrated to the schema"
                        + " this build expects");
    }
}
