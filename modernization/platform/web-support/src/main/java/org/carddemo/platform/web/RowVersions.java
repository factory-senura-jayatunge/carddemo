package org.carddemo.platform.web;

import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Optimistic concurrency on SQL Server {@code ROWVERSION} columns (D-17, INV-037, INV-042). A
 * resource's version is the hex of every row version a save of it will change, joined with
 * {@code .}; reads send it as a strong {@code ETag} and in the body, saves must send it back in
 * {@code If-Match}.
 */
public final class RowVersions {

    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern TAG = Pattern.compile("[0-9a-f]{16}(\\.[0-9a-f]{16})*");

    private RowVersions() {
    }

    /** The version as it travels in a body ({@code version}), without quotes. */
    public static String tag(byte[]... rowVersions) {
        if (rowVersions.length == 0) {
            throw new IllegalArgumentException("no row version");
        }
        StringBuilder sb = new StringBuilder();
        for (byte[] v : rowVersions) {
            if (v == null || v.length != 8) {
                throw new IllegalArgumentException("a ROWVERSION has 8 bytes");
            }
            sb.append(sb.isEmpty() ? "" : ".").append(HEX.formatHex(v));
        }
        return sb.toString();
    }

    /** The {@code ETag} header value: the tag in quotes. */
    public static String etag(byte[]... rowVersions) {
        return '"' + tag(rowVersions) + '"';
    }

    /**
     * The tag a save was sent in {@code If-Match}. Missing: {@code 428 PRECONDITION_REQUIRED}.
     * Weak, {@code *}, a list or anything else that is not one of our tags: {@code 400 INVALID_IF_MATCH}.
     */
    public static String requireIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, MessageCatalog.PRECONDITION_REQUIRED, null);
        }
        String v = ifMatch.strip();
        if (v.length() < 2 || v.charAt(0) != '"' || v.charAt(v.length() - 1) != '"'
                || !TAG.matcher(v.substring(1, v.length() - 1)).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_IF_MATCH, null);
        }
        return v.substring(1, v.length() - 1);
    }

    public static boolean matches(String tag, byte[]... current) {
        return tag(current).equals(tag);
    }

    /**
     * {@code 409 RECORD_CHANGED} with the legacy text, the fresh representation as {@code current}
     * and its {@code ETag}, so the client can show the new values next to the user's edits.
     */
    public static ApiException conflict(String field, Object current, byte[]... currentVersions) {
        return new ApiException(HttpStatus.CONFLICT, MessageCatalog.RECORD_CHANGED, field)
                .with("current", current)
                .header(HttpHeaders.ETAG, etag(currentVersions));
    }
}
