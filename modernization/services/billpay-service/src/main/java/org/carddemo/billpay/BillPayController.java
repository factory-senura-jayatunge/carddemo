package org.carddemo.billpay;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/billpay/accounts")
@PreAuthorize("hasRole('USER')")
@Tag(name = "billpay")
public class BillPayController {

    static final String JSON = "application/json";
    static final String PROBLEM_JSON = "application/problem+json";
    static final String PROBLEM = "#/components/schemas/Problem";
    static final String PAYMENT_PROBLEM = "#/components/schemas/PaymentProblem";

    private final BillPayService service;

    public BillPayController(BillPayService service) {
        this.service = service;
    }

    @GetMapping("/{acctId}")
    @Operation(operationId = "previewBillPayment", responses = {
        @ApiResponse(responseCode = "200", description = "Balance and the confirmation prompt (VAL-BILL-004, VAL-BILL-005).",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = Preview.class))),
        @ApiResponse(responseCode = "400", description = "ACCT_ID_REQUIRED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "ACCOUNT_LOOKUP_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    public Preview preview(@PathVariable String acctId) {
        return service.preview(acctId);
    }

    @PostMapping("/{acctId}/payments")
    @Operation(operationId = "payBill",
            parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "string", format = "uuid")),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = PaymentRequest.class))),
            responses = {
        @ApiResponse(responseCode = "201",
                description = "Paid, or the stored result of the same key (header Idempotent-Replayed: true).",
                headers = @Header(name = "Idempotent-Replayed", schema = @Schema(type = "boolean")),
                content = @Content(mediaType = JSON, schema = @Schema(implementation = PaymentResult.class))),
        @ApiResponse(responseCode = "400", description = "ACCT_ID_REQUIRED, INVALID_CONFIRM or IDEMPOTENCY_KEY_REQUIRED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED or CSRF.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "409", description = "TRAN_ID_EXISTS.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "422", description = "NOTHING_TO_PAY (with the balance) or IDEMPOTENCY_KEY_REUSED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PAYMENT_PROBLEM))),
        @ApiResponse(responseCode = "500", description = "ACCOUNT_LOOKUP_FAILED, XREF_LOOKUP_FAILED, TRAN_LOOKUP_FAILED, "
                + "TRAN_ADD_FAILED or ACCOUNT_UPDATE_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "503", description = "BATCH_WINDOW.",
                headers = @Header(name = "Retry-After", description = "Seconds.", schema = @Schema(type = "integer")),
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    public ResponseEntity<PaymentResult> pay(
            @PathVariable String acctId,
            @Parameter(hidden = true) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) PaymentRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        BillPayService.Outcome outcome = service.pay(acctId, request == null ? null : request.confirm(), idempotencyKey,
                jwt.getSubject());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.result());
    }
}
