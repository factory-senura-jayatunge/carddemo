package org.carddemo.authorization.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DetailKeyTest {

    @Test
    @DisplayName("NUM-AUTH-005: 99999 - YYDDD and 999999999 - (HHMMSS * 1000 + ms)")
    @SpecItem("NUM-AUTH-005")
    void complement() {
        DetailKey k = DetailKey.of(LocalDateTime.of(2024, 1, 2, 10, 0, 0, 123_000_000));
        assertThat(k.date9c()).isEqualTo(99999 - 24002);
        assertThat(k.time9c()).isEqualTo(999999999 - 100000123);
        DetailKey end = DetailKey.of(LocalDateTime.of(2024, 12, 31, 23, 59, 59, 999_999_999));
        assertThat(end.date9c()).isEqualTo(99999 - 24366);
        assertThat(end.time9c()).isEqualTo(999999999 - 235959999);
    }

    @Test
    @DisplayName("M27: one millisecond apart gives distinct keys, newer first; the same millisecond collides")
    @SpecItem({"NUM-AUTH-005", "ERR-AUTH-009"})
    void millisecond() {
        LocalDateTime t = LocalDateTime.of(2024, 1, 2, 10, 0, 0, 5_000_000);
        DetailKey a = DetailKey.of(t);
        DetailKey b = DetailKey.of(t.plusNanos(1_000_000));
        assertThat(b.time9c()).isEqualTo(a.time9c() - 1);
        assertThat(DetailKey.of(t.plusNanos(999_999))).isEqualTo(a);
    }
}
