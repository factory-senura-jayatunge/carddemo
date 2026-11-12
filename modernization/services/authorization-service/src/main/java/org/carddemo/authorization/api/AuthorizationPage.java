package org.carddemo.authorization.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** One page of an account's authorizations, newest first; the keys are opaque tokens (D-54). */
@Schema(name = "AuthorizationPage")
public record AuthorizationPage(
        List<AuthorizationRow> items,
        boolean hasNext,
        boolean hasPrevious,
        @Schema(types = {"string", "null"}) String firstKey,
        @Schema(types = {"string", "null"}) String lastKey) {
}
