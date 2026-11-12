package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Pii;
import org.carddemo.platform.web.Severity;

@Schema(name = "CardView")
public record CardView(
        @Schema(examples = "1") String cardId,
        @Schema(examples = "00000000050") String acctId,
        @Schema(implementation = String.class, description = "Masked (D-09).", examples = "************5740") Pii cardNum,
        String embossedName,
        @Schema(description = "As stored (BR-CRDV-002).") String activeStatus,
        @Schema(examples = "03") String expiryMonth,
        @Schema(examples = "2023") String expiryYear,
        @Schema(types = {"string", "null"}, examples = "   Displaying requested details") String message,
        @Schema(types = {"string", "null"}) Severity severity) {
}
