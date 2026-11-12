package org.carddemo.account.api;

import static org.carddemo.account.api.ApiDocs.JSON;
import static org.carddemo.account.api.ApiDocs.PROBLEM;
import static org.carddemo.account.api.ApiDocs.PROBLEM_JSON;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Unit CRDV (COCRDSLC): card view, USER and ADMIN; the audited card unmask, ADMIN only. */
public interface CardViewApi {

    @PostMapping("/api/cards/lookup")
    @Operation(operationId = "lookupCard",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = CardLookupRequest.class))),
            responses = {
        @ApiResponse(responseCode = "200", description = "The card of that account.",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = CardView.class))),
        @ApiResponse(responseCode = "400", description = "NO_INPUT, ACCT_ID_REQUIRED, INVALID_ACCT_ID, CARD_NUM_REQUIRED or"
                + " INVALID_CARD_NUM.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED or CSRF.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "CARD_NOT_FOUND (also for a card of another account, D-13).",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "CARD_LOOKUP_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    CardView lookupCard(@RequestBody(required = false) CardLookupRequest request);

    @GetMapping("/api/cards/{cardId}")
    @Operation(operationId = "viewCard", responses = {
        @ApiResponse(responseCode = "200", description = "The card of a list row.",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = CardView.class))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "CARD_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "CARD_LOOKUP_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    CardView viewCard(@PathVariable("cardId") String cardId);

    @PostMapping("/api/cards/{cardId}/unmask")
    @Operation(operationId = "unmaskCard",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = UnmaskRequest.class))),
            responses = {
        @ApiResponse(responseCode = "200", description = "The card number in clear; one ops.audit row written.",
                headers = {@Header(name = "Cache-Control", schema = @Schema(type = "string", examples = "no-store")),
                    @Header(name = "Pragma", schema = @Schema(type = "string", examples = "no-cache"))},
                content = @Content(mediaType = JSON, schema = @Schema(implementation = UnmaskResponse.class))),
        @ApiResponse(responseCode = "400", description = "INVALID_UNMASK_FIELD (cardNum).",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (ADMIN only) or CSRF.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "CARD_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "CARD_LOOKUP_FAILED or UNMASK_AUDIT_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    ResponseEntity<UnmaskResponse> unmaskCard(@PathVariable("cardId") String cardId,
            @RequestBody(required = false) UnmaskRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt);
}
