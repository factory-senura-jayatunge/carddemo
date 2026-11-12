package org.carddemo.transaction.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Severity;

/** An added transaction, waiting for the next cycle (D-27); a replay returns the same body. */
@Schema(name = "AddResult")
public record AddResult(
        @Schema(examples = "9000000000000000") String tranId,
        Severity severity,
        @Schema(examples = "acctId") String field,
        @Schema(examples = "Transaction added successfully.  Your Tran ID is 9000000000000000.") String message,
        Hint hint) {
}
