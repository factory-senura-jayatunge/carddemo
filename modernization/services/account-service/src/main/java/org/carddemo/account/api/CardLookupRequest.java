package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Both as typed; missing or null counts as blank. */
@Schema(name = "CardLookupRequest")
public record CardLookupRequest(
        @Schema(types = {"string", "null"}, examples = "00000000050") String acctId,
        @Schema(types = {"string", "null"}, examples = "0500024453765740") String cardNum) {
}
