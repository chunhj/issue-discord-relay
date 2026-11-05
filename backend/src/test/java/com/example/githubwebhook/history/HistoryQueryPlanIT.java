package com.example.githubwebhook.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.support.IntegrationTestBase;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;

/**
 * D-10: the 5-second poll must read the newest rows in index order instead of sorting the table. The SQL below is the
 * plain equivalent of HistoryQueryRepository.firstPage / pageBefore (those are covered by HistoryApiIT).
 */
class HistoryQueryPlanIT extends IntegrationTestBase {

    private static final String FIRST_PAGE = """
            SELECT e.id, e.delivery_id, e.repo, e.issue_number, e.title, e.html_url, e.received_at,
                   j.status, j.attempts, j.last_error, j.sent_at
            FROM webhook_events e JOIN dispatch_jobs j ON j.event_id = e.id
            WHERE j.status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'SENT', 'FAILED')
            ORDER BY e.received_at DESC, e.id DESC
            LIMIT 51
            """;

    private static final String PAGE_BEFORE = """
            SELECT e.id, e.delivery_id, e.repo, e.issue_number, e.title, e.html_url, e.received_at,
                   j.status, j.attempts, j.last_error, j.sent_at
            FROM webhook_events e JOIN dispatch_jobs j ON j.event_id = e.id
            WHERE j.status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'SENT', 'FAILED')
              AND e.received_at <= (SELECT b.received_at FROM webhook_events b WHERE b.id = 5000)
              AND (e.received_at < (SELECT b.received_at FROM webhook_events b WHERE b.id = 5000) OR e.id < 5000)
            ORDER BY e.received_at DESC, e.id DESC
            LIMIT 51
            """;

    @BeforeEach
    void seed10000() {
        jdbc.update("INSERT INTO webhook_events (delivery_id, event_type, action, repo, issue_number, title, html_url, "
                + "received_at) SELECT 'g-' || n, 'issues', 'opened', 'octo/repo', n, 't', 'https://x/' || n, "
                + "timestamptz '2026-01-01 00:00:00+00' + n * interval '1 second' FROM generate_series(1, 10000) n");
        jdbc.update("INSERT INTO dispatch_jobs (event_id, status, sent_at) SELECT id, "
                + "(ARRAY['PENDING','PROCESSING','RETRY_WAIT','SENT','FAILED'])[id % 5 + 1], "
                + "CASE WHEN id % 5 = 3 THEN received_at + interval '1 second' END FROM webhook_events");
        jdbc.execute("ANALYZE webhook_events");
        jdbc.execute("ANALYZE dispatch_jobs");
    }

    /** One connection for SET / EXPLAIN / RESET, because a pooled connection would lose the setting. */
    private String plan(String sql, boolean seqScanOff) {
        return jdbc.execute((ConnectionCallback<String>) con -> {
            List<String> lines = new ArrayList<>();
            try (Statement st = con.createStatement()) {
                if (seqScanOff) {
                    st.execute("SET enable_seqscan = off");
                }
                try (var rs = st.executeQuery("EXPLAIN " + sql)) {
                    while (rs.next()) {
                        lines.add(rs.getString(1));
                    }
                }
            } finally {
                try (Statement st = con.createStatement()) {
                    st.execute("RESET enable_seqscan");
                }
            }
            return String.join("\n", lines);
        });
    }

    @Test
    void first_page_plan_uses_the_received_at_index_without_sorting() {
        String baseline = plan(FIRST_PAGE, false);
        String forced = plan(FIRST_PAGE, true);
        System.out.println("PLAN first_page default:\n" + baseline);
        System.out.println("PLAN first_page seqscan=off:\n" + forced);

        assertThat(forced).contains("idx_webhook_events_received_at");
        assertThat(forced).doesNotContain("Sort");
    }

    @Test
    void page_before_plan_seeks_through_the_received_at_index() {
        String baseline = plan(PAGE_BEFORE, false);
        String forced = plan(PAGE_BEFORE, true);
        System.out.println("PLAN page_before default:\n" + baseline);
        System.out.println("PLAN page_before seqscan=off:\n" + forced);

        assertThat(forced).contains("idx_webhook_events_received_at");
        assertThat(forced).doesNotContain("Sort");
    }
}
