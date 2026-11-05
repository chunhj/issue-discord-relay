package com.example.githubwebhook.webhook;

import com.example.githubwebhook.dispatch.DispatchJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookIngestService {

    public enum Result { ACCEPTED, DUPLICATE }

    private final WebhookEventRepository events;
    private final DispatchJobRepository jobs;

    public WebhookIngestService(WebhookEventRepository events, DispatchJobRepository jobs) {
        this.events = events;
        this.jobs = jobs;
    }

    /** One transaction: event row and PENDING job commit together or not at all (STORE-01). */
    @Transactional
    public Result ingest(String deliveryId, IssuesPayload payload) {
        int rows = events.insertIfAbsent(deliveryId, "issues", payload.action(), payload.repository().fullName(),
                payload.issue().number(), payload.issue().title(), payload.issue().htmlUrl());
        if (rows == 0) {
            return Result.DUPLICATE;
        }
        jobs.enqueue(events.findIdByDeliveryId(deliveryId));
        return Result.ACCEPTED;
    }
}
