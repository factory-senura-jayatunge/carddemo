package org.carddemo.authorization.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** The fraud status of an authorization (BR-AUDT-004). */
@Schema(name = "AuthorizationFraud")
public record AuthorizationFraud(
        @Schema(types = {"string", "null"}, description = "F (marked), R (removed) or null (never marked).",
                examples = "F") String flag,
        @Schema(types = {"string", "null"}, format = "date", description = "The date of the last change.",
                examples = "2026-11-10") String reportDate,
        @Schema(description = "flag-MM/DD/YY, or - when there is no flag.", examples = "F-11/10/26") String display) {
}
