package io.github.michaelsamuel2005.payguard.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.michaelsamuel2005.payguard.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T09:00:00Z"));
    private final PaymentRateLimiter limiter = new PaymentRateLimiter(3, clock);

    @Test
    void allows_a_burst_up_to_the_limit_then_refills_over_the_minute() {
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isFalse();
        assertThat(limiter.retryAfterSeconds("alice")).isEqualTo(20);
        clock.advance(Duration.ofSeconds(20));
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isFalse();
    }

    @Test
    void customers_have_separate_buckets() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("alice");
        }
        assertThat(limiter.tryAcquire("alice")).isFalse();
        assertThat(limiter.tryAcquire("bob")).isTrue();
        assertThat(limiter.retryAfterSeconds("nobody")).isZero();
    }
}
