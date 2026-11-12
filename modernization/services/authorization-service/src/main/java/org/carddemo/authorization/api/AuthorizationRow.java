package org.carddemo.authorization.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** One list row (BR-AUSM-003). Text columns are returned as stored. */
@Schema(name = "AuthorizationRow")
public record AuthorizationRow(
        @Schema(examples = "1234") long authId,
        @Schema(examples = "TRN000000000001") String transactionId,
        @Schema(description = "The request's YYMMDD as MM/DD/YY, rearranged without validation (NUM-AUSM-003).",
                examples = "04/05/24") String requestDate,
        @Schema(description = "The request's HHMMSS as HH:MM:SS.", examples = "14:30:15") String requestTime,
        @Schema(examples = "SALE") String authType,
        @Schema(description = "A when the response code is 00, else D.", examples = "A") String approval,
        @Schema(description = "P, D, E or M.", examples = "P") String matchStatus,
        @Schema(description = "0.00 for declines.", examples = "50.00") String approvedAmount) {
}
