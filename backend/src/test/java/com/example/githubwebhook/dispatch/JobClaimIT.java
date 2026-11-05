package com.example.githubwebhook.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Spike for assumption A1: a native UPDATE ... RETURNING maps to entities through Spring Data JPA. */
class JobClaimIT extends IntegrationTestBase {

    @Autowired
    DispatchJobRepository jobs;

    @Test
    void claim_native_update_returning_maps_entities() {
        jdbc.update("""
                INSERT INTO webhook_events (delivery_id, event_type, action, repo, issue_number, title, html_url)
                VALUES ('d-1', 'issues', 'opened', 'octo/repo', 1, 't', 'https://github.com/octo/repo/issues/1')""");
        jdbc.update("INSERT INTO dispatch_jobs (event_id, status) SELECT id, 'PENDING' FROM webhook_events");

        List<DispatchJob> claimed = jobs.claim(10, 30);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getStatus()).isEqualTo(DispatchJob.Status.PROCESSING);
        assertThat(claimed.get(0).getAttempts()).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap("SELECT status, attempts, locked_until > now() AS leased FROM dispatch_jobs");
        assertThat(row.get("status")).isEqualTo("PROCESSING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("leased")).isEqualTo(true);
    }
}
