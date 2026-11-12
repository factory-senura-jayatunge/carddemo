package org.carddemo.transaction.api;

import static org.carddemo.transaction.api.ApiDocs.JSON;
import static org.carddemo.transaction.api.ApiDocs.PROBLEM;
import static org.carddemo.transaction.api.ApiDocs.PROBLEM_JSON;
import static org.carddemo.transaction.api.ApiDocs.REPORT_PROBLEM;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Unit RPTS (CORPT00C): a USER requests a transaction report, which batch-service writes; the
 * requester and any ADMIN read its status and download its file (D-48).
 */
public interface ReportApi {

    String REQUEST_ERRORS = "REPORT_TYPE_REQUIRED, START_/END_MONTH/DAY/YEAR_REQUIRED, START_/END_MONTH/DAY/YEAR_INVALID,"
            + " START_DATE_INVALID, END_DATE_INVALID, DATE_RANGE_INVALID or CONFIRM_INVALID; the first failing check only";
    String RUN_ID = "A positive integer.";

    @PostMapping("/api/reports/transactions")
    @Operation(operationId = "requestTransactionReport",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = ReportRequest.class))),
            responses = {
        @ApiResponse(responseCode = "202", description = "Requested and started; message is the success text.",
                headers = @Header(name = "Location", description = "/api/reports/transactions/{runId}",
                        schema = @Schema(type = "string")),
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ReportRun.class))),
        @ApiResponse(responseCode = "400", description = REQUEST_ERRORS + ".",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED (USER only) or CSRF.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "422", description = "CONFIRM_REQUIRED, with the resolved range. Nothing written.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = REPORT_PROBLEM))),
        @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "503", description = "REPORT_TRIGGER_FAILED: the run is FAILED, TRIGGER_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    ResponseEntity<ReportRun> requestTransactionReport(@RequestBody(required = false) ReportRequest request,
            @Parameter(hidden = true) Authentication authentication);

    @GetMapping("/api/reports/transactions/{runId}")
    @Operation(operationId = "getTransactionReport", responses = {
        @ApiResponse(responseCode = "200", description = "The run.",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = ReportRun.class))),
        @ApiResponse(responseCode = "400", description = "INVALID_RUN_ID.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "REPORT_RUN_NOT_FOUND, also for another user's run.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    ReportRun getTransactionReport(@Parameter(description = RUN_ID) @PathVariable("runId") String runId,
            @Parameter(hidden = true) Authentication authentication);

    @GetMapping("/api/reports/transactions/{runId}/file")
    @Operation(operationId = "downloadTransactionReport", responses = {
        @ApiResponse(responseCode = "200", description = "TRANREPT.txt as the job wrote it: 133-byte lines.",
                headers = {
                    @Header(name = "Content-Disposition", schema = @Schema(type = "string",
                            examples = "attachment; filename=\"TRANREPT-17.txt\"")),
                    @Header(name = "Cache-Control", schema = @Schema(type = "string", examples = "no-store"))},
                content = @Content(mediaType = "text/plain", schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "400", description = "INVALID_RUN_ID.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "404", description = "REPORT_RUN_NOT_FOUND, also for another user's run.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "409", description = "REPORT_NOT_READY: the run is not COMPLETED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "REPORT_FILE_MISSING or INTERNAL_ERROR.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    void downloadTransactionReport(@Parameter(description = RUN_ID) @PathVariable("runId") String runId,
            @Parameter(hidden = true) Authentication authentication, @Parameter(hidden = true) HttpServletResponse response);
}
