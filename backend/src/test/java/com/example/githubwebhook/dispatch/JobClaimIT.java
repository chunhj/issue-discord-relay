package com.example.githubwebhook.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.config.AppProperties;
import com.example.githubwebhook.discord.SendResult;
import com.example.githubwebhook.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Claim safety: spike for assumption A1 plus concurrency, lease expiry (D-05), renewal and fencing. */
class JobClaimIT extends IntegrationTestBase {

    @Autowired
    DispatchJobRepository jobs;

    @Autowired
    JobClaimService claimService;

    @Autowired
    JobResultService resultService;

    @Autowired
    AppProperties props;

    /** Seeds {@code count} events with one PENDING job each (due now). */
    private void seedPending(int count) {
        jdbc.update("""
                INSERT INTO webhook_events (delivery_id, event_type, action, repo, issue_number, title, html_url)
                SELECT 'd-' || g, 'issues', 'opened', 'octo/repo', g, 't' || g, 'https://github.com/octo/repo/issues/' || g
                FROM generate_series(1, ?) g""", count);
        jdbc.update("INSERT INTO dispatch_jobs (event_id, status) SELECT id, 'PENDING' FROM webhook_events");
    }

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

    @Test
    void expired_processing_job_is_reclaimed() {
        seedPending(3);
        // job 1: lease expired; job 2: lease still running; job 3: PENDING but not due yet
        jdbc.update("UPDATE dispatch_jobs SET status = 'PROCESSING', attempts = 1, locked_until = now() - interval '5 seconds' WHERE id = 1");
        jdbc.update("UPDATE dispatch_jobs SET status = 'PROCESSING', attempts = 1, locked_until = now() + interval '60 seconds' WHERE id = 2");
        jdbc.update("UPDATE dispatch_jobs SET next_attempt_at = now() + interval '1 hour' WHERE id = 3");

        List<DispatchJob> claimed = jobs.claim(10, 30);

        assertThat(claimed).extracting(DispatchJob::getId).containsExactly(1L);
        assertThat(claimed.get(0).getAttempts()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT attempts FROM dispatch_jobs WHERE id = 2", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM dispatch_jobs WHERE id = 3", String.class)).isEqualTo("PENDING");
    }

    @Test
    void batch_never_exceeds_limit() {
        seedPending(25);

        List<DispatchJob> claimed = jobs.claim(10, 30);

        assertThat(claimed).hasSize(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch_jobs WHERE status = 'PROCESSING'", Integer.class))
                .isEqualTo(10);
    }

    @RepeatedTest(3)
    void concurrent_claim_has_no_overlap() throws Exception {
        int jobCount = 50;
        int threads = 4;
        seedPending(jobCount);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<List<Long>>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Callable<List<List<Long>>> worker = () -> {
                    barrier.await();
                    List<List<Long>> batches = new ArrayList<>();
                    while (true) {
                        List<ClaimedJob> got = claimService.claim();
                        if (got.isEmpty()) {
                            return batches;
                        }
                        batches.add(got.stream().map(ClaimedJob::jobId).toList());
                    }
                };
                futures.add(pool.submit(worker));
            }

            Set<Long> all = new HashSet<>();
            int total = 0;
            for (Future<List<List<Long>>> f : futures) {
                for (List<Long> batch : f.get()) {
                    assertThat(batch.size()).isLessThanOrEqualTo(props.worker().batchSize());
                    total += batch.size();
                    all.addAll(batch);
                }
            }
            // total == all.size() means the per-thread sets are pairwise disjoint
            assertThat(total).isEqualTo(jobCount);
            assertThat(all).hasSize(jobCount);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch_jobs WHERE attempts = 1", Integer.class))
                .isEqualTo(jobCount);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch_attempts", Integer.class)).isEqualTo(jobCount);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT job_id) FROM dispatch_attempts", Integer.class))
                .isEqualTo(jobCount);
    }

    private ClaimedJob claimOne() {
        List<ClaimedJob> claimed = claimService.claim();
        assertThat(claimed).hasSize(1);
        return claimed.get(0);
    }

    private void expireLease() {
        jdbc.update("UPDATE dispatch_jobs SET locked_until = now() - interval '1 second'");
    }

    @Test
    void renew_lease_only_for_current_owner() {
        seedPending(1);
        ClaimedJob first = claimOne();
        assertThat(first.attempts()).isEqualTo(1);

        // shorten the lease so the renewal visibly moves it later
        jdbc.update("UPDATE dispatch_jobs SET locked_until = now() + interval '1 second'");
        assertThat(resultService.renewLease(first)).isTrue();
        assertThat(jdbc.queryForObject("SELECT locked_until > now() + interval '10 seconds' FROM dispatch_jobs",
                Boolean.class)).isTrue();

        expireLease();
        ClaimedJob second = claimOne();
        assertThat(second.attempts()).isEqualTo(2);
        Map<String, Object> before = jdbc.queryForMap("SELECT status, attempts, locked_until FROM dispatch_jobs");

        assertThat(resultService.renewLease(first)).isFalse();

        assertThat(jdbc.queryForMap("SELECT status, attempts, locked_until FROM dispatch_jobs")).isEqualTo(before);
        assertThat(resultService.renewLease(second)).isTrue();
    }

    @Test
    void stale_complete_does_not_overwrite() {
        seedPending(1);
        ClaimedJob stale = claimOne();
        expireLease();
        ClaimedJob current = claimOne();
        assertThat(current.attempts()).isEqualTo(2);

        assertThat(resultService.complete(stale, SendResult.delivered(200, "999"))).isFalse();

        Map<String, Object> job = jdbc.queryForMap("SELECT status, attempts, discord_message_id FROM dispatch_jobs");
        assertThat(job.get("status")).isEqualTo("PROCESSING");
        assertThat(job.get("attempts")).isEqualTo(2);
        assertThat(job.get("discord_message_id")).isNull();
        Map<String, Object> a1 = jdbc.queryForMap("SELECT finished_at, outcome FROM dispatch_attempts WHERE attempt_no = 1");
        assertThat(a1.get("finished_at")).isNotNull();
        assertThat(a1.get("outcome")).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT finished_at FROM dispatch_attempts WHERE attempt_no = 2",
                java.sql.Timestamp.class)).isNull();
    }
}
