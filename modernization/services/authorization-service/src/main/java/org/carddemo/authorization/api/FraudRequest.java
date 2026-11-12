package org.carddemo.authorization.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** The fraud toggle the screen computed from the detail it shows (BR-AUDT-007). */
@Schema(name = "FraudRequest")
public record FraudRequest(
        @Schema(description = "R when the detail shown is F, else F.", allowableValues = {"F", "R"}, examples = "F")
        String action) {
}
