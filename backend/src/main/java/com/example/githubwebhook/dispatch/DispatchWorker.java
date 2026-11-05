package com.example.githubwebhook.dispatch;

import com.example.githubwebhook.discord.DiscordClient;
import com.example.githubwebhook.discord.SendResult;
import com.example.githubwebhook.webhook.WebhookEvent;
import com.example.githubwebhook.webhook.WebhookEventRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Orchestrates claim, send, complete as three separate units. Deliberately NOT transactional (SEND-02): no DB
 * transaction or connection is held while Discord is called.
 */
@Component
public class DispatchWorker {

    private static final Logger log = LoggerFactory.getLogger(DispatchWorker.class);

    private final JobClaimService claimService;
    private final JobResultService resultService;
    private final WebhookEventRepository events;
    private final DiscordClient discord;

    public DispatchWorker(JobClaimService claimService, JobResultService resultService,
                          WebhookEventRepository events, DiscordClient discord) {
        this.claimService = claimService;
        this.resultService = resultService;
        this.events = events;
        this.discord = discord;
    }

    /** fixedDelay (not fixedRate) so ticks never overlap; never lets an exception kill the scheduler thread. */
    @Scheduled(fixedDelayString = "${app.worker.poll-interval-ms}")
    public void tick() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.error("Poll failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Returns the number of jobs attempted. */
    public int pollOnce() {
        List<ClaimedJob> claimed = claimService.claim();
        for (ClaimedJob job : claimed) {
            try {
                // The batch shares one lease but sends are serial: re-check ownership right before the send (PD-06).
                if (!resultService.renewLease(job)) {
                    log.warn("Lease lost, skipping job {} attempts {}", job.jobId(), job.attempts());
                    continue;
                }
                WebhookEvent event = events.findById(job.eventId()).orElseThrow();
                SendResult result = discord.send(event.getRepo(), event.getIssueNumber(), event.getTitle(),
                        event.getHtmlUrl());
                resultService.complete(job, result);
            } catch (RuntimeException e) {
                // Only ids and the class name: payload text and exception messages may carry secrets/untrusted data.
                // The job stays PROCESSING and is reclaimed once its lease expires.
                log.error("Job {} processing failed: {}", job.jobId(), e.getClass().getSimpleName());
            }
        }
        return claimed.size();
    }
}
