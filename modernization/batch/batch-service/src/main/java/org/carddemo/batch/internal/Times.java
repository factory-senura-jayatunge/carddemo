package org.carddemo.batch.internal;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Times of the internal API: UTC, to the millisecond, with Z (batch-service and SQL Server run in UTC). */
final class Times {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private Times() {
    }

    static String utc(LocalDateTime time) {
        return time == null ? null : time.format(UTC);
    }
}
