package com.example.githubwebhook.dispatch;

import com.example.githubwebhook.config.AppProperties;
import com.example.githubwebhook.discord.SendResult;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Pure backoff math and attempt budget (D-01, D-02, D-03). Holds no state besides the config and a random source. */
@Component
public class RetryPolicy {

    /** Prefix of last_error when the attempt budget is spent. */
    static final String EXHAUSTED_CODE = "RETRIES_EXHAUSTED";

    private final AppProperties.Worker.Retry config;

    private final RandomGenerator random;

    @Autowired
    public RetryPolicy(AppProperties props) {
        this(props.worker().retry(), RandomGenerator.getDefault());
    }

    RetryPolicy(AppProperties.Worker.Retry config, RandomGenerator random) {
        this.config = config;
        this.random = random;
    }

    /** min(maxDelay, base x multiplier^(n-1)) times a uniform factor in [1-ratio, 1+ratio]. */
    public Duration nextDelay(int attemptNo) {
        // The power may be Infinity for huge n; min() makes that harmless.
        double raw = config.baseDelay().toMillis() * Math.pow(config.multiplier(), attemptNo - 1);
        double capped = Math.min(config.maxDelay().toMillis(), raw);
        // Jitter comes after the cap, so a delay may exceed maxDelay by the jitter ratio.
        double jittered = capped * (1 + config.jitterRatio() * (2 * random.nextDouble() - 1));
        return Duration.ofMillis(Math.max(0, Math.round(jittered)));
    }

    /** A 429 with Discord's announced wait is honoured exactly (no jitter, never earlier), capped at max-retry-after. */
    public Duration nextDelay(int attemptNo, SendResult result) {
        if (Integer.valueOf(429).equals(result.httpStatus()) && result.retryAfter() != null) {
            Duration cap = config.maxRetryAfter();
            return result.retryAfter().compareTo(cap) > 0 ? cap : result.retryAfter();
        }
        return nextDelay(attemptNo);
    }

    /** True when {@code attempts} sends have been made and no more are allowed (maxAttempts counts the first send). */
    public boolean isExhausted(int attempts) {
        return attempts >= config.maxAttempts();
    }

    public int maxAttempts() {
        return config.maxAttempts();
    }
}
