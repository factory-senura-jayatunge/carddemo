package org.carddemo.batch.internal;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/** Reads members of a JSON request; a missing or malformed member is the caller's error, naming it. */
final class RequestValues {

    static final int MAX_KEY_LENGTH = 250;
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,18}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final BiFunction<String, String, InternalApiException> error;

    private RequestValues(BiFunction<String, String, InternalApiException> error) {
        this.error = error;
    }

    static RequestValues forRequest() {
        return new RequestValues(InternalApiException::invalidRequest);
    }

    static RequestValues forJobParameters() {
        return new RequestValues(InternalApiException::invalidParameter);
    }

    InternalApiException error(String field, String message) {
        return error.apply(field, message);
    }

    /** A positive ID: a JSON integer or a string of digits (Airflow's templates render numbers as strings). */
    long id(Object value, String field) {
        if (value == null) {
            throw error(field, field + " is required.");
        }
        long id;
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            id = ((Number) value).longValue();
        } else if (value instanceof BigInteger big && big.bitLength() < 63) {
            id = big.longValue();
        } else if (value instanceof String s && DIGITS.matcher(s).matches()) {
            id = Long.parseLong(s);
        } else {
            throw error(field, field + " must be a positive integer.");
        }
        if (id < 1) {
            throw error(field, field + " must be a positive integer.");
        }
        return id;
    }

    String key(Object value, String field) {
        if (value == null) {
            throw error(field, field + " is required.");
        }
        if (!(value instanceof String s) || s.isEmpty() || s.length() > MAX_KEY_LENGTH) {
            throw error(field, field + " must be a string of 1 to " + MAX_KEY_LENGTH + " characters.");
        }
        return s;
    }

    LocalDate date(Object value, String field) {
        if (value == null) {
            throw error(field, field + " is required.");
        }
        if (value instanceof String s && s.length() == 10) {
            try {
                return LocalDate.parse(s, DATE);
            } catch (DateTimeParseException e) {
                // falls through to the error below
            }
        }
        throw error(field, field + " must be a date yyyy-MM-dd.");
    }

    boolean bool(Object value, String field) {
        if (value instanceof Boolean b) {
            return b;
        }
        throw error(field, field + " must be true or false.");
    }
}
