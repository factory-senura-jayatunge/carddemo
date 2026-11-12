package org.carddemo.account.api;

import static org.carddemo.account.api.ApiDocs.CARD_CONFLICT_PROBLEM;
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

/** Unit CRDU (COCRDUPC): card update, USER only. */
public interface CardUpdateApi {

    @PostMapping("/api/cards/update-lookup")
    @Operation(operationId = "lookupCardForUpdate",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = CardLookupRequest.class))),
            responses = {
        @ApiResponse(responseCode = "200", description = "The form.",
                headers = @Header(name = "ETag", schema = @Schema(type = "string")),
                content = @Content(mediaType = JSON, schema = @Schema(implementation = CardUpdateForm.class))),
        @ApiResponse(responseCode = "400", description = "NO_INPUT, ACCT_ID_REQUIRED, INVALID_ACCT_ID, CARD_NUM_REQUIRED or"
                + " INVALID_CARD_NUM.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED or CSRF.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "CARD_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "CARD_LOOKUP_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    ResponseEntity<CardUpdateForm> lookupCardForUpdate(@RequestBody(required = false) CardLookupRequest request);

    @GetMapping("/api/cards/{cardId}/update-form")
    @Operation(operationId = "fetchCardForUpdate", responses = {
        @ApiResponse(responseCode = "200", description = "The form.",
                headers = @Header(name = "ETag", schema = @Schema(type = "string")),
                content = @Content(mediaType = JSON, schema = @Schema(implementation = CardUpdateForm.class))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "CARD_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "CARD_LOOKUP_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    ResponseEntity<CardUpdateForm> fetchCardForUpdate(@PathVariable("cardId") String cardId);

    @PostMapping("/api/cards/{cardId}/validate")
    @Operation(operationId = "validateCardUpdate",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = CardValidateRequest.class))),
            responses = {
        @ApiResponse(responseCode = "200", description = "Changes validated; nothing written.",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = CardValidateResponse.class))),
        @ApiResponse(responseCode = "400", description = "INVALID_IF_MATCH, NO_CHANGE, or a field rule (with invalidFields).",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = VALIDATION_PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED or CSRF.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "CARD_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "409", description = "RECORD_CHANGED, with current and a fresh ETag.",
                headers = @Header(name = "ETag", schema = @Schema(type = "string")),
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = CARD_CONFLICT_PROBLEM))),
        @ApiResponse(responseCode = "500", description = "CARD_LOOKUP_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    CardValidateResponse validateCardUpdate(@PathVariable("cardId") String cardId,
            @RequestBody(required = false) CardValidateRequest request);

    @PutMapping("/api/cards/{cardId}")
    @Operation(operationId = "updateCard",
            parameters = @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "string")),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = CardUpdateRequest.class))),
            responses = {
        @ApiResponse(responseCode = "200", description = "Saved; the form read back.",
                headers = @Header(name = "ETag", schema = @Schema(type = "string")),
                content = @Content(mediaType = JSON, schema = @Schema(implementation = CardUpdateResponse.class))),
        @ApiResponse(responseCode = "400", description = "INVALID_IF_MATCH, NO_CHANGE, or a field rule (with invalidFields).",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = VALIDATION_PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED or CSRF.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "CARD_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "409", description = "RECORD_CHANGED (with current and a fresh ETag) or CARD_LOCK_FAILED.",
                headers = @Header(name = "ETag", schema = @Schema(type = "string")),
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = CARD_CONFLICT_PROBLEM))),
        @ApiResponse(responseCode = "428", description = "PRECONDITION_REQUIRED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "CARD_LOOKUP_FAILED, CARD_LOCK_FAILED or CARD_UPDATE_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "503", description = "BATCH_WINDOW.",
                headers = @Header(name = "Retry-After", description = "Seconds.", schema = @Schema(type = "integer")),
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    ResponseEntity<CardUpdateResponse> updateCard(@PathVariable("cardId") String cardId,
            @Parameter(hidden = true) @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) CardUpdateRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt);
}
