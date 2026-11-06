package org.carddemo.platform.fidelity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FidelityLedgerTest {

    @Test
    void readsTheItemsTableAndSkipsEntriesWithoutALegacyMode() {
        Map<String, FidelityMode> modes = FidelityLedger.parse(List.of(
                "# comment with \"quotes\" = { mode = \"legacy\" }",
                "schema = 1",
                "[items]",
                "\"DEF-BILL-010\" = { mode = \"fixed\", decision = \"D-28\", date = 2026-11-06 } # trailing",
                "\"DEF-BILL-009\" = { mode = \"legacy\", note = \"a note, with { braces } and mode = \\\"fixed\\\"\" }",
                "\"DEF-BILL-001\" = { mode = \"fixed\", legacy_mode = false, deviation = \"DEV-ARCH-01\" }",
                "\"DEF-JCLOSE-003\" = { mode = \"fixed\", setting = \"WRITES_BLOCKED\", tags = [\"a\", \"b\"] }"), "t");
        assertThat(modes).containsExactly(
                Map.entry("DEF-BILL-010", FidelityMode.FIXED),
                Map.entry("DEF-BILL-009", FidelityMode.LEGACY),
                Map.entry("DEF-JCLOSE-003", FidelityMode.FIXED));
    }

    @Test
    void readsSwitchTables() {
        Map<String, FidelityMode> modes = FidelityLedger.parse(List.of(
                "[switch.\"DEF-BILL-010\"]",
                "value = \"fixed\"          # legacy | fixed",
                "code_switch = true",
                "[switch.\"Q-BILL-001\"]",
                "value = \"legacy\"",
                "code_switch = false",
                "[switch.\"BR-TRNA-003\"]",
                "value = \"fixed\""), "t");
        assertThat(modes).containsExactly(
                Map.entry("DEF-BILL-010", FidelityMode.FIXED), Map.entry("BR-TRNA-003", FidelityMode.FIXED));
    }

    @Test
    void locatesTheNearestModernizationLedger(@TempDir Path root) throws Exception {
        Path ledger = Files.createDirectories(root.resolve("modernization/ledger"));
        Files.writeString(ledger.resolve("fidelity.toml"), "");
        Path module = Files.createDirectories(root.resolve("modernization/services/billpay-service"));
        assertThat(FidelityLedger.locate(null, module)).contains(ledger.resolve("fidelity.toml"));
        assertThat(FidelityLedger.locate(ledger.toString(), Path.of("/"))).contains(ledger.resolve("fidelity.toml"));
        assertThat(FidelityLedger.locate(root.resolve("nowhere").toString(), module)).isEmpty();
    }
}
