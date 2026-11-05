package com.example.githubwebhook.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared base for all integration tests: one static Postgres + one static WireMock, so Spring caches a single
 * application context. Containers are started manually (not via the JUnit extension) so they outlive a test class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.worker.enabled=false")
public abstract class IntegrationTestBase {

    // Same literal as docker-compose.yml.
    public static final String POSTGRES_IMAGE = "postgres:17";
    // The official GitHub test-vector secret, so one value serves unit, endpoint and generated-payload signing.
    public static final String WEBHOOK_SECRET = "It's a Secret to Everybody";
    public static final String DISCORD_PATH = "/webhooks/123/test-token";

    protected static final PostgreSQLContainer POSTGRES;
    protected static final WireMockServer WIREMOCK;

    static {
        POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);
        POSTGRES.start();
        // The JDK HttpClient behind RestClient tries an h2c upgrade on cleartext POSTs, which WireMock's Jetty drops
        // (EOFException). Production talks HTTPS (ALPN), so only the plain-text test server needs this switched off.
        WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.discord.webhook-url", () -> "http://localhost:" + WIREMOCK.port() + DISCORD_PATH);
        registry.add("app.github.webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("spring.http.clients.connect-timeout", () -> "1s");
        registry.add("spring.http.clients.read-timeout", () -> "1s");
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void resetState() {
        jdbc.execute("TRUNCATE webhook_events, dispatch_jobs, dispatch_attempts RESTART IDENTITY CASCADE");
        WIREMOCK.resetAll();
    }

    protected String webhookUrl() {
        return "http://localhost:" + port + "/webhooks/github";
    }

    protected HttpResponse<String> postSigned(String event, String deliveryId, byte[] body) throws Exception {
        return http.send(
                SignedRequests.request(webhookUrl(), event, deliveryId, body, SignedRequests.signature(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> post(String event, String deliveryId, byte[] body, String signatureHeaderOrNull)
            throws Exception {
        return http.send(SignedRequests.request(webhookUrl(), event, deliveryId, body, signatureHeaderOrNull).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
