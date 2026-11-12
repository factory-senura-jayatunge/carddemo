package org.carddemo.authorization.api;

import static org.carddemo.authorization.api.ApiDocs.CONFLICT_PROBLEM;
import static org.carddemo.authorization.api.ApiDocs.JSON;
import static org.carddemo.authorization.api.ApiDocs.PROBLEM;
import static org.carddemo.authorization.api.ApiDocs.PROBLEM_JSON;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** Units AUDT and AUFR (COPAUS1C, COPAUS2C): one authorization and its fraud toggle, USER only. */
public interface AuthorizationDetailApi {

    @GetMapping("/api/authorizations/{authId}")
    @Operation(operationId = "getAuthorization", responses = {
        @ApiResponse(responseCode = "200", description = "The detail with the next older authorization of the account.",
                headers = @Header(name = "ETag", schema = @Schema(type = "string")),
                content = @Content(mediaType = JSON, schema = @Schema(implementation = AuthorizationDetail.class))),
        @ApiResponse(responseCode = "400", description = "INVALID_AUTH_ID.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (USER only).",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "AUTHORIZATION_NOT_FOUND, for example purged since the list"
                + " was shown.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "AUTH_DETAIL_READ_FAILED or AUTH_NEXT_READ_FAILED; no detail.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    ResponseEntity<AuthorizationDetail> getAuthorization(@PathVariable("authId") String authId);

    @PostMapping("/api/authorizations/{authId}/fraud")
    @Operation(operationId = "changeAuthorizationFraud",
            parameters = @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "string")),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = FraudRequest.class))),
            responses = {
        @ApiResponse(responseCode = "200", description = "Committed: AUTH_MARKED_FRAUD or AUTH_FRAUD_REMOVED, with the"
                + " detail read back.",
                headers = @Header(name = "ETag", schema = @Schema(type = "string")),
                content = @Content(mediaType = JSON, schema = @Schema(implementation = FraudResponse.class))),
        @ApiResponse(responseCode = "400", description = "INVALID_AUTH_ID, INVALID_FRAUD_ACTION or INVALID_IF_MATCH.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (USER only) or CSRF_REJECTED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "AUTHORIZATION_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "409", description = "RECORD_CHANGED: the version differs, or the action is not"
                + " the toggle of the stored flag; with current and a fresh ETag. Nothing is written.",
                headers = @Header(name = "ETag", schema = @Schema(type = "string")),
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = CONFLICT_PROBLEM))),
        @ApiResponse(responseCode = "428", description = "PRECONDITION_REQUIRED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "AUTH_DETAIL_READ_FAILED (the locking read),"
                + " AUTH_NEXT_READ_FAILED or FRAUD_UPDATE_FAILED; nothing is written.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    ResponseEntity<FraudResponse> changeAuthorizationFraud(@PathVariable("authId") String authId,
            @Parameter(hidden = true) @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) FraudRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt);
}
