package org.carddemo.identity.web;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** Request and response bodies of the identity API. */
public final class Dtos {

    private Dtos() {
    }

    public record SignOnRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 8) String userId,
            @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 64) String password) {
    }

    /** {@code userId} is required without a session; with one it may be omitted. */
    public record PasswordRequest(
            @Schema(description = "Required without a session; with one, omitted or the session's user", maxLength = 8)
            String userId,
            @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 64) String currentPassword,
            @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 64) String newPassword) {
    }

    public record SessionView(
            @Schema(requiredMode = RequiredMode.REQUIRED) String userId,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The stored legacy type, e.g. A or U")
            String userType,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"USER", "ADMIN"}) String role,
            @Schema(requiredMode = RequiredMode.REQUIRED) String firstName,
            @Schema(requiredMode = RequiredMode.REQUIRED) String lastName,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"/", "/admin"}) String home,
            @Schema(requiredMode = RequiredMode.REQUIRED) long idleTimeoutSeconds) {
    }

    public record SignOffResult(
            @Schema(requiredMode = RequiredMode.REQUIRED) String message,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"info"}) String severity) {
    }

    public record Modules(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean authorization,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean transactionTypes) {
    }

    public record Features(
            @Schema(requiredMode = RequiredMode.REQUIRED) Modules modules,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<String> converted,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Spec item ID to legacy or fixed")
            Map<String, String> uiSwitches) {
    }
}
