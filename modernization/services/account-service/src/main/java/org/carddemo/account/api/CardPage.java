package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.carddemo.platform.web.Severity;

/** One page of the card list, ascending card number; the keys are opaque tokens, never PANs. */
@Schema(name = "CardPage")
public record CardPage(
        List<CardRow> items,
        boolean hasNext,
        boolean hasPrevious,
        @Schema(types = {"string", "null"}) String firstKey,
        @Schema(types = {"string", "null"}) String lastKey,
        @Schema(types = {"string", "null"}, examples = "NO MORE RECORDS TO SHOW") String message,
        @Schema(types = {"string", "null"}) Severity severity) {
}
