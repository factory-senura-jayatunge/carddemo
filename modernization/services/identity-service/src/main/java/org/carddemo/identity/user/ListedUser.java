package org.carddemo.identity.user;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** One row of the user list (BR-USRL-001). */
public record ListedUser(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 8) String userId,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 20) String firstName,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 20) String lastName,
        @Schema(requiredMode = RequiredMode.REQUIRED) String userType) {
}
