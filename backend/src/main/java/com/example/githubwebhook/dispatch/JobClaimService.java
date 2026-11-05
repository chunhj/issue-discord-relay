package com.example.githubwebhook.dispatch;

import com.example.githubwebhook.config.AppProperties;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobClaimService {

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
     */
    @Transactional
    public List<ClaimedJob> claim() {
        return jobs.claim(worker.batchSize(), worker.leaseSeconds()).stream()
                .peek(j -> attempts.start(j.getId(), j.getAttempts()))
                .map(j -> new ClaimedJob(j.getId(), j.getEventId(), j.getAttempts())).toList();
    }
}
