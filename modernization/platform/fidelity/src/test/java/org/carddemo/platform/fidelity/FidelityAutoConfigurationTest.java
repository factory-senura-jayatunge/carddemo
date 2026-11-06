package org.carddemo.platform.fidelity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class FidelityAutoConfigurationTest {

    static final FidelitySwitch DEF_BILL_010 = FidelitySwitch.of("DEF-BILL-010", "balance only", "posted");
    static final FidelitySwitch DEF_BILL_014 = FidelitySwitch.of("DEF-BILL-014", "editable", "locked");

    @TempDir
    Path ledgerDir;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FidelityAutoConfiguration.class))
                .withBean("billPaySwitches", FidelitySwitchCatalog.class, () -> FidelitySwitchCatalog.of(DEF_BILL_010, DEF_BILL_014))
                .withPropertyValues("carddemo.ledger.dir=" + ledgerDir);
    }

    private void writeLedger(String body) throws Exception {
        Files.writeString(ledgerDir.resolve("fidelity.toml"), body);
    }

    @Test
    void propertiesSetModesAndUnsetSwitchesStayLegacy() {
        runner().withPropertyValues("carddemo.fidelity.DEF-BILL-010=fixed").run(context -> {
            FidelitySwitches switches = context.getBean(FidelitySwitches.class);
            assertThat(switches.isFixed(DEF_BILL_010)).isTrue();
            assertThat(switches.fixed("DEF-BILL-010")).isTrue();
            assertThat(switches.isLegacy(DEF_BILL_014)).isTrue();
        });
    }

    @Test
    void environmentVariableFormIsAccepted() {
        runner().withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("CARDDEMO_FIDELITY_DEF_BILL_014", "FIXED"))))
                .run(context -> assertThat(context.getBean(FidelitySwitches.class).isFixed(DEF_BILL_014)).isTrue());
    }

    @Test
    void ledgerSetsDeclaredSwitchesAndIgnoresTheOtherUnits() throws Exception {
        writeLedger("""
                schema = 1
                [items]
                "DEF-BILL-010" = { mode = "fixed", decision = "D-28", approver = "user", date = 2026-11-06 }
                "DEF-SGN-002" = { mode = "fixed", decision = "D-05" }
                """);
        runner().run(context -> {
            FidelitySwitches switches = context.getBean(FidelitySwitches.class);
            assertThat(switches.isFixed(DEF_BILL_010)).isTrue();
            assertThat(switches.isLegacy(DEF_BILL_014)).isTrue();
        });
    }

    @Test
    void explicitPropertyWinsOverTheLedger() throws Exception {
        writeLedger("""
                [items]
                "DEF-BILL-010" = { mode = "fixed" }
                """);
        runner().withPropertyValues("carddemo.fidelity.def-bill-010=legacy").run(context ->
                assertThat(context.getBean(FidelitySwitches.class).isLegacy(DEF_BILL_010)).isTrue());
    }

    @Test
    void missingLedgerLeavesEverySwitchLegacy() {
        runner().withPropertyValues("carddemo.ledger.dir=" + ledgerDir.resolve("absent")).run(context ->
                assertThat(context.getBean(FidelitySwitches.class).effectiveModes())
                        .containsOnly(Map.entry("DEF-BILL-010", FidelityMode.LEGACY), Map.entry("DEF-BILL-014", FidelityMode.LEGACY)));
    }

    @Test
    void unknownSwitchStopsTheApplication() {
        runner().withPropertyValues("carddemo.fidelity.DEF-BILL-099=fixed").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("DEF-BILL-099"));
    }

    @Test
    void invalidModeStopsTheApplication() {
        runner().withPropertyValues("carddemo.fidelity.DEF-BILL-010=maybe").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("legacy or fixed"));
    }

    @Test
    void invalidLedgerModeStopsTheApplication() throws Exception {
        writeLedger("""
                [items]
                "DEF-BILL-010" = { mode = "maybe" }
                """);
        runner().run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("fidelity.toml:2"));
    }
}
