package org.carddemo.transaction.api;

import static org.carddemo.transaction.api.ApiDocs.JSON;
import static org.carddemo.transaction.api.ApiDocs.PROBLEM;
import static org.carddemo.transaction.api.ApiDocs.PROBLEM_JSON;
import static org.carddemo.transaction.api.ApiDocs.TRANSACTION_PROBLEM;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Unit TRNA (COTRN02C): validate, add and copy last, role USER only. An add writes one waiting
 * {@code core.daily_tran} row, which the next cycle posts (D-27).
 */
public interface TransactionAddApi {

    String CHECK_ERRORS = "KEY_REQUIRED, INVALID_ACCT_ID, INVALID_CARD_NUM, CARD_CHOICE_REQUIRED (with cards),"
            + " CARD_ACCOUNT_MISMATCH, FIELD_REQUIRED, UNSUPPORTED_CHARACTER, TOO_LONG, TYPE_NOT_NUMERIC,"
            + " CATEGORY_NOT_NUMERIC, TYPE_NOT_FOUND, CATEGORY_NOT_FOUND, INVALID_AMOUNT, INVALID_ORIG_DATE_FORMAT,"
            + " INVALID_ORIG_DATE or MERCHANT_ID_NOT_NUMERIC; the first failing check only";

    @PostMapping("/api/transactions/validate")
    @Operation(operationId = "validateTransaction",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = TransactionForm.class))),
            responses = {
        @ApiResponse(responseCode = "200", description = "Every check passed; the confirmation prompt. Nothing written.",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = TransactionCheck.class))),
        @ApiResponse(responseCode = "400", description = CHECK_ERRORS + ".",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = TRANSACTION_PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (USER only) or CSRF.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND or CARD_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = TRANSACTION_PROBLEM))),
        @ApiResponse(responseCode = "500", description = "XREF_LOOKUP_FAILED, CARD_LOOKUP_FAILED or REFERENCE_LOOKUP_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = TRANSACTION_PROBLEM))),
    })
    TransactionCheck validateTransaction(@RequestBody(required = false) TransactionForm form);

    @PostMapping("/api/transactions")
    @Operation(operationId = "addTransaction",
            parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "string", format = "uuid")),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = TransactionForm.class))),
            responses = {
        @ApiResponse(responseCode = "201",
                description = "Added, or the stored result of the same key and body (header Idempotent-Replayed: true).",
                headers = @Header(name = "Idempotent-Replayed", schema = @Schema(type = "boolean")),
                content = @Content(mediaType = JSON, schema = @Schema(implementation = AddResult.class))),
        @ApiResponse(responseCode = "400", description = CHECK_ERRORS + "; then INVALID_CONFIRM or IDEMPOTENCY_KEY_REQUIRED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = TRANSACTION_PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (USER only) or CSRF.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND or CARD_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = TRANSACTION_PROBLEM))),
        @ApiResponse(responseCode = "409", description = "TRAN_ID_EXISTS.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "422", description = "TRAN_IDEMPOTENCY_KEY_REUSED: the key came with another body.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "XREF_LOOKUP_FAILED, CARD_LOOKUP_FAILED, REFERENCE_LOOKUP_FAILED,"
                + " TRAN_LOOKUP_FAILED or ADD_FAILED (also when no transaction ID is left).",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = TRANSACTION_PROBLEM))),
        @ApiResponse(responseCode = "503", description = "BATCH_WINDOW.",
                headers = @Header(name = "Retry-After", description = "Seconds.", schema = @Schema(type = "integer")),
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    ResponseEntity<AddResult> addTransaction(
            @Parameter(hidden = true) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) TransactionForm form,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt);

    @GetMapping("/api/transactions/latest")
    @Operation(operationId = "latestTransaction", responses = {
        @ApiResponse(responseCode = "200", description = "The data fields to copy.",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = LatestTransactionView.class))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (USER only).",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "NO_TRANSACTION: neither a master row nor a waiting add.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "TRAN_LOOKUP_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    LatestTransactionView latestTransaction();
}
