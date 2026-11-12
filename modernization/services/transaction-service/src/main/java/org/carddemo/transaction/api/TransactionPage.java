package org.carddemo.transaction.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.carddemo.platform.web.Severity;

/** One page of the transaction list; the keys are the 16-character transaction IDs themselves. */
@Schema(name = "TransactionPage")
public record TransactionPage(
        List<TransactionRow> items,
        boolean hasNext,
        boolean hasPrevious,
        @Schema(types = {"string", "null"}, examples = "0000000000683580") String firstKey,
        @Schema(types = {"string", "null"}, examples = "0000000021711604") String lastKey,
        @Schema(types = {"string", "null"}, examples = "You have reached the bottom of the page...") String message,
        @Schema(types = {"string", "null"}) Severity severity) {
}
