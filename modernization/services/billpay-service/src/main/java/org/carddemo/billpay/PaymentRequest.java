package org.carddemo.billpay;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "PaymentRequest", requiredProperties = "confirm")
public record PaymentRequest(@Schema(maxLength = 1, examples = "Y") String confirm) {
}
