package com.example.githubwebhook.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.support.IntegrationTestBase;
import com.example.githubwebhook.support.SignedRequests;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookControllerIT extends IntegrationTestBase {

    private static final String UNAUTHORIZED = "{\"status\":\"unauthorized\"}";
    private static final String BAD_REQUEST = "{\"status\":\"bad_request\"}";
    private static final String TOO_LARGE = "{\"status\":\"too_large\"}";
    private static final String IGNORED = "{\"status\":\"ignored\"}";
    private static final int MAX = 1_048_576;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void assertNothingStored() {
        assertThat(count("webhook_events")).isZero();
        assertThat(count("dispatch_jobs")).isZero();
    }

    /** Like SignedRequests.request, but a null event/delivery/signature omits that header. */
    private HttpResponse<String> send(String event, String delivery, String signature, byte[] body,
                                      String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(webhookUrl()))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (event != null) {
            b.header("X-GitHub-Event", event);
        }
        if (delivery != null) {
            b.header("X-GitHub-Delivery", delivery);
        }
        if (signature != null) {
            b.header("X-Hub-Signature-256", signature);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendSigned(String event, String delivery, byte[] body) throws Exception {
        return send(event, delivery, SignedRequests.signature(body), body, "application/json");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---- accepted -------------------------------------------------------------------------------------------

    @Test
    void accepted_delivery_stores_all_fields_and_a_pending_job_before_the_response() throws Exception {
        // 5 s slack each way for clock skew between the test JVM and the Postgres container VM.
        Instant before = Instant.now().minusSeconds(5);
        byte[] body = SignedRequests.issuesOpened(77, "Login fails");

        HttpResponse<String> response = sendSigned("issues", "d-accepted", body);

        Instant after = Instant.now().plusSeconds(5);
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEqualTo("{\"status\":\"accepted\"}");

        // WEBHOOK-04: a connection that is not part of the application pool already sees the committed rows.
        try (Connection raw = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement st = raw.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM webhook_events")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM dispatch_jobs")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }

        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM webhook_events");
        assertThat(event.get("delivery_id")).isEqualTo("d-accepted");
        assertThat(event.get("event_type")).isEqualTo("issues");
        assertThat(event.get("action")).isEqualTo("opened");
        assertThat(event.get("repo")).isEqualTo("octo/repo");
        assertThat(event.get("issue_number")).isEqualTo(77L);
        assertThat(event.get("title")).isEqualTo("Login fails");
        assertThat(event.get("html_url")).isEqualTo("https://github.com/octo/repo/issues/77");
        Instant receivedAt = jdbc.queryForObject("SELECT received_at FROM webhook_events", OffsetDateTime.class)
                .toInstant();
        assertThat(receivedAt).isBetween(before, after);
        Map<String, Object> job = jdbc.queryForMap("SELECT * FROM dispatch_jobs");
        assertThat(job.get("status")).isEqualTo("PENDING");
        assertThat(job.get("attempts")).isEqualTo(0);
    }

    @Test
    void no_raw_payload_column_exists_on_webhook_events() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'webhook_events'",
                String.class);
        assertThat(columns).doesNotContain("payload", "raw_payload", "body", "raw_body", "raw", "json");
        assertThat(columns).noneMatch(c -> c.contains("payload") || c.contains("raw") || c.contains("json"));
    }

    @Test
    void content_type_is_irrelevant() throws Exception {
        byte[] body = SignedRequests.issuesOpened(5, "plain");

        HttpResponse<String> response = send("issues", "d-text", SignedRequests.signature(body), body, "text/plain");

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(count("webhook_events")).isEqualTo(1);
    }

    // ---- ignored --------------------------------------------------------------------------------------------

    @Test
    void ping_and_non_issues_events_are_ignored_even_with_non_json_bodies() throws Exception {
        HttpResponse<String> ping = sendSigned("ping", "d-ping", bytes("{\"zen\":\"Keep it logically awesome.\"}"));
        HttpResponse<String> pushNonJson = sendSigned("push", "d-push", bytes("this is not json"));

        assertThat(ping.statusCode()).isEqualTo(200);
        assertThat(ping.body()).isEqualTo(IGNORED);
        assertThat(pushNonJson.statusCode()).isEqualTo(200);
        assertThat(pushNonJson.body()).isEqualTo(IGNORED);
        assertNothingStored();
    }

    @Test
    void issues_with_other_actions_are_ignored() throws Exception {
        for (String action : List.of("edited", "closed")) {
            byte[] body = bytes(new String(SignedRequests.issuesOpened(9, "t"), StandardCharsets.UTF_8)
                    .replaceFirst("\"action\": \"opened\"", "\"action\": \"" + action + "\""));

            HttpResponse<String> response = sendSigned("issues", "d-" + action, body);

            assertThat(response.statusCode()).as(action).isEqualTo(200);
            assertThat(response.body()).as(action).isEqualTo(IGNORED);
        }
        assertNothingStored();
    }

    @Test
    void official_github_test_vector_passes_at_endpoint_level() throws Exception {
        byte[] body = bytes("Hello, World!");

        HttpResponse<String> response = send("ping", "d-vector",
                "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17", body, "text/plain");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(IGNORED);
    }

    // ---- 401 ------------------------------------------------------------------------------------------------

    @Test
    void bad_signatures_return_401_and_store_nothing() throws Exception {
        byte[] body = SignedRequests.issuesOpened(1, "sig");
        String good = SignedRequests.signature(body);
        String otherBodySig = SignedRequests.signature(bytes("another body"));
        List<String> badHeaders = List.of(
                "sha1=" + good.substring("sha256=".length(), "sha256=".length() + 40),
                "sha256=" + "0".repeat(64),
                "sha256=" + good.substring("sha256=".length()).toUpperCase(),
                otherBodySig,
                "");

        assertThat(good.substring(7)).isNotEqualTo(good.substring(7).toUpperCase());
        assertThat(send("issues", "d-nosig", null, body, "application/json").statusCode()).isEqualTo(401);
        for (String header : badHeaders) {
            HttpResponse<String> response = send("issues", "d-bad", header, body, "application/json");
            assertThat(response.statusCode()).as(header).isEqualTo(401);
            assertThat(response.body()).as(header).isEqualTo(UNAUTHORIZED);
        }
        assertNothingStored();
    }

    @Test
    void signature_is_checked_before_json_parsing_and_header_validation() throws Exception {
        HttpResponse<String> malformed = send("issues", "d-order-1", "sha256=" + "0".repeat(64),
                bytes("{not json"), "application/json");
        HttpResponse<String> noEventHeader = send(null, "d-order-2", "sha256=" + "0".repeat(64),
                SignedRequests.issuesOpened(1, "x"), "application/json");
        HttpResponse<String> noDeliveryHeader = send("issues", null, "sha256=" + "0".repeat(64),
                SignedRequests.issuesOpened(1, "x"), "application/json");

        assertThat(malformed.statusCode()).isEqualTo(401);
        assertThat(noEventHeader.statusCode()).isEqualTo(401);
        assertThat(noDeliveryHeader.statusCode()).isEqualTo(401);
        assertNothingStored();
    }

    // ---- 400 ------------------------------------------------------------------------------------------------

    @Test
    void missing_or_blank_required_headers_return_400() throws Exception {
        byte[] body = SignedRequests.issuesOpened(3, "hdr");
        String sig = SignedRequests.signature(body);

        HttpResponse<String> noEvent = send(null, "d-1", sig, body, "application/json");
        HttpResponse<String> noDelivery = send("issues", null, sig, body, "application/json");
        HttpResponse<String> emptyDelivery = send("issues", "", sig, body, "application/json");
        HttpResponse<String> blankDelivery = send("issues", "   ", sig, body, "application/json");
        HttpResponse<String> blankEvent = send("   ", "d-2", sig, body, "application/json");

        for (HttpResponse<String> r : List.of(noEvent, noDelivery, emptyDelivery, blankDelivery, blankEvent)) {
            assertThat(r.statusCode()).isEqualTo(400);
            assertThat(r.body()).isEqualTo(BAD_REQUEST);
        }
        assertNothingStored();
    }

    @Test
    void malformed_or_incomplete_issues_bodies_return_400() throws Exception {
        String valid = new String(SignedRequests.issuesOpened(8, "ok"), StandardCharsets.UTF_8);
        List<String> bodies = List.of(
                "this is not json",
                "",
                "null",
                "[]",
                "{}",
                valid.replace("\"html_url\": \"https://github.com/octo/repo/issues/8\"", "\"x\": 1"),
                valid.replace("\"full_name\": \"octo/repo\"", "\"x\": 1"),
                valid.replace("\"title\": \"ok\"", "\"x\": 1"),
                valid.replace("\"number\": 8", "\"x\": 1"),
                valid.replace("\"title\": \"ok\"", "\"title\": \"bad\\u0000title\""));

        assertThat(valid).contains("\"title\": \"ok\"", "\"number\": 8", "\"full_name\": \"octo/repo\"");
        for (String body : bodies) {
            HttpResponse<String> response = sendSigned("issues", "d-bad-body", bytes(body));
            assertThat(response.statusCode()).as(body.length() > 40 ? body.substring(0, 40) : body).isEqualTo(400);
            assertThat(response.body()).isEqualTo(BAD_REQUEST);
        }
        assertNothingStored();
    }

    // ---- 413 ------------------------------------------------------------------------------------------------

    @Test
    void body_over_one_megabyte_returns_413_and_exactly_one_megabyte_is_processed() throws Exception {
        HttpResponse<String> tooBig = sendSigned("issues", "d-big", new byte[MAX + 1]);
        HttpResponse<String> atLimit = sendSigned("ping", "d-limit", new byte[MAX]);

        assertThat(tooBig.statusCode()).isEqualTo(413);
        assertThat(tooBig.body()).isEqualTo(TOO_LARGE);
        assertThat(atLimit.statusCode()).isEqualTo(200);
        assertThat(atLimit.body()).isEqualTo(IGNORED);
        assertNothingStored();
    }

    @Test
    void chunked_body_over_one_megabyte_is_capped_by_the_bounded_read() throws Exception {
        byte[] body = new byte[MAX + 1];
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl()))
                .header("X-GitHub-Event", "issues")
                .header("X-GitHub-Delivery", "d-chunked")
                .header("X-Hub-Signature-256", SignedRequests.signature(body))
                .POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofByteArray(body)))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).isEqualTo(TOO_LARGE);
        assertNothingStored();
    }

    // ---- 500 / rollback -------------------------------------------------------------------------------------

    @Test
    void failing_job_insert_returns_500_without_details_and_rolls_back_the_event() throws Exception {
        byte[] body = SignedRequests.issuesOpened(11, "rollback");
        jdbc.execute("ALTER TABLE dispatch_jobs ADD CONSTRAINT zz_always_fail CHECK (false) NOT VALID");
        HttpResponse<String> failed;
        try {
            failed = sendSigned("issues", "d-rollback", body);
            assertThat(count("webhook_events")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE dispatch_jobs DROP CONSTRAINT zz_always_fail");
        }

        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(failed.body()).isEqualTo("{\"status\":\"error\"}");
        assertThat(failed.body()).doesNotContain("Exception", "org.", "java.", "at ");

        HttpResponse<String> retry = sendSigned("issues", "d-rollback", body);

        assertThat(retry.statusCode()).isEqualTo(202);
        assertThat(count("webhook_events")).isEqualTo(1);
        assertThat(count("dispatch_jobs")).isEqualTo(1);
    }
}
