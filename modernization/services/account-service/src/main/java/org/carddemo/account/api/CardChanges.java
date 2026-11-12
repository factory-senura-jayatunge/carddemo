package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** All four as typed, every time; a missing one counts as empty. */
@Schema(name = "CardChanges")
public record CardChanges(
        @Schema(types = {"string", "null"}) String embossedName,
        @Schema(types = {"string", "null"}) String activeStatus,
        @Schema(types = {"string", "null"}, examples = "03") String expiryMonth,
        @Schema(types = {"string", "null"}, examples = "2023") String expiryYear) {
}
