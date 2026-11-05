package com.example.githubwebhook.history;

import com.example.githubwebhook.dispatch.DispatchJob;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

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

    // WR-03: defense in depth. last_error carries up to 300 chars of an external response body; if a proxy/error page
    // echoes the webhook URL, a signature or a token, the unauthenticated read API must not repeat it.
    private static final Pattern WEBHOOK_URL =
            Pattern.compile("(?:https?://[\\w.-]+)?/(?:api/(?:v\\d+/)?)?webhooks/[^\\s\"'<>]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIGNATURE = Pattern.compile("sha256=[0-9a-fA-F]*", Pattern.CASE_INSENSITIVE);
    // 40+ chars keeps short codes ("HTTP 404", "CONNECT_ERROR", exception simple names) intact.
    private static final Pattern LONG_TOKEN = Pattern.compile("[\\w-]{40,}");

    static String redact(String lastError) {
        if (lastError == null) {
            return null;
        }
        String s = WEBHOOK_URL.matcher(lastError).replaceAll("[redacted-webhook-url]");
        s = SIGNATURE.matcher(s).replaceAll("[redacted-signature]");
        return LONG_TOKEN.matcher(s).replaceAll("[redacted-token]");
    }

    /** D-06: latency from the two DB values, only for SENT jobs. */
    static HistoryItem from(HistoryRow r) {
        Long latency = r.status() == DispatchJob.Status.SENT && r.sentAt() != null
                ? Duration.between(r.receivedAt(), r.sentAt()).toMillis()
                : null;
        return new HistoryItem(r.eventId(), r.deliveryId(), r.repo(), r.issueNumber(), r.title(), r.htmlUrl(),
                r.receivedAt(), r.status(), r.attempts(), redact(r.lastError()), r.sentAt(), latency);
    }
}
