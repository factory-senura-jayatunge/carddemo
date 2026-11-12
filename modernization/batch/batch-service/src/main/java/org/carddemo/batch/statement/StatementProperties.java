package org.carddemo.batch.statement;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * statementJob's settings (batch.md, "Minimum payment"). A bad value fails the start with the
 * property's name and is never clamped. The capture's lock wait is not one of them: it is
 * batch-service's one lock wait, {@code carddemo.batch.internal.launch-lock-timeout}
 * (batch-execution-contract.md section 9).
 *
 * @param minimumPaymentFloor {@code F}, at least 0
 * @param minimumPaymentPercent {@code P}, more than 0 and at most 100
 */
@ConfigurationProperties("carddemo.batch.statement")
public record StatementProperties(
        @DefaultValue("25.00") BigDecimal minimumPaymentFloor,
        @DefaultValue("1.00") BigDecimal minimumPaymentPercent) {

    static final String FLOOR = "carddemo.batch.statement.minimum-payment-floor";
    static final String PERCENT = "carddemo.batch.statement.minimum-payment-percent";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public StatementProperties {
        if (minimumPaymentFloor == null || minimumPaymentFloor.signum() < 0 || minimumPaymentFloor.scale() > 2) {
            throw new IllegalArgumentException(FLOOR + " must be an amount of at least 0 with at most 2 decimals");
        }
        if (minimumPaymentPercent == null || minimumPaymentPercent.signum() <= 0
                || minimumPaymentPercent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException(PERCENT + " must be more than 0 and at most 100");
        }
    }
}
