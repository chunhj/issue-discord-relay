package com.example.githubwebhook.discord;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Plain JUnit, no Spring, no Docker (D-05, T-02-06). */
class RetryAfterParseTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Duration parse(String header, String body) {
        return DiscordClient.parseRetryAfter(header, body, JSON);
    }

    @Test
    void integer_and_fractional_headers() {
        assertThat(parse("65", null)).isEqualTo(Duration.ofSeconds(65));
        assertThat(parse("64.57", null)).isEqualTo(Duration.ofMillis(64_570));
        assertThat(parse("  2 ", null)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void body_is_the_fallback_and_header_wins() {
        assertThat(parse(null, "{\"retry_after\":1.5}")).isEqualTo(Duration.ofMillis(1500));
        assertThat(parse("2", "{\"retry_after\":1.5}")).isEqualTo(Duration.ofSeconds(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"soon", "-5", "0", "NaN", "Infinity", "1e400", "Wed, 21 Oct 2026 07:28:00 GMT", ""})
    void unusable_header_falls_through_to_body(String header) {
        assertThat(parse(header, null)).isNull();
        assertThat(parse(header, "{\"retry_after\":1.5}")).isEqualTo(Duration.ofMillis(1500));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "not json", "{}", "{\"retry_after\":\"2\"}", "{\"retry_after\":null}",
            "{\"retry_after\":-1}", "{\"retry_after\":0}", "{\"retry_after\":1e400}", "[1,2]"})
    void unusable_body_gives_null(String body) {
        assertThat(parse(null, body)).isNull();
        assertThat(parse("soon", body)).isNull();
    }

    @Test
    void huge_values_are_clamped_not_overflowed() {
        Duration header = parse("99999999999999", null);
        Duration body = parse(null, "{\"retry_after\":99999999999999}");
        for (Duration d : new Duration[] {header, body}) {
            assertThat(d).isPositive().isLessThanOrEqualTo(Duration.ofDays(365));
        }
    }
}
