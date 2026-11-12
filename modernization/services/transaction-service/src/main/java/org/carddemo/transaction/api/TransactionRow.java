package org.carddemo.transaction.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** One list row: no card number, account or customer (BR-TRNL-006). */
@Schema(name = "TransactionRow")
public record TransactionRow(
        @Schema(examples = "0000000000683580") String tranId,
        @Schema(description = "mm/dd/yy of orig_ts (NUM-TRNL-002).", examples = "06/10/22") String origDate,
        @Schema(description = "The first 26 characters, trailing spaces removed (BR-TRNL-002).",
                examples = "Purchase at Abshire-Lowe") String description,
        @Schema(description = "Decimal string, scale 2.", examples = "504.77") String amount,
        @Schema(description = "+99999999.99, widened to +999999999.99 from 100,000,000.00 (NUM-TRNL-001,"
                + " DEF-TRNL-002).", examples = "+00000504.77") String amountDisplay) {
}
