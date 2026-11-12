package org.carddemo.authorization.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Severity;

@Schema(name = "FraudResponse")
public record FraudResponse(
        @Schema(examples = "AUTH MARKED FRAUD...") String message,
        Severity severity,
        @Schema(description = "The detail read back after the commit.") AuthorizationDetail detail) {
}
