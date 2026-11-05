package com.example.githubwebhook.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.support.IntegrationTestBase;
import com.example.githubwebhook.support.SignedRequests;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WalkingSkeletonIT extends IntegrationTestBase {

    private static final String SNOWFLAKE = "1234567890123456789";

    @Autowired
    private DispatchWorker worker;

    @Autowired
    private JsonMapper json;

    private List<LoggedRequest> discordRequests() {
        return WIREMOCK.findAll(postRequestedFor(urlPathEqualTo(DISCORD_PATH)));
    }

    @Test
    void signed_webhook_to_sent_message() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"" + SNOWFLAKE + "\"}")));
        String title = "Crash on startup 한글 café";

        assertThat(postSigned("issues", "delivery-w1", SignedRequests.issuesOpened(7001, title)).statusCode())
                .isEqualTo(202);

        assertThat(worker.pollOnce()).isEqualTo(1);

        assertThat(WIREMOCK.findAll(postRequestedFor(urlPathEqualTo(DISCORD_PATH))
                .withQueryParam("wait", equalTo("true")))).hasSize(1);
        JsonNode body = json.readTree(new String(discordRequests().get(0).getBody(), StandardCharsets.UTF_8));
        assertThat(body.get("embeds").size()).isEqualTo(1);
        JsonNode embed = body.get("embeds").get(0);
        assertThat(embed.get("title").asString()).isEqualTo("#7001 " + title);
        assertThat(embed.get("url").asString()).isEqualTo("https://github.com/octo/repo/issues/7001");
        assertThat(embed.get("footer").get("text").asString()).isEqualTo("octo/repo");
        assertThat(body.get("allowed_mentions").get("parse").isArray()).isTrue();
        assertThat(body.get("allowed_mentions").get("parse").size()).isZero();

        Map<String, Object> job = jdbc.queryForMap("SELECT * FROM dispatch_jobs");
        assertThat(job.get("status")).isEqualTo("SENT");
        assertThat(job.get("discord_message_id")).isEqualTo(SNOWFLAKE);
        assertThat(job.get("sent_at")).isNotNull();
        assertThat(job.get("locked_until")).isNull();
        assertThat(job.get("last_error")).isNull();

        assertThat(worker.pollOnce()).isZero();
        assertThat(discordRequests()).hasSize(1);
    }

    @Test
    void permanent_discord_failure_marks_job_failed_without_retry() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH))
                .willReturn(aResponse().withStatus(404).withBody("unknown webhook")));

        assertThat(postSigned("issues", "delivery-w2", SignedRequests.issuesOpened(7002, "Fails")).statusCode())
                .isEqualTo(202);

        assertThat(worker.pollOnce()).isEqualTo(1);

        Map<String, Object> job = jdbc.queryForMap("SELECT * FROM dispatch_jobs");
        assertThat(job.get("status")).isEqualTo("FAILED");
        assertThat(job.get("attempts")).isEqualTo(1);
        assertThat((String) job.get("last_error")).startsWith("HTTP 404").doesNotContain("localhost")
                .doesNotContain("test-token");
        assertThat(job.get("locked_until")).isNull();

        assertThat(worker.pollOnce()).isZero();
        assertThat(discordRequests()).hasSize(1);
    }
}
