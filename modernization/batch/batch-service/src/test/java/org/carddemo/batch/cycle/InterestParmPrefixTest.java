package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.BothModes;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.FidelityOverrides;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * DEF-JINTC-001 on its own, with DEF-INTC-010 legacy so the IDs carry the prefix: legacy takes the
 * configured INTCALC PARM, fixed the cycle's interestParm, its business date and {@code 00}.
 */
@BothModes("DEF-JINTC-001")
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.fidelity.DEF-INTC-010=legacy",
        "carddemo.batch.interest.legacy-parm=2022071800"})
class InterestParmPrefixTest extends CycleJobTestBase {

    @Test
    @SpecItem({"DEF-JINTC-001", "BR-INTC-005", "FLOW-JINTC-001"})
    void theIdPrefixComesFromWhereTheModeSays() {
        DB.admin().sql("DELETE FROM ref.disclosure_group").update();
        DB.admin().sql("INSERT INTO ref.disclosure_group (group_id, type_cd, cat_cd, int_rate)"
                + " VALUES ('DEFAULT', '01', '0001', :r)").param("r", new BigDecimal("18.00")).update();
        DB.fixtures().customer("000000001").account(CoreFixtures.Account.of("00000000001", "0.00"))
                .card("9".repeat(12) + "0001", "00000000001", "000000001")
                .categoryBalance("00000000001", "01", "0001", "120.00");
        long cycle = openCycle("2101-06-07", "always");

        JobExecution run = launch("calculateInterestJob", cycle);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        String prefix = FidelityOverrides.current() == FidelityMode.LEGACY ? "2022071800" : "2101060700";
        List<String> rows = DB.admin().sql("SELECT CONCAT(tran_id, ' ', amount) FROM core.[transaction]"
                + " WHERE cycle_id = :c AND origin = 'INTEREST'").param("c", cycle).query(String.class).list();
        assertThat(rows).containsExactly(prefix + "000001 1.80");
    }
}
