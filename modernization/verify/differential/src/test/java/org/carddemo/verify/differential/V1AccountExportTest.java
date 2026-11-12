package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.junit.jupiter.api.Test;

/**
 * The v1 account export: the stored ZIP at 103-112 and the stored group ID at 113-122 in legacy
 * mode, exchanged in fixed mode, every other byte the same; and fixed mode refuses a database
 * holding accounts stored as read.
 */
class V1AccountExportTest {

    private static final String ZIP = "12345     ";
    private static final String GROUP = "GROUPA    ";

    private static AccountRecord account() {
        BigDecimal amount = new BigDecimal("12.34");
        return new AccountRecord("00000000001", "Y", amount, amount, amount, "2020-01-01", "2030-01-01", "2025-01-01",
                amount, amount, "stale-zip", "stale-grp", null);
    }

    private static String field(byte[] record, int column, int length) {
        return new String(record, column - 1, length, StandardCharsets.ISO_8859_1);
    }

    @Test
    void legacyModeWritesTheZipAt103AndTheGroupAt113() {
        byte[] record = BatchExport.v1Account(account(), ZIP, GROUP, FidelityMode.LEGACY);

        assertThat(record).hasSize(300);
        assertThat(field(record, 103, 10)).isEqualTo(ZIP);
        assertThat(field(record, 113, 10)).isEqualTo(GROUP);
    }

    @Test
    void fixedModeExchangesThemAndNothingElse() {
        byte[] legacy = BatchExport.v1Account(account(), ZIP, GROUP, FidelityMode.LEGACY);
        byte[] fixed = BatchExport.v1Account(account(), ZIP, GROUP, FidelityMode.FIXED);

        assertThat(fixed).hasSize(300);
        assertThat(field(fixed, 103, 10)).isEqualTo(GROUP);
        assertThat(field(fixed, 113, 10)).isEqualTo(ZIP);
        assertThat(Arrays.copyOfRange(fixed, 0, 102)).isEqualTo(Arrays.copyOfRange(legacy, 0, 102));
        assertThat(Arrays.copyOfRange(fixed, 122, 300)).isEqualTo(Arrays.copyOfRange(legacy, 122, 300));
    }

    @Test
    void fixedModeRefusesAccountsStoredAsRead() {
        assertThatThrownBy(() -> BatchExport.refuseFixedV1(3)).isInstanceOf(ExportException.class)
                .hasMessageContaining("cannot export 3 accounts stored as read").hasMessageContaining("--load-execution-id");
        assertThatCode(() -> BatchExport.refuseFixedV1(0)).doesNotThrowAnyException();
    }
}
