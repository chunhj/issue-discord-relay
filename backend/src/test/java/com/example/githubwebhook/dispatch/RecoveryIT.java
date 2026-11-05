package com.example.githubwebhook.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.githubwebhook.discord.SendResult;
import com.example.githubwebhook.support.IntegrationTestBase;
import com.example.githubwebhook.support.SignedRequests;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/** RETRY-04 (crash path) and RECOV-02, in-process: leases are expired with SQL instead of killing a process. */
class RecoveryIT extends IntegrationTestBase {

    @Autowired
    private DispatchWorker worker;

    @Autowired
    private JobClaimService claimService;

    @Autowired
    private JobResultService resultService;

    private int requestCount() {
        return WIREMOCK.findAll(postRequestedFor(urlPathEqualTo(DISCORD_PATH))).size();
    }

    private void stubOk() {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"42\"}")));
    }

    private void seed(long issueNumber) throws Exception {
        assertThat(postSigned("issues", "delivery-" + issueNumber, SignedRequests.issuesOpened(issueNumber, "T"))
                .statusCode()).isEqualTo(202);
    }

    private long jobIdOf(long issueNumber) {
        return jdbc.queryForObject("SELECT j.id FROM dispatch_jobs j JOIN webhook_events e ON e.id = j.event_id "
                + "WHERE e.issue_number = ?", Long.class, issueNumber);
    }

    private Map<String, Object> job(long jobId) {
        return jdbc.queryForMap("SELECT * FROM dispatch_jobs WHERE id = ?", jobId);
    }

    private List<Map<String, Object>> attemptsOf(long jobId) {
        return jdbc.queryForList("SELECT * FROM dispatch_attempts WHERE job_id = ? ORDER BY attempt_no", jobId);
    }

    /** The state after {@code crashes} workers died mid-send: PROCESSING, lease expired, one open attempt row each. */
    private void prepareCrashed(long jobId, int crashes) {
        jdbc.update("UPDATE dispatch_jobs SET status = 'PROCESSING', attempts = ?, "
                + "locked_until = now() - interval '5 seconds' WHERE id = ?", crashes, jobId);
        jdbc.update("INSERT INTO dispatch_attempts (job_id, attempt_no, started_at) "
                + "SELECT ?, g, now() FROM generate_series(1, ?) g", jobId, crashes);
    }

    private void assertFiveOpenRows(long jobId) {
        List<Map<String, Object>> rows = attemptsOf(jobId);
        assertThat(rows).hasSize(5);
        assertThat(rows).allSatisfy(r -> assertThat(r.get("finished_at")).isNull());
    }

    @Test
    void lease_expiry_exhaustion() throws Exception {
        stubOk();
        seed(9001);
        long id = jobIdOf(9001);
        prepareCrashed(id, 5);

        assertThat(worker.pollOnce()).isZero();

        Map<String, Object> j = job(id);
        assertThat(j.get("status")).isEqualTo("FAILED");
        assertThat(j.get("attempts")).isEqualTo(6);
        assertThat(j.get("locked_until")).isNull();
        assertThat((String) j.get("last_error")).startsWith("RETRIES_EXHAUSTED").contains("lease expired");
        assertThat(requestCount()).isZero();
        assertFiveOpenRows(id);
    }

    @Test
    void exhausted_job_does_not_block_batch() throws Exception {
        stubOk();
        seed(9002);
        seed(9003);
        long exhausted = jobIdOf(9002);
        long healthy = jobIdOf(9003);
        prepareCrashed(exhausted, 5);

        assertThat(worker.pollOnce()).isEqualTo(1);

        assertThat(job(exhausted).get("status")).isEqualTo("FAILED");
        assertThat(job(healthy).get("status")).isEqualTo("SENT");
        assertThat(requestCount()).isEqualTo(1);
        assertThat(WIREMOCK.findAll(postRequestedFor(urlPathEqualTo(DISCORD_PATH))).get(0).getBodyAsString())
                .contains("/issues/9003");
        List<Map<String, Object>> sent = attemptsOf(healthy);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).get("outcome")).isEqualTo("SUCCESS");
        assertFiveOpenRows(exhausted);
    }

    @Test
    void last_allowed_attempt_is_still_sent() throws Exception {
        stubOk();
        seed(9004);
        long id = jobIdOf(9004);
        prepareCrashed(id, 4);

        assertThat(worker.pollOnce()).isEqualTo(1);

        assertThat(job(id).get("status")).isEqualTo("SENT");
        assertThat(job(id).get("attempts")).isEqualTo(5);
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void reclaim_after_lease_expiry_sends_again() throws Exception {
        stubOk();
        seed(9010);
        long id = jobIdOf(9010);

        assertThat(claimService.claim()).hasSize(1); // the worker "died" after the claim
        assertThat(worker.pollOnce()).isZero(); // lease still runs
        assertThat(requestCount()).isZero();

        jdbc.update("UPDATE dispatch_jobs SET locked_until = now() - interval '1 second'");
        assertThat(worker.pollOnce()).isEqualTo(1);

        assertThat(job(id).get("status")).isEqualTo("SENT");
        assertThat(job(id).get("attempts")).isEqualTo(2);
        List<Map<String, Object>> rows = attemptsOf(id);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("finished_at")).isNull();
        assertThat(rows.get(0).get("outcome")).isNull();
        assertThat(rows.get(1).get("outcome")).isEqualTo("SUCCESS");
        assertThat(requestCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"500,RETRYABLE_FAIL", "404,PERMANENT_FAIL"})
    void stale_retry_result_cannot_overwrite_new_owner(int status, String expectedOutcome) throws Exception {
        seed(9011);
        long id = jobIdOf(9011);
        ClaimedJob stale = claimService.claim().get(0);
        jdbc.update("UPDATE dispatch_jobs SET locked_until = now() - interval '1 second'");
        ClaimedJob current = claimService.claim().get(0);
        assertThat(stale.attempts()).isEqualTo(1);
        assertThat(current.attempts()).isEqualTo(2);
        Map<String, Object> before = job(id);

        assertThat(resultService.complete(stale, SendResult.httpFailure(status, "HTTP " + status))).isFalse();

        Map<String, Object> after = job(id);
        assertThat(after.get("status")).isEqualTo("PROCESSING");
        assertThat(after.get("attempts")).isEqualTo(2);
        assertThat(after.get("locked_until")).isNotNull().isEqualTo(before.get("locked_until"));
        assertThat(after.get("last_error")).isNull();
        assertThat(after.get("next_attempt_at")).isEqualTo(before.get("next_attempt_at"));
        List<Map<String, Object>> rows = attemptsOf(id);
        assertThat(rows.get(0).get("outcome")).isEqualTo(expectedOutcome);
        assertThat(rows.get(0).get("finished_at")).isNotNull();
        assertThat(rows.get(1).get("finished_at")).isNull();

        assertThat(resultService.complete(current, SendResult.delivered(200, "7"))).isTrue();
        assertThat(job(id).get("status")).isEqualTo("SENT");
    }

    @Test
    void retry_wait_job_is_reclaimed_only_when_due() throws Exception {
        WIREMOCK.stubFor(WireMock.post(urlPathEqualTo(DISCORD_PATH)).willReturn(aResponse().withStatus(500)));
        seed(9012);
        long id = jobIdOf(9012);
        assertThat(worker.pollOnce()).isEqualTo(1);
        assertThat(job(id).get("status")).isEqualTo("RETRY_WAIT");

        assertThat(claimService.claim()).isEmpty();

        jdbc.update("UPDATE dispatch_jobs SET next_attempt_at = now()");
        List<ClaimedJob> claimed = claimService.claim();
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).attempts()).isEqualTo(2);
        assertThat(job(id).get("attempts")).isEqualTo(2);
    }
}
