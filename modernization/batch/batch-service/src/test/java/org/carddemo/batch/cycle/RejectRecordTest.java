package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RejectRecordTest {

    private static final String RAW = "A".repeat(349) + "\u00ff";

    @Test
    void aRejectIsTheInputRecordTheReasonAndItsPaddedText() {
        byte[] record = ExportRejectsTasklet.record(1, RAW, "0102", "OVERLIMIT TRANSACTION");
        assertThat(record).hasSize(430);
        String text = new String(record, StandardCharsets.ISO_8859_1);
        assertThat(text.substring(0, 350)).isEqualTo(RAW);
        assertThat(text.substring(350, 354)).isEqualTo("0102");
        assertThat(text.substring(354)).isEqualTo(String.format("%-76s", "OVERLIMIT TRANSACTION"));
        assertThat(record[349]).isEqualTo((byte) 0xff);
    }

    @Test
    void aRecordOfTheWrongLengthOrWithoutAReasonFailsWithoutItsContent() {
        assertThatThrownBy(() -> ExportRejectsTasklet.record(3, RAW.substring(1), "0102", "X"))
                .isInstanceOf(CycleJobException.class).hasMessage(
                        "REJECT_RECORD_INVALID: reject 3 does not hold a 350-byte input record");
        assertThatThrownBy(() -> ExportRejectsTasklet.record(4, RAW, "102", "X"))
                .hasMessage("REJECT_RECORD_INVALID: reject 4 has no valid reason");
        assertThatThrownBy(() -> ExportRejectsTasklet.record(5, RAW, "0102", "X".repeat(77)))
                .hasMessage("REJECT_RECORD_INVALID: reject 5 has no valid reason");
    }

    @Test
    void aCharacterOutsideTheByteRangeIsNotReplacedSilently() {
        String raw = "\u0100" + RAW.substring(1);
        assertThatThrownBy(() -> ExportRejectsTasklet.record(6, raw, "0100", "INVALID CARD NUMBER FOUND"))
                .isInstanceOf(CycleJobException.class)
                .hasMessage("REJECT_RECORD_INVALID: reject 6 holds a character that no input byte maps to");
    }

    @Test
    void eachAttemptHasItsOwnFile() {
        CycleRun run = new CycleRun(12, LocalDate.of(2022, 7, 6), 2);
        assertThat(ExportRejectsTasklet.relativePath(run)).isEqualTo(Path.of("rejects", "12-2", "DALYREJS.txt"));
    }
}
