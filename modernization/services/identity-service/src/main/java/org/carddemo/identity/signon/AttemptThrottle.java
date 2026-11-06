package org.carddemo.identity.signon;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.carddemo.identity.IdentityProperties;
import org.springframework.stereotype.Component;

/**
 * Sliding one-minute limits on failed attempts per client address and per user ID (fixed mode
 * of DEF-SGN-002). Successful sign-ons are never limited. In memory: one identity-service
 * instance serves the slice.
 */
@Component
public class AttemptThrottle {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_KEYS = 10_000;

    private final Map<String, Deque<Instant>> failures = new HashMap<>();
    private final int perClient;
    private final int perUser;
    private final Clock clock;

    public AttemptThrottle(IdentityProperties props, Clock clock) {
        this.perClient = props.lockout().failuresPerClientPerMinute();
        this.perUser = props.lockout().failuresPerUserPerMinute();
        this.clock = clock;
    }

    /** How long to wait when the client or the user ID has reached its limit. */
    public synchronized Optional<Duration> check(String client, String userId) {
        Instant now = clock.instant();
        Duration wait = max(waitFor("c:" + client, perClient, now), waitFor("u:" + userId, perUser, now));
        if (wait.isZero()) {
            return Optional.empty();
        }
        return Optional.of(wait.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : wait);
    }

    public synchronized void recordFailure(String client, String userId) {
        Instant now = clock.instant();
        if (failures.size() > MAX_KEYS) {
            failures.values().removeIf(d -> prune(d, now).isEmpty());
        }
        prune(failures.computeIfAbsent("c:" + client, k -> new ArrayDeque<>()), now).addLast(now);
        prune(failures.computeIfAbsent("u:" + userId, k -> new ArrayDeque<>()), now).addLast(now);
    }

    public synchronized void reset() {
        failures.clear();
    }

    private Duration waitFor(String key, int limit, Instant now) {
        Deque<Instant> d = failures.get(key);
        if (d == null || prune(d, now).size() < limit) {
            return Duration.ZERO;
        }
        return Duration.between(now, d.peekFirst().plus(WINDOW));
    }

    private static Deque<Instant> prune(Deque<Instant> d, Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!d.isEmpty() && !d.peekFirst().isAfter(cutoff)) {
            d.pollFirst();
        }
        return d;
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
