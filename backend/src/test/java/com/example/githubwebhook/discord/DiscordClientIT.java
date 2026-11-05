package com.example.githubwebhook.discord;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.config.AppProperties;
import com.example.githubwebhook.support.IntegrationTestBase;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** D-11, D-12: I/O failure kinds carry stable codes without leaking the URL; a stalled body keeps its status. */
class DiscordClientIT extends IntegrationTestBase {

    @Autowired
    private DiscordClient client;

    @Autowired
    private JsonMapper json;

    @Autowired
    private AppProperties props;

    private SendResult send(DiscordClient c) {
        return c.send("o/r", 1, "title", "https://github.com/o/r/issues/1");
    }

    private void assertNoLeak(SendResult r, String... extraForbidden) {
        assertThat(r.error()).isNotBlank().doesNotContain("test-token").doesNotContain("/webhooks/")
                .doesNotContain("localhost").doesNotContain(String.valueOf(WIREMOCK.port()));
        for (String f : extraForbidden) {
            assertThat(r.error()).doesNotContain(f);
        }
    }

    @Test
    void connection_refused_is_connect_error() throws Exception {
        int freed;
        try (ServerSocket socket = new ServerSocket(0)) {
            freed = socket.getLocalPort();
        }
        DiscordClient refused = new DiscordClient(RestClient.builder(), json, new AppProperties(props.github(),
                new AppProperties.Discord("http://localhost:" + freed + "/webhooks/1/token"), props.worker()));

        SendResult r = send(refused);

        assertThat(r.delivered()).isFalse();
        assertThat(r.httpStatus()).isNull();
        assertThat(r.ioKind()).isEqualTo(IoKind.CONNECT);
        assertThat(r.error()).startsWith("CONNECT_ERROR");
        assertNoLeak(r, String.valueOf(freed));
    }

    @Test
    void response_timeout_is_read_timeout() {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2500).withBody("{}")));

        SendResult r = send(client);

        assertThat(r.httpStatus()).isNull();
        assertThat(r.ioKind()).isEqualTo(IoKind.RESPONSE_TIMEOUT);
        assertThat(r.error()).startsWith("READ_TIMEOUT");
        assertNoLeak(r);
    }

    @Test
    void connection_reset_is_other_io_error() {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        SendResult r = send(client);

        assertThat(r.httpStatus()).isNull();
        assertThat(r.ioKind()).isEqualTo(IoKind.OTHER);
        assertThat(r.error()).startsWith("IO_ERROR");
        assertNoLeak(r);
    }

    @Test
    void delivered_response_with_stalled_body_is_still_delivered() {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"42\"}")
                .withChunkedDribbleDelay(5, 3000)));

        SendResult r = send(client);

        assertThat(r.delivered()).isTrue();
        assertThat(r.httpStatus()).isEqualTo(200);
        assertThat(r.ioKind()).isNull();
    }

    @Test
    void rate_limited_with_stalled_body_keeps_status_and_retry_after() {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", "2").withHeader("Content-Type", "application/json")
                .withBody("{\"retry_after\":1.5,\"message\":\"rate limited\"}").withChunkedDribbleDelay(5, 3000)));

        SendResult r = send(client);

        assertThat(r.delivered()).isFalse();
        assertThat(r.httpStatus()).isEqualTo(429);
        assertThat(r.retryAfter()).isEqualTo(Duration.ofSeconds(2));
        assertThat(r.ioKind()).isNull();
    }
}
