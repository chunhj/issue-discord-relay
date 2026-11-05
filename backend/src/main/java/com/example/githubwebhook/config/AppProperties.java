package com.example.githubwebhook.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app")
@Validated
public record AppProperties(@NotNull @Valid Github github, @NotNull @Valid Discord discord,
                            @NotNull @Valid Worker worker) {

    private static final String REDACTED = "<redacted>";

    public record Github(@NotBlank String webhookSecret) {
        @Override
        public String toString() {
            return "Github[webhookSecret=" + REDACTED + "]";
        }
    }

    public record Discord(@NotBlank String webhookUrl) {
        @Override
        public String toString() {
            return "Discord[webhookUrl=" + REDACTED + "]";
        }
    }

    public record Worker(boolean enabled, @Positive long pollIntervalMs, @Positive int batchSize,
                         @Positive int leaseSeconds, @NotNull @Valid @DefaultValue Retry retry) {

        /** maxAttempts counts the first send. Leaf defaults are mandatory: configs without a retry key must bind. */
        public record Retry(@DefaultValue("5") @Positive int maxAttempts,
                            @DefaultValue("5s") @NotNull Duration baseDelay,
                            @DefaultValue("3") @DecimalMin("1") double multiplier,
                            @DefaultValue("300s") @NotNull Duration maxDelay,
                            @DefaultValue("0.2") @DecimalMin("0") @DecimalMax("1") double jitterRatio,
                            @DefaultValue("3600s") @NotNull Duration maxRetryAfter) {
        }
    }
}
