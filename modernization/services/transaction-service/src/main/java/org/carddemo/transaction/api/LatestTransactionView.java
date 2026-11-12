package org.carddemo.transaction.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The data fields of the transaction with the highest ID (BR-TRNA-006), master or waiting add.
 * No card number and no ID.
 */
@Schema(name = "LatestTransaction")
public record LatestTransactionView(
        @Schema(examples = "01") String typeCd,
        @Schema(examples = "0001") String catCd,
        @Schema(examples = "POS TERM") String source,
        @Schema(description = "The first 60 characters (NUM-TRNA-005).", examples = "Purchase at Abshire-Lowe")
        String description,
        @Schema(description = "Decimal string, scale 2, as stored.", examples = "504.77") String amount,
        @Schema(description = "+99999999.99 of the amount cut to eight integer digits (NUM-TRNA-001).",
                examples = "+00000504.77") String amountDisplay,
        @Schema(description = "YYYY-MM-DD.", examples = "2022-06-10") String origDate,
        @Schema(description = "Today, not copied (D-52).", examples = "2026-11-07") String procDate,
        @Schema(examples = "800000000") String merchantId,
        @Schema(description = "The first 30 characters.", examples = "Abshire-Lowe") String merchantName,
        @Schema(description = "The first 25 characters.", examples = "North Enoshaven") String merchantCity,
        @Schema(examples = "72112") String merchantZip) {
}
