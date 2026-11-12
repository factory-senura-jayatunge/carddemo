package org.carddemo.authorization.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Severity;

/**
 * The summary screen's header and totals (COPAUS0C BR-AUSM-001, BR-AUSM-002). Amounts are decimal
 * strings with two decimals, at full width; the screen's display widths belong to the web.
 */
@Schema(name = "AuthorizationSummary")
public record AuthorizationSummary(
        @Schema(examples = "00000000001") String acctId,
        @Schema(types = {"object", "null"}, description = "Null when the account has no cross-reference row or the"
                + " customer is missing.") SummaryCustomer customer,
        @Schema(description = "From core.account.", examples = "20200.00") String creditLimit,
        @Schema(examples = "10200.00") String cashLimit,
        boolean summaryFound,
        @Schema(description = "Zero when summaryFound is false.") int approvedCount,
        int declinedCount,
        @Schema(examples = "50.00") String approvedAmount,
        String declinedAmount,
        @Schema(description = "Pending approvals.") String creditBalance,
        String cashBalance,
        @Schema(types = {"string", "null"}, examples = "Account:00000000001 not found in XREF file.") String message,
        @Schema(types = {"string", "null"}) Severity severity) {
}
