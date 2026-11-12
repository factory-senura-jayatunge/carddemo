package org.carddemo.platform.web;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The explicit {@code ADMIN} request that reveals masked PII (D-09, D-SEC-05). It names the fields
 * it wants; the response holds only those, and no cache may keep it. The caller writes the
 * {@link AuditLog} row in the same transaction as the read.
 */
public final class Unmask {

    /** The request body: {@code {"fields": ["ssn", "dob"]}}. */
    public record Request(List<String> fields) {
    }

    private Unmask() {
    }

    /**
     * The requested field names, sorted and without duplicates. None, or one not in
     * {@code allowed}: {@code 400 INVALID_UNMASK_FIELD}, naming the allowed fields.
     */
    public static SortedSet<String> requireFields(Request request, Set<String> allowed) {
        return requireFields(request, List.copyOf(new TreeSet<>(allowed)));
    }

    /** As {@link #requireFields(Request, Set)}; the message names the allowed fields in this order. */
    public static SortedSet<String> requireFields(Request request, List<String> allowed) {
        List<String> fields = request == null || request.fields() == null ? List.of() : request.fields();
        if (fields.isEmpty() || !allowed.containsAll(fields)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_UNMASK_FIELD, "fields",
                    String.join(", ", allowed));
        }
        return new TreeSet<>(fields);
    }

    /** {@code 200} with {@code Cache-Control: no-store} and {@code Pragma: no-cache}. */
    public static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
    }

    static String join(Collection<String> fieldNames) {
        return String.join(",", new TreeSet<>(fieldNames));
    }
}
