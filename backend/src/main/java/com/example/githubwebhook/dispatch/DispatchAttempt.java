package com.example.githubwebhook.dispatch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One send attempt. {@code finishedAt == null} marks an attempt whose result is unknown (design 6.3). */
@Entity
@Table(name = "dispatch_attempts")
public class DispatchAttempt {

    @Id
    private Long id;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "attempt_no")
    private int attemptNo;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    private AttemptOutcome outcome;

    @Column(name = "http_status")
    private Integer httpStatus;

    private String error;

    protected DispatchAttempt() {
    }

    public Long getId() { return id; }
    public Long getJobId() { return jobId; }
    public int getAttemptNo() { return attemptNo; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public AttemptOutcome getOutcome() { return outcome; }
    public Integer getHttpStatus() { return httpStatus; }
    public String getError() { return error; }
}
