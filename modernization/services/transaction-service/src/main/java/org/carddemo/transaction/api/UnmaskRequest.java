package org.carddemo.transaction.api;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "UnmaskRequest", requiredProperties = "fields")
public record UnmaskRequest(
        @ArraySchema(schema = @Schema(examples = "cardNum"), minItems = 1) List<String> fields) {
}
