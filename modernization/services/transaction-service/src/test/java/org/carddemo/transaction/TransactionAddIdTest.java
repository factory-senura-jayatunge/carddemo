package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.ledger.JdbcTransactionIds;
import org.carddemo.platform.ledger.TransactionIds;
import org.carddemo.platform.testsupport.BothModes;
import org.carddemo.platform.testsupport.FidelityOverrides;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The TRNA scenarios that run in both BR-TRNA-003 modes: where the add's transaction ID comes
 * from, and what happens when it is taken or used up.
 */
@BothModes("BR-TRNA-003")
@Import(TransactionAddIdTest.CollidingIds.class)
class TransactionAddIdTest extends TransactionAddTestBase {

    static final String HIGHEST = "0000000000000300";
    static final String FIELD_NAMES =
            "amount,cardNum,catCd,description,merchantCity,merchantId,merchantName,merchantZip,origDate,source,typeCd";

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

    @AfterEach
    void realIds() {
        FORCED.set(null);
    }

    private static boolean fixed() {
        return FidelityOverrides.current() == FidelityMode.FIXED;
    }

    /** CVTRA06Y written out field by field, as the cycle reads the daily file. */
    static String dailyRecord(String tranId, String amountZoned) {
        return String.format("%-16s%-2s%-4s%-10s%-100s%s%-9s%-50s%-50s%-10s%-16s%-26s%-26s%-20s", tranId, "01",
                "0001", "POS TERM", "Test purchase", amountZoned, "123456789", "Shop", "Town", "12345", CARD,
                "2022-07-06", "2026-11-07", "");
    }

    @Test
    @DisplayName("TRNA-S21: one waiting row with its raw_record, the success text and hint, one audit row")
    @SpecItem({"BR-TRNA-003", "BR-TRNA-004", "BR-TRNA-005", "SE-TRNA-002", "NUM-TRNA-002", "DEF-TRNA-001", "INV-001"})
    void s21_add() throws Exception {
        DB.fixtures().transaction(HIGHEST, OTHER_CARD, "10.00");
        startDataDone();
        String expected = fixed() ? String.valueOf(FIXED_FIRST_ID) : "0000000000000301";
        String key = key();
        add(valid(), key).andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.tranId").value(expected))
                .andExpect(jsonPath("$.severity").value("success"))
                .andExpect(jsonPath("$.message").value(ADDED.formatted(expected)))
                .andExpect(jsonPath("$.hint.message").value(HINT))
                .andExpect(jsonPath("$.hint.severity").value("info"));

        Map<String, Object> row = admin.sql("""
                SELECT tran_id, origin, cycle_id, seq, created_by, CAST(idempotency_key AS VARCHAR(36)) AS idempotency_key,
                    type_cd, cat_cd, source, description, amount, merchant_id, merchant_name, merchant_city,
                    merchant_zip, card_num, orig_ts, proc_ts, raw_record
                FROM core.daily_tran""").query().singleRow();
        assertThat(row).containsEntry("tran_id", expected).containsEntry("origin", "ONLINE_ADD")
                .containsEntry("cycle_id", null).containsEntry("seq", null).containsEntry("created_by", USER)
                .containsEntry("type_cd", "01").containsEntry("cat_cd", "0001").containsEntry("source", "POS TERM")
                .containsEntry("description", "Test purchase").containsEntry("merchant_id", "123456789")
                .containsEntry("merchant_name", "Shop").containsEntry("merchant_city", "Town")
                .containsEntry("merchant_zip", "12345").containsEntry("card_num", CARD)
                .containsEntry("orig_ts", "2022-07-06" + " ".repeat(16))
                .containsEntry("proc_ts", "2026-11-07" + " ".repeat(16));
        assertThat(String.valueOf(row.get("idempotency_key"))).isEqualToIgnoringCase(key);
        assertThat(new java.math.BigDecimal(String.valueOf(row.get("amount")))).isEqualByComparingTo("-12.50");
        assertThat(row.get("raw_record")).isEqualTo(dailyRecord(expected, "0000000125}"));

        List<Map<String, Object>> audit = addAuditRows();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).containsEntry("actor_user_id", USER).containsEntry("action", "CREATE")
                .containsEntry("subject_type", "DAILY_TRAN").containsEntry("subject_key", expected)
                .containsEntry("field_names", FIELD_NAMES);
        assertThat(audit.get(0).values().toString()).doesNotContain(CARD).doesNotContain("Test purchase");
    }

    @Test
    @DisplayName("TRNA-S22: legacy takes the highest of the master and the waiting adds; fixed the sequence")
    @SpecItem({"BR-TRNA-003", "SE-TRNA-001"})
    void s22_idSource() throws Exception {
        DB.fixtures().transaction(HIGHEST, OTHER_CARD, "10.00")
                .waitingOnlineAdd("0000000000000301", OTHER_CARD, "1.00")
                .restartTranIdSequence(9_000_000_000_001_000L);
        startDataDone();
        add(valid(), key()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.tranId").value(fixed() ? "9000000000001000" : "0000000000000302"));
    }

    @Test
    @DisplayName("TRNA-S29: adds at once get distinct IDs; a retry replays, also after the cycle claimed the row")
    @SpecItem({"BR-TRNA-003", "DEF-TRNA-003", "SE-TRNA-002", "INV-001"})
    void s29_concurrentAddsAndRetries() throws Exception {
        String firstKey = key();
        String secondKey = key();
        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> call(valid(), firstKey));
        CompletableFuture<MvcResult> second = CompletableFuture.supplyAsync(
                () -> call(with(valid(), "description", "Second purchase"), secondKey));
        String[] ids = new String[2];
        int i = 0;
        for (MvcResult result : List.of(first.get(), second.get())) {
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            ids[i++] = json.readTree(result.getResponse().getContentAsString()).path("tranId").asText();
        }
        assertThat(ids[0]).isNotEqualTo(ids[1]);
        assertThat(dailyRows()).isEqualTo(2);
        assertThat(auditRows()).isEqualTo(2);

        add(valid(), firstKey).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.tranId").value(ids[0]));

        admin.sql("INSERT INTO batch.cycle (business_date) VALUES ('2026-11-07')").update();
        admin.sql("""
                        UPDATE core.daily_tran SET seq = 1,
                            cycle_id = (SELECT cycle_id FROM batch.cycle WHERE business_date = '2026-11-07')
                        WHERE tran_id = :id""")
                .param("id", ids[0]).update();
        add(valid(), firstKey).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.tranId").value(ids[0]));
        assertThat(dailyRows()).isEqualTo(2);
        assertThat(auditRows()).isEqualTo(2);
    }

    private MvcResult call(Map<String, String> form, String idempotencyKey) {
        try {
            return add(form, idempotencyKey).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("TRNA-S34: the allocated ID is held by a waiting add: 409, nothing written, no audit row")
    @SpecItem({"ERR-TRNA-001", "INV-001", "BR-TRNA-003"})
    void s34_waitingAddHoldsTheId() throws Exception {
        String held = String.valueOf(FIXED_FIRST_ID);
        DB.fixtures().waitingOnlineAdd(held, OTHER_CARD, "1.00");
        startDataDone();
        if (!fixed()) {
            // Legacy mode takes the highest ID + 1 under a lock, which never meets a waiting add.
            FORCED.set(held);
        }
        expectProblem(add(valid(), key()), 409, "TRAN_ID_EXISTS", "Tran ID already exist...", "acctId");
        assertThat(dailyRows()).isEqualTo(1);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("TRNA-S37: the last ID is added; later adds are refused with one TRAN_ID_EXHAUSTED line each")
    @ExtendWith(OutputCaptureExtension.class)
    @SpecItem({"NUM-TRNA-004", "DEF-TRNA-013", "ERR-TRNA-001"})
    void s37_lastId(CapturedOutput output) throws Exception {
        String last = "9999999999999999";
        if (fixed()) {
            DB.fixtures().restartTranIdSequence(Long.parseLong(last));
        } else {
            DB.fixtures().transaction("9999999999999998", OTHER_CARD, "10.00");
        }
        startDataDone();
        add(valid(), key()).andExpect(status().isCreated()).andExpect(jsonPath("$.tranId").value(last));

        String mode = fixed() ? "fixed" : "legacy";
        for (String description : List.of("Second purchase", "Third purchase")) {
            MvcResult refused = expectProblem(add(with(valid(), "description", description), key()), 500,
                    "ADD_FAILED", ADD_FAILED, "acctId").andReturn();
            JsonNode body = json.readTree(refused.getResponse().getContentAsString());
            assertThat(members(body)).doesNotContain("sequence");
            String correlationId = refused.getResponse().getHeader("X-Correlation-Id");
            assertThat(correlationId).isNotBlank();
            List<String> lines = Arrays.stream(output.getAll().split("\\R"))
                    .filter(l -> l.contains("TRAN_ID_EXHAUSTED") && l.contains(correlationId)).toList();
            assertThat(lines).hasSize(1);
            assertThat(lines.get(0)).contains("ERROR").contains("mode " + mode).doesNotContain(last)
                    .doesNotContain("0000000000000000");
        }
        assertThat(Arrays.stream(output.getAll().split("\\R")).filter(l -> l.contains("TRAN_ID_EXHAUSTED"))).hasSize(2);
        assertThat(dailyRows()).isEqualTo(1);
        assertThat(auditRows()).isEqualTo(1);
        assertThat(admin.sql("SELECT COUNT(*) FROM core.daily_tran WHERE tran_id = '0000000000000000'")
                .query(Integer.class).single()).isZero();
        latest().andExpect(status().isOk()).andExpect(jsonPath("$.description").value("Test purchase"));
    }
}
