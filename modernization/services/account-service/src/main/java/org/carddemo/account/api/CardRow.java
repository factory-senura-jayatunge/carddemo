package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Pii;

@Schema(name = "CardRow")
public record CardRow(
        @Schema(examples = "1") String cardId,
        @Schema(examples = "00000000050") String acctId,
        @Schema(implementation = String.class, description = "Masked (D-09).", examples = "************5740") Pii cardNum,
        @Schema(description = "As stored (NUM-CRDL-003).") String activeStatus) {
}
