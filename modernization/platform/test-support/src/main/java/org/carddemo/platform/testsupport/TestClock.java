package org.carddemo.platform.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock tests set and advance. {@link #shared()} is the one {@link TestClockConfiguration}
 * registers as the application clock and {@link TestTokens} issues tokens from, so a token is
 * always valid at the application's "now" unless a test moves the clock past its expiry.
 */
public final class TestClock extends Clock {

    /** 2026-11-06 09:30:15.654321 UTC: a fraction of a second, so whole-second truncation shows. */
    public static final Instant START = Instant.parse("2026-11-06T09:30:15.654321Z");
    public static final ZoneId ZONE = ZoneOffset.UTC;

    private static final TestClock SHARED = new TestClock(START, ZONE);

    private volatile Instant now;
    private final ZoneId zone;

    public TestClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public static TestClock shared() {
        return SHARED;
    }

    public TestClock set(Instant instant) {
        this.now = instant;
        return this;
    }

    public TestClock advance(Duration duration) {
        this.now = now.plus(duration);
        return this;
    }

    public TestClock reset() {
        return set(START);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        // A view on the same instant, so advancing this clock moves the view too.
        TestClock owner = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return owner.withZone(other);
            }

            @Override
            public Instant instant() {
                return owner.instant();
            }
        };
    }

    @Override
    public Instant instant() {
        return now;
    }
}
