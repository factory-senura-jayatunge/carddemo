package org.carddemo.batch.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class InternalValuesTest {

    private final RequestValues values = RequestValues.forJobParameters();

    @Test
    void anIdIsAJsonIntegerOrAStringOfDigits() {
        assertThat(values.id(42, "exportId")).isEqualTo(42);
        assertThat(values.id(42L, "exportId")).isEqualTo(42);
        assertThat(values.id("42", "exportId")).isEqualTo(42);
        assertThat(values.id(BigInteger.valueOf(7), "exportId")).isEqualTo(7);
        for (Object bad : new Object[] {"4 2", "-1", 0, -3, 4.5, "", "1234567890123456789", true}) {
            assertThatThrownBy(() -> values.id(bad, "exportId")).as(String.valueOf(bad))
                    .isInstanceOfSatisfying(InternalApiException.class, e -> {
                        assertThat(e.code()).isEqualTo("INVALID_JOB_PARAMETERS");
                        assertThat(e.field()).isEqualTo("exportId");
                    });
        }
    }

    @Test
    void aDateIsStrictIso() {
        assertThat(values.date("2024-02-29", "businessDate")).isEqualTo(LocalDate.of(2024, 2, 29));
        for (String bad : new String[] {"2023-02-29", "2022-7-06", "20220706", "2022-07-06T00:00"}) {
            assertThatThrownBy(() -> values.date(bad, "businessDate")).as(bad).isInstanceOf(InternalApiException.class);
        }
    }

    @Test
    void theInterestPolicyResolvesToTheParmOrNone() {
        assertThat(Cycles.interestParm(LocalDate.of(2022, 7, 6), Cycles.Interest.ALWAYS)).isEqualTo("2022070600");
        assertThat(Cycles.interestParm(LocalDate.of(2022, 7, 6), Cycles.Interest.MONTH_END)).isNull();
        assertThat(Cycles.interestParm(LocalDate.of(2024, 2, 29), Cycles.Interest.MONTH_END)).isEqualTo("2024022900");
        assertThat(Cycles.interestParm(LocalDate.of(2024, 2, 29), Cycles.Interest.NEVER)).isNull();
    }

    @Test
    void theCliRunKeyNamesTheHostTheMicrosecondAndARandomIdInAtMost250Characters() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Instant now = Instant.parse("2022-07-06T21:00:00.123456789Z");
        assertThat(LoadCommand.runKey("host-a", now, id))
                .isEqualTo("cli/host-a/20220706T210000.123456Z/00000000-0000-0000-0000-000000000001");
        assertThat(LoadCommand.runKey("h".repeat(255), now, id)).hasSize(250);
    }
}
