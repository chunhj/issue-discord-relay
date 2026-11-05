package com.example.githubwebhook.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    /** Design 6.2: returns 1 for a new event, 0 for a duplicate delivery_id. Duplicates are never detected by exception. */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO webhook_events
              (delivery_id, event_type, action, repo, issue_number, title, html_url, received_at)
            VALUES
              (:deliveryId, :eventType, :action, :repo, :issueNumber, :title, :htmlUrl, now())
            ON CONFLICT (delivery_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("deliveryId") String deliveryId, @Param("eventType") String eventType,
                       @Param("action") String action, @Param("repo") String repo,
                       @Param("issueNumber") long issueNumber, @Param("title") String title,
                       @Param("htmlUrl") String htmlUrl);

    @Query("select e.id from WebhookEvent e where e.deliveryId = :deliveryId")
    Long findIdByDeliveryId(@Param("deliveryId") String deliveryId);
}
