package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Pii;

/** The cross-reference row of the account's lowest card number (D-61). */
@Schema(name = "CrossReference")
public record CrossReference(
        @Schema(examples = "000000001") String custId,
        @Schema(types = {"string", "null"}, description = "core.card.card_id; null when the card row is missing.",
                examples = "1") String cardId,
        @Schema(implementation = String.class, description = "Masked to the last four digits (D-09).",
                examples = "************3697") Pii cardNum) {
}
