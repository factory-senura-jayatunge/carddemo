package org.carddemo.transaction.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Pii;
import org.carddemo.platform.web.Severity;

/** A form that passed every check, with the values as the screen shows them after the checks. */
@Schema(name = "TransactionCheck")
public record TransactionCheck(
        @Schema(description = "Padded to 11 digits, or looked up from the card.", examples = "00000000050") String acctId,
        @Schema(implementation = String.class, description = "Masked (D-09); the looked-up card when only the"
                + " account was typed.", examples = "************5740") Pii cardNum,
        @Schema(examples = "01") String typeCd,
        @Schema(examples = "0001") String catCd,
        @Schema(examples = "000000123") String merchantId,
        @Schema(description = "Decimal string, scale 2.", examples = "-12.50") String amount,
        @Schema(description = "+99999999.99 (NUM-TRNA-001).", examples = "-00000012.50") String amountDisplay,
        @Schema(description = "Today, YYYY-MM-DD, in the service's zone.", examples = "2026-11-07") String procDate,
        @Schema(examples = "Confirm to add this transaction...") String message,
        Severity severity,
        @Schema(examples = "confirm") String field) {
}
