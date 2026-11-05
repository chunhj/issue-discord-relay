package com.example.githubwebhook.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.support.IntegrationTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HistoryApiIT extends IntegrationTestBase {

    static final Set<String> TWELVE = Set.of("eventId", "deliveryId", "repo", "issueNumber", "title", "htmlUrl",
            "receivedAt", "status", "attempts", "lastError", "sentAt", "deliveryLatencyMs");

    private static final Instant T = Instant.parse("2026-01-01T00:00:00.123456Z");

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(apiUrl(pathAndQuery))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Inserts one event with an explicit received_at and one job; returns the event id. */
    private long seed(String delivery, Instant receivedAt, String status, int attempts, String lastError,
                      Instant sentAt) {
        Long id = jdbc.queryForObject(
                "INSERT INTO webhook_events (delivery_id, event_type, action, repo, issue_number, title, html_url, "
                        + "received_at) VALUES (?, 'issues', 'opened', 'octo/repo', 7, 'Hello', "
                        + "'https://github.com/octo/repo/issues/7', ?) RETURNING id",
                Long.class, delivery, Timestamp.from(receivedAt));
        jdbc.update("INSERT INTO dispatch_jobs (event_id, status, attempts, last_error, discord_message_id, "
                        + "locked_until, next_attempt_at, sent_at) VALUES (?, ?, ?, ?, 'msg-secret-1', NULL, now(), ?)",
                id, status, attempts, lastError, sentAt == null ? null : Timestamp.from(sentAt));
        return id;
    }

    // ---- tracer -----------------------------------------------------------------------------------------------

    @Test
    void sent_job_is_returned_with_exactly_the_whitelisted_fields() throws Exception {
        long id = seed("d-1", T, "SENT", 1, null, T.plusMillis(1234));

        HttpResponse<String> res = get("/api/history");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).contains("json");
        JsonNode body = mapper.readTree(res.body());
        assertThat(body.get("items")).hasSize(1);
        assertThat(body.get("nextBefore").isNull()).isTrue();
        JsonNode item = body.get("items").get(0);
        Set<String> names = new HashSet<>();
        item.propertyNames().forEach(names::add);
        assertThat(names).containsExactlyInAnyOrderElementsOf(TWELVE);
        assertThat(item.get("eventId").asLong()).isEqualTo(id);
        assertThat(item.get("deliveryId").asString()).isEqualTo("d-1");
        assertThat(item.get("repo").asString()).isEqualTo("octo/repo");
        assertThat(item.get("issueNumber").asLong()).isEqualTo(7);
        assertThat(item.get("title").asString()).isEqualTo("Hello");
        assertThat(item.get("htmlUrl").asString()).isEqualTo("https://github.com/octo/repo/issues/7");
        assertThat(item.get("status").asString()).isEqualTo("SENT");
        assertThat(item.get("attempts").asInt()).isEqualTo(1);
        assertThat(item.get("lastError").isNull()).isTrue();
        assertThat(Instant.parse(item.get("receivedAt").asString())).isEqualTo(T);
        assertThat(Instant.parse(item.get("sentAt").asString())).isEqualTo(T.plusMillis(1234));
        assertThat(item.get("deliveryLatencyMs").asLong()).isEqualTo(1234);
        assertThat(res.body()).doesNotContain("msg-secret-1");
    }

    @Test
    void non_sent_job_has_null_latency_and_null_sent_at() throws Exception {
        seed("d-1", T, "PENDING", 0, null, null);
        String err = "HTTP 404: {\"message\": \"Unknown Webhook\", \"code\": 10015}";
        seed("d-2", T.plusSeconds(1), "FAILED", 5, err, null);

        JsonNode items = mapper.readTree(get("/api/history").body()).get("items");

        assertThat(items).hasSize(2);
        for (JsonNode item : items) {
            assertThat(item.get("deliveryLatencyMs").isNull()).isTrue();
            assertThat(item.get("sentAt").isNull()).isTrue();
        }
        JsonNode failed = items.get(0);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("attempts").asInt()).isEqualTo(5);
        assertThat(failed.get("lastError").asString()).isEqualTo(err);
    }

    // ---- ordering, cursor, limit, status ------------------------------------------------------------------------

    private List<Long> ids(String pathAndQuery) throws Exception {
        List<Long> out = new ArrayList<>();
        mapper.readTree(get(pathAndQuery).body()).get("items").forEach(i -> out.add(i.get("eventId").asLong()));
        return out;
    }

    @Test
    void newest_first_by_received_at_then_id() throws Exception {
        seed("d-1", T.plusSeconds(10), "PENDING", 0, null, null);
        seed("d-2", T.plusSeconds(30), "PENDING", 0, null, null);
        seed("d-3", T.plusSeconds(20), "PENDING", 0, null, null);
        seed("d-4", T.plusSeconds(20), "PENDING", 0, null, null);

        assertThat(ids("/api/history")).containsExactly(2L, 4L, 3L, 1L);
    }

    @Test
    void cursor_walk_covers_every_row_once() throws Exception {
        long[] offsets = {50, 10, 40, 40, 70, 20, 40};
        List<long[]> seeded = new ArrayList<>(); // {id, offset}
        for (int i = 0; i < offsets.length; i++) {
            long id = seed("d-" + i, T.plusSeconds(offsets[i]), "PENDING", 0, null, null);
            seeded.add(new long[] {id, offsets[i]});
        }
        List<Long> expected = seeded.stream()
                .sorted(Comparator.<long[]>comparingLong(r -> r[1]).thenComparingLong(r -> r[0]).reversed())
                .map(r -> r[0]).toList();

        for (int limit : new int[] {1, 2, 3}) {
            List<Long> walked = new ArrayList<>();
            String path = "/api/history?limit=" + limit;
            while (true) {
                JsonNode page = mapper.readTree(get(path).body());
                List<Long> pageIds = new ArrayList<>();
                page.get("items").forEach(i -> pageIds.add(i.get("eventId").asLong()));
                walked.addAll(pageIds);
                if (page.get("nextBefore").isNull()) {
                    break;
                }
                assertThat(pageIds).hasSize(limit);
                assertThat(page.get("nextBefore").asLong()).isEqualTo(pageIds.get(pageIds.size() - 1));
                path = "/api/history?limit=" + limit + "&before=" + page.get("nextBefore").asLong();
            }
            assertThat(walked).as("limit " + limit).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void before_with_unknown_id_returns_an_empty_page() throws Exception {
        seed("d-1", T, "PENDING", 0, null, null);

        JsonNode page = mapper.readTree(get("/api/history?before=999999").body());

        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("nextBefore").isNull()).isTrue();
    }

    @Test
    void limit_defaults_and_clamps() throws Exception {
        jdbc.update("INSERT INTO webhook_events (delivery_id, event_type, action, repo, issue_number, title, html_url, "
                + "received_at) SELECT 'g-' || n, 'issues', 'opened', 'octo/repo', n, 't', 'https://x/' || n, "
                + "now() + n * interval '1 second' FROM generate_series(1, 205) n");
        jdbc.update("INSERT INTO dispatch_jobs (event_id, status) SELECT id, 'PENDING' FROM webhook_events");

        assertThat(ids("/api/history")).hasSize(50);
        assertThat(mapper.readTree(get("/api/history").body()).get("nextBefore").isNull()).isFalse();
        assertThat(ids("/api/history?limit=0")).hasSize(1);
        assertThat(ids("/api/history?limit=-5")).hasSize(1);
        assertThat(ids("/api/history?limit=1000")).hasSize(200);
        assertThat(mapper.readTree(get("/api/history?limit=1000").body()).get("nextBefore").isNull()).isFalse();
    }

    @Test
    void non_numeric_limit_or_before_is_400() throws Exception {
        assertThat(get("/api/history?limit=abc").statusCode()).isEqualTo(400);
        assertThat(get("/api/history?before=abc").statusCode()).isEqualTo(400);
    }

    @Test
    void status_filter_returns_only_that_status_and_pages_with_it() throws Exception {
        seed("d-1", T.plusSeconds(1), "PENDING", 0, null, null);
        long f1 = seed("d-2", T.plusSeconds(2), "FAILED", 5, "e", null);
        seed("d-3", T.plusSeconds(3), "PROCESSING", 1, null, null);
        seed("d-4", T.plusSeconds(4), "RETRY_WAIT", 1, "e", null);
        seed("d-5", T.plusSeconds(5), "SENT", 1, null, T.plusSeconds(6));
        long f2 = seed("d-6", T.plusSeconds(7), "FAILED", 5, "e", null);

        assertThat(ids("/api/history?status=FAILED")).containsExactly(f2, f1);
        assertThat(ids("/api/history")).hasSize(6);

        JsonNode first = mapper.readTree(get("/api/history?status=FAILED&limit=1").body());
        assertThat(first.get("items")).hasSize(1);
        assertThat(first.get("nextBefore").asLong()).isEqualTo(f2);
        JsonNode second = mapper.readTree(get("/api/history?status=FAILED&limit=1&before=" + f2).body());
        assertThat(second.get("items").get(0).get("eventId").asLong()).isEqualTo(f1);
        assertThat(second.get("nextBefore").isNull()).isTrue();
    }

    @Test
    void unknown_status_is_400() throws Exception {
        assertThat(get("/api/history?status=BOGUS").statusCode()).isEqualTo(400);
        assertThat(get("/api/history?status=failed").statusCode()).isEqualTo(400);
    }
}
