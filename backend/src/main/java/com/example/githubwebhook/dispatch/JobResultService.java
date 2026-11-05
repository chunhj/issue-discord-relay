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

    private final int leaseSeconds;

    public JobResultService(DispatchJobRepository jobs, DispatchAttemptRepository attempts, AppProperties props) {
        this.jobs = jobs;
        this.attempts = attempts;
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
     * Short transaction. Finishes the attempt first, always: it records what Discord answered even if this worker lost
     * the job. D-02: every failure is FAILED, no retry state. Returns false if the job was re-claimed.
     */
    @Transactional
    public boolean complete(ClaimedJob job, SendResult result) {
        String error = null;
        if (!result.delivered()) {
            error = result.error() == null ? "unknown" : result.error();
            error = error.length() > ERROR_MAX ? error.substring(0, ERROR_MAX) : error;
        }
        attempts.finish(job.jobId(), job.attempts(), AttemptOutcome.from(result).name(), result.httpStatus(), error);
        int updated = result.delivered()
                ? jobs.markSent(job.jobId(), job.attempts(), result.messageId())
                : jobs.markFailed(job.jobId(), job.attempts(), error);
        if (updated == 0) {
            log.warn("Stale result ignored: job {} attempts {} no longer owned", job.jobId(), job.attempts());
        }
        return updated > 0;
    }
}
