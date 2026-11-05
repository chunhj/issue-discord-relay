package com.example.githubwebhook.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.config.AppProperties;
import com.example.githubwebhook.discord.SendResult;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Plain JUnit, no Spring, no Docker (D-02, D-03). */
class RetryPolicyTest {

    private static final AppProperties.Worker.Retry DEFAULTS = new AppProperties.Worker.Retry(5,
            Duration.ofSeconds(5), 3, Duration.ofSeconds(300), 0.2, Duration.ofSeconds(3600));

    private static RandomGenerator fixed(double value) {
        RandomGenerator delegate = RandomGenerator.getDefault();
        return new RandomGenerator() {
            @Override
            public double nextDouble() {
                return value;
            }

            @Override
            public long nextLong() {
                return delegate.nextLong();
            }
        };
    }

    @Test
    void middle_random_gives_pure_exponential_with_cap() {
        RetryPolicy policy = new RetryPolicy(DEFAULTS, fixed(0.5));
        assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofSeconds(15));
        assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofSeconds(45));
        assertThat(policy.nextDelay(4)).isEqualTo(Duration.ofSeconds(135));
        assertThat(policy.nextDelay(5)).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void jitter_stays_within_ratio() {
        assertThat(new RetryPolicy(DEFAULTS, fixed(0.0)).nextDelay(1)).isEqualTo(Duration.ofSeconds(4));
        assertThat(new RetryPolicy(DEFAULTS, fixed(0.999999)).nextDelay(1)).isLessThanOrEqualTo(Duration.ofSeconds(6));
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 5000})
    void huge_attempt_numbers_are_capped_and_safe(int attemptNo) {
        Duration atTop = new RetryPolicy(DEFAULTS, fixed(0.999999)).nextDelay(attemptNo);
        Duration atBottom = new RetryPolicy(DEFAULTS, fixed(0.0)).nextDelay(attemptNo);
        assertThat(atBottom).isEqualTo(Duration.ofSeconds(240));
        assertThat(atTop).isBetween(Duration.ofSeconds(240), Duration.ofSeconds(360));
        assertThat(atBottom.isNegative()).isFalse();
    }

    @Test
    void budget_counts_the_first_send() {
        RetryPolicy policy = new RetryPolicy(DEFAULTS, fixed(0.5));
        assertThat(policy.isExhausted(4)).isFalse();
        assertThat(policy.isExhausted(5)).isTrue();
        assertThat(policy.isExhausted(6)).isTrue();
    }

    @Test
    void custom_config_is_honoured() {
        RetryPolicy policy = new RetryPolicy(new AppProperties.Worker.Retry(3, Duration.ofSeconds(1), 2,
                Duration.ofSeconds(10), 0.2, Duration.ofSeconds(3600)), fixed(0.5));
        assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.isExhausted(2)).isFalse();
        assertThat(policy.isExhausted(3)).isTrue();
    }

    @Test
    void rate_limit_wait_is_exact_without_jitter() {
        SendResult r = SendResult.httpFailure(429, "HTTP 429", Duration.ofSeconds(2));
        assertThat(new RetryPolicy(DEFAULTS, fixed(0.0)).nextDelay(1, r)).isEqualTo(Duration.ofSeconds(2));
        assertThat(new RetryPolicy(DEFAULTS, fixed(0.999999)).nextDelay(3, r)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void rate_limit_wait_is_capped_at_max_retry_after() {
        SendResult r = SendResult.httpFailure(429, "HTTP 429", Duration.ofSeconds(3601));
        assertThat(new RetryPolicy(DEFAULTS, fixed(0.5)).nextDelay(1, r)).isEqualTo(Duration.ofSeconds(3600));
    }

    @Test
    void rate_limit_without_hint_uses_backoff() {
        SendResult r = SendResult.httpFailure(429, "HTTP 429");
        assertThat(new RetryPolicy(DEFAULTS, fixed(0.5)).nextDelay(2, r)).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void retry_after_on_non_429_is_ignored() {
        SendResult r = SendResult.httpFailure(503, "HTTP 503", Duration.ofSeconds(2));
        assertThat(new RetryPolicy(DEFAULTS, fixed(0.5)).nextDelay(1, r)).isEqualTo(Duration.ofSeconds(5));
    }
}
