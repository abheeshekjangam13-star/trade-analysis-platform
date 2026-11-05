package com.tradeanalysis.strategy.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Locks a username for 15 minutes after 5 failed logins in a row (in memory). */
@Component
public class LoginAttempts {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(15);

    private record State(int failures, Instant lockedUntil) {}

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttempts(Clock clock) { this.clock = clock; }

    /** Seconds until the lock ends, or 0 if not locked. */
    public long lockedFor(String username) {
        State s = states.get(key(username));
        if (s == null || s.lockedUntil() == null) return 0;
        long left = Duration.between(clock.instant(), s.lockedUntil()).getSeconds();
        return Math.max(0, left);
    }

    public void failed(String username) {
        states.compute(key(username), (k, s) -> {
            int n = (s == null || (s.lockedUntil() != null && !clock.instant().isBefore(s.lockedUntil()))) ? 1 : s.failures() + 1;
            return new State(n, n >= MAX_FAILURES ? clock.instant().plus(LOCK) : null);
        });
    }

    public void succeeded(String username) { states.remove(key(username)); }

    private static String key(String u) { return u == null ? "" : u.trim().toLowerCase(Locale.ROOT); }
}
