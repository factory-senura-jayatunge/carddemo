package org.carddemo.batch.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The internal API (batch-execution-contract.md).
 *
 * @param token the bearer token Airflow sends (CARDDEMO_BATCH_INTERNAL_TOKEN); empty refuses every request
 * @param windowRetryAfter the Retry-After of 503 WINDOW_BUSY
 * @param windowDrainTimeout how long a window open waits for the online writes in flight
 * @param launchLockTimeout how long a launch and a window close wait for each other; also the
 *        Retry-After of 503 LAUNCH_IN_PROGRESS and WINDOW_CLOSE_IN_PROGRESS
 */
@ConfigurationProperties("carddemo.batch.internal")
public record InternalApiProperties(String token, @DefaultValue("60s") Duration windowRetryAfter,
        @DefaultValue("120s") Duration windowDrainTimeout, @DefaultValue("30s") Duration launchLockTimeout) {

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    String launchLockRetryAfter() {
        return Long.toString(Math.max(1, launchLockTimeout.toSeconds()));
    }
}
