package com.example.githubwebhook.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.support.IntegrationTestBase;
import com.example.githubwebhook.support.SignedRequests;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** RETRY-02..04: a failed send is retried with backoff, permanent errors and exhausted budgets end FAILED. */
class RetryIT extends IntegrationTestBase {

    @Autowired
    private DispatchWorker worker;

    private int requestCount() {
        return WIREMOCK.findAll(postRequestedFor(urlPathEqualTo(DISCORD_PATH))).size();
    }

    private void seed(long n) throws Exception {
        assertThat(postSigned("issues", "delivery-" + n, SignedRequests.issuesOpened(n, "T")).statusCode())
                .isEqualTo(202);
    }

    private Map<String, Object> job() {
        return jdbc.queryForMap("SELECT * FROM dispatch_jobs");
    }

    private List<Map<String, Object>> attemptRows() {
        return jdbc.queryForList("SELECT * FROM dispatch_attempts ORDER BY attempt_no");
    }

    private void forceDue() {
        jdbc.update("UPDATE dispatch_jobs SET next_attempt_at = now()");
    }

    /** Both timestamps come from the same transaction, so this is exactly the computed backoff delay. */
    private double delaySeconds(int attemptNo) {
        return jdbc.queryForObject("""
                SELECT EXTRACT(EPOCH FROM (j.next_attempt_at - a.finished_at))::float8
                FROM dispatch_jobs j JOIN dispatch_attempts a ON a.job_id = j.id
                WHERE a.attempt_no = ?
                """, Double.class, attemptNo);
    }

    private void stubScenario(String name, int... statuses) {
        String state = Scenario.STARTED;
        for (int i = 0; i < statuses.length; i++) {
            boolean last = i == statuses.length - 1;
            String next = last ? state : "s" + (i + 1);
            var response = statuses[i] == 200
                    ? aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                            .withBody("{\"id\":\"42\"}")
                    : aResponse().withStatus(statuses[i]).withBody("boom");
            WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).inScenario(name)
                    .whenScenarioStateIs(state).willReturn(response).willSetStateTo(next));
            state = next;
        }
    }

    @Test
    void server_error_then_success() throws Exception {
        stubScenario("retry", 500, 200);
        seed(9001);

        assertThat(worker.pollOnce()).isEqualTo(1);

        Map<String, Object> j = job();
        assertThat(j.get("status")).isEqualTo("RETRY_WAIT");
        assertThat(j.get("attempts")).isEqualTo(1);
        assertThat(j.get("locked_until")).isNull();
        assertThat((String) j.get("last_error")).startsWith("HTTP 500");
        assertThat(delaySeconds(1)).isBetween(4.0, 6.0);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > now() FROM dispatch_jobs", Boolean.class)).isTrue();
        assertThat(worker.pollOnce()).isZero();

        forceDue();
        assertThat(worker.pollOnce()).isEqualTo(1);

        j = job();
        assertThat(j.get("status")).isEqualTo("SENT");
        assertThat(j.get("attempts")).isEqualTo(2);
        assertThat(j.get("discord_message_id")).isEqualTo("42");
        assertThat(j.get("last_error")).isNull();
        List<Map<String, Object>> rows = attemptRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("outcome")).isEqualTo("RETRYABLE_FAIL");
        assertThat(rows.get(0).get("http_status")).isEqualTo(500);
        assertThat(rows.get(0).get("finished_at")).isNotNull();
        assertThat(rows.get(1).get("outcome")).isEqualTo("SUCCESS");
        assertThat(requestCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 410})
    void permanent_errors_fail_immediately(int status) throws Exception {
        stubScenario("permanent", status);
        seed(9100 + status);

        assertThat(worker.pollOnce()).isEqualTo(1);

        Map<String, Object> j = job();
        assertThat(j.get("status")).isEqualTo("FAILED");
        assertThat(j.get("attempts")).isEqualTo(1);
        assertThat((String) j.get("last_error")).startsWith("HTTP " + status);
        List<Map<String, Object>> rows = attemptRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("outcome")).isEqualTo("PERMANENT_FAIL");
        assertThat(rows.get(0).get("http_status")).isEqualTo(status);
        forceDue();
        assertThat(worker.pollOnce()).isZero();
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void request_timeout_408_is_retried() throws Exception {
        stubScenario("timeout408", 408, 200);
        seed(9408);

        assertThat(worker.pollOnce()).isEqualTo(1);

        assertThat(job().get("status")).isEqualTo("RETRY_WAIT");
        assertThat(delaySeconds(1)).isBetween(4.0, 6.0);
    }

    @Test
    void retry_sequence_grows_backoff() throws Exception {
        stubScenario("growth", 500, 500, 200);
        seed(9201);

        assertThat(worker.pollOnce()).isEqualTo(1);
        assertThat(delaySeconds(1)).isBetween(4.0, 6.0);

        forceDue();
        assertThat(worker.pollOnce()).isEqualTo(1);
        Map<String, Object> j = job();
        assertThat(j.get("status")).isEqualTo("RETRY_WAIT");
        assertThat(j.get("attempts")).isEqualTo(2);
        assertThat(delaySeconds(2)).isBetween(12.0, 18.0);

        forceDue();
        assertThat(worker.pollOnce()).isEqualTo(1);
        j = job();
        assertThat(j.get("status")).isEqualTo("SENT");
        assertThat(j.get("attempts")).isEqualTo(3);
        assertThat(attemptRows()).extracting(r -> r.get("outcome"))
                .containsExactly("RETRYABLE_FAIL", "RETRYABLE_FAIL", "SUCCESS");
    }

    @Test
    void exhausted_becomes_failed() throws Exception {
        stubScenario("exhaust", 500);
        seed(9202);

        for (int k = 1; k <= 4; k++) {
            assertThat(worker.pollOnce()).isEqualTo(1);
            Map<String, Object> j = job();
            assertThat(j.get("status")).isEqualTo("RETRY_WAIT");
            assertThat(j.get("attempts")).isEqualTo(k);
            forceDue();
        }
        assertThat(worker.pollOnce()).isEqualTo(1);

        Map<String, Object> j = job();
        assertThat(j.get("status")).isEqualTo("FAILED");
        assertThat(j.get("attempts")).isEqualTo(5);
        assertThat(j.get("locked_until")).isNull();
        assertThat((String) j.get("last_error")).startsWith("RETRIES_EXHAUSTED").contains("HTTP 500")
                .doesNotContain("test-token").doesNotContain("/webhooks/123").doesNotContain("localhost")
                .doesNotContain(String.valueOf(WIREMOCK.port()));
        List<Map<String, Object>> rows = attemptRows();
        assertThat(rows).hasSize(5);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("finished_at")).isNotNull();
            assertThat(r.get("outcome")).isEqualTo("RETRYABLE_FAIL");
            assertThat(r.get("http_status")).isEqualTo(500);
        });
        assertThat(requestCount()).isEqualTo(5);

        forceDue();
        assertThat(worker.pollOnce()).isZero();
        assertThat(requestCount()).isEqualTo(5);
    }

    /** First answer is a 429 with the given headers/body, second answer is a 200. */
    private void stubRateLimitedThenOk(String retryAfterHeader, String body) {
        var limited = aResponse().withStatus(429).withHeader("Content-Type", "application/json").withBody(body);
        if (retryAfterHeader != null) {
            limited = limited.withHeader("Retry-After", retryAfterHeader);
        }
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).inScenario("rl")
                .whenScenarioStateIs(Scenario.STARTED).willReturn(limited).willSetStateTo("ok"));
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).inScenario("rl").whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"42\"}")));
    }

    private void stubAlways429(String retryAfterHeader) {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", retryAfterHeader).withBody("{\"message\":\"rate limited\"}")));
    }

    @Test
    void rate_limited_honours_retry_after() throws Exception {
        stubRateLimitedThenOk("2", "{\"message\":\"You are being rate limited.\",\"retry_after\":1.5,\"global\":false}");
        seed(9301);

        assertThat(worker.pollOnce()).isEqualTo(1);

        assertThat(job().get("status")).isEqualTo("RETRY_WAIT");
        // Header wins over the body's 1.5 s, and there is no jitter.
        assertThat(delaySeconds(1)).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(worker.pollOnce()).isZero();

        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    worker.pollOnce();
                    return "SENT".equals(job().get("status"));
                });

        var requests = WIREMOCK.findAll(postRequestedFor(urlPathEqualTo(DISCORD_PATH)));
        assertThat(requests).hasSize(2);
        long gap = requests.get(1).getLoggedDate().getTime() - requests.get(0).getLoggedDate().getTime();
        assertThat(gap).isGreaterThanOrEqualTo(2000);
    }

    @Test
    void rate_limited_body_only_retry_after() throws Exception {
        stubRateLimitedThenOk(null, "{\"message\":\"You are being rate limited.\",\"retry_after\":1.5}");
        seed(9302);

        assertThat(worker.pollOnce()).isEqualTo(1);

        assertThat(job().get("status")).isEqualTo("RETRY_WAIT");
        assertThat(delaySeconds(1)).isCloseTo(1.5, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void rate_limited_without_hint_uses_backoff() throws Exception {
        stubRateLimitedThenOk(null, "opaque");
        seed(9303);

        assertThat(worker.pollOnce()).isEqualTo(1);

        assertThat(job().get("status")).isEqualTo("RETRY_WAIT");
        assertThat(delaySeconds(1)).isBetween(4.0, 6.0);
    }

    @Test
    void retry_after_is_capped_at_max_retry_after() throws Exception {
        stubRateLimitedThenOk("999999", "{}");
        seed(9304);

        assertThat(worker.pollOnce()).isEqualTo(1);

        assertThat(delaySeconds(1)).isCloseTo(3600.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void delivered_with_stalled_body_is_sent_once() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"42\"}")
                .withChunkedDribbleDelay(5, 3000)));
        seed(9306);

        assertThat(worker.pollOnce()).isEqualTo(1);

        Map<String, Object> j = job();
        assertThat(j.get("status")).isEqualTo("SENT");
        assertThat(j.get("attempts")).isEqualTo(1);
        forceDue();
        assertThat(worker.pollOnce()).isZero();
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void rate_limited_exhausts_budget() throws Exception {
        stubAlways429("1");
        seed(9305);

        for (int k = 1; k <= 4; k++) {
            assertThat(worker.pollOnce()).isEqualTo(1);
            assertThat(job().get("status")).isEqualTo("RETRY_WAIT");
            forceDue();
        }
        assertThat(worker.pollOnce()).isEqualTo(1);

        Map<String, Object> j = job();
        assertThat(j.get("status")).isEqualTo("FAILED");
        assertThat(j.get("attempts")).isEqualTo(5);
        assertThat((String) j.get("last_error")).startsWith("RETRIES_EXHAUSTED");
        assertThat(requestCount()).isEqualTo(5);
    }
}
