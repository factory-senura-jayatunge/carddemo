package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(name = "AccountUpdateRequest", requiredProperties = "changes")
public record AccountUpdateRequest(Map<String, String> changes) {
}
