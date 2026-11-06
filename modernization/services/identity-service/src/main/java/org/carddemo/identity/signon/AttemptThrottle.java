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
 * The sign-on rate limit of fixed DEF-SGN-002: a sliding window of failed attempts per client
 * address. Only failures are recorded, so a successful sign-on is never limited (the browser
 * tests sign on many times from one address), and a refused request counts nothing. In memory:
 * one identity-service instance serves the slice.
 */
@Component
public class AttemptThrottle {

    private static final int MAX_KEYS = 10_000;

    private final Map<String, Deque<Instant>> failures = new HashMap<>();
    private final int limit;
    private final Duration window;
    private final Clock clock;

    public AttemptThrottle(IdentityProperties props, Clock clock) {
        this.limit = props.rateLimit().failures();
        this.window = props.rateLimit().window();
        this.clock = clock;
    }

    /** How long the client must wait, when it has reached the limit. */
    public synchronized Optional<Duration> check(String client) {
        Instant now = clock.instant();
        Deque<Instant> d = failures.get(client);
        if (d == null || prune(d, now).size() < limit) {
            return Optional.empty();
        }
        Duration wait = Duration.between(now, d.peekFirst().plus(window));
        return Optional.of(wait.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : wait);
    }

    public synchronized void recordFailure(String client) {
        Instant now = clock.instant();
        if (failures.size() > MAX_KEYS) {
            failures.values().removeIf(d -> prune(d, now).isEmpty());
        }
        prune(failures.computeIfAbsent(client, k -> new ArrayDeque<>()), now).addLast(now);
    }

    public synchronized void reset() {
        failures.clear();
    }

    private Deque<Instant> prune(Deque<Instant> d, Instant now) {
        Instant cutoff = now.minus(window);
        while (!d.isEmpty() && !d.peekFirst().isAfter(cutoff)) {
            d.pollFirst();
        }
        return d;
    }
}
