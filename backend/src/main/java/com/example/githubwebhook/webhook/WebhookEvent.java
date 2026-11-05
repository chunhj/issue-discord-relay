package com.example.githubwebhook.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Read-only mapping of webhook_events; rows are written by WebhookEventRepository#insertIfAbsent. */
@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    @Id
    private Long id;

    @Column(name = "delivery_id")
    private String deliveryId;

    @Column(name = "event_type")
    private String eventType;

    private String action;
    private String repo;

    @Column(name = "issue_number")
    private long issueNumber;

    private String title;

    @Column(name = "html_url")
    private String htmlUrl;

    @Column(name = "received_at")
    private Instant receivedAt;

    protected WebhookEvent() {
    }

    public Long getId() { return id; }
    public String getDeliveryId() { return deliveryId; }
    public String getEventType() { return eventType; }
    public String getAction() { return action; }
    public String getRepo() { return repo; }
    public long getIssueNumber() { return issueNumber; }
    public String getTitle() { return title; }
    public String getHtmlUrl() { return htmlUrl; }
    public Instant getReceivedAt() { return receivedAt; }
}
