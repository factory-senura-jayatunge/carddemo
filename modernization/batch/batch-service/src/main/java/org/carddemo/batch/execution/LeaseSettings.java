package org.carddemo.batch.execution;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The execution lease timing (batch-execution-contract.md, section 4.1).
 *
 * @param renewalInterval how often the owner renews its leases
 * @param ttl after this long without a renewal a lease is reported expired
 * @param grace added to the TTL before a launch may recover the execution
 */
@ConfigurationProperties("carddemo.batch.lease")
public record LeaseSettings(@DefaultValue("10s") Duration renewalInterval, @DefaultValue("60s") Duration ttl,
        @DefaultValue("60s") Duration grace) {

    public LeaseSettings {
        if (renewalInterval.isNegative() || renewalInterval.isZero()) {
            throw new IllegalArgumentException("carddemo.batch.lease.renewal-interval must be positive");
        }
        if (ttl.compareTo(renewalInterval.multipliedBy(3)) < 0) {
            throw new IllegalArgumentException("carddemo.batch.lease.ttl must be at least 3 times the renewal interval");
        }
        if (grace.compareTo(ttl) < 0) {
            throw new IllegalArgumentException("carddemo.batch.lease.grace must be at least the TTL");
        }
        if (ttl.plus(grace).toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("carddemo.batch.lease.ttl + grace must fit execution_lease.recoverable_after_ms");
        }
    }

    /** TTL + grace: a lease not renewed for this long is recoverable. */
    public Duration recoverableAfter() {
        return ttl.plus(grace);
    }
}
