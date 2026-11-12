package org.carddemo.authorization.api;

import static org.carddemo.authorization.api.ApiDocs.JSON;
import static org.carddemo.authorization.api.ApiDocs.PROBLEM;
import static org.carddemo.authorization.api.ApiDocs.PROBLEM_JSON;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** Unit AUSM (COPAUS0C): the summary header and totals, and the authorization list, USER only. */
public interface AuthorizationSummaryApi {

    @GetMapping("/api/accounts/{acctId}/authorizations/summary")
    @Operation(operationId = "getAuthorizationSummary", responses = {
        @ApiResponse(responseCode = "200", description = "Header, limits and totals. Without a cross-reference row or"
                + " a customer, customer is null and message (error) says which read found nothing; the totals are"
                + " still returned. Without a summary, summaryFound is false and the totals are zeros.",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = AuthorizationSummary.class))),
        @ApiResponse(responseCode = "400", description = "ACCOUNT_ID_REQUIRED or ACCOUNT_ID_NOT_NUMERIC.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (USER only).",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "AUTH_ACCOUNT_READ_FAILED, AUTH_XREF_READ_FAILED,"
                + " AUTH_CUSTOMER_READ_FAILED or AUTH_SUMMARY_READ_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    AuthorizationSummary getAuthorizationSummary(@PathVariable("acctId") String acctId);

    @GetMapping("/api/accounts/{acctId}/authorizations")
    @Operation(operationId = "listAuthorizations", responses = {
        @ApiResponse(responseCode = "200", description = "Up to five authorizations, newest first. An account with no"
                + " summary has an empty page.",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = AuthorizationPage.class))),
        @ApiResponse(responseCode = "400", description = "ACCOUNT_ID_REQUIRED, ACCOUNT_ID_NOT_NUMERIC,"
                + " INVALID_PAGE_SIZE, INVALID_PAGE_REQUEST or INVALID_CURSOR.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (USER only).",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "AUTH_ACCOUNT_READ_FAILED or AUTH_DETAIL_READ_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    AuthorizationPage listAuthorizations(@PathVariable("acctId") String acctId,
            @Parameter(description = "lastKey of the page shown: the next, older page.")
            @RequestParam(name = "after", required = false) String after,
            @Parameter(description = "firstKey of the page shown: the previous, newer page.")
            @RequestParam(name = "before", required = false) String before,
            @Parameter(description = "Only 5, the screen's rows.",
                    schema = @Schema(type = "integer", format = "int32", defaultValue = "5", allowableValues = "5"))
            @RequestParam(name = "size", required = false) String size);
}
