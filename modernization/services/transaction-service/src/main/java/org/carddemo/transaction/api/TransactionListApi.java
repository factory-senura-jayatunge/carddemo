package org.carddemo.transaction.api;

import static org.carddemo.transaction.api.ApiDocs.JSON;
import static org.carddemo.transaction.api.ApiDocs.PROBLEM;
import static org.carddemo.transaction.api.ApiDocs.PROBLEM_JSON;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Unit TRNL (COTRN00C): the transaction list, USER and ADMIN. The keys are transaction IDs, which
 * are not PII, so they travel as query parameters.
 */
public interface TransactionListApi {

    @GetMapping("/api/transactions")
    @Operation(operationId = "listTransactions", responses = {
        @ApiResponse(responseCode = "200", description = "One page, ascending tran_id, with a notice (information) at"
                + " either end of the list.",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = TransactionPage.class))),
        @ApiResponse(responseCode = "400", description = "INVALID_CURSOR, INVALID_PAGE_SIZE or INVALID_SEARCH_TRAN_ID.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "TRAN_LIST_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    TransactionPage listTransactions(
            @Parameter(description = "Search Tran ID as typed: 1 to 16 digits, trailing spaces ignored, padded on the"
                    + " left with 0. The page starts at the first ID equal or greater.",
                    schema = @Schema(type = "string", examples = "683580"))
            @RequestParam(name = "start", required = false) String start,
            @Parameter(description = "The lastKey of the page shown: the next page.",
                    schema = @Schema(type = "string", pattern = "^[0-9]{16}$", examples = "0000000021711604"))
            @RequestParam(name = "after", required = false) String after,
            @Parameter(description = "The firstKey of the page shown: the previous page.",
                    schema = @Schema(type = "string", pattern = "^[0-9]{16}$", examples = "0000000000683580"))
            @RequestParam(name = "before", required = false) String before,
            @Parameter(description = "Rows per page, 1 to 10; default 10, the screen's row count.",
                    schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "10",
                            defaultValue = "10"))
            @RequestParam(name = "size", required = false) String size);
}
