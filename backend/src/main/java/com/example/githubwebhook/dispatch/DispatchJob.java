package com.example.githubwebhook.dispatch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "dispatch_jobs")
public class DispatchJob {

    public enum Status { PENDING, PROCESSING, RETRY_WAIT, SENT, FAILED }

    @Id
    private Long id;

    @Column(name = "event_id")
    private Long eventId;

    @Enumerated(EnumType.STRING)
    private Status status;

    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "discord_message_id")
    private String discordMessageId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected DispatchJob() {
    }

    public Long getId() { return id; }
    public Long getEventId() { return eventId; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getLockedUntil() { return lockedUntil; }
    public String getLastError() { return lastError; }
    public String getDiscordMessageId() { return discordMessageId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}
