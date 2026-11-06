package org.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The batch range of the schema (V400-V499) as batch_svc sees it. */
class BatchSchemaTest {

    private static final TestDatabase DB = TestDatabase.shared();
    private static final Path DAILY = Path.of(System.getProperty("carddemo.repo.root", "../../.."),
            "app", "data", "ASCII", "dailytran.txt");

    private final JdbcClient batch = JdbcClient.create(DB.dataSource(ServiceLogin.BATCH));

    @BeforeEach
    void empty() {
        DB.admin().sql("DELETE FROM core.daily_reject").update();
        DB.admin().sql("DELETE FROM core.daily_tran").update();
        DB.fixtures().deleteAll();
        DB.admin().sql("DELETE FROM batch.cycle").update();
    }

    private int openCycle(LocalDate businessDate) {
        batch.sql("INSERT INTO batch.cycle (business_date, interest_parm) VALUES (:d, :p)")
                .param("d", businessDate).param("p", businessDate.toString().replace("-", "") + "00").update();
        return batch.sql("SELECT cycle_id FROM batch.cycle WHERE business_date = :d").param("d", businessDate)
                .query(Integer.class).single();
    }

    @Test
    void oneCyclePerBusinessDate() {
        openCycle(LocalDate.of(2022, 7, 6));
        assertThatThrownBy(() -> openCycle(LocalDate.of(2022, 7, 6))).hasMessageContaining("UQ_cycle_business_date");
        assertThatThrownBy(() -> batch.sql("INSERT INTO batch.cycle (business_date, interest_parm) VALUES ('2022-07-07', '2022070712')")
                .update()).hasMessageContaining("CK_cycle_interest_parm");
    }

    @Test
    void transactionsAndBatchWindowsReferToAnExistingCycle() {
        int cycle = openCycle(LocalDate.of(2022, 7, 6));
        DB.fixtures().account(CoreFixtures.Account.of("00000000001", "0.00")).customer("000000001")
                .card("4111111111111111", "00000000001", "000000001")
                .transaction("0000000000000001", "4111111111111111", "1.00");
        assertThat(DB.admin().sql("UPDATE core.[transaction] SET cycle_id = :c").param("c", cycle).update()).isEqualTo(1);
        assertThatThrownBy(() -> DB.admin().sql("UPDATE core.[transaction] SET cycle_id = :c").param("c", cycle + 1).update())
                .hasMessageContaining("FK_transaction_cycle");
        assertThatThrownBy(() -> DB.admin().sql(
                        "INSERT INTO ops.batch_window (cycle_id, mode, opened_by) VALUES (:c, 'WRITES_BLOCKED', 'test')")
                .param("c", cycle + 1).update())
                .hasMessageContaining("FK_batch_window_cycle");
    }

    private long stage(Integer cycle, Integer seq, String origin, String raw) {
        batch.sql("""
                INSERT INTO core.daily_tran (cycle_id, seq, origin, created_by, tran_id, type_cd, cat_cd, source,
                    description, amount, merchant_id, merchant_name, merchant_city, merchant_zip, card_num, orig_ts,
                    proc_ts, raw_record)
                VALUES (:c, :s, :o, :u, :id, '01', '0001', 'POS TERM', 'TEST', 1.00, '000000001', 'M', 'C', 'Z',
                    :card, :ts, '', :raw)""")
                .param("c", cycle).param("s", seq).param("o", origin)
                .param("u", origin.equals("ONLINE_ADD") ? "USER0001" : null)
                .param("id", raw.substring(0, 16)).param("card", raw.substring(262, 278))
                .param("ts", raw.substring(278, 304)).param("raw", raw).update();
        return batch.sql("SELECT MAX(daily_pk) FROM core.daily_tran").query(Long.class).single();
    }

    private static String daily(int line) throws Exception {
        return Files.readAllLines(DAILY, StandardCharsets.ISO_8859_1).get(line);
    }

    @Test
    void onlineAddsWaitUntilACycleClaimsThem() throws Exception {
        int cycle = openCycle(LocalDate.of(2022, 7, 6));
        String raw = daily(0);
        long waiting = stage(null, null, "ONLINE_ADD", daily(1));

        assertThatThrownBy(() -> stage(null, null, "DAILY_FILE", raw)).hasMessageContaining("CK_daily_tran_waiting");
        assertThatThrownBy(() -> stage(null, null, "ONLINE_ADD", daily(1))).hasMessageContaining("UX_daily_waiting");
        assertThatThrownBy(() -> stage(cycle, null, "DAILY_FILE", raw)).hasMessageContaining("CK_daily_tran_claimed");
        stage(cycle, 1, "DAILY_FILE", raw);
        assertThatThrownBy(() -> stage(cycle, 1, "DAILY_FILE", daily(2))).hasMessageContaining("UX_daily_claimed");

        assertThat(batch.sql("UPDATE core.daily_tran SET cycle_id = :c, seq = 2 WHERE daily_pk = :pk")
                .param("c", cycle).param("pk", waiting).update()).isEqualTo(1);
        assertThatThrownBy(() -> batch.sql("UPDATE core.daily_tran SET amount = 0 WHERE daily_pk = :pk")
                .param("pk", waiting).update()).hasMessageContaining("permission was denied");
        // Once claimed, the same ID may wait again (the filtered index covers waiting rows only).
        stage(null, null, "ONLINE_ADD", daily(1));
    }

    @Test
    void rejectViewMasksTheCardNumberInTheRecord() throws Exception {
        int cycle = openCycle(LocalDate.of(2022, 7, 6));
        String raw = daily(0);
        String card = raw.substring(262, 278);
        long pk = stage(cycle, 1, "DAILY_FILE", raw);
        batch.sql("""
                INSERT INTO core.daily_reject (cycle_id, seq, daily_pk, reason_cd, reason_desc, raw_record)
                VALUES (:c, 1, :pk, '0100', 'INVALID CARD NUMBER FOUND', :raw)""")
                .param("c", cycle).param("pk", pk).param("raw", raw).update();

        assertThat(batch.sql("SELECT card_num FROM core.daily_tran").query(String.class).single())
                .as("batch_svc reads the card number unmasked").isEqualTo(card);
        var row = batch.sql("SELECT raw_record_masked, origin FROM core.v_daily_reject").query().singleRow();
        String masked = (String) row.get("raw_record_masked");
        assertThat(row.get("origin")).isEqualTo("DAILY_FILE");
        assertThat(masked).hasSize(350).doesNotContain(card);
        assertThat(masked.substring(262, 278)).isEqualTo("XXXXXXXXXXXX" + card.substring(12));
        assertThat(masked.substring(0, 262)).isEqualTo(raw.substring(0, 262));
        batch.sql("DELETE FROM core.daily_reject").update();
        assertThatThrownBy(() -> batch.sql("""
                INSERT INTO core.daily_reject (cycle_id, seq, daily_pk, reason_cd, reason_desc, raw_record)
                VALUES (:c, 1, :pk, '0104', 'X', :raw)""").param("c", cycle).param("pk", pk).param("raw", raw).update())
                .hasMessageContaining("CK_daily_reject_reason");
    }
}
