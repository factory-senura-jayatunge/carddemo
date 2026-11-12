package org.carddemo.authorization.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** The merchant of an authorization, as stored. */
@Schema(name = "AuthorizationMerchant")
public record AuthorizationMerchant(
        @Schema(examples = "GROCERY STORE") String name,
        @Schema(examples = "MERCH0000000001") String id,
        @Schema(examples = "SPRINGFIELD") String city,
        @Schema(examples = "IL") String state,
        @Schema(examples = "62701") String zip) {
}
