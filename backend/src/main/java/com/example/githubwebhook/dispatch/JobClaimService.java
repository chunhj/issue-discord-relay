package com.example.githubwebhook.dispatch;

import com.example.githubwebhook.config.AppProperties;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobClaimService {

    private static final Logger log = LoggerFactory.getLogger(JobClaimService.class);

    private final DispatchJobRepository jobs;
    private final DispatchAttemptRepository attempts;
    private final AppProperties.Worker worker;

    public JobClaimService(DispatchJobRepository jobs, DispatchAttemptRepository attempts, AppProperties props) {
        this.jobs = jobs;
        this.attempts = attempts;
        this.worker = props.worker();
    }

    /**
     * Short transaction: claim, record the attempt and commit before any HTTP call happens. The attempt row is written
     * at claim time so a crash mid-send leaves an attempt with finished_at NULL (uncertain result, design 6.3).
     * A claim that pushes attempts above max-attempts means every allowed attempt was spent and the lease expired
     * again (a worker died each time): such a job is failed right here, with no attempt row and no send (D-08). The
     * returned list only holds jobs that are to be sent.
     */
    @Transactional
    public List<ClaimedJob> claim() {
        int maxAttempts = worker.retry().maxAttempts();
        List<ClaimedJob> toSend = new ArrayList<>();
        for (DispatchJob row : jobs.claim(worker.batchSize(), worker.leaseSeconds())) {
            if (row.getAttempts() > maxAttempts) {
                String reason = RetryPolicy.EXHAUSTED_CODE + " after " + maxAttempts
                        + " attempts / lease expired before a result was recorded";
                jobs.markFailed(row.getId(), row.getAttempts(), reason);
                log.warn("Job {} exhausted its attempt budget through lease expiry", row.getId());
            } else {
                attempts.start(row.getId(), row.getAttempts());
                toSend.add(new ClaimedJob(row.getId(), row.getEventId(), row.getAttempts()));
            }
        }
        return toSend;
    }
}
