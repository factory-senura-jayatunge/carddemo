package org.carddemo.authorization.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Pii;

/**
 * One authorization as the detail screen shows it (COPAUS1C BR-AUDT-002). Text columns are returned
 * as stored; amounts are decimal strings with two decimals, at full width.
 */
@Schema(name = "AuthorizationDetail")
public record AuthorizationDetail(
        @Schema(examples = "1234") long authId,
        @Schema(examples = "00000000001") String acctId,
        @Schema(description = "Same value as the ETag, without quotes.", examples = "00000000000007d3") String version,
        @Schema(implementation = String.class, description = "Masked (D-09).", examples = "************1111")
        Pii cardNum,
        @Schema(description = "The request's YYMMDD as MM/DD/YY, rearranged without validation (NUM-AUDT-002).",
                examples = "04/05/24") String requestDate,
        @Schema(description = "The request's HHMMSS as HH:MM:SS.", examples = "14:30:15") String requestTime,
        @Schema(description = "A when the response code is 00, else D.", examples = "A") String approval,
        @Schema(examples = "00") String respCode,
        @Schema(examples = "0000") String reason,
        @Schema(description = "Code and description from the fixed table (BR-AUDT-003); 9999-ERROR for a code not in"
                + " it.", examples = "0000-APPROVED") String reasonText,
        @Schema(description = "The authorization ID code (DEF-AUDT-002 fixed).", examples = "001234") String authIdCode,
        @Schema(examples = "000000") String processingCode,
        @Schema(description = "0.00 for declines.", examples = "50.00") String approvedAmount,
        @Schema(examples = "50.00") String transactionAmount,
        @Schema(examples = "01") String posEntryMode,
        @Schema(examples = "POS   ") String messageSource,
        @Schema(examples = "5411") String merchantCategoryCode,
        @Schema(description = "Characters 1-2, /, characters 3-4 of the stored expiry (NUM-AUDT-003).",
                examples = "04/26") String cardExpiry,
        @Schema(examples = "SALE") String authType,
        @Schema(examples = "TRN000000000001") String transactionId,
        @Schema(description = "P, D, E or M.", examples = "M") String matchStatus,
        @Schema(types = {"string", "null"}, description = "The matched transaction; set only when matchStatus is M.",
                examples = "0000000000000301") String matchedTranId,
        AuthorizationFraud fraud,
        AuthorizationMerchant merchant,
        @Schema(types = {"integer", "null"}, format = "int64", description = "The next older authorization of the"
                + " account in list order; null at the oldest (BR-AUDT-006).", examples = "1201") Long nextOlderAuthId) {
}
