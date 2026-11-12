package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CardUpdateRequest", requiredProperties = "changes")
public record CardUpdateRequest(CardChanges changes) {
}
