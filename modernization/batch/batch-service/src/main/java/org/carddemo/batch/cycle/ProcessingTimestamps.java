package org.carddemo.batch.cycle;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * Z-GET-DB2-FORMAT-TIMESTAMP of CBTRN02C and CBACT04C: the date is the cycle's business date (the
 * legacy runs pin the clock's date to it), the time the clock's to hundredths of a second, as
 * FUNCTION CURRENT-DATE gives it.
 */
final class ProcessingTimestamps {

    private ProcessingTimestamps() {
    }

    static LocalDateTime of(LocalDate businessDate, Clock clock) {
        LocalTime now = LocalTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        return LocalDateTime.of(businessDate, now.withNano(now.getNano() / 10_000_000 * 10_000_000));
    }
}
