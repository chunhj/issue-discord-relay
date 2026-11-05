package com.example.githubwebhook.dispatch;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DispatchJobRepository extends JpaRepository<DispatchJob, Long> {

    /**
     * Claims up to {@code limit} due jobs (design 6.3). CTE AS MATERIALIZED instead of IN (subselect) so the planner
     * cannot re-run the locking subquery and claim more than {@code limit} rows. The PROCESSING branch re-claims jobs
     * whose lease expired (restart recovery, D-05). Needs its own transaction: declared query methods get none.
     */
    @Transactional
    @Query(nativeQuery = true, value = """
            WITH picked AS MATERIALIZED (
              SELECT id FROM dispatch_jobs
              WHERE (status IN ('PENDING', 'RETRY_WAIT') AND next_attempt_at <= now())
                 OR (status = 'PROCESSING' AND locked_until < now())
              ORDER BY next_attempt_at
              LIMIT :limit
              FOR UPDATE SKIP LOCKED
            )
            UPDATE dispatch_jobs j
            SET status = 'PROCESSING',
                locked_until = now() + CAST(:leaseSeconds AS integer) * interval '1 second',
                attempts = j.attempts + 1
            FROM picked
            WHERE j.id = picked.id
            RETURNING j.*
            """)
    List<DispatchJob> claim(@Param("limit") int limit, @Param("leaseSeconds") int leaseSeconds);

    @Modifying
    @Query(nativeQuery = true, value = "INSERT INTO dispatch_jobs (event_id, status) VALUES (:eventId, 'PENDING')")
    int enqueue(@Param("eventId") long eventId);

    // Extends the lease and doubles as an ownership check: 0 rows means the job was re-claimed (attempts moved on).
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE dispatch_jobs
            SET locked_until = now() + CAST(:leaseSeconds AS integer) * interval '1 second'
            WHERE id = :id AND status = 'PROCESSING' AND attempts = :attempts
            """)
    int renewLease(@Param("id") long id, @Param("attempts") int attempts, @Param("leaseSeconds") int leaseSeconds);

    // Result writes are fenced: attempts is incremented by every claim, so only the current claimer matches (T-01-09).
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE dispatch_jobs
            SET status = 'SENT', sent_at = now(), locked_until = NULL, discord_message_id = :messageId, last_error = NULL
            WHERE id = :id AND status = 'PROCESSING' AND attempts = :attempts
            """)
    int markSent(@Param("id") long id, @Param("attempts") int attempts, @Param("messageId") String messageId);

    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE dispatch_jobs
            SET status = 'FAILED', locked_until = NULL, last_error = :error
            WHERE id = :id AND status = 'PROCESSING' AND attempts = :attempts
            """)
    int markFailed(@Param("id") long id, @Param("attempts") int attempts, @Param("error") String error);
}
