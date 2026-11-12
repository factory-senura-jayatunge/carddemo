package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Severity;

@Schema(name = "CardUpdateResponse")
public record CardUpdateResponse(
        @Schema(examples = "Changes committed to database") String message,
        Severity severity,
        CardUpdateForm form) {
}
