package com.example.githubwebhook.dispatch;

import com.example.githubwebhook.config.AppProperties;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobClaimService {

    private final DispatchJobRepository jobs;
    private final AppProperties.Worker worker;

    public JobClaimService(DispatchJobRepository jobs, AppProperties props) {
        this.jobs = jobs;
        this.worker = props.worker();
    }

    /** Short transaction: claim and commit before any HTTP call happens. */
    @Transactional
    public List<ClaimedJob> claim() {
        return jobs.claim(worker.batchSize(), worker.leaseSeconds()).stream()
                .map(j -> new ClaimedJob(j.getId(), j.getEventId(), j.getAttempts())).toList();
    }
}
