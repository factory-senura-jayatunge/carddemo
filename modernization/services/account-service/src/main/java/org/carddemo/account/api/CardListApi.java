package org.carddemo.account.api;

import static org.carddemo.account.api.ApiDocs.JSON;
import static org.carddemo.account.api.ApiDocs.PROBLEM;
import static org.carddemo.account.api.ApiDocs.PROBLEM_JSON;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Unit CRDL (COCRDLIC): the card list, USER and ADMIN. A POST, so no PAN filter is in a URL. */
public interface CardListApi {

    @PostMapping("/api/cards/search")
    @Operation(operationId = "listCards",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = JSON, schema = @Schema(implementation = CardSearchRequest.class))),
            responses = {
        @ApiResponse(responseCode = "200", description = "One page, with a notice (information) at the end of the list.",
                content = @Content(mediaType = JSON, schema = @Schema(implementation = CardPage.class))),
        @ApiResponse(responseCode = "400", description = "INVALID_CURSOR, INVALID_PAGE_SIZE, INVALID_ACCT_FILTER or"
                + " INVALID_CARD_FILTER.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "403", description = "ACCESS_DENIED or CSRF.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
        @ApiResponse(responseCode = "500", description = "CARD_LIST_FAILED.",
                content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM))),
    })
    CardPage listCards(@RequestBody(required = false) CardSearchRequest request);
}
