package org.carddemo.batch.pauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;

/** The purge's parameter rules, without a database. */
class PurgeParametersTest {

    private static JobParametersBuilder valid() {
        return new JobParametersBuilder()
                .addLong("cycleId", 7L).addLocalDate("businessDate", LocalDate.of(2024, 1, 2)).addLong("attempt", 1L)
                .addLong("expiryDays", 5L, false).addLong("commitInterval", 10L, false);
    }

    private static void invalid(JobParameters p, String message) {
        assertThatThrownBy(() -> PurgeParameters.VALIDATOR.validate(p))
                .isInstanceOf(InvalidJobParametersException.class).hasMessageContaining(message);
    }

    @Test
    @SpecItem("FLOW-AUPG-001")
    void theCyclesParametersAreRequiredWithTheirTypesAndIdentifyingFlags() throws Exception {
        PurgeParameters p = PurgeParameters.of(valid().toJobParameters());
        assertThat(p).isEqualTo(new PurgeParameters(7, LocalDate.of(2024, 1, 2), 1, 5, 10));

        for (String name : new String[] {"cycleId", "businessDate", "attempt", "expiryDays", "commitInterval"}) {
            JobParameters without = new JobParameters(valid().toJobParameters().parameters().stream()
                    .filter(x -> !x.name().equals(name)).collect(java.util.stream.Collectors.toSet()));
            invalid(without, name + " is missing");
        }
        invalid(valid().addString("cycleId", "7").toJobParameters(), "cycleId must be a Long");
        invalid(valid().addString("businessDate", "2024-01-02").toJobParameters(), "businessDate must be a LocalDate");
        invalid(valid().addLong("expiryDays", 5L, true).toJobParameters(), "expiryDays must be non-identifying");
        invalid(valid().addLong("commitInterval", 10L, true).toJobParameters(), "commitInterval must be non-identifying");
        invalid(valid().addLong("attempt", 1L, false).toJobParameters(), "attempt must be identifying");
    }

    @Test
    @SpecItem({"DEF-AUPG-001", "FLOW-AUPG-001"})
    void expiryDaysIsAtLeastOneWithNoMaximumAndTheCutoffMustBeAStoredDate() throws Exception {
        invalid(valid().addLong("expiryDays", 0L, false).toJobParameters(), "expiryDays must be at least 1");
        invalid(valid().addLong("expiryDays", -1L, false).toJobParameters(), "expiryDays must be at least 1");
        invalid(valid().addLong("expiryDays", Long.MAX_VALUE, false).toJobParameters(), "before 0001-01-01");
        invalid(valid().addLocalDate("businessDate", LocalDate.of(1, 1, 5)).toJobParameters(), "before 0001-01-01");

        PurgeParameters edge = PurgeParameters.of(valid().addLocalDate("businessDate", LocalDate.of(1, 1, 5))
                .addLong("expiryDays", 4L, false).toJobParameters());
        assertThat(edge.detailCutoff()).isEqualTo(LocalDate.of(1, 1, 1));
        PurgeParameters large = PurgeParameters.of(valid().addLocalDate("businessDate", LocalDate.of(9999, 12, 31))
                .addLong("expiryDays", 3_000_000L, false).toJobParameters());
        assertThat(large.detailCutoff()).isEqualTo(LocalDate.of(9999, 12, 31).minusDays(3_000_000));
    }

    @Test
    void commitIntervalIsAtLeastOne() {
        invalid(valid().addLong("commitInterval", 0L, false).toJobParameters(), "commitInterval must be between 1");
        invalid(valid().addLong("commitInterval", 1L + Integer.MAX_VALUE, false).toJobParameters(),
                "commitInterval must be between 1");
    }

    @Test
    @SpecItem({"BR-AUPG-001", "BR-AUPG-002", "DEF-AUPG-004"})
    void theCutoffCountsCalendarDaysBackFromTheBusinessDate() throws Exception {
        PurgeParameters p = PurgeParameters.of(valid().toJobParameters());
        assertThat(p.detailCutoff()).as("2024-01-02 - 5 days, across the year end").isEqualTo(LocalDate.of(2023, 12, 28));
        assertThat(p.detailsBefore()).isEqualTo(LocalDateTime.of(2023, 12, 29, 0, 0));
    }

    @Test
    void theMessageBoundIsTheEarlierOfTheBusinessAndTheDatabaseClockCutoffs() throws Exception {
        PurgeParameters p = PurgeParameters.of(valid().toJobParameters());
        assertThat(p.messagesBefore(LocalDate.of(2026, 11, 10))).contains(LocalDateTime.of(2023, 12, 29, 0, 0));
        assertThat(p.messagesBefore(LocalDate.of(2023, 12, 30))).as("business date ahead of the clock")
                .contains(LocalDateTime.of(2023, 12, 26, 0, 0));
        assertThat(p.messagesBefore(LocalDate.of(1, 1, 3))).isEmpty();
    }
}
