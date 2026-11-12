package org.carddemo.transaction.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Severity;

/** A second, neutral line under a message. */
@Schema(name = "Hint")
public record Hint(
        @Schema(examples = "The transaction will be posted in the next batch cycle.") String message,
        Severity severity) {
}
