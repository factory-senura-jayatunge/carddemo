package org.carddemo.platform.web;

import org.springframework.http.HttpStatus;

/**
 * Where a keyset page starts (D-54): the first page, the first key equal or greater than
 * {@code start}, the rows after {@code after} (next page) or before {@code before} (previous
 * page), and how many rows the page holds, at most the screen's row count.
 */
public record PageRequest(Kind kind, String key, int size) {

    public enum Kind { FIRST, START, AFTER, BEFORE }

    /**
     * At most one of {@code start}, {@code after} and {@code before} (else
     * {@code 400 INVALID_PAGE_REQUEST}); a blank one counts as absent. {@code size} defaults to
     * {@code screenSize} and must be 1 to {@code screenSize} ({@code 400 INVALID_PAGE_SIZE}).
     */
    public static PageRequest of(String start, String after, String before, Integer size, int screenSize) {
        if (screenSize < 1) {
            throw new IllegalArgumentException("screenSize " + screenSize);
        }
        int given = present(start) + present(after) + present(before);
        if (given > 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_PAGE_REQUEST, null);
        }
        int n = size == null ? screenSize : size;
        if (n < 1 || n > screenSize) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_PAGE_SIZE, null,
                    Integer.toString(screenSize));
        }
        if (present(after) == 1) {
            return new PageRequest(Kind.AFTER, after, n);
        }
        if (present(before) == 1) {
            return new PageRequest(Kind.BEFORE, before, n);
        }
        if (present(start) == 1) {
            return new PageRequest(Kind.START, start, n);
        }
        return new PageRequest(Kind.FIRST, null, n);
    }

    /** Rows to read: one more than the page, for {@code hasNext} (or {@code hasPrevious} backwards). */
    public int fetchSize() {
        return size + 1;
    }

    private static int present(String s) {
        return s == null || s.isBlank() ? 0 : 1;
    }
}
