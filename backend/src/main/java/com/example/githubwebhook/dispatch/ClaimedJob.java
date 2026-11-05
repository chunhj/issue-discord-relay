package com.example.githubwebhook.dispatch;

/** Identity of a claimed job; {@code attempts} is the fencing token for result writes. */
public record ClaimedJob(long jobId, long eventId, int attempts) {
}
