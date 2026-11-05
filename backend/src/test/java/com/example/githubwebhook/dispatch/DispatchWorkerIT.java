package com.example.githubwebhook.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.support.IntegrationTestBase;
import com.example.githubwebhook.support.SignedRequests;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** SEND-04: every send attempt leaves a classified row; failure text never leaks the Discord URL. */
class DispatchWorkerIT extends IntegrationTestBase {

    @Autowired
    private DispatchWorker worker;

    @Autowired
    private JobClaimService claimService;

    @Autowired
    private JsonMapper json;

    private int requestCount() {
        return WIREMOCK.findAll(postRequestedFor(urlPathEqualTo(DISCORD_PATH))).size();
    }

    private void seed(long issueNumber) throws Exception {
        assertThat(postSigned("issues", "delivery-" + issueNumber, SignedRequests.issuesOpened(issueNumber, "T"))
                .statusCode()).isEqualTo(202);
    }

    private Map<String, Object> job() {
        return jdbc.queryForMap("SELECT * FROM dispatch_jobs");
    }

    private Map<String, Object> onlyAttempt() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM dispatch_attempts");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    /** Runs one poll, then a second one that must find nothing: there is no retry code in phase 1. */
    private void failOnceWithoutRetry(long issueNumber) throws Exception {
        seed(issueNumber);
        assertThat(worker.pollOnce()).isEqualTo(1);
        assertThat(worker.pollOnce()).isZero();
        // The journal entry of a delayed stub is written after the response ends, so wait for it briefly.
        for (int i = 0; i < 50 && requestCount() < 1; i++) {
            Thread.sleep(100);
        }
        assertThat(requestCount()).isEqualTo(1);
        assertThat(job().get("status")).isEqualTo("FAILED");
    }

    private void assertNoUrlLeak(Map<String, Object> attempt) {
        for (String text : new String[] {(String) attempt.get("error"), (String) job().get("last_error")}) {
            assertThat(text).isNotBlank().doesNotContain("test-token").doesNotContain("/webhooks/123")
                    .doesNotContain("localhost").doesNotContain(String.valueOf(WIREMOCK.port()));
        }
    }

    @Test
    void success_records_attempt() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"42\"}")));
        seed(8001);

        assertThat(worker.pollOnce()).isEqualTo(1);

        Map<String, Object> a = onlyAttempt();
        assertThat(a.get("attempt_no")).isEqualTo(1);
        assertThat(a.get("started_at")).isNotNull();
        assertThat(((Timestamp) a.get("finished_at"))).isAfterOrEqualTo((Timestamp) a.get("started_at"));
        assertThat(a.get("outcome")).isEqualTo("SUCCESS");
        assertThat(a.get("http_status")).isEqualTo(200);
        assertThat(a.get("error")).isNull();
        assertThat(job().get("status")).isEqualTo("SENT");
    }

    @Test
    void server_error_500() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        failOnceWithoutRetry(8002);

        Map<String, Object> a = onlyAttempt();
        assertThat(a.get("outcome")).isEqualTo("RETRYABLE_FAIL");
        assertThat(a.get("http_status")).isEqualTo(500);
        assertThat((String) a.get("error")).startsWith("HTTP 500");
        assertThat(job().get("last_error")).isEqualTo(a.get("error"));
    }

    @Test
    void rate_limited_429() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "5").withBody("slow down")));

        failOnceWithoutRetry(8003);

        Map<String, Object> a = onlyAttempt();
        assertThat(a.get("outcome")).isEqualTo("RETRYABLE_FAIL");
        assertThat(a.get("http_status")).isEqualTo(429);
    }

    @Test
    void permanent_404() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH))
                .willReturn(aResponse().withStatus(404).withBody("unknown webhook")));

        failOnceWithoutRetry(8004);

        Map<String, Object> a = onlyAttempt();
        assertThat(a.get("outcome")).isEqualTo("PERMANENT_FAIL");
        assertThat(a.get("http_status")).isEqualTo(404);
    }

    @Test
    void connection_reset() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        failOnceWithoutRetry(8005);

        Map<String, Object> a = onlyAttempt();
        assertThat(a.get("outcome")).isEqualTo("RETRYABLE_FAIL");
        assertThat(a.get("http_status")).isNull();
        assertNoUrlLeak(a);
    }

    @Test
    void read_timeout() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2500).withBody("{}")));

        failOnceWithoutRetry(8006);

        Map<String, Object> a = onlyAttempt();
        assertThat(a.get("outcome")).isEqualTo("RETRYABLE_FAIL");
        assertThat(a.get("http_status")).isNull();
        assertNoUrlLeak(a);
    }

    @Test
    void long_error_is_capped() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH))
                .willReturn(aResponse().withStatus(500).withBody("x".repeat(5000))));

        failOnceWithoutRetry(8007);

        assertThat((String) onlyAttempt().get("error")).hasSizeLessThanOrEqualTo(500);
        assertThat((String) job().get("last_error")).hasSizeLessThanOrEqualTo(500);
    }

    private JsonNode sentBody() {
        List<LoggedRequest> requests = WIREMOCK.findAll(postRequestedFor(urlPathEqualTo(DISCORD_PATH)));
        assertThat(requests).hasSize(1);
        return json.readTree(new String(requests.get(0).getBody(), StandardCharsets.UTF_8));
    }

    private void stubOk() {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"42\"}")));
    }

    @Test
    void hostile_mentions_are_text() throws Exception {
        stubOk();
        String hostile = "@everyone @here <@123456789>";
        assertThat(postSigned("issues", "delivery-8010", SignedRequests.issuesOpened(8010, hostile)).statusCode())
                .isEqualTo(202);

        assertThat(worker.pollOnce()).isEqualTo(1);

        JsonNode body = sentBody();
        assertThat(body.get("embeds").get(0).get("title").asString()).isEqualTo("#8010 " + hostile);
        assertThat(body.get("allowed_mentions").get("parse").isArray()).isTrue();
        assertThat(body.get("allowed_mentions").get("parse").size()).isZero();
    }

    @Test
    void long_title_over_the_wire() throws Exception {
        stubOk();
        assertThat(postSigned("issues", "delivery-8011", SignedRequests.issuesOpened(8011, "t".repeat(400)))
                .statusCode()).isEqualTo(202);

        assertThat(worker.pollOnce()).isEqualTo(1);

        JsonNode embed = sentBody().get("embeds").get(0);
        String title = embed.get("title").asString();
        assertThat(title.length()).isLessThanOrEqualTo(256);
        assertThat(title).endsWith("…");
        assertThat(embed.get("footer").get("text").asString()).isEqualTo("octo/repo");
        assertThat(embed.get("url").asString()).isEqualTo("https://github.com/octo/repo/issues/8011");
    }

    @Test
    void unfinished_attempt_after_claim() throws Exception {
        seed(8008);

        assertThat(claimService.claim()).hasSize(1);

        Map<String, Object> a = onlyAttempt();
        assertThat(a.get("attempt_no")).isEqualTo(1);
        assertThat(a.get("started_at")).isNotNull();
        assertThat(a.get("finished_at")).isNull();
        assertThat(a.get("outcome")).isNull();
        assertThat(a.get("http_status")).isNull();

        jdbc.update("UPDATE dispatch_jobs SET locked_until = now() - interval '1 second'");
        assertThat(claimService.claim()).hasSize(1);

        assertThat(job().get("attempts")).isEqualTo(2);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM dispatch_attempts ORDER BY attempt_no");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("attempt_no")).isEqualTo(1);
        assertThat(rows.get(0).get("finished_at")).isNull();
        assertThat(rows.get(1).get("attempt_no")).isEqualTo(2);
        assertThat(rows.get(1).get("finished_at")).isNull();
    }
}
