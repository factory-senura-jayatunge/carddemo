package org.carddemo.authorization.decision;

import java.time.LocalDateTime;
import java.time.temporal.ChronoField;

/**
 * The 9-complement parts of a detail's key (NUM-AUTH-005), from its processing time:
 * {@code 99999 - YYDDD} and {@code 999999999 - (HHMMSS * 1000 + milliseconds)}. They are unique per
 * account ({@code UQ_auth_detail_9c}); two requests in the same millisecond collide (ERR-AUTH-009).
 */
public record DetailKey(int date9c, int time9c) {

    public static DetailKey of(LocalDateTime processedAt) {
        int yyddd = (processedAt.getYear() % 100) * 1000 + processedAt.getDayOfYear();
        int hhmmss = processedAt.getHour() * 10000 + processedAt.getMinute() * 100 + processedAt.getSecond();
        int millis = processedAt.get(ChronoField.MILLI_OF_SECOND);
        return new DetailKey(99999 - yyddd, 999999999 - (hhmmss * 1000 + millis));
    }
}
