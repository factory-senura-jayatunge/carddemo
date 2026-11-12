package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Filters as typed; missing or null counts as blank. At most one of start, after and before.
 * The filters and {@code size} take any JSON value, so that a filter that is not a string, or a size
 * that is not an integer, gets its own problem instead of an unreadable body.
 */
@Schema(name = "CardSearchRequest")
public record CardSearchRequest(
        @Schema(types = {"string", "null"}) Object acctId,
        @Schema(types = {"string", "null"}) Object cardNum,
        @Schema(types = {"string", "null"}, description = "A firstKey of an earlier CardPage: refresh from that row.")
        String start,
        @Schema(types = {"string", "null"}, description = "A lastKey: the next page.") String after,
        @Schema(types = {"string", "null"}, description = "A firstKey: the previous page.") String before,
        @Schema(types = {"integer", "null"}, format = "int32", minimum = "1", maximum = "7",
                description = "Default 7, the screen's row count.") Object size) {
}
