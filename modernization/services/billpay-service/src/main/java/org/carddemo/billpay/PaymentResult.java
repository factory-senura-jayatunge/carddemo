package org.carddemo.billpay;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Severity;

/** {@code amount} is the amount paid, always positive, whatever the stored sign (DEF-BILL-010). */
@Schema(name = "PaymentResult", requiredProperties = {"tranId", "amount", "acctId", "message", "severity", "field"})
public record PaymentResult(
        @Schema(examples = "0000000000000001") String tranId,
        @Schema(examples = "194.00") String amount,
        String acctId,
        String message,
        Severity severity,
        @Schema(types = {"string", "null"}) String field) {
}
