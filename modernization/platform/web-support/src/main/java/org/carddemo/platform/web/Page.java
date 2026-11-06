package org.carddemo.platform.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One page of a keyset list (D-54): the rows, whether a next and a previous page exist, and the
 * keys of the first and last row, which the client sends back as {@code after} or {@code before}.
 * {@code hasNext} comes from reading one row more than the page holds, as the legacy browse did.
 */
public record Page<T>(List<T> items, boolean hasNext, boolean hasPrevious, String firstKey, String lastKey) {

    public Page {
        items = List.copyOf(items);
    }

    /**
     * A page read forwards ({@link PageRequest.Kind#FIRST}, {@code START} or {@code AFTER}):
     * {@code fetched} holds up to {@code size + 1} rows in ascending key order.
     * {@code hasPrevious} is the caller's answer (true after {@code AFTER}; a one-row probe before
     * the first key otherwise).
     */
    public static <T> Page<T> forward(List<T> fetched, int size, Function<T, String> key, boolean hasPrevious) {
        Objects.requireNonNull(key, "key");
        boolean more = fetched.size() > size;
        List<T> rows = more ? fetched.subList(0, size) : fetched;
        return of(rows, more, hasPrevious, key);
    }

    /**
     * A page read backwards ({@code BEFORE}): {@code fetchedDescending} holds up to
     * {@code size + 1} rows in descending key order. The page shows them ascending; the row the
     * request named exists, so there is a next page.
     */
    public static <T> Page<T> backward(List<T> fetchedDescending, int size, Function<T, String> key) {
        Objects.requireNonNull(key, "key");
        boolean more = fetchedDescending.size() > size;
        List<T> rows = new ArrayList<>(more ? fetchedDescending.subList(0, size) : fetchedDescending);
        Collections.reverse(rows);
        return of(rows, true, more, key);
    }

    private static <T> Page<T> of(List<T> rows, boolean hasNext, boolean hasPrevious, Function<T, String> key) {
        String first = rows.isEmpty() ? null : key.apply(rows.getFirst());
        String last = rows.isEmpty() ? null : key.apply(rows.getLast());
        return new Page<>(rows, hasNext, hasPrevious, first, last);
    }

    /** The same page with each row turned into its response form; the keys stay. */
    public <R> Page<R> map(Function<T, R> mapper) {
        return new Page<>(items.stream().map(mapper).toList(), hasNext, hasPrevious, firstKey, lastKey);
    }

    /** The same page with the keys replaced, for example by opaque {@link Cursors} tokens. */
    public Page<T> withKeys(Function<String, String> keyMapper) {
        return new Page<>(items, hasNext, hasPrevious,
                firstKey == null ? null : keyMapper.apply(firstKey), lastKey == null ? null : keyMapper.apply(lastKey));
    }
}
