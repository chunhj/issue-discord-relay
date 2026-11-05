package com.example.githubwebhook.dispatch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DispatchAttemptRepository extends JpaRepository<DispatchAttempt, Long> {

    /** Called inside the claim transaction, so a claimed job always has an attempt row (design 6.3). */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO dispatch_attempts (job_id, attempt_no, started_at)
            VALUES (:jobId, :attemptNo, now())
            """)
    int start(@Param("jobId") long jobId, @Param("attemptNo") int attemptNo);

    // CAST lets PostgreSQL type a null parameter (successful attempts have no error; I/O failures have no status).
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE dispatch_attempts
            SET finished_at = now(), outcome = :outcome,
                http_status = CAST(:httpStatus AS integer), error = CAST(:error AS text)
            WHERE job_id = :jobId AND attempt_no = :attemptNo AND finished_at IS NULL
            """)
    int finish(@Param("jobId") long jobId, @Param("attemptNo") int attemptNo, @Param("outcome") String outcome,
               @Param("httpStatus") Integer httpStatus, @Param("error") String error);
}
