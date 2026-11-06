package org.carddemo.billpay;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Severity;

@Schema(name = "Preview", requiredProperties = {"acctId", "currentBalance", "currentBalanceDisplay", "payable",
        "message", "severity", "field"})
public record Preview(
        @Schema(examples = "00000000001") String acctId,
        @Schema(description = "Decimal string.", examples = "194.00") String currentBalance,
        @Schema(description = "Legacy edit +9999999999.99 (NUM-BILL-001).", examples = "+0000000194.00")
        String currentBalanceDisplay,
        boolean payable,
        String message,
        Severity severity,
        @Schema(types = {"string", "null"}) String field) {
}
