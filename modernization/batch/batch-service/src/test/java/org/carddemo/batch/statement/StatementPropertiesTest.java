package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * statementJob's settings: a bad value fails the start with the property's name and is never
 * clamped. The capture has no lock wait of its own: it takes batch-service's one lock wait
 * (batch-execution-contract.md section 9).
 */
class StatementPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StatementProperties.class)
    static class Settings {
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(Settings.class);
    }

    @Test
    void theDefaults() {
        runner().run(context -> {
            StatementProperties p = context.getBean(StatementProperties.class);
            assertThat(p.minimumPaymentFloor()).isEqualByComparingTo("25.00");
            assertThat(p.minimumPaymentPercent()).isEqualByComparingTo("1.00");
        });
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "carddemo.batch.statement.minimum-payment-floor, -0.01",
            "carddemo.batch.statement.minimum-payment-floor, 1.005",
            "carddemo.batch.statement.minimum-payment-percent, 0",
            "carddemo.batch.statement.minimum-payment-percent, -1",
            "carddemo.batch.statement.minimum-payment-percent, 100.01"})
    void aBadValueFailsTheStartWithThePropertyName(String property, String value) {
        runner().withPropertyValues(property + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure().rootCause().isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith(property);
        });
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "carddemo.batch.statement.minimum-payment-floor, 0",
            "carddemo.batch.statement.minimum-payment-percent, 100"})
    void theLimitsAreAccepted(String property, String value) {
        runner().withPropertyValues(property + "=" + value).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aValueIsNeverClamped() {
        assertThatThrownBy(() -> new StatementProperties(new BigDecimal("25.00"), new BigDecimal("150")))
                .hasMessageContaining(StatementProperties.PERCENT);
    }

    @Test
    void theCaptureWaitsBatchServicesLockWait() {
        assertThat(StatementCapture.millis(Duration.ofSeconds(30))).isEqualTo(30_000);
        assertThat(StatementCapture.millis(Duration.ofMillis(1))).isEqualTo(1);
        assertThat(StatementCapture.millis(Duration.ofMillis(Integer.MAX_VALUE))).isEqualTo(Integer.MAX_VALUE);
    }

    /** 0 would not wait and a negative wait would wait forever; neither is clamped. */
    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S", "PT0.0009S", "P25D"})
    void aLockWaitTheCaptureCannotUseFailsWithItsPropertyName(String wait) {
        assertThatThrownBy(() -> StatementCapture.millis(Duration.parse(wait)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(StatementCapture.LAUNCH_LOCK_TIMEOUT);
    }
}
