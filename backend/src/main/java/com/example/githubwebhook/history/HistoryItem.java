package com.example.githubwebhook.history;

import com.example.githubwebhook.dispatch.DispatchJob;
import java.time.Duration;
import java.time.Instant;

/** D-05: the only wire contract of /api/history. Never add a component without a decision; never serialize entities. */
public record HistoryItem(
        Long eventId,
        String deliveryId,
        String repo,
        long issueNumber,
        String title,
        String htmlUrl,
        Instant receivedAt,
        DispatchJob.Status status,
        int attempts,
        String lastError,
        Instant sentAt,
        Long deliveryLatencyMs) {

    /** D-06: latency from the two DB values, only for SENT jobs. */
    static HistoryItem from(HistoryRow r) {
        Long latency = r.status() == DispatchJob.Status.SENT && r.sentAt() != null
                ? Duration.between(r.receivedAt(), r.sentAt()).toMillis()
                : null;
        return new HistoryItem(r.eventId(), r.deliveryId(), r.repo(), r.issueNumber(), r.title(), r.htmlUrl(),
                r.receivedAt(), r.status(), r.attempts(), r.lastError(), r.sentAt(), latency);
    }
}
