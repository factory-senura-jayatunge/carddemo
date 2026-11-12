package org.carddemo.authorization.summary;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;

/**
 * A place in an account's list, newest first: {@code processed_at}, then {@code auth_id}. The key
 * {@code "<processed_at>|<auth_id>"} carries the position itself, so a row purged after it was shown
 * still positions the next page (contract §8).
 */
public record ListPosition(LocalDateTime processedAt, long authId) {

    /** {@code processed_at} is DATETIME2(3): milliseconds always, so one position has one key. */
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS")
            .withResolverStyle(ResolverStyle.STRICT);

    public ListPosition {
        if (authId < 1) {
            throw new IllegalArgumentException("authId " + authId);
        }
    }

    public String key() {
        return at() + "|" + authId;
    }

    /** The ISO-8601 text the repository casts to DATETIME2(3). */
    public String at() {
        return AT.format(processedAt);
    }

    /** Empty for anything {@link #key()} did not make. */
    public static Optional<ListPosition> parse(String key) {
        int bar = key.indexOf('|');
        if (bar < 0) {
            return Optional.empty();
        }
        String id = key.substring(bar + 1);
        if (!id.matches("[1-9][0-9]{0,18}")) {
            return Optional.empty();
        }
        try {
            ListPosition position = new ListPosition(LocalDateTime.parse(key.substring(0, bar), AT), Long.parseLong(id));
            return position.key().equals(key) ? Optional.of(position) : Optional.empty();
        } catch (DateTimeParseException | NumberFormatException e) {
            return Optional.empty();
        }
    }
}
