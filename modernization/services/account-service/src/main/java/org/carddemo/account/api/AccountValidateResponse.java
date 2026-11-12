package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.carddemo.platform.web.Severity;

@Schema(name = "AccountValidateResponse")
public record AccountValidateResponse(
        @Schema(examples = "Changes validated.Press F5 to save") String message,
        Severity severity,
        @Schema(description = "Changed fields in screen order, one entry per group (its first part).")
        List<String> changedFields) {
}
