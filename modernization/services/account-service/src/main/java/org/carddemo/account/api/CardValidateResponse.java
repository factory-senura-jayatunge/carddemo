package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Severity;

@Schema(name = "CardValidateResponse")
public record CardValidateResponse(
        @Schema(examples = "Changes validated.Press F5 to save") String message,
        Severity severity) {
}
