package com.example.githubwebhook.dispatch;

import com.example.githubwebhook.config.AppProperties;
import com.example.githubwebhook.discord.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobResultService {

    private static final Logger log = LoggerFactory.getLogger(JobResultService.class);
    private static final int ERROR_MAX = 500;

    private final DispatchJobRepository jobs;

    private final DispatchAttemptRepository attempts;

    private final RetryPolicy retryPolicy;

    private final int leaseSeconds;

    public JobResultService(DispatchJobRepository jobs, DispatchAttemptRepository attempts, AppProperties props,
            RetryPolicy retryPolicy) {
        this.jobs = jobs;
        this.attempts = attempts;
        this.retryPolicy = retryPolicy;
        this.leaseSeconds = props.worker().leaseSeconds();
    }

    /**
     * Claim takes a batch under one lease but sends are serial, so the worker renews right before each send. False means
     * another worker re-claimed the job: the caller must skip it without sending.
     */
    @Transactional
    public boolean renewLease(ClaimedJob job) {
        return jobs.renewLease(job.jobId(), job.attempts(), leaseSeconds) == 1;
    }

    /**
     * Short transaction, the single SENT / FAILED / RETRY_WAIT decision point (D-10). Finishes the attempt first,
     * always: it records what Discord answered even if this worker lost the job, and the last attempt of an exhausted
     * job stays RETRYABLE_FAIL (D-09). Returns false if the job was re-claimed.
     */
    @Transactional
    public boolean complete(ClaimedJob job, SendResult result) {
        String error = null;
        if (!result.delivered()) {
            error = cap(result.error() == null ? "unknown" : result.error());
        }
        AttemptOutcome outcome = AttemptOutcome.from(result);
        attempts.finish(job.jobId(), job.attempts(), outcome.name(), result.httpStatus(), error);
        int updated;
        if (result.delivered()) {
            updated = jobs.markSent(job.jobId(), job.attempts(), result.messageId());
        } else if (outcome == AttemptOutcome.RETRYABLE_FAIL && !retryPolicy.isExhausted(job.attempts())) {
            long delayMillis = retryPolicy.nextDelay(job.attempts(), result).toMillis();
            updated = jobs.markRetry(job.jobId(), job.attempts(), delayMillis, error);
        } else if (outcome == AttemptOutcome.RETRYABLE_FAIL) {
            String text = RetryPolicy.EXHAUSTED_CODE + " after " + job.attempts() + " attempts / " + error;
            updated = jobs.markFailed(job.jobId(), job.attempts(), cap(text));
        } else {
            updated = jobs.markFailed(job.jobId(), job.attempts(), error);
        }
        if (updated == 0) {
            log.warn("Stale result ignored: job {} attempts {} no longer owned", job.jobId(), job.attempts());
        }
        return updated > 0;
    }

    private static String cap(String text) {
        return text.length() > ERROR_MAX ? text.substring(0, ERROR_MAX) : text;
    }
}
