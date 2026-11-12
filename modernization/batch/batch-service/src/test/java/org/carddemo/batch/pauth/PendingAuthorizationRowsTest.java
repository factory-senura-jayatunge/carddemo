package org.carddemo.batch.pauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.carddemo.batch.data.InsertColumnsTest;
import org.carddemo.platform.legacyformat.LegacyFormatException;
import org.carddemo.platform.legacyformat.record.HdUnloadReader;
import org.carddemo.platform.legacyformat.record.HdUnloadReader.Segment;
import org.junit.jupiter.api.Test;

/** The detail decoding rules the load applies before any write, and the columns its rows name. */
class PendingAuthorizationRowsTest {

    private static final byte[] BLANK = {0x40, 0x40};

    private static Segment detail(PauthUnload.Patched patched) {
        return HdUnloadReader.read(patched.bytes(), "unload", PendingAuthorizationRows.SEGMENTS).stream()
                .filter(s -> s.recordNumber() == patched.recordNumber()).findFirst().orElseThrow();
    }

    private static byte[] ebcdic(String text) {
        return text.getBytes(HdUnloadReader.EBCDIC);
    }

    @Test
    void aResponseCodeOtherThan00Or05IsRefusedBlankIncluded() {
        for (byte[] code : List.of(BLANK, ebcdic("01"), ebcdic("5 "), ebcdic(" 0"), ebcdic("0 "))) {
            Segment s = detail(PauthUnload.withFirstDetailField("PA-AUTH-RESP-CODE", code));
            assertThatThrownBy(() -> PendingAuthorizationRows.detail(s.data()))
                    .isInstanceOf(LegacyFormatException.class)
                    .hasMessage("PA-AUTH-RESP-CODE is not one of the values the table accepts");
        }
        for (String code : List.of("00", "05")) {
            Segment s = detail(PauthUnload.withFirstDetailField("PA-AUTH-RESP-CODE", ebcdic(code)));
            assertThat(PendingAuthorizationRows.detail(s.data()).respCode()).isEqualTo(code);
        }
    }

    @Test
    void theRowsNameEveryColumnTheMigrationsGiveExceptTheOnesTheLoadLeavesNull() {
        Segment summary = HdUnloadReader.read(PauthUnload.sample(), "unload", PendingAuthorizationRows.SEGMENTS)
                .stream().filter(s -> s.name().equals(PendingAuthorizationRows.ROOT))
                .filter(s -> !s.data().allBytes("PA-ACCT-ID", 0x40)).findFirst().orElseThrow();
        Segment detail = detail(PauthUnload.withFirstDetailField("PA-AUTH-RESP-CODE", ebcdic("00")));

        assertThat(PendingAuthorizationRows.summaryColumns(PendingAuthorizationRows.summary(summary.data())).keySet())
                .containsExactlyInAnyOrderElementsOf(InsertColumnsTest.insertableColumns("pauth.auth_summary"));
        Set<String> detailColumns = new LinkedHashSet<>(InsertColumnsTest.insertableColumns("pauth.auth_detail"));
        assertThat(detailColumns).as("left NULL by the load").containsAll(Set.of("fraud_changed_by",
                "matched_tran_pk", "matched_tran_id", "matched_cycle_id", "matched_at"));
        detailColumns.removeAll(Set.of("fraud_changed_by", "matched_tran_pk", "matched_tran_id", "matched_cycle_id",
                "matched_at"));
        assertThat(PendingAuthorizationRows.detailColumns("00000000001",
                PendingAuthorizationRows.detail(detail.data())).keySet())
                .containsExactlyInAnyOrderElementsOf(detailColumns);
    }
}
