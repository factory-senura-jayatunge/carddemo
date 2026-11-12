package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The trigger settings are checked when the service starts, never clamped or defaulted. */
class ReportPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReportProperties.class)
    static class Settings {
    }

    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Settings.class);

    @Test
    void theDefaultsStart() {
        context.run(started -> {
            assertThat(started).hasNotFailed();
            ReportProperties.Trigger trigger = started.getBean(ReportProperties.class).trigger();
            assertThat(trigger.retries()).isEqualTo(3);
            assertThat(trigger.backoff()).hasSeconds(1);
            assertThat(trigger.timeout()).hasSeconds(10);
            assertThat(trigger.deadline()).hasSeconds(30);
        });
    }

    @Test
    void aTimeoutLongerThanTheDeadlineAndNoBackoffOrRetriesStart() {
        context.withPropertyValues("carddemo.transaction.report.trigger.timeout=60s",
                        "carddemo.transaction.report.trigger.deadline=2s",
                        "carddemo.transaction.report.trigger.backoff=0s",
                        "carddemo.transaction.report.trigger.retries=0")
                .run(started -> assertThat(started).hasNotFailed());
    }

    /** ReportStaging parses the staging folder before any bean; the binding here ignores it. */
    @Test
    void theStagingFolderIsNotBoundHere() {
        context.withPropertyValues("carddemo.transaction.report.staging-dir=relative")
                .run(started -> assertThat(started).hasNotFailed());
    }

    @ParameterizedTest
    @CsvSource({"deadline, 0s", "deadline, -1s", "timeout, 0s", "timeout, -5s", "backoff, -1ms", "retries, -1"})
    void aWrongValueFailsTheStartWithItsName(String name, String value) {
        String property = "carddemo.transaction.report.trigger." + name;
        context.withPropertyValues(property + "=" + value).run(failed -> {
            assertThat(failed).hasFailed();
            assertThat(failed.getStartupFailure()).rootCause().hasMessageStartingWith(property + " must be");
        });
    }
}
