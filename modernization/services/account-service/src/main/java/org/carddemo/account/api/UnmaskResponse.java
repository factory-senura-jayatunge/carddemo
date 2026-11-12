package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** Only the requested fields, in clear; a field with no value (no customer, no card) is null. */
@Schema(name = "UnmaskResponse")
public record UnmaskResponse(
        @Schema(description = "Field name to clear value, or null.",
                additionalPropertiesSchema = NullableString.class) Map<String, String> fields) {

    @Schema(types = {"string", "null"})
    interface NullableString {
    }
}
