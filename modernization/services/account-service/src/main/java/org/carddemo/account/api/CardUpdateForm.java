package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Pii;
import org.carddemo.platform.web.Severity;

@Schema(name = "CardUpdateForm")
public record CardUpdateForm(
        @Schema(examples = "1") String cardId,
        @Schema(description = "Read-only (D-13).", examples = "00000000050") String acctId,
        @Schema(implementation = String.class, description = "Masked (D-09).", examples = "************5740") Pii cardNum,
        @Schema(description = "As stored, not upper-cased (D-51).") String embossedName,
        String activeStatus,
        @Schema(examples = "03") String expiryMonth,
        @Schema(examples = "2023") String expiryYear,
        @Schema(description = "Same value as the ETag, without quotes.", examples = "00000000000007d1") String version,
        @Schema(types = {"string", "null"}) String message,
        @Schema(types = {"string", "null"}) Severity severity) {
}
