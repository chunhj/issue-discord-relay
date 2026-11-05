package com.example.githubwebhook.dispatch;

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

    public JobResultService(DispatchJobRepository jobs) {
        this.jobs = jobs;
    }

    /** Short transaction. D-02: every failure is FAILED, no retry state. Returns false if the job was re-claimed. */
    @Transactional
    public boolean complete(ClaimedJob job, SendResult result) {
        int updated;
        if (result.delivered()) {
            updated = jobs.markSent(job.jobId(), job.attempts(), result.messageId());
        } else {
            String error = result.error() == null ? "unknown" : result.error();
            updated = jobs.markFailed(job.jobId(), job.attempts(),
                    error.length() > ERROR_MAX ? error.substring(0, ERROR_MAX) : error);
        }
        if (updated == 0) {
            log.warn("Stale result ignored: job {} attempts {} no longer owned", job.jobId(), job.attempts());
        }
        return updated > 0;
    }
}
