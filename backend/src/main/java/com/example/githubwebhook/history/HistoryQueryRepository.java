package com.example.githubwebhook.history;

import com.example.githubwebhook.dispatch.DispatchJob;
import com.example.githubwebhook.webhook.WebhookEvent;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

/** Read-only (D-03): extends Repository, so no save/delete exists. JPQL only (D-10). */
public interface HistoryQueryRepository extends Repository<WebhookEvent, Long> {

    // The entities have no association, so this is a Hibernate entity join on the id columns.
    String SELECT = "select new com.example.githubwebhook.history.HistoryRow("
            + "e.id, e.deliveryId, e.repo, e.issueNumber, e.title, e.htmlUrl, e.receivedAt, "
            + "j.status, j.attempts, j.lastError, j.sentAt) "
            + "from WebhookEvent e join DispatchJob j on j.eventId = e.id where j.status in :statuses";
    String ORDER = " order by e.receivedAt desc, e.id desc";

    @Query(SELECT + ORDER)
    List<HistoryRow> firstPage(@Param("statuses") Collection<DispatchJob.Status> statuses, Pageable window);

    // D-08: the cursor stays the last eventId, but the bound follows the (received_at, id) ordering through the
    // cursor row; a plain id < :before skips rows when id order and received_at order disagree. The leading <=
    // conjunct lets the received_at index seek. An unknown cursor id makes the subquery null: empty page.
    @Query(SELECT
            + " and e.receivedAt <= (select b.receivedAt from WebhookEvent b where b.id = :before)"
            + " and (e.receivedAt < (select b.receivedAt from WebhookEvent b where b.id = :before)"
            + " or e.id < :before)" + ORDER)
    List<HistoryRow> pageBefore(@Param("statuses") Collection<DispatchJob.Status> statuses,
                                @Param("before") long before, Pageable window);
}
