package com.example.githubwebhook.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.support.IntegrationTestBase;
import com.example.githubwebhook.support.SignedRequests;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TracerIngestIT extends IntegrationTestBase {

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void signed_issue_opened_is_stored_once_with_a_pending_job_and_replay_is_a_duplicate() throws Exception {
        byte[] body = SignedRequests.issuesOpened(4242, "Crash on startup");

        HttpResponse<String> first = postSigned("issues", "delivery-1", body);

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(first.body()).isEqualTo("{\"status\":\"accepted\"}");
        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM webhook_events");
        assertThat(event.get("delivery_id")).isEqualTo("delivery-1");
        assertThat(event.get("event_type")).isEqualTo("issues");
        assertThat(event.get("action")).isEqualTo("opened");
        assertThat(event.get("repo")).isEqualTo("octo/repo");
        assertThat(event.get("issue_number")).isEqualTo(4242L);
        assertThat(event.get("title")).isEqualTo("Crash on startup");
        assertThat(event.get("html_url")).isEqualTo("https://github.com/octo/repo/issues/4242");
        assertThat(event.get("received_at")).isNotNull();
        Map<String, Object> job = jdbc.queryForMap("SELECT * FROM dispatch_jobs");
        assertThat(job.get("status")).isEqualTo("PENDING");
        assertThat(job.get("attempts")).isEqualTo(0);

        HttpResponse<String> replay = postSigned("issues", "delivery-1", body);

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo("{\"status\":\"duplicate\"}");
        assertThat(count("webhook_events")).isEqualTo(1);
        assertThat(count("dispatch_jobs")).isEqualTo(1);
    }

    @Test
    void wrong_signature_is_rejected_and_nothing_is_stored() throws Exception {
        byte[] body = SignedRequests.issuesOpened(1, "x");

        HttpResponse<String> response = post("issues", "delivery-2", body, "sha256=" + "0".repeat(64));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(count("webhook_events")).isZero();
        assertThat(count("dispatch_jobs")).isZero();
    }

    @Test
    void missing_signature_is_rejected_and_nothing_is_stored() throws Exception {
        HttpResponse<String> response = post("issues", "delivery-3", SignedRequests.issuesOpened(2, "y"), null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(count("webhook_events")).isZero();
    }
}
