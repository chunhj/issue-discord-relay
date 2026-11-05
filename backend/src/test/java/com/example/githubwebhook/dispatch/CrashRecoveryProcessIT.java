package com.example.githubwebhook.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.githubwebhook.support.AppProcess;
import com.example.githubwebhook.support.IntegrationTestBase;
import com.example.githubwebhook.support.SignedRequests;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

/**
 * RECOV-01 and RECOV-03 with real process kills: two child JVMs run the actual application against the shared
 * Postgres and WireMock. The in-JVM context keeps its worker disabled, so only the children consume jobs.
 */
@Tag("process")
class CrashRecoveryProcessIT extends IntegrationTestBase {

    private static final Duration READY = Duration.ofSeconds(90);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private static Map<String, String> childEnv(boolean workerEnabled) {
        return Map.of(
                "SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl(),
                "SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername(),
                "SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword(),
                "GITHUB_WEBHOOK_SECRET", WEBHOOK_SECRET,
                "DISCORD_WEBHOOK_URL", "http://localhost:" + WIREMOCK.port() + DISCORD_PATH,
                "APP_WORKER_ENABLED", Boolean.toString(workerEnabled),
                "APP_WORKER_LEASE_SECONDS", "3");
    }

    private int postIssueTo(AppProcess app) throws Exception {
        byte[] body = SignedRequests.issuesOpened(7001, "crash me");
        HttpResponse<String> response = http.send(
                SignedRequests.request(app.webhookUrl(), "issues", "delivery-r1", body, SignedRequests.signature(body))
                        .build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    private int requestCount() {
        return WIREMOCK.findAll(postRequestedFor(urlPathEqualTo(DISCORD_PATH))).size();
    }

    private Map<String, Object> theJob() {
        return jdbc.queryForMap("SELECT * FROM dispatch_jobs");
    }

    private String theJobStatus() {
        List<String> s = jdbc.queryForList("SELECT status FROM dispatch_jobs", String.class);
        return s.isEmpty() ? "NONE" : s.get(0);
    }

    private List<Map<String, Object>> attemptRows() {
        return jdbc.queryForList("SELECT * FROM dispatch_attempts ORDER BY attempt_no");
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void kill_after_commit_before_send() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"42\"}")));

        try (AppProcess a = AppProcess.start("a", childEnv(false))) {
            a.awaitReady(READY);
            assertThat(postIssueTo(a)).isEqualTo(202);

            // Worker off in A: the committed job just sits there (also proves APP_WORKER_ENABLED reached the child).
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(theJob().get("status")).isEqualTo("PENDING");
                assertThat(theJob().get("attempts")).isEqualTo(0);
                assertThat(requestCount()).isZero();
            });

            a.kill();
            assertThat(a.isAlive()).isFalse();
        }

        try (AppProcess b = AppProcess.start("b", childEnv(true))) {
            b.awaitReady(READY);
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                    .until(() -> "SENT".equals(theJobStatus()));

            assertThat(requestCount()).isEqualTo(1);
            assertThat(theJob().get("attempts")).isEqualTo(1);
            List<Map<String, Object>> rows = attemptRows();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("outcome")).isEqualTo("SUCCESS");
        }
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void duplicate_after_received_before_sent() throws Exception {
        // First answer is held 3 s (the mock has the message, the sender is still waiting); every later answer is instant.
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).inScenario("dup")
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("replied")
                .willReturn(aResponse().withStatus(200).withFixedDelay(3000)
                        .withHeader("Content-Type", "application/json").withBody("{\"id\":\"1\"}")));
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).inScenario("dup")
                .whenScenarioStateIs("replied")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{\"id\":\"2\"}")));

        try (AppProcess a = AppProcess.start("a", childEnv(true))) {
            a.awaitReady(READY);
            assertThat(postIssueTo(a)).isEqualTo(202);

            // The mock has received the message: A is mid-send and has not recorded SENT.
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                    .until(() -> requestCount() == 1);
            assertThat(theJob().get("status")).isEqualTo("PROCESSING");
            assertThat(theJob().get("attempts")).isEqualTo(1);
            // The 3 s lease override reached the child (the default 30 s lease would fail this).
            Double leaseSeconds = jdbc.queryForObject("SELECT extract(epoch FROM (j.locked_until - a.started_at)) "
                    + "FROM dispatch_jobs j JOIN dispatch_attempts a ON a.job_id = j.id", Double.class);
            assertThat(leaseSeconds).isLessThan(10.0);

            a.kill();
        }

        try (AppProcess b = AppProcess.start("b", childEnv(true))) {
            b.awaitReady(READY);
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                    .until(() -> "SENT".equals(theJobStatus()));

            var requests = WIREMOCK.findAll(postRequestedFor(urlPathEqualTo(DISCORD_PATH)));
            assertThat(requests).hasSize(2);
            String first = JSON.readTree(requests.get(0).getBodyAsString()).get("embeds").get(0).get("url").asString();
            String second = JSON.readTree(requests.get(1).getBodyAsString()).get("embeds").get(0).get("url").asString();
            assertThat(first).endsWith("/issues/7001");
            assertThat(second).isEqualTo(first);

            assertThat(theJob().get("attempts")).isEqualTo(2);
            List<Map<String, Object>> rows = attemptRows();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get("finished_at")).isNull();
            assertThat(rows.get(0).get("outcome")).isNull();
            assertThat(rows.get(1).get("outcome")).isEqualTo("SUCCESS");
            assertThat(rows.get(1).get("http_status")).isEqualTo(200);
        }
    }
}
