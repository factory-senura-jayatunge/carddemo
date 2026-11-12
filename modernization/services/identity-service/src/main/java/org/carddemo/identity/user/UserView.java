package org.carddemo.identity.user;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** A user as the edit and delete pages show it; {@code version} goes back in {@code If-Match}. */
public record UserView(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 8, description = "The canonical user ID")
        String userId,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 20) String firstName,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 20) String lastName,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "A or U; a legacy-loaded user may hold another type")
        String userType,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The row version, also sent as the ETag")
        String version) {

    /** The ETag header value. */
    public String etag() {
        return '"' + version + '"';
    }
}
