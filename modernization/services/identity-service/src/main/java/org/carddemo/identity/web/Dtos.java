package org.carddemo.identity.web;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.carddemo.identity.user.ListedUser;
import org.carddemo.identity.user.UserView;

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

    /** The add form, values as typed; the server applies every rule and canonicalises the user ID. */
    public record NewUserRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 20) String firstName,
            @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 20) String lastName,
            @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 8) String userId,
            @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 64) String password,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"A", "U"}) String userType) {
    }

    /** The edit form; the user ID is the path's. An absent, null or blank password keeps the current one. */
    public record UpdateUserRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 20) String firstName,
            @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 20) String lastName,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"A", "U"}) String userType,
            @Schema(maxLength = 64, nullable = true, description = "Write-only; blank keeps the current password")
            String password) {
    }

    public record SavedUser(
            @Schema(requiredMode = RequiredMode.REQUIRED) UserView user,
            @Schema(requiredMode = RequiredMode.REQUIRED) String message,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"success"}) String severity) {
    }

    public record Notice(
            @Schema(requiredMode = RequiredMode.REQUIRED) String message,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"success"}) String severity) {
    }

    /** {@code firstKey} and {@code lastKey} are page tokens for {@code before} and {@code after}. */
    public record UserPage(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<ListedUser> items,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean hasNext,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean hasPrevious,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String firstKey,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String lastKey,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "The message line, or null")
            String message,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, allowableValues = {"info"}) String severity) {
    }

    public record Features(
            @Schema(requiredMode = RequiredMode.REQUIRED) Modules modules,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<String> converted,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Spec item ID to legacy or fixed")
            Map<String, String> uiSwitches) {
    }
}
