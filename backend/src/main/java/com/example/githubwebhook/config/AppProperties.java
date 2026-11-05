package com.example.githubwebhook.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
                         @Positive int leaseSeconds) {
    }
}
