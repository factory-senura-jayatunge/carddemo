package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Severity;

@Schema(name = "AccountUpdateResponse")
public record AccountUpdateResponse(
        @Schema(examples = "Changes committed to database") String message,
        Severity severity,
        AccountUpdateForm form) {
}
