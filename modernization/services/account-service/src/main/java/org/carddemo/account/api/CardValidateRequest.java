package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CardValidateRequest", requiredProperties = {"version", "changes"})
public record CardValidateRequest(String version, CardChanges changes) {
}
