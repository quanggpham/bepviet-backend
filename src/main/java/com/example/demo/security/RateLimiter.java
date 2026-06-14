package com.example.demo.security;

import com.example.demo.exception.TooManyRequestsException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window counter keyed by an arbitrary string (IP, e-mail, ...).
 * State is per JVM: with several instances each one enforces its own limits.
 */
@Component
public class RateLimiter {

    private static final Duration MAX_WINDOW = Duration.ofDays(1);

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    /** Records a hit, or throws if {@code limit} hits were already recorded within {@code window}. */
    public void acquire(String key, int limit, Duration window, String message) {
        Deque<Instant> timestamps = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            Instant now = Instant.now();
            prune(timestamps, now.minus(window));
            if (timestamps.size() >= limit) {
                throw new TooManyRequestsException(message, retryAfterSeconds(timestamps, window, now));
            }
            timestamps.addLast(now);
        }
    }

    /** Throws if {@code limit} hits were recorded within {@code window}, without recording a new one. */
    public void ensureBelow(String key, int limit, Duration window, String message) {
        Deque<Instant> timestamps = hits.get(key);
        if (timestamps == null) {
            return;
        }
        synchronized (timestamps) {
            Instant now = Instant.now();
            prune(timestamps, now.minus(window));
            if (timestamps.size() >= limit) {
                throw new TooManyRequestsException(message, retryAfterSeconds(timestamps, window, now));
            }
        }
    }

    public void record(String key) {
        Deque<Instant> timestamps = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            timestamps.addLast(Instant.now());
        }
    }

    public void reset(String key) {
        hits.remove(key);
    }

    @Scheduled(fixedDelay = 600_000)
    void evictIdleKeys() {
        Instant cutoff = Instant.now().minus(MAX_WINDOW);
        hits.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                prune(entry.getValue(), cutoff);
                return entry.getValue().isEmpty();
            }
        });
    }

    void clearAll() {
        hits.clear();
    }

    private static void prune(Deque<Instant> timestamps, Instant cutoff) {
        while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
            timestamps.pollFirst();
        }
    }

    private static long retryAfterSeconds(Deque<Instant> timestamps, Duration window, Instant now) {
        long seconds = Duration.between(now, timestamps.peekFirst().plus(window)).toSeconds() + 1;
        return Math.max(1, seconds);
    }
}
