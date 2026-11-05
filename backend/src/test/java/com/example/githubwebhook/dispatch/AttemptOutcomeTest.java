package com.example.githubwebhook.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.discord.IoKind;
import com.example.githubwebhook.discord.SendResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Plain JUnit, no Spring, no Docker (D-06). */
class AttemptOutcomeTest {

    @Test
    void delivered_is_success() {
        assertThat(AttemptOutcome.from(SendResult.delivered(200, "1"))).isEqualTo(AttemptOutcome.SUCCESS);
    }

    @Test
    void no_status_is_retryable() {
        assertThat(AttemptOutcome.from(SendResult.ioFailure(IoKind.OTHER, "timeout"))).isEqualTo(AttemptOutcome.RETRYABLE_FAIL);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503, 599})
    void retryable_statuses(int status) {
        assertThat(AttemptOutcome.from(SendResult.httpFailure(status, "x"))).isEqualTo(AttemptOutcome.RETRYABLE_FAIL);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 401, 403, 404, 410, 422})
    void permanent_statuses(int status) {
        assertThat(AttemptOutcome.from(SendResult.httpFailure(status, "x"))).isEqualTo(AttemptOutcome.PERMANENT_FAIL);
    }
}
