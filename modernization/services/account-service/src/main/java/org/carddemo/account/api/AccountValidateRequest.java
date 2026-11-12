package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** {@code changes}: only the fields the user changed, as typed; a group is sent whole. */
@Schema(name = "AccountValidateRequest", requiredProperties = {"version", "changes"})
public record AccountValidateRequest(String version, Map<String, String> changes) {
}
