package com.example.githubwebhook.history;

import com.example.githubwebhook.dispatch.DispatchJob;
import java.time.Instant;

/** JPQL select-new target; components match the constructor expression in HistoryQueryRepository exactly. */
record HistoryRow(
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
        Instant sentAt) {
}
