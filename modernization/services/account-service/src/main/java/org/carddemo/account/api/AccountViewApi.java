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

/** Unit ACVW (COACTVWC): account view, USER and ADMIN; the audited unmask, ADMIN only. */
public interface AccountViewApi {

    @GetMapping("/api/accounts/{acctId}")
    @Operation(operationId = "viewAccount", responses = {
        @ApiResponse(responseCode = "200", description = "The account, its customer and card; or the account alone with"
                + " NO_CARD_LINKED (information) when it has no card (D-61).",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = AccountView.class))),
        @ApiResponse(responseCode = "400", description = "ACCT_ID_REQUIRED or INVALID_ACCT_ID.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND or CUSTOMER_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "ACCOUNT_LOOKUP_FAILED, XREF_LOOKUP_FAILED or CUSTOMER_LOOKUP_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    AccountView viewAccount(@PathVariable("acctId") String acctId);

    @PostMapping("/api/accounts/{acctId}/unmask")
    @Operation(operationId = "unmaskAccount",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = UnmaskRequest.class))),
            responses = {
        @ApiResponse(responseCode = "200", description = "The requested fields in clear; one ops.audit row written.",
                headers = {@Header(name = "Cache-Control", schema = @Schema(type = "string", examples = "no-store")),
                    @Header(name = "Pragma", schema = @Schema(type = "string", examples = "no-cache"))},
                content = @Content(mediaType = JSON, schema = @Schema(implementation = UnmaskResponse.class))),
        @ApiResponse(responseCode = "400", description = "ACCT_ID_REQUIRED, INVALID_ACCT_ID or INVALID_UNMASK_FIELD"
                + " (ssn, dob, govtIssuedId, eftAccountId, cardNum).",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (ADMIN only) or CSRF.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "ACCOUNT_LOOKUP_FAILED, XREF_LOOKUP_FAILED, CUSTOMER_LOOKUP_FAILED"
                + " or UNMASK_AUDIT_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    ResponseEntity<UnmaskResponse> unmaskAccount(@PathVariable("acctId") String acctId,
            @RequestBody(required = false) UnmaskRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt);
}
