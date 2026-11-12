package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "UnmaskRequest", requiredProperties = "fields")
public record UnmaskRequest(
        @ArraySchema(schema = @Schema(examples = "ssn"), minItems = 1) List<String> fields) {
}
