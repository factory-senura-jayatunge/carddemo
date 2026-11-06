package org.carddemo.platform.legacyformat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class LegacyTimestampsTest {

    private static final LocalDateTime T = LocalDateTime.of(2022, 7, 6, 13, 5, 9, 123_456_789);

    @Test
    void writerLayouts() {
        assertThat(LegacyTimestamps.online(T)).isEqualTo("2022-07-06 13:05:09.000000");
        assertThat(LegacyTimestamps.posting(T)).isEqualTo("2022-07-06-13.05.09.120000");
        assertThat(LegacyTimestamps.isoMicros(T)).isEqualTo("2022-07-06 13:05:09.123456");
        assertThat(LegacyTimestamps.dateOnly(T.toLocalDate())).isEqualTo("2022-07-06" + " ".repeat(16));
        assertThat(LegacyTimestamps.exportHeader(T)).isEqualTo("2022-07-06 13:05:09.00\0\0\0\0");
        for (String s : new String[] {LegacyTimestamps.online(T), LegacyTimestamps.posting(T),
                LegacyTimestamps.isoMicros(T), LegacyTimestamps.dateOnly(T.toLocalDate()),
                LegacyTimestamps.exportHeader(T)}) {
            assertThat(s).hasSize(LegacyTimestamps.WIDTH);
        }
    }

    @Test
    void onlineIsWholeSeconds() {
        assertThat(LegacyTimestamps.parse(LegacyTimestamps.online(T))).contains(T.withNano(0));
    }

    @Test
    void classifyAndParse() {
        assertThat(LegacyTimestamps.classify(LegacyTimestamps.posting(T))).isEqualTo(TimestampLayout.DB2);
        assertThat(LegacyTimestamps.parse(LegacyTimestamps.posting(T))).contains(T.withNano(120_000_000));
        assertThat(LegacyTimestamps.classify(LegacyTimestamps.isoMicros(T))).isEqualTo(TimestampLayout.ISO_MICROS);
        assertThat(LegacyTimestamps.parse(LegacyTimestamps.isoMicros(T))).contains(T.withNano(123_456_000));
        assertThat(LegacyTimestamps.classify(LegacyTimestamps.dateOnly(T.toLocalDate())))
                .isEqualTo(TimestampLayout.DATE_ONLY);
        assertThat(LegacyTimestamps.parse(LegacyTimestamps.dateOnly(T.toLocalDate())))
                .contains(T.toLocalDate().atStartOfDay());
        assertThat(LegacyTimestamps.classify(" ".repeat(26))).isEqualTo(TimestampLayout.BLANK);
        assertThat(LegacyTimestamps.classify("2022-02-30 10:00:00.000000")).isEqualTo(TimestampLayout.UNKNOWN);
        assertThat(LegacyTimestamps.classify("short")).isEqualTo(TimestampLayout.UNKNOWN);
        assertThat(LegacyTimestamps.parse("2022-07-06T13:05:09.000000")).isEmpty();
    }

    @Test
    void strictDates() {
        assertThat(LegacyTimestamps.parseDate("2024-02-29")).contains(LocalDate.of(2024, 2, 29));
        assertThat(LegacyTimestamps.parseDate("2023-02-29")).isEmpty();
        assertThat(LegacyTimestamps.parseDate("2023-2-28")).isEmpty();
        assertThat(LegacyTimestamps.formatDate(LocalDate.of(2025, 1, 2))).isEqualTo("2025-01-02");
    }

    @Test
    void maskingKeepsTheLastFour() {
        assertThat(Masking.lastFour("4111111111111111")).isEqualTo("************1111");
        assertThat(Masking.lastFour("123")).isEqualTo("***");
        assertThat(Masking.lastFour(null)).isNull();
    }
}
