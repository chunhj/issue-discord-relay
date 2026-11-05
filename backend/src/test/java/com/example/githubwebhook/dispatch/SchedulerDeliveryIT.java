package com.example.githubwebhook.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.githubwebhook.support.IntegrationTestBase;
import com.example.githubwebhook.support.SignedRequests;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** Own context with the scheduler on; discarded afterwards so it cannot claim jobs of other test classes. */
@TestPropertySource(properties = "app.worker.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SchedulerDeliveryIT extends IntegrationTestBase {

    @Test
    void scheduler_delivers_a_webhook_ingested_job_without_a_manual_poll() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"9876543210987654321\"}")));

        assertThat(postSigned("issues", "delivery-s1", SignedRequests.issuesOpened(7101, "Scheduled")).statusCode())
                .isEqualTo(202);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                jdbc.queryForObject("SELECT status FROM dispatch_jobs", String.class)).isEqualTo("SENT"));

        List<com.github.tomakehurst.wiremock.verification.LoggedRequest> requests =
                WIREMOCK.findAll(postRequestedFor(urlPathEqualTo(DISCORD_PATH)));
        assertThat(requests).hasSize(1);
        assertThat(new String(requests.get(0).getBody(), StandardCharsets.UTF_8))
                .contains("https://github.com/octo/repo/issues/7101");
    }
}
