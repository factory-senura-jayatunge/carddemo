package org.carddemo.account.api;

import static org.carddemo.account.api.ApiDocs.ACCOUNT_CONFLICT_PROBLEM;
import static org.carddemo.account.api.ApiDocs.JSON;
import static org.carddemo.account.api.ApiDocs.PROBLEM;
import static org.carddemo.account.api.ApiDocs.PROBLEM_JSON;
import static org.carddemo.account.api.ApiDocs.VALIDATION_PROBLEM;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** Unit ACUP (COACTUPC): account update, USER only. */
public interface AccountUpdateApi {

    @GetMapping("/api/accounts/{acctId}/update-form")
    @Operation(operationId = "fetchAccountForUpdate", responses = {
        @ApiResponse(responseCode = "200", description = "The form, PII masked.",
                headers = @Header(name = "ETag", schema = @Schema(type = "string")),
                content = @Content(mediaType = JSON, schema = @Schema(implementation = AccountUpdateForm.class))),
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
    ResponseEntity<AccountUpdateForm> fetchAccountForUpdate(@PathVariable("acctId") String acctId);

    @PostMapping("/api/accounts/{acctId}/validate")
    @Operation(operationId = "validateAccountUpdate",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = AccountValidateRequest.class))),
            responses = {
        @ApiResponse(responseCode = "200", description = "Changes validated; nothing written.",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = AccountValidateResponse.class))),
        @ApiResponse(responseCode = "400", description = "ACCT_ID_REQUIRED, INVALID_ACCT_ID, INVALID_IF_MATCH,"
                + " FIELD_NOT_EDITABLE, NO_CHANGE, or a field rule (with invalidFields).",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = VALIDATION_PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED or CSRF.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND or CUSTOMER_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "409", description = "RECORD_CHANGED, with current and a fresh ETag.",
                headers = @Header(name = "ETag", schema = @Schema(type = "string")),
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = ACCOUNT_CONFLICT_PROBLEM))),
        @ApiResponse(responseCode = "500", description = "ACCOUNT_LOOKUP_FAILED, XREF_LOOKUP_FAILED or CUSTOMER_LOOKUP_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    AccountValidateResponse validateAccountUpdate(@PathVariable("acctId") String acctId,
            @RequestBody(required = false) AccountValidateRequest request);

    @PutMapping("/api/accounts/{acctId}")
    @Operation(operationId = "updateAccount",
            parameters = @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "string")),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = AccountUpdateRequest.class))),
            responses = {
        @ApiResponse(responseCode = "200", description = "Saved; the form read back.",
                headers = @Header(name = "ETag", schema = @Schema(type = "string")),
                content = @Content(mediaType = JSON, schema = @Schema(implementation = AccountUpdateResponse.class))),
        @ApiResponse(responseCode = "400", description = "ACCT_ID_REQUIRED, INVALID_ACCT_ID, INVALID_IF_MATCH,"
                + " FIELD_NOT_EDITABLE, NO_CHANGE, or a field rule (with invalidFields).",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = VALIDATION_PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED or CSRF.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND or CUSTOMER_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "409", description = "RECORD_CHANGED (with current and a fresh ETag),"
                + " ACCOUNT_LOCK_FAILED or CUSTOMER_LOCK_FAILED.",
                headers = @Header(name = "ETag", schema = @Schema(type = "string")),
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = ACCOUNT_CONFLICT_PROBLEM))),
        @ApiResponse(responseCode = "428", description = "PRECONDITION_REQUIRED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "ACCOUNT_LOOKUP_FAILED, XREF_LOOKUP_FAILED, CUSTOMER_LOOKUP_FAILED,"
                + " ACCOUNT_LOCK_FAILED, CUSTOMER_LOCK_FAILED or ACCOUNT_UPDATE_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "503", description = "BATCH_WINDOW.",
                headers = @Header(name = "Retry-After", description = "Seconds.", schema = @Schema(type = "integer")),
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    ResponseEntity<AccountUpdateResponse> updateAccount(@PathVariable("acctId") String acctId,
            @Parameter(hidden = true) @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) AccountUpdateRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt);
}
