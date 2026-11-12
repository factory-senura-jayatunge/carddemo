package org.carddemo.transaction.api;

import static org.carddemo.transaction.api.ApiDocs.JSON;
import static org.carddemo.transaction.api.ApiDocs.PROBLEM;
import static org.carddemo.transaction.api.ApiDocs.PROBLEM_JSON;

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

/** Unit TRNV (COTRN01C): transaction view, USER and ADMIN; the audited card number unmask, ADMIN only. */
public interface TransactionViewApi {

    @GetMapping("/api/transactions/{tranId}")
    @Operation(operationId = "viewTransaction", responses = {
        @ApiResponse(responseCode = "200", description = "The master transaction, card number masked.",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = TransactionView.class))),
        @ApiResponse(responseCode = "400", description = "TRAN_ID_REQUIRED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "TRAN_NOT_FOUND (also for an add still waiting for its cycle).",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "TRAN_LOOKUP_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    TransactionView viewTransaction(
            @Parameter(description = "As typed; trailing spaces ignored, 1 to 15 digits padded on the left with 0.",
                    examples = @io.swagger.v3.oas.annotations.media.ExampleObject(value = "0000000000683580"))
            @PathVariable("tranId") String tranId);

    @PostMapping("/api/transactions/{tranId}/unmask")
    @Operation(operationId = "unmaskTransaction",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = UnmaskRequest.class))),
            responses = {
        @ApiResponse(responseCode = "200", description = "The card number in clear; one ops.audit row written.",
                headers = {@Header(name = "Cache-Control", schema = @Schema(type = "string", examples = "no-store")),
                    @Header(name = "Pragma", schema = @Schema(type = "string", examples = "no-cache"))},
                content = @Content(mediaType = JSON, schema = @Schema(implementation = UnmaskResponse.class))),
        @ApiResponse(responseCode = "400", description = "INVALID_UNMASK_FIELD (cardNum) or TRAN_ID_REQUIRED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (ADMIN only) or CSRF.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "TRAN_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "TRAN_LOOKUP_FAILED or UNMASK_AUDIT_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    ResponseEntity<UnmaskResponse> unmaskTransaction(@PathVariable("tranId") String tranId,
            @RequestBody(required = false) UnmaskRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt);
}
