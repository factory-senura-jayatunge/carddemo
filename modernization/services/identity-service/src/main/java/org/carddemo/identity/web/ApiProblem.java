package org.carddemo.identity.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * RFC 9457 problem details with the CardDemo members: {@code code}, {@code message} (the exact
 * screen text), {@code field} (focus target, null when none) and {@code severity}
 * ({@code error} red, {@code success} green, {@code info} neutral).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiProblem(
        @Schema(requiredMode = RequiredMode.REQUIRED) String type,
        @Schema(requiredMode = RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = RequiredMode.REQUIRED) int status,
        @Schema(requiredMode = RequiredMode.REQUIRED) String detail,
        @Schema(requiredMode = RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The exact screen text") String message,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "The field to focus, or null")
        String field,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"error", "success", "info"})
        String severity) {

    public static ApiProblem of(int status, String title, String code, String message, String field,
            String severity) {
        return new ApiProblem("about:blank", title, status, message, code, message, field, severity);
    }
}
