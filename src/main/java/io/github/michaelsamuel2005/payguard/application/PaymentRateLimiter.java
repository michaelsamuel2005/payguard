package io.github.michaelsamuel2005.payguard.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A token bucket per customer: up to {@code perMinute} payment requests, refilled evenly over a minute. It slows a
 * stolen token or a runaway client down; it is per instance, so with N instances the real limit is N times higher
 * (see docs/THREAT_MODEL.md).
 */
public final class PaymentRateLimiter {

    private static final long MINUTE_NANOS = Duration.ofMinutes(1).toNanos();

    private final int perMinute;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public PaymentRateLimiter(int perMinute, Clock clock) {
        if (perMinute < 1) {
            throw new IllegalArgumentException("perMinute must be at least 1");
        }
        this.perMinute = perMinute;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return true if the request may go ahead */
    public boolean tryAcquire(String customer) {
        long now = nanos();
        Bucket bucket = buckets.computeIfAbsent(customer, k -> new Bucket(perMinute, now));
        synchronized (bucket) {
            bucket.refill(now, perMinute);
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /** Whole seconds until one more request would be allowed. */
    public long retryAfterSeconds(String customer) {
        Bucket bucket = buckets.get(customer);
        if (bucket == null) {
            return 0;
        }
        synchronized (bucket) {
            bucket.refill(nanos(), perMinute);
            double missing = Math.max(0.0, 1.0 - bucket.tokens);
            return (long) Math.ceil(missing * 60.0 / perMinute);
        }
    }

    private long nanos() {
        return Math.multiplyExact(clock.millis(), 1_000_000L);
    }

    private static final class Bucket {
        double tokens;
        long last;

        Bucket(int tokens, long now) {
            this.tokens = tokens;
            this.last = now;
        }

        void refill(long now, int perMinute) {
            if (now > last) {
                tokens = Math.min(perMinute, tokens + (double) (now - last) * perMinute / MINUTE_NANOS);
                last = now;
            }
        }
    }
}
